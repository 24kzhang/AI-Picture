"""COS 桥接存储后端测试：以 httpx.MockTransport 模拟云图库 Spring Boot 网关。"""

import hashlib
import json
import uuid

import httpx
import pytest

from app import service_token, storage, storage_bridge
from app.config import get_settings
from app.deps import GALLERY_TOKEN_HEADER

SECRET = "bridge-test-secret-0123456789-0123456789-0123"

BRIDGE_URL = "http://gallery.test/api/agent-internal"
PUBLIC_URL = "http://gallery.test"
UPLOAD_URL = "https://cos.test/upload-presigned"
DOWNLOAD_URL = "https://cos.test/download-presigned"


@pytest.fixture
def bridge_settings(monkeypatch):
    settings = get_settings()
    monkeypatch.setattr(settings, "storage_backend", "bridge")
    monkeypatch.setattr(settings, "gallery_bridge_url", BRIDGE_URL)
    monkeypatch.setattr(settings, "gallery_public_url", PUBLIC_URL)
    monkeypatch.setattr(settings, "service_token_secret", SECRET)
    storage_bridge._download_cache.clear()
    yield settings
    storage_bridge._download_cache.clear()


class FakeGallery:
    """记录桥接请求并模拟 COS 直传/直读。

    响应统一使用云图库的 BaseResponse 信封 {code,data,message}，
    以锁定真实网关契约。
    """

    def __init__(self):
        self.requests: list[tuple[str, dict]] = []
        self.objects: dict[str, bytes] = {}
        self.reject = False
        self.reject_business = False

    @staticmethod
    def _ok(data: dict) -> httpx.Response:
        return httpx.Response(200, json={"code": 0, "data": data, "message": "ok"})

    def handler(self, request: httpx.Request) -> httpx.Response:
        if self.reject:
            return httpx.Response(500, text="gateway down")
        if self.reject_business:
            return httpx.Response(200, json={"code": 50001, "data": None, "message": "COS 未配置"})
        path = request.url.path
        if path.endswith("/health"):
            return self._ok({"status": "ok"})
        # COS 直传不需要桥接令牌
        if request.method == "PUT":
            self.objects[str(request.url)] = request.content
            return httpx.Response(200)
        # COS 预签名下载同样不需要桥接令牌
        if request.method == "GET" and str(request.url) == DOWNLOAD_URL:
            data = self.objects.get(UPLOAD_URL)
            if data is None:
                return httpx.Response(404)
            return httpx.Response(200, content=data)
        # 校验桥接令牌：必须是 Agent→Spring 方向
        token = request.headers.get(GALLERY_TOKEN_HEADER, "")
        if not token:
            return httpx.Response(401)
        payload = service_token.verify(token, SECRET, service_token.AGENT_AUDIENCE)
        if payload is None:
            return httpx.Response(401)
        body = json.loads(request.content or b"{}")
        if request.method == "POST" and path.endswith("/storage/upload-request"):
            self.requests.append(("upload-request", body))
            if not body["object_key"].startswith("agent-temp/"):
                return self._ok({})
            return self._ok({"url": UPLOAD_URL, "object_key": body["object_key"]})
        if request.method == "POST" and path.endswith("/storage/register"):
            self.requests.append(("register", body))
            data = self.objects.get(UPLOAD_URL)
            if data is None:
                return httpx.Response(409, text="对象尚未上传")
            if body["sha256"] != hashlib.sha256(data).hexdigest():
                return httpx.Response(400, text="SHA-256 不匹配")
            return self._ok({"status": "registered"})
        if request.method == "POST" and path.endswith("/storage/download-request"):
            self.requests.append(("download-request", body))
            return self._ok({"url": DOWNLOAD_URL})
        if request.method == "POST" and path.endswith("/storage/cleanup"):
            self.requests.append(("cleanup", body))
            return self._ok({"status": "scheduled"})
        return httpx.Response(404)


@pytest.fixture
async def fake_gallery(monkeypatch):
    gallery = FakeGallery()
    client = httpx.AsyncClient(transport=httpx.MockTransport(gallery.handler))
    monkeypatch.setattr(storage_bridge, "_http", lambda: client)
    yield gallery
    await client.aclose()


