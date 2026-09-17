from typing import Annotated

from fastapi import Cookie, Depends, HTTPException, Request, status

from app import service_token
from app.config import get_settings
from app.db import SessionDep
from app.models import User
from app.security import SESSION_COOKIE, read_token
from app.services import auth as auth_service
from app.services import gallery as gallery_service

GALLERY_TOKEN_HEADER = "X-Gallery-Token"

_UNAUTHENTICATED = HTTPException(status.HTTP_401_UNAUTHORIZED, "未登录或会话已过期")


async def current_user(
    request: Request,
    session: SessionDep,
    token: Annotated[str | None, Cookie(alias=SESSION_COOKIE)] = None,
) -> User:
    settings = get_settings()
    if settings.integration_mode:
        # 集成模式：只认云图库网关签发的短时 HMAC 服务令牌
        gallery_token = request.headers.get(GALLERY_TOKEN_HEADER)
        if gallery_token is None:
            raise _UNAUTHENTICATED
        payload = service_token.verify(
            gallery_token, settings.service_token_secret, service_token.GALLERY_AUDIENCE
        )
        if payload is None:
            raise _UNAUTHENTICATED
        return await gallery_service.ensure_gallery_user(session, payload["uid"])

    # 独立模式：维持原有 Cookie 会话，忽略网关令牌
    if token is None:
        raise _UNAUTHENTICATED

    user_id = read_token(token)
    if user_id is None:
        raise _UNAUTHENTICATED

    user = await auth_service.get_by_id(session, user_id)
    if user is None:
        raise _UNAUTHENTICATED
    return user


CurrentUser = Annotated[User, Depends(current_user)]
