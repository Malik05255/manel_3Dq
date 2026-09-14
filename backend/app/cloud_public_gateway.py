from __future__ import annotations

import threading
import time
from collections import defaultdict, deque
from typing import Any

import httpx
from fastapi import Header, HTTPException, Request

from .cloud_gateway import ParseRequest, _modal_config, app

_ALLOWED_CLIENTS = {"android-cloud-only-v1"}
_WINDOW_SECONDS = 60.0
_PER_CLIENT_LIMIT = 12
_GLOBAL_LIMIT = 90
_rate_lock = threading.Lock()
_per_client: dict[str, deque[float]] = defaultdict(deque)
_global_requests: deque[float] = deque()


def _require_android_client(value: str | None) -> str:
    client = (value or "").strip()
    if client not in _ALLOWED_CLIENTS:
        raise HTTPException(403, "unsupported parser client")
    return client


def _client_key(request: Request, client: str) -> str:
    forwarded = request.headers.get("x-forwarded-for", "").split(",", 1)[0].strip()
    host = forwarded or (request.client.host if request.client else "unknown")
    return f"{host}:{client}"


def _trim(bucket: deque[float], now: float) -> None:
    cutoff = now - _WINDOW_SECONDS
    while bucket and bucket[0] < cutoff:
        bucket.popleft()


def _enforce_rate_limit(request: Request, client: str) -> None:
    now = time.monotonic()
    key = _client_key(request, client)
    with _rate_lock:
        _trim(_global_requests, now)
        bucket = _per_client[key]
        _trim(bucket, now)
        if len(_global_requests) >= _GLOBAL_LIMIT or len(bucket) >= _PER_CLIENT_LIMIT:
            raise HTTPException(429, "parser rate limit exceeded")
        _global_requests.append(now)
        bucket.append(now)


@app.get("/v1/public/parser/status")
async def public_parser_status(
    x_manzili_parser_client: str | None = Header(default=None),
) -> dict[str, Any]:
    _require_android_client(x_manzili_parser_client)
    modal_url, modal_token = _modal_config()
    ready = bool(modal_url and modal_token)
    return {
        "ready": ready,
        "reader": "modal-raster2seq-cloud-only",
        "preferred_path": "modal-raster2seq-cloud-only" if ready else "unavailable",
        "modal_reader_configured": ready,
        "local_inference": False,
        "detail": "" if ready else "Modal reader is not configured on Render",
    }


@app.post("/v1/public/parse-floorplan")
async def public_parse_floorplan(
    payload: ParseRequest,
    request: Request,
    x_manzili_parser_client: str | None = Header(default=None),
) -> dict[str, Any]:
    client = _require_android_client(x_manzili_parser_client)
    _enforce_rate_limit(request, client)
    modal_url, modal_token = _modal_config()
    if not modal_url or not modal_token:
        raise HTTPException(503, "Modal reader is not configured")
    async with httpx.AsyncClient(timeout=330) as http:
        response = await http.post(
            modal_url,
            json=payload.model_dump(),
            headers={"Authorization": f"Bearer {modal_token}", "Content-Type": "application/json"},
        )
    if response.status_code >= 400:
        raise HTTPException(response.status_code, response.text[:1200])
    body = response.json()
    if not isinstance(body, dict):
        raise HTTPException(502, "Cloud reader returned an invalid response")
    body["page_index"] = payload.page_index
    body["reader_path"] = "modal-raster2seq-cloud-only"
    body["local_inference"] = False
    return body
