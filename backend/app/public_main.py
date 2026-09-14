from __future__ import annotations

import asyncio
import threading
import time
from collections import defaultdict, deque
from typing import Any

from fastapi import Header, HTTPException, Request

from .cubicasa_model import model_status
from .main import ParseRequest, app
from .parser_v2 import parse_floorplan
from .roboflow_parser import roboflow_status

_ALLOWED_CLIENTS = {"android-accuracy-v4", "android-accuracy-v5"}
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
    local = model_status()
    roboflow = roboflow_status()
    roboflow_ready = bool(roboflow.get("configured"))
    local_ready = bool(local.get("configured"))
    return {
        "ready": roboflow_ready or local_ready,
        "model": local,
        "roboflow": roboflow,
        "preferred_path": (
            "roboflow-first+local-verifier"
            if roboflow_ready
            else "cubicasa-tiled-consensus+accuracy-v3"
            if local_ready
            else "unavailable"
        ),
        "detail": "Parser-only mobile route; AI, cloud projects, and 3D rendering remain authenticated.",
    }


@app.post("/v1/public/parse-floorplan")
async def public_parse_floorplan(
    payload: ParseRequest,
    request: Request,
    x_manzili_parser_client: str | None = Header(default=None),
) -> dict[str, Any]:
    client = _require_android_client(x_manzili_parser_client)
    _enforce_rate_limit(request, client)
    try:
        result = await asyncio.to_thread(parse_floorplan, payload.image_base64)
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from exc
    result["page_index"] = payload.page_index
    result["transport"] = "public-parser-only"
    return result
