from __future__ import annotations

import argparse
import csv
import json
import mimetypes
import re
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Iterable
from urllib.parse import unquote, urlparse

import httpx


PROJECT_ROOT = Path(__file__).resolve().parents[1]
PICSUM_LIST_URL = "https://picsum.photos/v2/list"
PICSUM_NAME_PREFIX = "Picsum #"
PICSUM_NAME_PATTERN = re.compile(r"^Picsum #(\d+) - ")
DEFAULT_API_BASE = "http://127.0.0.1:8080/api"
DEFAULT_VECTOR_BASE = "http://127.0.0.1:18001"


class ImportFailure(RuntimeError):
    """可重试导入失败或最终验收失败。"""


@dataclass(frozen=True)
class SourceImage:
    source_id: str
    author: str
    source_url: str
    original_download_url: str
    original_width: int
    original_height: int
    import_url: str
    gallery_name: str


class GalleryClient:
    def __init__(
        self,
        api_base: str,
        username: str,
        password: str,
        timeout_seconds: float,
        max_connections: int,
    ) -> None:
        self._client = httpx.Client(
            base_url=api_base.rstrip("/"),
            timeout=httpx.Timeout(timeout_seconds, connect=15),
            limits=httpx.Limits(
                max_connections=max_connections,
                max_keepalive_connections=max_connections,
            ),
        )
        self._username = username
        self._password = password

    def __enter__(self) -> GalleryClient:
        self.login()
        return self

    def __exit__(self, *_: object) -> None:
        self._client.close()

    def login(self) -> None:
        response = self._client.post(
            "/user/login",
            json={
                "userAccount": self._username,
                "userPassword": self._password,
            },
        )
        data = self._unwrap(response, "管理员登录")
        if not isinstance(data, dict) or data.get("userRole") != "admin":
            raise ImportFailure("登录账号不是管理员，无法导入公共图库")

    def list_picsum_records(self, page_size: int = 500) -> list[dict[str, Any]]:
        records: list[dict[str, Any]] = []
        current = 1
        total = None
        while total is None or len(records) < total:
            response = self._client.post(
                "/picture/list/page",
                json={
                    "current": current,
                    "pageSize": page_size,
                    "name": PICSUM_NAME_PREFIX,
                    "sortField": "createTime",
                    "sortOrder": "ascend",
                },
            )
            page = self._unwrap(response, "读取已导入图片")
            if not isinstance(page, dict):
                raise ImportFailure("图库分页接口返回格式错误")
            page_records = page.get("records") or []
            if not isinstance(page_records, list):
                raise ImportFailure("图库分页 records 格式错误")
            records.extend(item for item in page_records if isinstance(item, dict))
            total = int(page.get("total", len(records)))
            if not page_records:
                break
            current += 1
        return records

    def find_exact_name(self, name: str) -> dict[str, Any] | None:
        response = self._client.post(
            "/picture/list/page",
            json={"current": 1, "pageSize": 20, "name": name},
        )
        page = self._unwrap(response, f"确认图片 {name}")
        records = page.get("records", []) if isinstance(page, dict) else []
        return next(
            (
                item
                for item in records
                if isinstance(item, dict) and item.get("name") == name
            ),
            None,
        )

    def upload(self, source: SourceImage, retries: int) -> dict[str, Any]:
        last_error = "未知错误"
        for attempt in range(1, retries + 1):
            try:
                response = self._client.post(
                    "/picture/upload/url",
                    json={
                        "fileUrl": source.import_url,
                        "picName": source.gallery_name,
                    },
                )
                data = self._unwrap(response, f"导入 {source.gallery_name}")
                if not isinstance(data, dict) or not data.get("id"):
                    raise ImportFailure("上传成功响应缺少图片 ID")
                return data
            except (httpx.HTTPError, ImportFailure) as exc:
                last_error = str(exc)
                try:
                    existing = self.find_exact_name(source.gallery_name)
                    if existing is not None:
                        return existing
                except (httpx.HTTPError, ImportFailure):
                    pass
                if attempt < retries:
                    time.sleep(min(30, 2 ** (attempt - 1)))
        raise ImportFailure(f"{source.gallery_name} 重试 {retries} 次后仍失败：{last_error}")

    @staticmethod
    def _unwrap(response: httpx.Response, action: str) -> Any:
        try:
            payload = response.json()
        except ValueError as exc:
            raise ImportFailure(
                f"{action}失败：HTTP {response.status_code}，响应不是 JSON"
            ) from exc
        if not response.is_success:
            raise ImportFailure(
                f"{action}失败：HTTP {response.status_code}，{payload}"
            )
        if not isinstance(payload, dict) or payload.get("code") != 0:
            message = payload.get("message") if isinstance(payload, dict) else payload
            raise ImportFailure(f"{action}失败：{message}")
        return payload.get("data")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="可续跑地把 Lorem Picsum 真实摄影图片导入公共图库并核对向量。"
    )
    parser.add_argument("--count", type=int, default=1000, help="目标图片数")
    parser.add_argument("--workers", type=int, default=3, help="并发上传数")
    parser.add_argument(
        "--vector-workers", type=int, default=2, help="缺失向量重试并发数"
    )
    parser.add_argument("--retries", type=int, default=5, help="单项最大重试次数")
    parser.add_argument("--width", type=int, default=800, help="导入图片宽度")
    parser.add_argument("--height", type=int, default=600, help="导入图片高度")
    parser.add_argument("--api-base", default=DEFAULT_API_BASE)
    parser.add_argument("--vector-base", default=DEFAULT_VECTOR_BASE)
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default="admin123")
    parser.add_argument("--timeout", type=float, default=180)
    parser.add_argument(
        "--manifest",
        type=Path,
        default=PROJECT_ROOT / "docs" / "picsum-5000-sources.csv",
    )
    parser.add_argument(
        "--progress",
        type=Path,
        default=PROJECT_ROOT / "runtime-logs" / "picsum-import.jsonl",
    )
    parser.add_argument(
        "--summary",
        type=Path,
        default=PROJECT_ROOT / "runtime-logs" / "picsum-import-summary.json",
    )
    args = parser.parse_args()
    if args.count < 1 or args.count > 5000:
        parser.error("--count 必须在 1 到 5000 之间")
    if args.workers < 1 or args.workers > 12:
        parser.error("--workers 必须在 1 到 12 之间")
    if args.vector_workers < 1 or args.vector_workers > 6:
        parser.error("--vector-workers 必须在 1 到 6 之间")
    if args.width < 64 or args.height < 64:
        parser.error("图片宽高不能小于 64")
    return args


