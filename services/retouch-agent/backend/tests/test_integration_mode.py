import io
import time

import httpx
import pytest
from PIL import Image

from app import service_token
from app.config import get_settings
from app.deps import GALLERY_TOKEN_HEADER

SECRET = "unit-test-service-token-secret-0123456789"


@pytest.fixture(autouse=True)
async def cleanup_gallery_users():
    """清理测试创建的云图库影子账号（资产随外键级联删除）。"""
    from sqlalchemy import delete

    from app.db import SessionFactory
    from app.models import User

    yield
    async with SessionFactory() as session:
        await session.execute(delete(User).where(User.username.like("gallery:%")))
        await session.commit()


@pytest.fixture
def integration_settings(monkeypatch):
    settings = get_settings()
    monkeypatch.setattr(settings, "integration_mode", True)
    monkeypatch.setattr(settings, "service_token_secret", SECRET)
    yield settings


def gallery_token(uid="1000000000000000001", **extra) -> str:
    return service_token.issue({"uid": uid, **extra}, SECRET, service_token.GALLERY_AUDIENCE)


def upload_payload(data: bytes, name="a.png", content_type="image/png"):
    return {"file": (name, data, content_type)}


def make_image(size=(256, 256), mode="RGB", fmt="PNG") -> bytes:
    buffer = io.BytesIO()
    Image.new(mode, size, "white").save(buffer, format=fmt)
    return buffer.getvalue()


async def test_auth_endpoints_closed_in_integration_mode(
    client: httpx.AsyncClient, integration_settings
):
    response = await client.post(
        "/api/auth/register", json={"username": "someone", "password": "secret123"}
    )

    assert response.status_code == 404


async def test_valid_token_grants_access(client: httpx.AsyncClient, integration_settings):
    response = await client.get(
        "/api/assets", headers={GALLERY_TOKEN_HEADER: gallery_token()}
    )

    assert response.status_code == 200
    assert response.json() == []


async def test_missing_token_rejected(client: httpx.AsyncClient, integration_settings):
    response = await client.get("/api/assets")

    assert response.status_code == 401


async def test_tampered_token_rejected(client: httpx.AsyncClient, integration_settings):
    token = gallery_token()
    # 修改签名段首个字符（确保解码字节变化，避免仅改动填充位导致的假通过）
    payload_part, signature_part = token.split(".", 1)
    forged_signature = ("B" if signature_part[0] == "A" else "A") + signature_part[1:]
    forged = f"{payload_part}.{forged_signature}"

    response = await client.get("/api/assets", headers={GALLERY_TOKEN_HEADER: forged})

    assert response.status_code == 401


async def test_wrong_secret_rejected(client: httpx.AsyncClient, integration_settings):
    token = service_token.issue(
        {"uid": "1"}, "another-secret-0123456789-0123456789-0123", service_token.GALLERY_AUDIENCE
    )

    response = await client.get("/api/assets", headers={GALLERY_TOKEN_HEADER: token})

    assert response.status_code == 401


async def test_expired_token_rejected(client: httpx.AsyncClient, integration_settings):
    # 手工构造过期令牌，绕过 issue 的 ttl 校验
    import json as _json

    body = {
        "uid": "1000000000000000001",
        "aud": service_token.GALLERY_AUDIENCE,
        "iat": int(time.time()) - 600,
        "exp": int(time.time()) - 300,
        "nonce": "x" * 16,
    }
    raw = _json.dumps(body, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    signature = service_token.hmac.new(
        SECRET.encode(), raw.encode(), service_token.hashlib.sha256
    ).digest()
    expired = service_token._b64encode(raw.encode()) + "." + service_token._b64encode(signature)

    response = await client.get("/api/assets", headers={GALLERY_TOKEN_HEADER: expired})

    assert response.status_code == 401


async def test_wrong_audience_rejected(client: httpx.AsyncClient, integration_settings):
    token = service_token.issue({"uid": "1"}, SECRET, service_token.AGENT_AUDIENCE)

    response = await client.get("/api/assets", headers={GALLERY_TOKEN_HEADER: token})

    assert response.status_code == 401


async def test_cookie_rejected_in_integration_mode(
    client: httpx.AsyncClient, integration_settings, credentials
):
    # 先在独立模式下注册拿到会话 Cookie
    settings = get_settings()
    settings.integration_mode = False
    try:
        await client.post("/api/auth/register", json=credentials)
    finally:
        settings.integration_mode = True

    response = await client.get("/api/assets")

    assert response.status_code == 401


async def test_shadow_user_isolated_between_gallery_users(
    client: httpx.AsyncClient, integration_settings
):
    token_a = gallery_token(uid="1000000000000000001")
    token_b = gallery_token(uid="1000000000000000002")

    created = await client.post(
        "/api/assets",
        files=upload_payload(make_image((320, 200))),
        headers={GALLERY_TOKEN_HEADER: token_a},
    )
    assert created.status_code == 201
    asset_id = created.json()["id"]

    # 另一个云图库用户不可见
    other = await client.get("/api/assets", headers={GALLERY_TOKEN_HEADER: token_b})
    assert other.json() == []

    # 同一云图库用户复用同一影子账号，素材可见
    again = await client.get("/api/assets", headers={GALLERY_TOKEN_HEADER: token_a})
    assert [item["id"] for item in again.json()] == [asset_id]


async def test_token_ignored_in_standalone_mode(
    client: httpx.AsyncClient, monkeypatch
):
    settings = get_settings()
    monkeypatch.setattr(settings, "integration_mode", False)
    token = service_token.issue(
        {"uid": "1"}, SECRET, service_token.GALLERY_AUDIENCE
    )

    response = await client.get("/api/assets", headers={GALLERY_TOKEN_HEADER: token})

    assert response.status_code == 401


def test_issue_rejects_out_of_range_ttl():
    with pytest.raises(ValueError):
        service_token.issue({"uid": "1"}, SECRET, service_token.GALLERY_AUDIENCE, ttl_seconds=301)
    with pytest.raises(ValueError):
        service_token.issue({"uid": "1"}, SECRET, service_token.GALLERY_AUDIENCE, ttl_seconds=0)


async def test_app_starts_when_bridge_unreachable(monkeypatch):
    """发布顺序容错：云图库网关未就绪时 Agent 仍可启动，状态由健康检查暴露。"""
    from app.main import app

    settings = get_settings()
    monkeypatch.setattr(settings, "integration_mode", True)
    monkeypatch.setattr(settings, "service_token_secret", SECRET)
    # 指向不可达端口，确保探测失败
    monkeypatch.setattr(settings, "gallery_bridge_url", "http://127.0.0.1:1/api/agent-internal")
    try:
        async with app.router.lifespan_context(app):
            pass
    finally:
        settings.integration_mode = False


def test_verify_rejects_overlong_ttl():
    body = {
        "uid": "1",
        "aud": service_token.GALLERY_AUDIENCE,
        "iat": int(time.time()) - 1000,
        "exp": int(time.time()) + 100,
        "nonce": "x" * 16,
    }
    import json as _json

    raw = _json.dumps(body, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    signature = service_token.hmac.new(
        SECRET.encode(), raw.encode(), service_token.hashlib.sha256
    ).digest()
    token = service_token._b64encode(raw.encode()) + "." + service_token._b64encode(signature)

    assert service_token.verify(token, SECRET, service_token.GALLERY_AUDIENCE) is None
