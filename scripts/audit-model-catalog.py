#!/usr/bin/env python3
"""Audit an exported installer catalog without downloading model weights.

First export the actual Kotlin catalog (Java 17 for Gradle):
  ./gradlew :app:testPlayQaUnitTest --tests '*ModelCatalogExportTest'
Then run:
  python3 scripts/audit-model-catalog.py --output build/reports/model-catalog/live-audit.json

Only public, unauthenticated HTTPS requests are made. Hugging Face immutable-revision
metadata and GitHub release metadata verify file size and SHA-256 where publishers
provide it. Small non-LFS metadata files (<= 8 MiB) are hashed in memory. Vosk and
older release assets may lack a published SHA-256; those remain explicitly marked
as size/link checks rather than falsely claiming a fresh full-content hash check.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import datetime as dt
import hashlib
import json
import re
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from pathlib import Path

USER_AGENT = "Hearth-public-model-catalog-audit/1"
MAX_METADATA_BYTES = 16 * 1024 * 1024
MAX_SMALL_FILE_BYTES = 8 * 1024 * 1024
CACHE: dict[str, tuple[int, dict[str, str], bytes]] = {}
CACHE_LOCK = threading.Lock()


class AuditError(Exception):
    pass


def validate_public_url(url: str) -> None:
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != "https" or parsed.username or parsed.password:
        raise AuditError("Only public HTTPS links without credentials may be audited")


class PublicHttpsRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        validate_public_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


PUBLIC_OPENER = urllib.request.build_opener(PublicHttpsRedirectHandler())


def request(url: str, *, method: str = "GET", limit: int = MAX_METADATA_BYTES,
            headers: dict[str, str] | None = None) -> tuple[int, dict[str, str], bytes]:
    """No credentials, environment tokens or cookies; never read unbounded bodies."""
    validate_public_url(url)
    request_headers = {"User-Agent": USER_AGENT, **(headers or {})}
    for attempt in range(3):
        try:
            with PUBLIC_OPENER.open(urllib.request.Request(url, method=method, headers=request_headers), timeout=35) as response:
                if urllib.parse.urlsplit(response.url).scheme != "https":
                    raise AuditError("Publisher redirected outside HTTPS")
                body = b"" if method == "HEAD" else response.read(limit + 1)
                if len(body) > limit:
                    raise AuditError(f"Response exceeded bounded read ({limit} bytes)")
                return response.status, dict(response.headers), body
        except urllib.error.HTTPError as error:
            if error.code in (429, 500, 502, 503, 504) and attempt < 2:
                time.sleep(attempt + 1)
                continue
            return error.code, dict(error.headers), b""
        except (TimeoutError, OSError) as error:
            if attempt < 2:
                time.sleep(attempt + 1)
                continue
            raise AuditError(type(error).__name__) from error
    raise AssertionError("Unreachable")


def metadata(url: str) -> tuple[int, dict | list | None]:
    # Cache per repository revision/release; keep the lock during the request so
    # concurrent artifacts do not waste publisher rate limits on duplicate calls.
    with CACHE_LOCK:
        if url not in CACHE:
            CACHE[url] = request(url)
        status, _, body = CACHE[url]
    return status, json.loads(body) if status == 200 else None


def status_name(code: int) -> str:
    if 200 <= code < 400:
        return "reachable"
    if code in (404, 410):
        return "missing"
    if code in (401, 403):
        return "access_blocked"
    if code == 429 or code >= 500:
        return "retry_needed"
    return "http_error"


def probe(url: str) -> dict:
    status, headers, _ = request(url, method="HEAD")
    if status in (400, 403, 405, 501):
        # HEAD can be unsupported while downloads work. Never consume a weight:
        # a Range response is probed separately with a one-byte bounded read.
        try:
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Range": "bytes=0-0"})
            with PUBLIC_OPENER.open(req, timeout=35) as response:
                status, headers = response.status, dict(response.headers)
                response.read(1)
        except urllib.error.HTTPError as error:
            status, headers = error.code, dict(error.headers)
    normalized = {k.lower(): v for k, v in headers.items()}
    content_range = normalized.get("content-range", "")
    total = content_range.rsplit("/", 1)[-1] if "/" in content_range else normalized.get("content-length", "")
    return {"status": status_name(status), "http_status": status,
            "remote_bytes": int(total) if total.isdigit() else None,
            **({"retry_after_seconds": int(normalized["retry-after"])}
               if normalized.get("retry-after", "").isdigit() else {})}


def check_artifact(item: dict) -> dict:
    result = {k: item[k] for k in ("id", "category", "url", "bytes", "sha256", "license")}
    try:
        parsed = urllib.parse.urlsplit(item["url"])
        hf = re.fullmatch(r"/([^/]+/[^/]+)/resolve/([0-9a-f]{40})/(.+)", parsed.path)
        gh = re.fullmatch(r"/([^/]+/[^/]+)/releases/download/([^/]+)/(.+)", parsed.path)
        if parsed.hostname == "huggingface.co" and hf:
            repo, revision, name = hf.groups()
            api = f"https://huggingface.co/api/models/{repo}/revision/{revision}?blobs=true"
            status, data = metadata(api)
            result.update(metadata_url=api, http_status=status)
            if status != 200:
                result["status"] = status_name(status)
                return result
            result["publisher_license"] = (data.get("cardData") or {}).get("license")
            if data.get("sha") != revision:
                raise AuditError("Publisher revision does not match pinned revision")
            remote = next((x for x in data.get("siblings", []) if x["rfilename"] == urllib.parse.unquote(name)), None)
            if remote is None:
                result["status"] = "missing"
                return result
            result["remote_bytes"] = remote.get("size")
            result["remote_sha256"] = remote.get("lfs", {}).get("sha256")
            if result["remote_sha256"] is None and item["bytes"] <= MAX_SMALL_FILE_BYTES:
                small_status, _, body = request(item["url"], limit=MAX_SMALL_FILE_BYTES)
                if small_status != 200:
                    result.update(status=status_name(small_status), http_status=small_status)
                    return result
                result["remote_sha256"] = hashlib.sha256(body).hexdigest()
                result["hash_evidence"] = "bounded_small_file_sha256"
            else:
                result["hash_evidence"] = "publisher_lfs_sha256" if result["remote_sha256"] else "not_published"
            # Pinned resolve route also has to work, not merely the metadata API.
            result["download_probe"] = probe(item["url"])
        elif parsed.hostname == "github.com" and gh:
            repo, tag, name = gh.groups()
            api = f"https://api.github.com/repos/{repo}/releases/tags/{tag}"
            status, data = metadata(api)
            result.update(metadata_url=api, http_status=status)
            if status != 200:
                result["status"] = status_name(status)
                return result
            remote = next((x for x in data.get("assets", []) if x["name"] == urllib.parse.unquote(name)), None)
            if remote is None:
                result["status"] = "missing"
                return result
            result["remote_bytes"] = remote["size"]
            digest = remote.get("digest") or ""
            result["remote_sha256"] = digest.removeprefix("sha256:") if digest.startswith("sha256:") else None
            result["hash_evidence"] = "publisher_asset_sha256" if result["remote_sha256"] else "not_published"
            result["download_probe"] = probe(item["url"])
        else:
            result["download_probe"] = probe(item["url"])
            result["remote_bytes"] = result["download_probe"].get("remote_bytes")
            result["remote_sha256"] = None
            result["hash_evidence"] = "not_published"
        if result["remote_bytes"] is not None and result["remote_bytes"] != item["bytes"]:
            result["status"] = "size_mismatch"
        elif result["remote_sha256"] is not None and result["remote_sha256"] != item["sha256"]:
            result["status"] = "sha256_mismatch"
        elif result["download_probe"]["status"] != "reachable":
            result["status"] = result["download_probe"]["status"]
        else:
            size_matches = result["remote_bytes"] == item["bytes"]
            hash_matches = result["remote_sha256"] == item["sha256"]
            result["status"] = ("verified" if size_matches and hash_matches else
                "reachable_size_only" if size_matches else
                "reachable_hash_only" if hash_matches else "reachable_link_only")
    except (AuditError, OSError, ValueError, KeyError, TypeError) as error:
        result.update(status="retry_needed", error=str(error))
    return result


def check_source(url: str) -> dict:
    try:
        return {"url": url, **probe(url)}
    except (AuditError, OSError, ValueError) as error:
        return {"url": url, "status": "retry_needed", "error": str(error)}


def supplemental_sources(root: Path) -> set[str]:
    # These source cards cover server-side voice adapters and the platform translator.
    paths = [root / "app/src/main/java/com/sal7one/transiber/settings/settings_voice_cloud_ui.kt",
             root / "app/src/main/java/com/sal7one/transiber/translation/MlKitSetup.kt"]
    return {url for path in paths for url in re.findall(r'"(https://[^"\s]+)"', path.read_text()) if "$" not in url}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--catalog", type=Path, default=Path("app/build/reports/model-catalog/play-qa.json"))
    parser.add_argument("--output", type=Path, default=Path("build/reports/model-catalog/live-audit.json"))
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--resume-report", type=Path,
                        help="Reuse successful identical checks from a report less than one hour old; retry failures and new links")
    args = parser.parse_args()
    catalog = json.loads(args.catalog.read_text())
    if catalog.get("schemaVersion") != 1:
        parser.error("Unsupported exported catalog schema")
    root = Path(__file__).resolve().parent.parent
    previous_artifacts: dict[str, dict] = {}
    previous_sources: dict[str, dict] = {}
    if args.resume_report:
        previous = json.loads(args.resume_report.read_text())
        checked = dt.datetime.fromisoformat(previous["checkedAt"])
        age = (dt.datetime.now(dt.timezone.utc) - checked).total_seconds()
        if not 0 <= age < 3600:
            parser.error("Resume report must be less than one hour old")
        for item in previous.get("artifacts", []):
            size_matches = item.get("remote_bytes") == item.get("bytes")
            hash_matches = item.get("remote_sha256") == item.get("sha256")
            if (item.get("status") == "verified" and size_matches and hash_matches or
                    item.get("status") == "reachable_size_only" and size_matches and item.get("remote_sha256") is None):
                previous_artifacts[item["id"]] = {**item, "checkedAt": item.get("checkedAt", previous["checkedAt"])}
        previous_sources = {item["url"]: {**item, "checkedAt": item.get("checkedAt", previous["checkedAt"])}
                            for item in previous.get("sources", []) if item.get("status") == "reachable"}
    def artifact_check(item: dict) -> dict:
        old = previous_artifacts.get(item["id"])
        if old and all(old.get(key) == value for key, value in item.items()):
            return {**old, "reusedRecentCheck": True}
        return {**check_artifact(item), "checkedAt": dt.datetime.now(dt.timezone.utc).isoformat()}
    def source_check(url: str) -> dict:
        if url in previous_sources:
            return {**previous_sources[url], "reusedRecentCheck": True}
        return {**check_source(url), "checkedAt": dt.datetime.now(dt.timezone.utc).isoformat()}
    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, min(args.workers, 8))) as pool:
        artifacts = []
        for index, result in enumerate(pool.map(artifact_check, catalog["artifacts"]), 1):
            artifacts.append(result)
            print(f"Artifact {index}/{len(catalog['artifacts'])}: {result['id']} — {result['status']}", flush=True)
        source_urls = sorted(set(catalog["sources"]) | supplemental_sources(root))
        sources = []
        for index, result in enumerate(pool.map(source_check, source_urls), 1):
            sources.append(result)
            print(f"Source {index}/{len(source_urls)}: {result['url']} — {result['status']}", flush=True)
    summary = {"artifacts": dict(Counter(x["status"] for x in artifacts)),
               "sources": dict(Counter(x["status"] for x in sources)),
               "categories": dict(Counter(x["category"] for x in artifacts))}
    report = {"schemaVersion": 1, "checkedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
              "catalogSha256": hashlib.sha256(args.catalog.read_bytes()).hexdigest(),
              "method": "Public publisher metadata and bounded HTTP probes; no model weights downloaded",
              "summary": summary, "artifacts": artifacts, "sources": sources}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps(summary, indent=2))
    print(f"Audit evidence: {args.output}")
    bad = {"missing", "size_mismatch", "sha256_mismatch", "http_error"}
    # Access/rate/server failures remain a non-success exit requiring human review;
    # they are never relabeled as missing files or silently treated as verified.
    return 1 if any(x["status"] in bad | {"access_blocked", "retry_needed"} for x in artifacts + sources) else 0


if __name__ == "__main__":
    sys.exit(main())
