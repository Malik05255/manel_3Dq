from __future__ import annotations

import threading
import time
from collections import defaultdict, deque
from typing import Any

from fastapi import Header, HTTPException, Request

from .cloud_gateway import ParseRequest, _gateway_public_key_b64, app
from .reader_provider import reader_provider, request_reader

_ALLOWED_CLIENTS = {"android-cloud-only-v1", "android-reader-v2"}
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


@app.get("/v1/public/gateway-key")
async def public_gateway_key() -> dict[str, Any]:
    public_key = _gateway_public_key_b64()
    if not public_key:
        raise HTTPException(503, "gateway signing identity is not configured")
    return {"algorithm": "Ed25519", "public_key": public_key}


@app.get("/v1/public/parser/status")
async def public_parser_status(
    x_manzili_parser_client: str | None = Header(default=None),
) -> dict[str, Any]:
    _require_android_client(x_manzili_parser_client)
    provider = reader_provider()
    return {
        "ready": provider.ready,
        "reader": provider.name,
        "preferred_path": provider.name if provider.ready else "unavailable",
        "reader_configured": provider.ready,
        "modal_reader_configured": provider.name == "modal-raster2seq-legacy" and provider.ready,
        "local_inference": False,
        "detail": "" if provider.ready else "Floor-plan reader provider is not configured on the gateway",
    }


@app.post("/v1/public/parse-floorplan")
async def public_parse_floorplan(
    payload: ParseRequest,
    request: Request,
    x_manzili_parser_client: str | None = Header(default=None),
) -> dict[str, Any]:
    client = _require_android_client(x_manzili_parser_client)
    _enforce_rate_limit(request, client)
    return await request_reader(payload.image_base64, payload.page_index)
