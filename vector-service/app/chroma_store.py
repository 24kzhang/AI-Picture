from __future__ import annotations

import json
import sqlite3
import threading
from pathlib import Path
from typing import Iterable

import chromadb
import numpy as np
from chromadb.api.models.Collection import Collection


class ChromaStore:
    """无需 Docker 的本地持久化图片向量库。"""

    def __init__(self, data_path: Path, collection_name: str) -> None:
        data_path.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        self._client = chromadb.PersistentClient(path=str(data_path))
        self._collection: Collection = self._client.get_or_create_collection(
            name=collection_name,
            metadata={
                "hnsw:space": "cosine",
                "hnsw:batch_size": 100,
                "hnsw:sync_threshold": 100,
                "description": "AI 协同云图库图片向量",
            },
        )
        self._retain_embedding_log(data_path)

    @property
    def count(self) -> int:
        return self._collection.count()

    def upsert(
        self,
        picture_id: int,
        space_id: int,
        embedding: list[float],
    ) -> None:
        with self._lock:
            self._collection.upsert(
                ids=[str(int(picture_id))],
                embeddings=[embedding],
                metadatas=[
                    {
                        "picture_id": int(picture_id),
                        "space_id": int(space_id),
                    }
                ],
            )

    def search(
        self,
        embedding: list[float],
        allowed_space_ids: Iterable[int],
        limit: int,
    ) -> list[dict[str, float | int]]:
        spaces = sorted({int(space_id) for space_id in allowed_space_ids})
        if not spaces or self.count == 0:
            return []
        where = (
            {"space_id": spaces[0]}
            if len(spaces) == 1
            else {"space_id": {"$in": spaces}}
        )
        with self._lock:
            result = self._collection.query(
                query_embeddings=[embedding],
                n_results=min(max(1, int(limit)), self.count),
                where=where,
                include=["metadatas", "distances"],
            )
        metadatas = (result.get("metadatas") or [[]])[0]
        distances = (result.get("distances") or [[]])[0]
        items: list[dict[str, float | int]] = []
        for metadata, distance in zip(metadatas, distances):
            if not isinstance(metadata, dict) or "picture_id" not in metadata:
                continue
            similarity = max(-1.0, min(1.0, 1.0 - float(distance)))
            items.append(
                {
                    "pictureId": int(metadata["picture_id"]),
                    "score": similarity,
                }
            )
        return items

    def delete(self, picture_id: int) -> None:
        with self._lock:
            self._collection.delete(ids=[str(int(picture_id))])

    def existing_ids(self, picture_ids: Iterable[int]) -> list[int]:
        ids = sorted({int(picture_id) for picture_id in picture_ids})
        if not ids:
            return []
        with self._lock:
            result = self._collection.get(
                ids=[str(picture_id) for picture_id in ids],
                include=[],
            )
        return sorted(int(picture_id) for picture_id in result.get("ids", []))

    def duplicate_groups(self, threshold: float = 0.999999) -> list[list[int]]:
        """按余弦相似度返回重复向量组，不跨图片空间合并由后端负责。"""
        safe_threshold = float(threshold)
        if not 0.95 <= safe_threshold <= 1.0:
            raise ValueError("重复向量阈值必须在 0.95 到 1.0 之间")
        with self._lock:
            result = self._collection.get(include=["embeddings"])
        raw_ids = result.get("ids", [])
        raw_embeddings = result.get("embeddings")
        if raw_embeddings is None or len(raw_ids) < 2:
            return []

        picture_ids = [int(picture_id) for picture_id in raw_ids]
        embeddings = np.asarray(raw_embeddings, dtype=np.float32)
        norms = np.linalg.norm(embeddings, axis=1, keepdims=True)
        embeddings = embeddings / np.maximum(norms, 1e-12)
        parents = np.arange(len(picture_ids), dtype=np.int32)

        def find(index: int) -> int:
            while parents[index] != index:
                parents[index] = parents[int(parents[index])]
                index = int(parents[index])
            return index

        def union(left: int, right: int) -> None:
            left_root = find(left)
            right_root = find(right)
            if left_root != right_root:
                parents[right_root] = left_root

        # 分块计算，避免一次性创建 N×N 的大矩阵。
        block_size = 256
        for start in range(0, len(picture_ids), block_size):
            stop = min(len(picture_ids), start + block_size)
            similarities = embeddings[start:stop] @ embeddings.T
            for local_index, source_index in enumerate(range(start, stop)):
                matches = np.flatnonzero(
                    similarities[local_index, source_index + 1:] >= safe_threshold
                ) + source_index + 1
                for target_index in matches.tolist():
                    union(source_index, int(target_index))

        grouped: dict[int, list[int]] = {}
        for index, picture_id in enumerate(picture_ids):
            grouped.setdefault(find(index), []).append(picture_id)
        duplicates = [sorted(group) for group in grouped.values() if len(group) > 1]
        return sorted(duplicates, key=lambda group: (-len(group), group[0]))

    def close(self) -> None:
        """正常停止时让 Chroma 刷盘并释放本地文件。"""
        self._client._system.stop()

    @staticmethod
    def _retain_embedding_log(data_path: Path) -> None:
        """保留原始 embedding 日志，HNSW 损坏时无需重新调用模型。"""
        config = json.dumps(
            {
                "automatically_purge": False,
                "_type": "EmbeddingsQueueConfigurationInternal",
            },
            separators=(",", ":"),
        )
        with sqlite3.connect(data_path / "chroma.sqlite3", timeout=5) as database:
            database.execute(
                "UPDATE embeddings_queue_config SET config_json_str=? WHERE id=1",
                (config,),
            )
            database.commit()

    def ping(self) -> bool:
        self._client.heartbeat()
        return True
