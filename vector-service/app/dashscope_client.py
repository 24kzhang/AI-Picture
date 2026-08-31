from __future__ import annotations

import base64
from pathlib import Path
from typing import Any

import httpx
import numpy as np
from PIL import Image, UnidentifiedImageError

from .config import Settings, settings

IMAGE_FORMATS = {
    "JPEG": "jpeg",
    "PNG": "png",
    "WEBP": "webp",
    "BMP": "bmp",
    "TIFF": "tiff",
    "ICO": "x-icon",
}


class DashScopeEmbeddingError(RuntimeError):
    """百炼向量接口调用失败。"""


class DashScopeEmbeddingClient:
    """把本地图片以 Base64 Data URI 发送给百炼多模态向量接口。"""

    def __init__(
        self,
        config: Settings = settings,
        http_client: httpx.Client | None = None,
    ) -> None:
        self.config = config
        self._owns_client = http_client is None
        self._client = http_client or httpx.Client(
            timeout=config.api_timeout_seconds,
            follow_redirects=False,
        )

    @property
    def configured(self) -> bool:
        return bool(self.config.api_key)

    def encode(self, image_path: Path) -> list[float]:
        if not self.config.api_key:
            raise DashScopeEmbeddingError(
                "未配置 DASHSCOPE_API_KEY，无法调用百炼图片向量接口"
            )
        image_data = self._to_data_uri(image_path)
        payload = {
            "model": self.config.model_name,
            "input": {"contents": [{"image": image_data}]},
            "parameters": {
                "dimension": self.config.dimension,
                "res_level": self.config.res_level,
            },
        }
        try:
            response = self._client.post(
                self.config.api_url,
                headers={
                    "Authorization": f"Bearer {self.config.api_key}",
                    "Content-Type": "application/json",
                },
                json=payload,
            )
        except httpx.HTTPError as exc:
            raise DashScopeEmbeddingError(f"百炼接口网络请求失败：{exc}") from exc

        response_data = self._read_response(response)
        vector = self._extract_vector(response_data)
        values = np.asarray(vector, dtype=np.float32).reshape(-1)
        if values.size != self.config.dimension:
            raise DashScopeEmbeddingError(
                f"百炼返回向量维度 {values.size}，预期 {self.config.dimension}"
            )
        norm = float(np.linalg.norm(values))
        if not np.isfinite(norm) or norm <= 0:
            raise DashScopeEmbeddingError("百炼返回了无效向量")
        return (values / norm).tolist()

    def close(self) -> None:
        if self._owns_client:
            self._client.close()

    def _to_data_uri(self, image_path: Path) -> str:
        try:
            with Image.open(image_path) as image:
                image.verify()
                image_format = IMAGE_FORMATS.get((image.format or "").upper())
        except (OSError, UnidentifiedImageError) as exc:
            raise DashScopeEmbeddingError("上传文件不是有效图片") from exc
        if image_format is None:
            raise DashScopeEmbeddingError("百炼暂不支持该图片格式")
        encoded = base64.b64encode(image_path.read_bytes()).decode("ascii")
        return f"data:image/{image_format};base64,{encoded}"

    def _read_response(self, response: httpx.Response) -> dict[str, Any]:
        try:
            data = response.json()
        except ValueError as exc:
            raise DashScopeEmbeddingError(
                f"百炼接口返回了非 JSON 响应（HTTP {response.status_code}）"
            ) from exc
        if not response.is_success:
            code = str(data.get("code", "")).strip()
            message = str(data.get("message", "")).strip()
            detail = "：".join(item for item in (code, message) if item)
            suffix = f"（{detail}）" if detail else ""
            raise DashScopeEmbeddingError(
                f"百炼接口调用失败，HTTP {response.status_code}{suffix}"
            )
        return data

    @staticmethod
    def _extract_vector(data: dict[str, Any]) -> list[float]:
        output = data.get("output")
        embeddings = output.get("embeddings") if isinstance(output, dict) else None
        if not isinstance(embeddings, list) or not embeddings:
            raise DashScopeEmbeddingError("百炼响应中缺少 embeddings")
        first = embeddings[0]
        vector = first.get("embedding") if isinstance(first, dict) else None
        if not isinstance(vector, list) or not vector:
            raise DashScopeEmbeddingError("百炼响应中缺少有效向量")
        return vector
