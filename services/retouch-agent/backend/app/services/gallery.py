"""云图库用户影子账号。

Agent 不维护独立用户体系；集成模式下按云图库用户 ID 懒创建影子账号，
仅用于满足数据表外键与按用户隔离查询，密码为随机值且无法登录。
"""

import secrets

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import User
from app.security import hash_password

GALLERY_USERNAME_PREFIX = "gallery:"


class InvalidGalleryUser(Exception):
    pass


def _username_for(gallery_user_id: str) -> str:
    return f"{GALLERY_USERNAME_PREFIX}{gallery_user_id}"


async def ensure_gallery_user(session: AsyncSession, gallery_user_id: str) -> User:
    """按云图库用户 ID 取影子账号，不存在则创建；并发创建冲突时回读。"""
    username = _username_for(gallery_user_id)
    user = await session.scalar(select(User).where(User.username == username))
    if user is not None:
        return user

    user = User(username=username, password_hash=hash_password(secrets.token_hex(32)))
    session.add(user)
    try:
        await session.commit()
    except IntegrityError:
        await session.rollback()
        user = await session.scalar(select(User).where(User.username == username))
        if user is None:
            raise
    return user
