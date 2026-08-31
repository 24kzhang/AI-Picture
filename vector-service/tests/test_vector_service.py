from __future__ import annotations

import json
from io import BytesIO
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient
from PIL import Image

from app import main as main_module
from app.chroma_store import ChromaStore
from app.config import Settings, load_api_key
from app.dashscope_client import DashScopeEmbeddingClient, DashScopeEmbeddingError


def make_png(path: Path, color: tuple[int, int, int] = (20, 40, 60)) -> bytes:
    buffer = BytesIO()
    Image.new("RGB", (16, 16), color).save(buffer, format="PNG")
    data = buffer.getvalue()
    path.write_bytes(data)
    return data


def test_load_api_key_prefers_environment(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    config_path = tmp_path / "application-local.yml"
    config_path.write_text("aliYunAi:\n  apiKey: 'file-key'\n", encoding="utf-8")
    monkeypatch.setenv("DASHSCOPE_API_KEY", "environment-key")
    assert load_api_key(config_path) == "environment-key"
    monkeypatch.delenv("DASHSCOPE_API_KEY")
    assert load_api_key(config_path) == "file-key"


def make_settings(tmp_path: Path, api_key: str = "test-key") -> Settings:
    return Settings(
        api_key=api_key,
        api_url="https://example.test/embedding",
        model_name="tongyi-embedding-vision-flash-2026-03-06",
        dimension=4,
        res_level=1,
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


def test_dashscope_client_sends_data_uri_and_normalizes(
    tmp_path: Path,
) -> None:
    image_path = tmp_path / "query.png"
    make_png(image_path)

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.headers["Authorization"] == "Bearer test-key"
        payload = json.loads(request.content)
        assert payload["model"] == "tongyi-embedding-vision-flash-2026-03-06"
        assert payload["parameters"] == {"dimension": 4, "res_level": 1}
        assert payload["input"]["contents"][0]["image"].startswith(
            "data:image/png;base64,"
        )
        return httpx.Response(
            200,
            json={"output": {"embeddings": [{"embedding": [3, 4, 0, 0]}]}},
        )

    http_client = httpx.Client(transport=httpx.MockTransport(handler))
    client = DashScopeEmbeddingClient(make_settings(tmp_path), http_client)
    assert client.encode(image_path) == pytest.approx([0.6, 0.8, 0, 0])


def test_dashscope_client_requires_api_key(tmp_path: Path) -> None:
    image_path = tmp_path / "query.png"
    make_png(image_path)
    client = DashScopeEmbeddingClient(make_settings(tmp_path, api_key=""))
    with pytest.raises(DashScopeEmbeddingError, match="DASHSCOPE_API_KEY"):
        client.encode(image_path)
    client.close()


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
