"""云图库 COS 桥接存储后端。

Agent 不持有 COS SecretId/SecretKey；所有对象读写经云图库 Spring Boot
签发的受限 URL 完成：

- put   → POST {bridge}/storage/upload-request 取预签名上传 URL，直传 COS，
          再 POST {bridge}/storage/register 登记 SHA-256/MIME/尺寸/大小
- get   → POST {bridge}/storage/download-request 取预签名下载 URL 后拉取
- delete→ POST {bridge}/storage/cleanup 登记临时资源清理
- signed_url → 本地签发素材访问令牌，浏览器经
          GET {gallery}/api/agent-asset/{token} 由云图库鉴权后访问

对象键一律落在 agent-temp/ 前缀下，由云图库侧校验；临时对象默认 7 天清理。
桥接请求以 AGENT_AUDIENCE（cloud-gallery）方向的 HMAC 服务令牌认证。
"""

import hashlib
import time
import uuid
from functools import lru_cache

import httpx

from app import service_token
from app.config import get_settings
from app.deps import GALLERY_TOKEN_HEADER

KEY_PREFIX = "agent-temp"
# 预签名下载 URL 的本地缓存，避免工具链反复向云图库申请
_DOWNLOAD_URL_TTL_SECONDS = 240
_DOWNLOAD_CACHE_LIMIT = 256
# key -> (url, 时间片)
_download_cache: dict[str, tuple[str, str]] = {}


class StorageBridgeError(Exception):
    """桥接调用失败（网络错误或网关拒绝）。"""


@lru_cache
def _http() -> httpx.AsyncClient:
    return httpx.AsyncClient(timeout=httpx.Timeout(60.0, connect=5.0))


def _cache_tick() -> str:
    """简单的时间片指纹：同一段时间内命中同一缓存槽。"""
    return str(int(time.time() // _DOWNLOAD_URL_TTL_SECONDS))


async def _post(path: str, payload: dict) -> dict:
    settings = get_settings()
    if not settings.gallery_bridge_url:
        raise StorageBridgeError("GALLERY_BRIDGE_URL 未配置")
    token = service_token.issue(
        {"uid": "0", "svc": "retouch-agent"},
        settings.service_token_secret,
        service_token.AGENT_AUDIENCE,
    )
    try:
        response = await _http().post(
            f"{settings.gallery_bridge_url}{path}",
            json=payload,
            headers={GALLERY_TOKEN_HEADER: token},
        )
    except httpx.HTTPError as exc:
        raise StorageBridgeError(f"云图库桥接不可达：{exc}") from exc
    if response.status_code != 200:
        raise StorageBridgeError(f"云图库桥接返回 {response.status_code}: {response.text[:200]}")
    return response.json()


def object_key(user_id: uuid.UUID, asset_id: uuid.UUID, extension: str) -> str:
    return f"{KEY_PREFIX}/{user_id}/{asset_id}.{extension}"


def ensure_bucket() -> None:
    """bridge 模式桶由云图库管理；此处仅探测桥接健康。"""
    settings = get_settings()
    if not settings.gallery_bridge_url:
        raise StorageBridgeError("GALLERY_BRIDGE_URL 未配置")
    _sync_probe()


def _sync_probe() -> None:
    settings = get_settings()
    try:
        response = httpx.get(f"{settings.gallery_bridge_url}/health", timeout=5.0)
    except httpx.HTTPError as exc:
        raise StorageBridgeError(f"云图库桥接不可达：{exc}") from exc
    if response.status_code != 200:
        raise StorageBridgeError(f"云图库桥接健康检查返回 {response.status_code}")


async def put(key: str, data: bytes, content_type: str) -> None:
    granted = await _post(
        "/storage/upload-request",
        {"object_key": key, "content_type": content_type, "size_bytes": len(data)},
    )
    upload_url = granted.get("url")
    if not isinstance(upload_url, str) or not upload_url:
        raise StorageBridgeError("云图库未返回上传 URL")
    try:
        response = await _http().put(
            upload_url, content=data, headers={"Content-Type": content_type}
        )
    except httpx.HTTPError as exc:
        raise StorageBridgeError(f"上传到 COS 失败：{exc}") from exc
    if response.status_code not in (200, 201, 204):
        raise StorageBridgeError(f"COS 上传返回 {response.status_code}")

    await _post(
        "/storage/register",
        {
            "object_key": key,
            "sha256": hashlib.sha256(data).hexdigest(),
            "content_type": content_type,
            "size_bytes": len(data),
        },
    )


async def get(key: str) -> bytes:
    url = await _download_url(key)
    try:
        response = await _http().get(url)
    except httpx.HTTPError as exc:
        raise StorageBridgeError(f"从 COS 下载失败：{exc}") from exc
    if response.status_code != 200:
        raise StorageBridgeError(f"COS 下载返回 {response.status_code}")
    return response.content


async def delete(key: str) -> None:
    await _post("/storage/cleanup", {"object_keys": [key]})


def signed_url(key: str) -> str:
    """本地签发素材访问令牌，浏览器经云图库网关鉴权后访问。纯本地计算。"""
    settings = get_settings()
    if not settings.gallery_public_url:
        raise StorageBridgeError("GALLERY_PUBLIC_URL 未配置")
    token = service_token.issue_object_token(
        key, settings.service_token_secret, settings.asset_url_ttl_seconds
    )
    return f"{settings.gallery_public_url.rstrip('/')}/api/agent-asset/{token}"


async def _download_url(key: str) -> str:
    """申请预签名下载 URL；短时间内复用缓存结果。"""
    tick = _cache_tick()
    hit = _download_cache.get(key)
    if hit is not None and hit[1] == tick:
        return hit[0]
    granted = await _post("/storage/download-request", {"object_key": key})
    url = granted.get("url")
    if not isinstance(url, str) or not url:
        raise StorageBridgeError("云图库未返回下载 URL")
    if len(_download_cache) >= _DOWNLOAD_CACHE_LIMIT:
        _download_cache.clear()
    _download_cache[key] = (url, tick)
    return url
