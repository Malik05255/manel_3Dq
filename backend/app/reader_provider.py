from __future__ import annotations

import asyncio
import json
import os
from dataclasses import dataclass
from typing import Any
from urllib.parse import urlsplit, urlunsplit

import httpx
from fastapi import HTTPException


_RETRYABLE_STATUS = {502, 503, 504}
_RETRY_DELAYS_SECONDS = (0.0, 1.0, 2.5)


@dataclass(frozen=True)
class ReaderProvider:
    url: str
    name: str
    token: str

    @property
    def ready(self) -> bool:
        return self.url.startswith("https://")


def _normalize_reader_url(raw_url: str) -> str:
    """Accept either a full v2 contract endpoint or a service root URL.

    Render service URLs are commonly configured as only
    ``https://service.onrender.com``. Source-First Reader V4 still exposes the
    stable POST /v2/parse wire contract, so root URLs are normalized
    automatically instead of producing a 404/HTML proxy response at runtime.
    """
    value = raw_url.strip()
    if not value:
        return ""

    parts = urlsplit(value)
    path = parts.path.rstrip("/")
    if parts.scheme == "https" and parts.netloc and not path:
        path = "/v2/parse"
    return urlunsplit((parts.scheme, parts.netloc, path, parts.query, parts.fragment))


def reader_provider() -> ReaderProvider:
    """Resolve the active HAI floor-plan reader without coupling Android to a vendor.

    READER_PROVIDER_* is the production contract for Source-First Reader V4.
    MODAL_READER_* remains discoverable as migration/evidence infrastructure for
    non-public internal routes, but Android's public parser is not allowed to use
    it as final geometry.
    """
    url = _normalize_reader_url(os.getenv("READER_PROVIDER_URL", ""))
    token = os.getenv("READER_PROVIDER_TOKEN", "").strip()
    name = os.getenv("READER_PROVIDER_NAME", "").strip() or "hai-source-first-v4"
    if url:
        return ReaderProvider(url=url, name=name, token=token)

    legacy_url = os.getenv("MODAL_READER_URL", "").strip().rstrip("/")
    legacy_token = os.getenv("MODAL_READER_TOKEN", "").strip()
    if legacy_url:
        return ReaderProvider(
            url=legacy_url,
            name="modal-raster2seq-legacy",
            token=legacy_token,
        )
    return ReaderProvider(url="", name="unavailable", token="")


def provider_headers(provider: ReaderProvider) -> dict[str, str]:
    headers = {
        "Content-Type": "application/json",
        "X-Manzili-Reader-Contract": "v2",
    }
    if provider.token:
        headers["Authorization"] = f"Bearer {provider.token}"
    return headers


def _safe_provider_error(response: httpx.Response) -> str:
    content_type = response.headers.get("content-type", "").lower()
    if "application/json" in content_type:
        try:
            payload = response.json()
            if isinstance(payload, dict):
                detail = payload.get("detail")
                if isinstance(detail, str) and detail.strip():
                    return detail.strip()[:500]
        except ValueError:
            pass
    return f"HAI reader provider returned HTTP {response.status_code}"


async def request_reader(image_base64: str, page_index: int) -> dict[str, Any]:
    provider = reader_provider()
    if not provider.ready:
        raise HTTPException(503, "Floor-plan reader provider is not configured")

    body = json.dumps(
        {"image_base64": image_base64, "page_index": page_index},
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")

    last_transport_error: Exception | None = None
    last_response: httpx.Response | None = None

    async with httpx.AsyncClient(timeout=httpx.Timeout(330.0, connect=20.0)) as client:
        for attempt, delay in enumerate(_RETRY_DELAYS_SECONDS):
            if delay:
                await asyncio.sleep(delay)
            try:
                response = await client.post(
                    provider.url,
                    content=body,
                    headers=provider_headers(provider),
                )
            except (httpx.TimeoutException, httpx.RequestError) as exc:
                last_transport_error = exc
                if attempt + 1 < len(_RETRY_DELAYS_SECONDS):
                    continue
                raise HTTPException(503, "HAI reader is temporarily unreachable") from exc

            last_response = response
            if response.status_code in _RETRYABLE_STATUS and attempt + 1 < len(_RETRY_DELAYS_SECONDS):
                continue
            break

    if last_response is None:
        raise HTTPException(503, "HAI reader is temporarily unreachable") from last_transport_error

    if last_response.status_code >= 400:
        detail = _safe_provider_error(last_response)
        if last_response.status_code in _RETRYABLE_STATUS:
            raise HTTPException(503, detail)
        raise HTTPException(last_response.status_code, detail)

    try:
        result = last_response.json()
    except ValueError as exc:
        raise HTTPException(502, "HAI reader returned a non-JSON response") from exc
    if not isinstance(result, dict):
        raise HTTPException(502, "HAI reader returned an invalid response")

    result["page_index"] = page_index
    result["reader_path"] = provider.name
    result["local_inference"] = False
    return result
