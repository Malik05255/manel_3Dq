from __future__ import annotations

import hmac
import os
import threading
import time
from collections import defaultdict, deque
from typing import Any

import httpx
from fastapi import Header, HTTPException, Request

from .cloud_gateway import (
    ParseRequest,
    _gateway_public_key_b64,
    _modal_body,
    _modal_config,
    _modal_ready,
    _modal_request_headers,
    app,
)
from .reader_provider import reader_provider, request_reader

_ALLOWED_CLIENTS = {"android-cloud-only-v1", "android-reader-v2"}
_WINDOW_SECONDS = 60.0
_PER_CLIENT_LIMIT = 12
_GLOBAL_LIMIT = 90
_TRANSIENT_READER_FAILURES = {502, 503, 504}
_rate_lock = threading.Lock()
_per_client: dict[str, deque[float]] = defaultdict(deque)
_global_requests: deque[float] = deque()


def _require_android_client(value: str | None) -> str:
    client = (value or "").strip()
    if client not in _ALLOWED_CLIENTS:
        raise HTTPException(403, "unsupported parser client")
    return client


def _require_reader_service(authorization: str | None) -> None:
    expected = os.getenv("READER_PROVIDER_TOKEN", "").strip()
    if not expected:
        raise HTTPException(503, "reader evidence token is not configured")
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "missing reader evidence bearer token")
    supplied = authorization[7:].strip()
    if not hmac.compare_digest(supplied, expected):
        raise HTTPException(401, "invalid reader evidence bearer token")


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
    ready = _modal_ready() if provider.name == "modal-raster2seq-legacy" else provider.ready
    return {
        "ready": ready,
        "reader": provider.name,
        "preferred_path": provider.name if ready else "unavailable",
        "reader_configured": ready,
        "modal_reader_configured": provider.name == "modal-raster2seq-legacy" and ready,
        "legacy_evidence_configured": _modal_ready(),
        "local_inference": False,
        "detail": "" if ready else "Floor-plan reader provider is not configured on the gateway",
    }


async def _request_legacy_modal(payload: ParseRequest) -> dict[str, Any]:
    modal_url, _ = _modal_config()
    if not _modal_ready():
        raise HTTPException(503, "Modal reader is not configured")
    body = _modal_body(payload)
    async with httpx.AsyncClient(timeout=330) as http:
        response = await http.post(
            modal_url,
            content=body,
            headers=_modal_request_headers(body),
        )
    if response.status_code >= 400:
        content_type = response.headers.get("content-type", "").lower()
        detail = f"Legacy reader returned HTTP {response.status_code}"
        if "application/json" in content_type:
            try:
                payload_json = response.json()
                if isinstance(payload_json, dict) and isinstance(payload_json.get("detail"), str):
                    detail = payload_json["detail"][:500]
            except ValueError:
                pass
        raise HTTPException(response.status_code, detail)
    try:
        result = response.json()
    except ValueError as exc:
        raise HTTPException(502, "Legacy reader returned a non-JSON response") from exc
    if not isinstance(result, dict):
        raise HTTPException(502, "Cloud reader returned an invalid response")
    result["page_index"] = payload.page_index
    result["reader_path"] = "modal-raster2seq-legacy-evidence"
    result["local_inference"] = False
    return result


@app.post("/v1/internal/legacy-floorplan-evidence")
async def internal_legacy_floorplan_evidence(
    payload: ParseRequest,
    authorization: str | None = Header(default=None),
) -> dict[str, Any]:
    """Private migration evidence endpoint used only by HAI Reader V2.

    Modal/Raster2Seq is deliberately demoted to evidence. HAI Reader V2 owns
    wall validation, fusion, confidence gates and the final response.
    """
    _require_reader_service(authorization)
    return await _request_legacy_modal(payload)


async def _emergency_legacy_fallback(payload: ParseRequest, reason: str) -> dict[str, Any]:
    result = await _request_legacy_modal(payload)
    warnings = list(result.get("warnings") or [])
    warnings.append(
        "HAI Reader V2 was temporarily unavailable; emergency legacy fallback was used for this analysis."
    )
    result["warnings"] = list(dict.fromkeys(warnings))
    result["reader_path"] = "modal-raster2seq-emergency-fallback"
    result["fallback_reason"] = reason[:240]
    return result


@app.post("/v1/public/parse-floorplan")
async def public_parse_floorplan(
    payload: ParseRequest,
    request: Request,
    x_manzili_parser_client: str | None = Header(default=None),
) -> dict[str, Any]:
    client = _require_android_client(x_manzili_parser_client)
    _enforce_rate_limit(request, client)
    provider = reader_provider()
    if provider.name == "modal-raster2seq-legacy":
        return await _request_legacy_modal(payload)

    try:
        return await request_reader(payload.image_base64, payload.page_index)
    except HTTPException as exc:
        if exc.status_code in _TRANSIENT_READER_FAILURES and _modal_ready():
            return await _emergency_legacy_fallback(payload, str(exc.detail))
        raise
