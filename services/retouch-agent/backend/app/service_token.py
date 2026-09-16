"""云图库 Spring Boot 与修图 Agent 之间的短时 HMAC 服务令牌。

令牌结构：base64url(payload_json) + "." + base64url(hmac_sha256(secret, payload_json))。
payload 固定字段：

- uid  云图库用户 ID（数字字符串）
- pic  云图库图片 ID，可空
- spc  云图库空间 ID，可空
- perm 云图库权限列表
- rid  请求 ID
- aud  受众：Spring→Agent 为 retouch-agent，Agent→Spring 为 cloud-gallery
- iat/exp 签发/过期时间戳（秒）
- nonce 随机数，防重放

密钥由双方共享，有效期不超过 300 秒，两个方向使用不同 audience。
"""

import base64
import hashlib
import hmac
import json
import secrets
import time

GALLERY_AUDIENCE = "retouch-agent"
AGENT_AUDIENCE = "cloud-gallery"
ASSET_AUDIENCE = "agent-asset"

MAX_TTL_SECONDS = 300
MAX_ASSET_TTL_SECONDS = 24 * 3600
# 允许的时钟偏差
_CLOCK_SKEW_SECONDS = 10


def _sign(body: dict, secret: str) -> str:
    raw = json.dumps(body, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    signature = hmac.new(secret.encode(), raw.encode(), hashlib.sha256).digest()
    return f"{_b64encode(raw.encode())}.{_b64encode(signature)}"


def _parse(token: str) -> tuple[bytes, bytes] | None:
    try:
        raw_b64, signature_b64 = token.split(".", 1)
        raw = _b64decode(raw_b64)
        signature = _b64decode(signature_b64)
    except (ValueError, TypeError):
        return None
    return raw, signature


def _valid_signature(raw: bytes, signature: bytes, secret: str) -> bool:
    expected = hmac.new(secret.encode(), raw, hashlib.sha256).digest()
    return hmac.compare_digest(signature, expected)


def _b64encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode().rstrip("=")


def _b64decode(text: str) -> bytes:
    padding = "=" * (-len(text) % 4)
    return base64.urlsafe_b64decode(text + padding)


def issue(
    payload: dict,
    secret: str,
    audience: str,
    ttl_seconds: int = MAX_TTL_SECONDS,
) -> str:
    """签发服务令牌；调用方负责在 payload 中携带 uid/pic/spc/perm/rid 等业务字段。"""
    if ttl_seconds <= 0 or ttl_seconds > MAX_TTL_SECONDS:
        raise ValueError(f"服务令牌有效期必须在 1~{MAX_TTL_SECONDS} 秒之间")
    now = int(time.time())
    body = {
        **payload,
        "aud": audience,
        "iat": now,
        "exp": now + ttl_seconds,
        "nonce": secrets.token_hex(8),
    }
    return _sign(body, secret)


def verify(token: str, secret: str, audience: str) -> dict | None:
    """校验服务令牌；任何无效情形统一返回 None，由调用方按未认证处理。"""
    parsed = _parse(token)
    if parsed is None:
        return None
    raw, signature = parsed
    try:
        if not _valid_signature(raw, signature, secret):
            return None
        body = json.loads(raw)
        if not isinstance(body, dict):
            return None
        now = time.time()
        if body.get("aud") != audience:
            return None
        iat, exp = body.get("iat"), body.get("exp")
        if not isinstance(iat, int) or not isinstance(exp, int):
            return None
        if exp < now or iat > now + _CLOCK_SKEW_SECONDS:
            return None
        if exp - iat > MAX_TTL_SECONDS:
            return None
        uid = body.get("uid")
        if not isinstance(uid, str) or not uid.isdigit() or len(uid) > 19:
            return None
        return body
    except (KeyError, TypeError, json.JSONDecodeError):
        return None


def issue_object_token(object_key: str, secret: str, ttl_seconds: int = 3600) -> str:
    """签发素材访问令牌（浏览器经云图库网关查看 Agent 素材用）。"""
    if ttl_seconds <= 0 or ttl_seconds > MAX_ASSET_TTL_SECONDS:
        raise ValueError(f"素材访问令牌有效期必须在 1~{MAX_ASSET_TTL_SECONDS} 秒之间")
    now = int(time.time())
    body = {
        "obj": object_key,
        "aud": ASSET_AUDIENCE,
        "iat": now,
        "exp": now + ttl_seconds,
        "nonce": secrets.token_hex(8),
    }
    return _sign(body, secret)


def read_object_token(token: str, secret: str) -> str | None:
    """校验素材访问令牌并返回对象键；无效返回 None。"""
    parsed = _parse(token)
    if parsed is None:
        return None
    raw, signature = parsed
    try:
        if not _valid_signature(raw, signature, secret):
            return None
        body = json.loads(raw)
        if body.get("aud") != ASSET_AUDIENCE:
            return None
        iat, exp = body.get("iat"), body.get("exp")
        if not isinstance(iat, int) or not isinstance(exp, int):
            return None
        if exp < time.time() or iat > time.time() + _CLOCK_SKEW_SECONDS:
            return None
        object_key = body.get("obj")
        if not isinstance(object_key, str) or not object_key:
            return None
        return object_key
    except (KeyError, TypeError, json.JSONDecodeError):
        return None
