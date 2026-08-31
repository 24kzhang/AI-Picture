from __future__ import annotations

import hashlib
import os
from dataclasses import dataclass, field
from pathlib import Path


SERVICE_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_API_URL = (
    "https://dashscope.aliyuncs.com/api/v1/services/embeddings/"
    "multimodal-embedding/multimodal-embedding"
)


def default_chroma_path() -> Path:
    """Windows 使用纯 ASCII 的本机目录，规避 HNSW 中文路径兼容问题。"""
    configured = os.getenv("CHROMA_PATH", "").strip()
    if configured:
        return Path(configured).resolve()
    local_app_data = os.getenv("LOCALAPPDATA", "").strip()
    if os.name == "nt" and local_app_data:
        return (Path(local_app_data) / "CloudGallery" / "chroma").resolve()
    return (SERVICE_ROOT / "data" / "chroma").resolve()


def load_api_key(config_path: Path | None = None) -> str:
    """优先读取环境变量，否则复用后端 application-local.yml 中的密钥。"""
    environment_key = os.getenv("DASHSCOPE_API_KEY", "").strip()
    if environment_key:
        return environment_key
    path = config_path or (
        SERVICE_ROOT.parent
        / "backend"
        / "src"
        / "main"
        / "resources"
        / "application-local.yml"
    )
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError:
        return ""
    for line in lines:
        stripped = line.strip()
        if not stripped.startswith("apiKey:"):
            continue
        value = stripped.partition(":")[2].strip()
        return value.strip('"').strip("'")
    return ""


@dataclass(frozen=True)
class Settings:
    """百炼 Embedding 与 Chroma 的运行配置。"""

    api_key: str = field(
        default_factory=load_api_key
    )
    api_url: str = field(
        default_factory=lambda: os.getenv(
            "DASHSCOPE_EMBEDDING_URL", DEFAULT_API_URL
        ).strip()
    )
    model_name: str = field(
        default_factory=lambda: os.getenv(
            "EMBEDDING_MODEL",
            "tongyi-embedding-vision-flash-2026-03-06",
        ).strip()
    )
    dimension: int = field(
        default_factory=lambda: int(os.getenv("EMBEDDING_DIMENSION", "512"))
    )
    res_level: int = field(
        default_factory=lambda: int(os.getenv("EMBEDDING_RES_LEVEL", "1"))
    )
    api_timeout_seconds: float = field(
        default_factory=lambda: float(os.getenv("DASHSCOPE_TIMEOUT_SECONDS", "60"))
    )
    chroma_path: Path = field(default_factory=default_chroma_path)
    request_limit_mb: int = field(
        default_factory=lambda: int(os.getenv("REQUEST_LIMIT_MB", "10"))
    )

    @property
    def collection_name(self) -> str:
        signature = f"{self.model_name}:{self.dimension}:{self.res_level}"
        model_hash = hashlib.sha1(signature.encode("utf-8")).hexdigest()[:12]
        return f"gallery_{model_hash}"


settings = Settings()