def fetch_sources(count: int, width: int, height: int) -> list[SourceImage]:
    by_id: dict[str, SourceImage] = {}
    page = 1
    with httpx.Client(follow_redirects=True, timeout=30) as client:
        while len(by_id) < count:
            last_error: Exception | None = None
            rows: Any = None
            for attempt in range(1, 6):
                try:
                    response = client.get(
                        PICSUM_LIST_URL,
                        params={"page": page, "limit": 100},
                    )
                    response.raise_for_status()
                    rows = response.json()
                    break
                except (httpx.HTTPError, ValueError) as exc:
                    last_error = exc
                    if attempt < 5:
                        time.sleep(min(20, 2 ** (attempt - 1)))
            if not isinstance(rows, list):
                raise ImportFailure(
                    f"Picsum 第 {page} 页元数据不可用：{last_error or '格式错误'}"
                )
            if not rows:
                break
            for row in rows:
                if not isinstance(row, dict):
                    continue
                source_id = str(row.get("id", "")).strip()
                if not source_id.isdigit() or source_id in by_id:
                    continue
                author = str(row.get("author") or "未知作者").strip()
                source = SourceImage(
                    source_id=source_id,
                    author=author,
                    source_url=str(row.get("url") or "").strip(),
                    original_download_url=str(row.get("download_url") or "").strip(),
                    original_width=int(row.get("width") or 0),
                    original_height=int(row.get("height") or 0),
                    import_url=(
                        f"https://picsum.photos/id/{source_id}/{width}/{height}.jpg"
                    ),
                    gallery_name=f"{PICSUM_NAME_PREFIX}{source_id} - {author}",
                )
                by_id[source_id] = source
                if len(by_id) >= count:
                    break
            page += 1

    supplement_index = 1
    while len(by_id) < count:
        source_id = str(9_000_000 + supplement_index)
        seed = f"cloud-gallery-{supplement_index:04d}"
        if source_id not in by_id:
            by_id[source_id] = SourceImage(
                source_id=source_id,
                author="Lorem Picsum 种子图库",
                source_url="https://picsum.photos/",
                original_download_url=(
                    f"https://picsum.photos/seed/{seed}/{width}/{height}.jpg"
                ),
                original_width=width,
                original_height=height,
                import_url=(
                    f"https://picsum.photos/seed/{seed}/{width}/{height}.jpg"
                ),
                gallery_name=(
                    f"{PICSUM_NAME_PREFIX}{source_id} - Lorem Picsum 种子图库"
                ),
            )
        supplement_index += 1
    return list(by_id.values())


