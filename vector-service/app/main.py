from __future__ import annotations

import logging
import tempfile
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, File, Form, HTTPException, Response, UploadFile
from fastapi.concurrency import run_in_threadpool
from pydantic import BaseModel, Field

from .chroma_store import ChromaStore
from .config import settings
from .dashscope_client import DashScopeEmbeddingClient, DashScopeEmbeddingError

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s - %(message)s",
)
logger = logging.getLogger(__name__)

embedding_client: DashScopeEmbeddingClient | None = None
chroma_store: ChromaStore | None = None


@asynccontextmanager
async def lifespan(_: FastAPI):
    global embedding_client, chroma_store
    logger.info("正在初始化百炼 Embedding 与 Chroma 服务")
    embedding_client = DashScopeEmbeddingClient()
    chroma_store = ChromaStore(settings.chroma_path, settings.collection_name)
    logger.info(
        "向量服务初始化完成，model=%s，collection=%s",
        settings.model_name,
        settings.collection_name,
    )
    try:
        yield
    finally:
        if chroma_store is not None:
            chroma_store.close()
        if embedding_client is not None:
            embedding_client.close()


app = FastAPI(
    title="AI 协同云图库百炼向量服务",
    version="2.0.0",
    lifespan=lifespan,
)


class DeleteRequest(BaseModel):
    picture_id: int = Field(alias="pictureId")


class ExistingVectorsRequest(BaseModel):
    pictureIds: list[int] = Field(default_factory=list)


class DuplicateVectorsRequest(BaseModel):
    threshold: float = Field(default=0.999999, ge=0.95, le=1.0)


@app.get("/")
def service_info() -> dict[str, object]:
    return {
        "service": "AI 协同云图库向量服务",
        "status": "running",
        "health": "/health",
        "docs": "/docs",
        "openapi": "/openapi.json",
    }


@app.get("/favicon.ico", include_in_schema=False)
def favicon() -> Response:
    return Response(status_code=204)


@app.get("/health")
def health() -> dict[str, object]:
    if embedding_client is None or chroma_store is None:
        raise HTTPException(status_code=503, detail="服务仍在初始化")
    if not embedding_client.configured:
        raise HTTPException(status_code=503, detail="未配置 DASHSCOPE_API_KEY")
    try:
        chroma_ok = chroma_store.ping()
    except Exception as exc:
        raise HTTPException(status_code=503, detail=f"Chroma 不可用：{exc}") from exc
    return {
        "status": "ok",
        "provider": "阿里云百炼",
        "model": settings.model_name,
        "dimension": settings.dimension,
        "resLevel": settings.res_level,
        "chroma": chroma_ok,
        "vectorCount": chroma_store.count,
        "collection": settings.collection_name,
    }


@app.post("/vectors/upsert")
async def upsert_vector(
    image: UploadFile = File(...),
    picture_id: int = Form(..., alias="pictureId"),
    space_id: int = Form(..., alias="spaceId"),
) -> dict[str, object]:
    path = await _save_upload(image)
    try:
        vector = await run_in_threadpool(_embedder().encode, path)
        _store().upsert(picture_id, space_id, vector)
        return {"pictureId": picture_id, "dimension": len(vector), "indexed": True}
    except DashScopeEmbeddingError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc
    finally:
        path.unlink(missing_ok=True)


@app.post("/vectors/search")
async def search_vectors(
    image: UploadFile = File(...),
    allowed_space_ids: str = Form(..., alias="allowedSpaceIds"),
    limit: int = Form(12),
) -> dict[str, object]:
    spaces = _parse_space_ids(allowed_space_ids)
    path = await _save_upload(image)
    try:
        vector = await run_in_threadpool(_embedder().encode, path)
        items = _store().search(vector, spaces, max(1, min(120, limit)))
        return {"items": items}
    except DashScopeEmbeddingError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc
    finally:
        path.unlink(missing_ok=True)


@app.post("/vectors/delete")
def delete_vector(request: DeleteRequest) -> dict[str, object]:
    _store().delete(request.picture_id)
    return {"pictureId": request.picture_id, "deleted": True}


@app.post("/vectors/existing")
def existing_vectors(request: ExistingVectorsRequest) -> dict[str, object]:
    requested_ids = sorted({int(picture_id) for picture_id in request.pictureIds})
    existing_ids = _store().existing_ids(requested_ids)
    return {
        "requestedCount": len(requested_ids),
        "existingCount": len(existing_ids),
        "pictureIds": existing_ids,
    }


@app.post("/vectors/duplicates")
async def duplicate_vectors(request: DuplicateVectorsRequest) -> dict[str, object]:
    groups = await run_in_threadpool(_store().duplicate_groups, request.threshold)
    return {
        "threshold": request.threshold,
        "vectorCount": _store().count,
        "groupCount": len(groups),
        "duplicateCount": sum(len(group) - 1 for group in groups),
        "groups": groups,
    }

async def _save_upload(upload: UploadFile) -> Path:
    suffix = Path(upload.filename or "image.jpg").suffix or ".jpg"
    max_bytes = settings.request_limit_mb * 1024 * 1024
    with tempfile.NamedTemporaryFile(
        prefix="gallery-vector-", suffix=suffix, delete=False
    ) as handle:
        total = 0
        while chunk := await upload.read(1024 * 1024):
            total += len(chunk)
            if total > max_bytes:
                Path(handle.name).unlink(missing_ok=True)
                raise HTTPException(status_code=413, detail="图片文件过大")
            handle.write(chunk)
        if total == 0:
            Path(handle.name).unlink(missing_ok=True)
            raise HTTPException(status_code=422, detail="图片文件为空")
        return Path(handle.name)


def _parse_space_ids(value: str) -> list[int]:
    try:
        spaces = sorted({int(item.strip()) for item in value.split(",") if item.strip()})
    except ValueError as exc:
        raise HTTPException(status_code=422, detail="allowedSpaceIds 格式错误") from exc
    if not spaces:
        raise HTTPException(status_code=422, detail="检索范围不能为空")
    return spaces


def _embedder() -> DashScopeEmbeddingClient:
    if embedding_client is None:
        raise HTTPException(status_code=503, detail="百炼客户端尚未就绪")
    return embedding_client


def _store() -> ChromaStore:
    if chroma_store is None:
        raise HTTPException(status_code=503, detail="Chroma 尚未就绪")
    return chroma_store
