from __future__ import annotations

import base64
from pathlib import Path
from typing import Any

import httpx
import numpy as np
from PIL import Image, UnidentifiedImageError

from .config import Settings, settings


class EmbeddingError(RuntimeError):
    """服务器向量接口不可用或返回了不兼容的向量。"""


class ServerEmbeddingClient:
    def __init__(self, config: Settings = settings, http_client: httpx.Client | None = None) -> None:
        self.config = config
        self._owns_client = http_client is None
        self._client = http_client or httpx.Client(
            timeout=config.api_timeout_seconds, follow_redirects=False,
        )

    @property
    def configured(self) -> bool:
        return bool(self.config.base_url)

    def health(self) -> dict[str, Any]:
        data = self._request("GET", "/health", timeout=5)
        checkpoint = str(data.get("checkpoint", "")).rstrip("/").rsplit("/", 1)[-1]
        if (data.get("status") != "ok" or data.get("model") != self.config.model_name
                or data.get("dimension") != self.config.dimension
                or checkpoint != self.config.model_revision):
            raise EmbeddingError("服务器 Embedding 模型、版本或维度与当前索引不一致")
        return data

    def encode(self, image_path: Path) -> list[float]:
        try:
            with Image.open(image_path) as image:
                image.verify()
            encoded = base64.b64encode(image_path.read_bytes()).decode("ascii")
        except (OSError, UnidentifiedImageError) as exc:
            raise EmbeddingError("上传文件不是有效图片") from exc
        # A changed checkpoint must never write into an existing embedding space.
        self.health()
        data = self._request("POST", "/embed", json={"inputs": [{"type": "image", "image": encoded}]})
        vectors = data.get("embeddings")
        if (data.get("model") != self.config.model_name or data.get("dimension") != self.config.dimension
                or data.get("count") != 1 or not isinstance(vectors, list) or len(vectors) != 1):
            raise EmbeddingError("服务器 Embedding 返回的模型、维度或数量不正确")
        vector = vectors[0]
        if (not isinstance(vector, list) or len(vector) != self.config.dimension
                or any(isinstance(v, bool) or not isinstance(v, (int, float)) for v in vector)):
            raise EmbeddingError("服务器 Embedding 返回了无效向量")
        values = np.asarray(vector, dtype=np.float32)
        norm = float(np.linalg.norm(values))
        if not np.isfinite(values).all() or not np.isfinite(norm) or norm <= 0:
            raise EmbeddingError("服务器 Embedding 返回了无效向量")
        return (values / norm).tolist()

    def _request(self, method: str, path: str, **kwargs: Any) -> dict[str, Any]:
        try:
            if self.config.api_key:
                kwargs["headers"] = {"Authorization": f"Bearer {self.config.api_key}"}
            response = self._client.request(method, self.config.base_url + path, **kwargs)
            response.raise_for_status()
            data = response.json()
        except (httpx.HTTPError, ValueError) as exc:
            raise EmbeddingError("服务器 Embedding 请求失败，请检查模型服务") from exc
        if not isinstance(data, dict):
            raise EmbeddingError("服务器 Embedding 返回格式不正确")
        return data

    def close(self) -> None:
        if self._owns_client:
            self._client.close()