async def test_put_uploads_via_presigned_url_and_registers(
    bridge_settings, fake_gallery
):
    data = b"fake-image-bytes"

    await storage.put("agent-temp/u1/a1.png", data, "image/png")

    kinds = [kind for kind, _ in fake_gallery.requests]
    assert kinds == ["upload-request", "register"]
    register = dict(fake_gallery.requests)["register"]
    assert register["sha256"] == hashlib.sha256(data).hexdigest()
    assert register["content_type"] == "image/png"
    assert register["size_bytes"] == len(data)
    assert fake_gallery.objects[UPLOAD_URL] == data


async def test_get_downloads_via_presigned_url(bridge_settings, fake_gallery):
    data = b"fake-image-bytes"
    await storage.put("agent-temp/u1/a1.png", data, "image/png")
    fake_gallery.requests.clear()
    storage_bridge._download_cache.clear()

    fetched = await storage.get("agent-temp/u1/a1.png")

    assert fetched == data
    kinds = [kind for kind, _ in fake_gallery.requests]
    assert kinds == ["download-request"]


async def test_get_reuses_cached_download_url(bridge_settings, fake_gallery):
    data = b"fake-image-bytes"
    await storage.put("agent-temp/u1/a1.png", data, "image/png")
    fake_gallery.requests.clear()
    storage_bridge._download_cache.clear()

    await storage.get("agent-temp/u1/a1.png")
    await storage.get("agent-temp/u1/a1.png")

    kinds = [kind for kind, _ in fake_gallery.requests]
    assert kinds == ["download-request"]


async def test_delete_registers_cleanup(bridge_settings, fake_gallery):
    await storage.delete("agent-temp/u1/a1.png")

    kind, body = fake_gallery.requests[-1]
    assert kind == "cleanup"
    assert body == {"object_keys": ["agent-temp/u1/a1.png"]}


def test_signed_url_is_local_and_verifiable(bridge_settings, fake_gallery):
    key = "agent-temp/u1/a1.png"

    url = storage.signed_url(key)

    assert url.startswith(f"{PUBLIC_URL}/api/agent-asset/")
    token = url.rsplit("/", 1)[1]
    assert service_token.read_object_token(token, SECRET) == key


def test_object_key_uses_agent_temp_prefix(bridge_settings, fake_gallery):
    user_id = uuid.uuid4()
    asset_id = uuid.uuid4()

    key = storage.object_key(user_id, asset_id, "png")

    assert key == f"agent-temp/{user_id}/{asset_id}.png"


def test_object_key_uses_users_prefix_in_s3_mode(monkeypatch):
    settings = get_settings()
    monkeypatch.setattr(settings, "storage_backend", "s3")
    user_id = uuid.uuid4()
    asset_id = uuid.uuid4()

    key = storage.object_key(user_id, asset_id, "png")

    assert key == f"users/{user_id}/{asset_id}.png"


async def test_bridge_error_when_gateway_down(bridge_settings, fake_gallery):
    fake_gallery.reject = True

    with pytest.raises(storage_bridge.StorageBridgeError):
        await storage.put("agent-temp/u1/a1.png", b"data", "image/png")


async def test_bridge_business_error_code_is_raised(bridge_settings, fake_gallery):
    """云图库业务错误码（HTTP 200 + code != 0）同样视为失败。"""
    fake_gallery.reject_business = True

    with pytest.raises(storage_bridge.StorageBridgeError) as exc:
        await storage.put("agent-temp/u1/a1.png", b"data", "image/png")

    assert "COS 未配置" in str(exc.value)


def test_ensure_bucket_probes_health(bridge_settings, monkeypatch):
    calls = []

    def ok_probe():
        calls.append("ok")

    monkeypatch.setattr(storage_bridge, "_sync_probe", ok_probe)
    storage.ensure_bucket()
    assert calls == ["ok"]

    def failing_probe():
        raise storage_bridge.StorageBridgeError("云图库桥接不可达")

    monkeypatch.setattr(storage_bridge, "_sync_probe", failing_probe)
    with pytest.raises(storage_bridge.StorageBridgeError):
        storage.ensure_bucket()


def test_object_token_rejects_forged_signature():
    token = service_token.issue_object_token("agent-temp/x", SECRET)
    forged = token[:-1] + ("A" if token[-1] != "A" else "B")

    assert service_token.read_object_token(forged, SECRET) is None
    assert service_token.read_object_token(token, "wrong-secret" * 4) is None


def test_issue_object_token_rejects_out_of_range_ttl():
    with pytest.raises(ValueError):
        service_token.issue_object_token("k", SECRET, ttl_seconds=24 * 3600 + 1)
