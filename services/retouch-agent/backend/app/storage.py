"""统一对象存储门面。

按 STORAGE_BACKEND 配置分发到具体后端：
- s3     直连 S3 兼容对象存储（独立开发模式）
- bridge 经云图库 Spring Boot 签名 URL 访问腾讯 COS（集成模式）

后端需实现统一接口：object_key / ensure_bucket / put / get / delete / signed_url。
"""

import uuid

from app import storage_bridge, storage_s3
from app.config import get_settings

BRIDGE_BACKEND = "bridge"


def _backend():
    return storage_bridge if get_settings().storage_backend == BRIDGE_BACKEND else storage_s3


def object_key(user_id: uuid.UUID, asset_id: uuid.UUID, extension: str) -> str:
    """按后端规则生成对象键；bridge 模式固定落在 agent-temp/ 前缀。"""
    return _backend().object_key(user_id, asset_id, extension)


def ensure_bucket() -> None:
    """确保存储可用：s3 建桶并配置 CORS；bridge 探测云图库桥接健康。"""
    _backend().ensure_bucket()


async def put(key: str, data: bytes, content_type: str) -> None:
    await _backend().put(key, data, content_type)


async def get(key: str) -> bytes:
    return await _backend().get(key)


async def delete(key: str) -> None:
    await _backend().delete(key)


def signed_url(key: str) -> str:
    """生成短时访问 URL。两种后端均为纯本地计算，不产生网络请求。"""
    return _backend().signed_url(key)
