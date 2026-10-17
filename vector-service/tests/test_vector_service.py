from __future__ import annotations

import json
import base64
from dataclasses import replace
from io import BytesIO
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient
from PIL import Image

from app import main as main_module
from app.chroma_store import ChromaStore
from app.config import Settings
from app.embedding_client import ServerEmbeddingClient, EmbeddingError


def make_png(path: Path, color: tuple[int, int, int] = (20, 40, 60)) -> bytes:
    buffer = BytesIO()
    Image.new("RGB", (16, 16), color).save(buffer, format="PNG")
    data = buffer.getvalue()
    path.write_bytes(data)
    return data


def test_server_settings_ignore_legacy_cloud_credentials(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("DASHSCOPE_API_KEY", "old-cloud-key")
    monkeypatch.setenv("DASHSCOPE_EMBEDDING_URL", "https://old-cloud.test")
    monkeypatch.delenv("EMBEDDING_API_KEY", raising=False)
    monkeypatch.delenv("EMBEDDING_BASE_URL", raising=False)
    config = Settings()
    assert config.api_key == ""
    assert config.base_url == "http://172.18.20.44:30066/embedding"


def test_new_model_revision_and_dimension_use_isolated_indexes(tmp_path: Path) -> None:
    config = make_settings(tmp_path)
    assert config.collection_name != "gallery_ea7e7fccf0f0"
    assert replace(config, model_revision="checkpoint-61").collection_name != config.collection_name
    assert replace(config, dimension=2048).collection_name != config.collection_name


def make_settings(tmp_path: Path, api_key: str = "test-key") -> Settings:
    return Settings(
        api_key=api_key,
        base_url="https://example.test/embedding",
        model_name="qwen3-vl-embedding-2b-finetuned",
        dimension=4,
        model_revision="checkpoint-60",
        api_timeout_seconds=5,
        chroma_path=tmp_path / "chroma",
        request_limit_mb=2,
    )


def test_service_info_routes() -> None:
    client = TestClient(main_module.app)
    response = client.get("/")
    assert response.status_code == 200
    assert response.json() == {
        "service": "AI 协同云图库向量服务",
        "status": "running",
        "health": "/health",
        "docs": "/docs",
        "openapi": "/openapi.json",
    }
    assert client.get("/favicon.ico").status_code == 204


def test_server_client_sends_kg2_image_contract_and_normalizes(
    tmp_path: Path,
) -> None:
    image_path = tmp_path / "query.png"
    make_png(image_path)

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.headers["Authorization"] == "Bearer test-key"
        if request.method == "GET":
            assert request.url.path == "/embedding/health"
            return httpx.Response(200, json=healthy_response())
        assert request.url.path == "/embedding/embed"
        payload = json.loads(request.content)
        assert payload == {"inputs": [{"type": "image", "image": base64.b64encode(image_path.read_bytes()).decode("ascii")}]}
        return httpx.Response(
            200,
            json=embedding_response(),
        )

    http_client = httpx.Client(transport=httpx.MockTransport(handler))
    client = ServerEmbeddingClient(make_settings(tmp_path), http_client)
    assert client.encode(image_path) == pytest.approx([0.6, 0.8, 0, 0])


def healthy_response() -> dict:
    return {"status": "ok", "model": "qwen3-vl-embedding-2b-finetuned", "dimension": 4,
            "checkpoint": "/models/finetuned/checkpoint-60"}


def embedding_response() -> dict:
    return {"model": "qwen3-vl-embedding-2b-finetuned", "dimension": 4,
            "count": 1, "embeddings": [[3, 4, 0, 0]]}


def test_server_client_needs_no_cloud_api_key(tmp_path: Path) -> None:
    image_path = tmp_path / "query.png"
    make_png(image_path)
    def handler(request: httpx.Request) -> httpx.Response:
        assert "Authorization" not in request.headers
        return httpx.Response(200, json=healthy_response() if request.method == "GET" else embedding_response())
    with httpx.Client(transport=httpx.MockTransport(handler)) as http_client:
        client = ServerEmbeddingClient(make_settings(tmp_path, api_key=""), http_client)
        assert client.configured
        assert client.encode(image_path) == pytest.approx([0.6, 0.8, 0, 0])


@pytest.mark.parametrize("changes", [
    {"model": "other"}, {"dimension": 512}, {"count": 2}, {"embeddings": []},
    {"embeddings": [[0, 0, 0, 0]]}, {"embeddings": [[1, 2, 3]]},
    {"embeddings": [[True, 0, 0, 0]]}, {"embeddings": [[float("nan"), 0, 0, 1]]},
])
def test_incompatible_or_invalid_vectors_never_enter_chroma(tmp_path: Path, changes: dict) -> None:
    image_path = tmp_path / "query.png"
    make_png(image_path)
    def handler(request: httpx.Request) -> httpx.Response:
        data = healthy_response() if request.method == "GET" else {**embedding_response(), **changes}
        return httpx.Response(200, content=json.dumps(data))
    with httpx.Client(transport=httpx.MockTransport(handler)) as http_client:
        client = ServerEmbeddingClient(make_settings(tmp_path), http_client)
        with pytest.raises(EmbeddingError):
            client.encode(image_path)


def test_changed_checkpoint_stops_embedding_before_writing(tmp_path: Path) -> None:
    image_path = tmp_path / "query.png"
    make_png(image_path)
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.method == "GET"
        return httpx.Response(200, json={**healthy_response(), "checkpoint": "/models/checkpoint-61"})
    with httpx.Client(transport=httpx.MockTransport(handler)) as http_client:
        with pytest.raises(EmbeddingError, match="版本"):
            ServerEmbeddingClient(make_settings(tmp_path), http_client).encode(image_path)


def test_chroma_filters_gallery_scope(tmp_path: Path) -> None:
    store = ChromaStore(tmp_path / "db", "gallery_filter_test")
    store.upsert(1, 0, [1.0, 0.0, 0.0])
    store.upsert(2, 101, [0.99, 0.01, 0.0])
    store.upsert(3, 102, [0.98, 0.02, 0.0])

    public_ids = {
        item["pictureId"] for item in store.search([1.0, 0.0, 0.0], [0], 10)
    }
    mixed_ids = {
        item["pictureId"]
        for item in store.search([1.0, 0.0, 0.0], [0, 101], 10)
    }
    assert public_ids == {1}
    assert mixed_ids == {1, 2}
    assert 3 not in mixed_ids
    assert store.existing_ids([1, 2, 2, 999]) == [1, 2]
    store.close()


def test_chroma_groups_only_nearly_identical_vectors(tmp_path: Path) -> None:
    store = ChromaStore(tmp_path / "db", "gallery_duplicate_test")
    store.upsert(1, 0, [1.0, 0.0, 0.0])
    store.upsert(2, 0, [1.0, 0.0, 0.0])
    store.upsert(3, 0, [0.8, 0.2, 0.0])
    store.upsert(4, 0, [0.8, 0.2, 0.0])
    store.upsert(5, 0, [0.0, 1.0, 0.0])

    assert store.duplicate_groups() == [[1, 2], [3, 4]]
    store.close()


class FakeEmbedder:
    configured = True

    def health(self) -> dict:
        return {"status": "ok"}

    def encode(self, _: Path) -> list[float]:
        return [1.0, 0.0, 0.0]

    def close(self) -> None:
        return None


def test_vector_api_upsert_and_search_scope(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    image_path = tmp_path / "api.png"
    image_bytes = make_png(image_path)
    monkeypatch.setattr(main_module, "settings", make_settings(tmp_path))
    with TestClient(main_module.app) as client:
        if main_module.embedding_client is not None:
            main_module.embedding_client.close()
        main_module.embedding_client = FakeEmbedder()
        for picture_id, space_id in ((11, 0), (12, 2001)):
            response = client.post(
                "/vectors/upsert",
                files={"image": ("api.png", image_bytes, "image/png")},
                data={"pictureId": str(picture_id), "spaceId": str(space_id)},
            )
            assert response.status_code == 200

        response = client.post(
            "/vectors/search",
            files={"image": ("api.png", image_bytes, "image/png")},
            data={"allowedSpaceIds": "0", "limit": "10"},
        )
        assert response.status_code == 200
        assert {item["pictureId"] for item in response.json()["items"]} == {11}
        response = client.post(
            "/vectors/existing",
            json={"pictureIds": [11, 12, 999]},
        )
        assert response.status_code == 200
        assert response.json() == {
            "requestedCount": 3,
            "existingCount": 2,
            "pictureIds": [11, 12],
        }
        response = client.post(
            "/vectors/duplicates",
            json={"threshold": 0.999999},
        )
        assert response.status_code == 200
        assert response.json() == {
            "threshold": 0.999999,
            "vectorCount": 2,
            "groupCount": 1,
            "duplicateCount": 1,
            "groups": [[11, 12]],
        }
