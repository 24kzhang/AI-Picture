import asyncio
import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import APIRouter, FastAPI
from fastapi.staticfiles import StaticFiles

from app import storage
from app.config import get_settings
from app.queue import close_queue
from app.routers import assets, auth, batches, events, health, runs, sessions

settings = get_settings()
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    if settings.integration_mode and len(settings.service_token_secret) < 32:
        raise RuntimeError("INTEGRATION_MODE 已开启，但 SERVICE_TOKEN_SECRET 缺失或短于 32 字节")
    # 存储不可用不阻断启动（发布顺序：先部署 Agent，后部署云图库网关）；
    # 具体状态由 /api/health 暴露
    try:
        await asyncio.to_thread(storage.ensure_bucket)
    except Exception as exc:  # noqa: BLE001 - 启动期只告警，不退出
        logger.warning("对象存储初始化失败，服务仍将启动：%s", exc)
    yield
    await close_queue()


app = FastAPI(
    title="AI 修图智能体",
    docs_url="/api/docs",
    openapi_url="/api/openapi.json",
    lifespan=lifespan,
)

api = APIRouter(prefix="/api")
api.include_router(health.router)
api.include_router(auth.router)
api.include_router(assets.router)
api.include_router(runs.router)
api.include_router(sessions.router)
api.include_router(batches.router)
app.include_router(api)

# SSE 不挂在 /api 下，便于反向代理单独关闭缓冲
app.include_router(events.router)

# 生产环境下前端与 API 同源，静态产物由本服务托管；开发环境走 Vite dev proxy。
# 集成模式下云图库 Vue 前端是唯一入口，不再托管本服务 React 前端。
if not settings.integration_mode and settings.frontend_dist.is_dir():
    app.mount("/", StaticFiles(directory=settings.frontend_dist, html=True), name="frontend")
