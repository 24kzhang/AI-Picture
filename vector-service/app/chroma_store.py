from __future__ import annotations

import json
import sqlite3
import threading
import time
from pathlib import Path
from typing import Iterable

import chromadb
from chromadb.api.models.Collection import Collection


class DuplicateScanLimitError(RuntimeError):
    """重复扫描达到保护阈值，调用方应转后台任务或稍后重试。"""

    code = "DUPLICATE_SCAN_LIMIT"

    def __init__(
        self,
        message: str,
        *,
        vector_count: int,
        max_vectors: int,
        elapsed_seconds: float,
        max_seconds: float,
    ) -> None:
        super().__init__(message)
        self.vector_count = vector_count
        self.max_vectors = max_vectors
        self.elapsed_seconds = elapsed_seconds
        self.max_seconds = max_seconds


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
                "description": "AI 协同云影库图片向量",
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

    def duplicate_groups(
        self,
        threshold: float = 0.999999,
        *,
        max_vectors: int = 20_000,
        max_seconds: float = 30.0,
        max_neighbors: int = 128,
    ) -> list[list[int]]:
        """使用 Chroma 近邻查询返回重复向量组。

        旧实现会构造 N×N 相似度矩阵。这里按批次读取向量并查询 HNSW
        近邻，避免全量矩阵；数量、单个邻域和时间预算超限时明确拒绝，
        避免管理请求长时间占满服务线程。
        """
        safe_threshold = float(threshold)
        if not 0.95 <= safe_threshold <= 1.0:
            raise ValueError("重复向量阈值必须在 0.95 到 1.0 之间")
        safe_max_vectors = int(max_vectors)
        safe_max_seconds = float(max_seconds)
        safe_max_neighbors = int(max_neighbors)
        if safe_max_vectors < 2:
            raise ValueError("重复向量扫描上限必须至少为 2")
        if safe_max_seconds <= 0:
            raise ValueError("重复向量扫描时间预算必须大于 0")
        if safe_max_neighbors < 2:
            raise ValueError("重复向量邻居上限必须至少为 2")

        started_at = time.monotonic()

        def check_budget(vector_count: int) -> None:
            elapsed = time.monotonic() - started_at
            if vector_count > safe_max_vectors or elapsed >= safe_max_seconds:
                reason = (
                    f"重复向量扫描超出保护阈值：向量 {vector_count}/{safe_max_vectors}，"
                    f"耗时 {elapsed:.2f}/{safe_max_seconds:.2f} 秒"
                )
                raise DuplicateScanLimitError(
                    reason,
                    vector_count=vector_count,
                    max_vectors=safe_max_vectors,
                    elapsed_seconds=elapsed,
                    max_seconds=safe_max_seconds,
                )

        with self._lock:
            result = self._collection.get(include=[])
        raw_ids = result.get("ids", []) or []
        vector_count = len(raw_ids)
        check_budget(vector_count)
        if vector_count < 2:
            return []

        picture_ids = [int(picture_id) for picture_id in raw_ids]
        index_by_picture_id = {
            picture_id: index for index, picture_id in enumerate(picture_ids)
        }
        parents = list(range(vector_count))

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

        # 每次批量查询多个向量，保持单次返回结果有界；阈值很高时邻居通常很少。
        # 如果第 K 个邻居仍在阈值内，说明邻域可能被截断，宁可拒绝也不返回不完整分组。
        batch_size = 64
        neighbor_limit = min(safe_max_neighbors, vector_count)
        distance_threshold = 1.0 - safe_threshold
        for start in range(0, vector_count, batch_size):
            check_budget(vector_count)
            stop = min(vector_count, start + batch_size)
            batch_ids = picture_ids[start:stop]
            with self._lock:
                batch = self._collection.get(
                    ids=[str(picture_id) for picture_id in batch_ids],
                    include=["embeddings"],
                )
                raw_embeddings = batch.get("embeddings")
                if raw_embeddings is None or len(raw_embeddings) != len(batch_ids):
                    raise RuntimeError("Chroma 返回的重复扫描向量不完整")
                result = self._collection.query(
                    query_embeddings=[
                        list(map(float, embedding)) for embedding in raw_embeddings
                    ],
                    n_results=neighbor_limit,
                    include=["distances"],
                )
            check_budget(vector_count)

            result_ids = result.get("ids") or []
            result_distances = result.get("distances") or []
            for local_index, source_id in enumerate(batch_ids):
                source_index = index_by_picture_id[source_id]
                neighbor_ids = (
                    result_ids[local_index] if local_index < len(result_ids) else []
                )
                distances = (
                    result_distances[local_index]
                    if local_index < len(result_distances)
                    else []
                )
                if (
                    len(neighbor_ids) >= neighbor_limit
                    and neighbor_limit < vector_count
                    and distances
                    and float(distances[-1]) <= distance_threshold + 1e-7
                ):
                    elapsed = time.monotonic() - started_at
                    raise DuplicateScanLimitError(
                        "单个向量的重复邻居超过返回上限，未返回不完整分组",
                        vector_count=vector_count,
                        max_vectors=safe_max_vectors,
                        elapsed_seconds=elapsed,
                        max_seconds=safe_max_seconds,
                    )
                for target_id, distance in zip(neighbor_ids, distances):
                    target_picture_id = int(target_id)
                    target_index = index_by_picture_id.get(target_picture_id)
                    if target_index is None or target_index == source_index:
                        continue
                    if float(distance) <= distance_threshold + 1e-7:
                        union(source_index, target_index)

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