def write_manifest(path: Path, sources: Iterable[SourceImage]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fieldnames = [
        "source_id",
        "author",
        "source_url",
        "original_download_url",
        "original_width",
        "original_height",
        "import_url",
        "gallery_name",
    ]
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        for source in sources:
            writer.writerow(asdict(source))


def records_by_source_id(records: Iterable[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    duplicates: list[str] = []
    for record in records:
        name = str(record.get("name") or "")
        match = PICSUM_NAME_PATTERN.match(name)
        if not match:
            continue
        source_id = match.group(1)
        if source_id in result:
            duplicates.append(source_id)
        else:
            result[source_id] = record
    if duplicates:
        unique = ", ".join(sorted(set(duplicates), key=int)[:10])
        raise ImportFailure(f"数据库中存在重复的 Picsum 来源 ID：{unique}")
    return result


def append_progress(path: Path, source: SourceImage, record: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    event = {
        "time": datetime.now().astimezone().isoformat(timespec="seconds"),
        "sourceId": source.source_id,
        "name": source.gallery_name,
        "pictureId": str(record.get("id")),
        "url": record.get("url"),
        "thumbnailUrl": record.get("thumbnailUrl"),
    }
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(event, ensure_ascii=False) + "\n")


def query_existing_vectors(vector_base: str, picture_ids: Iterable[int]) -> set[int]:
    ids = sorted({int(picture_id) for picture_id in picture_ids})
    existing: set[int] = set()
    with httpx.Client(base_url=vector_base.rstrip("/"), timeout=30) as client:
        for start in range(0, len(ids), 500):
            response = client.post(
                "/vectors/existing",
                json={"pictureIds": ids[start : start + 500]},
            )
            response.raise_for_status()
            data = response.json()
            existing.update(int(item) for item in data.get("pictureIds", []))
    return existing


def resolve_local_path(storage_root: Path, url: str) -> Path:
    path = unquote(urlparse(url).path)
    marker = "/api/files/"
    if marker not in path:
        raise ImportFailure(f"图片 URL 不是本地仓库地址：{url}")
    relative = path.split(marker, 1)[1]
    root = storage_root.resolve()
    candidate = (root / relative).resolve()
    if not candidate.is_relative_to(root):
        raise ImportFailure(f"图片 URL 越出本地仓库：{url}")
    if not candidate.is_file():
        raise ImportFailure(f"本地原图不存在：{candidate}")
    return candidate


def index_one(
    vector_base: str,
    storage_root: Path,
    record: dict[str, Any],
    retries: int,
    output_lock: threading.Lock,
) -> int:
    picture_id = int(record["id"])
    image_path = resolve_local_path(storage_root, str(record.get("url") or ""))
    content_type = mimetypes.guess_type(image_path.name)[0] or "application/octet-stream"
    last_error = "未知错误"
    with httpx.Client(base_url=vector_base.rstrip("/"), timeout=180) as client:
        for attempt in range(1, retries + 1):
            try:
                with image_path.open("rb") as handle:
                    response = client.post(
                        "/vectors/upsert",
                        files={"image": (image_path.name, handle, content_type)},
                        data={"pictureId": str(picture_id), "spaceId": "0"},
                    )
                response.raise_for_status()
                data = response.json()
                if data.get("indexed") is not True:
                    raise ImportFailure(f"向量服务未确认索引：{data}")
                return picture_id
            except (httpx.HTTPError, ValueError, ImportFailure) as exc:
                last_error = str(exc)
                if attempt < retries:
                    time.sleep(min(30, 2 ** (attempt - 1)))
    with output_lock:
        print(f"[向量失败] pictureId={picture_id}：{last_error}", flush=True)
    raise ImportFailure(f"图片 {picture_id} 的向量重试失败")


def verify_public_records(
    records: dict[str, dict[str, Any]], required_ids: set[str]
) -> list[dict[str, Any]]:
    missing = sorted(required_ids - records.keys(), key=int)
    if missing:
        raise ImportFailure(f"数据库缺少 {len(missing)} 张目标图片，示例：{missing[:10]}")
    selected = [records[source_id] for source_id in sorted(required_ids, key=int)]
    invalid = [
        str(record.get("id"))
        for record in selected
        if record.get("spaceId") is not None or int(record.get("reviewStatus", -1)) != 1
    ]
    if invalid:
        raise ImportFailure(
            f"有 {len(invalid)} 张目标图片不是已审核公共图片，示例：{invalid[:10]}"
        )
    return selected


def main() -> int:
    args = parse_args()
    started_at = time.monotonic()
    print(f"正在读取 {args.count} 张 Picsum 真实图片元数据……", flush=True)
    sources = fetch_sources(args.count, args.width, args.height)
    write_manifest(args.manifest, sources)
    source_map = {source.source_id: source for source in sources}
    required_ids = set(source_map)
    print(f"来源清单已写入：{args.manifest}", flush=True)

    output_lock = threading.Lock()
    with GalleryClient(
        args.api_base,
        args.username,
        args.password,
        args.timeout,
        max_connections=max(10, args.workers * 2),
    ) as gallery:
        before_records = records_by_source_id(gallery.list_picsum_records())
        pending = [source for source in sources if source.source_id not in before_records]
        print(
            f"目标 {args.count} 张，已存在 {args.count - len(pending)} 张，待导入 {len(pending)} 张。",
            flush=True,
        )

        imported = 0
        failures: list[str] = []
        with ThreadPoolExecutor(max_workers=args.workers) as executor:
            futures = {
                executor.submit(gallery.upload, source, args.retries): source
                for source in pending
            }
            for future in as_completed(futures):
                source = futures[future]
                try:
                    record = future.result()
                    append_progress(args.progress, source, record)
                    imported += 1
                    completed = args.count - len(pending) + imported
                    if imported <= 3 or imported % 10 == 0 or imported == len(pending):
                        with output_lock:
                            print(
                                f"[导入] {completed}/{args.count}，最新 {source.gallery_name}",
                                flush=True,
                            )
                except Exception as exc:
                    failures.append(f"{source.source_id}: {exc}")
                    with output_lock:
                        print(f"[导入失败] {source.gallery_name}：{exc}", flush=True)

        current_records = records_by_source_id(gallery.list_picsum_records())
        selected_records = verify_public_records(current_records, required_ids)
        if failures:
            print(
                f"请求阶段记录了 {len(failures)} 个失败，但数据库目标数量已满足；继续向量核对。",
                flush=True,
            )

    picture_ids = [int(record["id"]) for record in selected_records]
    existing_vectors = query_existing_vectors(args.vector_base, picture_ids)
    missing_vector_records = [
        record for record in selected_records if int(record["id"]) not in existing_vectors
    ]
    print(
        f"目标图片向量已存在 {len(existing_vectors)}/{args.count}，缺失 {len(missing_vector_records)}。",
        flush=True,
    )

    retried_vectors = 0
    if missing_vector_records:
        storage_root = PROJECT_ROOT / "backend" / "data" / "storage"
        with ThreadPoolExecutor(max_workers=args.vector_workers) as executor:
            futures = {
                executor.submit(
                    index_one,
                    args.vector_base,
                    storage_root,
                    record,
                    args.retries,
                    output_lock,
                ): record
                for record in missing_vector_records
            }
            for future in as_completed(futures):
                future.result()
                retried_vectors += 1
                if retried_vectors % 10 == 0 or retried_vectors == len(futures):
                    print(
                        f"[补向量] {retried_vectors}/{len(futures)}",
                        flush=True,
                    )

    final_vectors = query_existing_vectors(args.vector_base, picture_ids)
    missing_final = sorted(set(picture_ids) - final_vectors)
    if missing_final:
        raise ImportFailure(
            f"最终仍有 {len(missing_final)} 张图片缺少向量，示例：{missing_final[:10]}"
        )

    elapsed = round(time.monotonic() - started_at, 2)
    summary = {
        "targetCount": args.count,
        "existingBefore": args.count - len(pending),
        "importedThisRun": imported,
        "databaseVerified": len(selected_records),
        "vectorVerified": len(final_vectors),
        "retriedVectors": retried_vectors,
        "elapsedSeconds": elapsed,
        "manifest": str(args.manifest.resolve()),
        "completedAt": datetime.now().astimezone().isoformat(timespec="seconds"),
    }
    args.summary.parent.mkdir(parents=True, exist_ok=True)
    args.summary.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2), flush=True)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ImportFailure, httpx.HTTPError) as exc:
        print(f"导入未完成：{exc}", file=sys.stderr, flush=True)
        raise SystemExit(1)
