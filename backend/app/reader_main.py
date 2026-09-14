from __future__ import annotations

import asyncio
import hmac
import os
from typing import Any

import httpx
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

from .cubicasa_model import model_status
from .ocr_reader import cloud_ocr_engine_name
from .parser_v2 import parse_floorplan
from .roboflow_parser import roboflow_status

app = FastAPI(title="Manzili HAI Reader V2", version="2.2.0")


class ParseRequest(BaseModel):
    image_base64: str = Field(min_length=32, max_length=18_000_000)
    page_index: int = Field(default=0, ge=0, le=32)


def _authorize(authorization: str | None) -> None:
    expected = os.getenv("READER_SERVICE_TOKEN", "").strip()
    if not expected:
        return
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "missing reader bearer token")
    supplied = authorization[7:].strip()
    if not hmac.compare_digest(supplied, expected):
        raise HTTPException(401, "invalid reader bearer token")


def _legacy_evidence_config() -> tuple[str, str]:
    return (
        os.getenv("LEGACY_EVIDENCE_URL", "").strip(),
        os.getenv("LEGACY_EVIDENCE_TOKEN", "").strip() or os.getenv("READER_SERVICE_TOKEN", "").strip(),
    )


async def _fetch_legacy_evidence(payload: ParseRequest) -> tuple[dict[str, Any] | None, str | None]:
    url, token = _legacy_evidence_config()
    if not url:
        return None, None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    try:
        async with httpx.AsyncClient(timeout=330) as client:
            response = await client.post(url, json=payload.model_dump(), headers=headers)
    except Exception as exc:
        return None, f"Legacy cloud evidence was unavailable: {type(exc).__name__}."
    if response.status_code >= 400:
        return None, f"Legacy cloud evidence returned HTTP {response.status_code}; source-first parsing continued without it."
    try:
        body = response.json()
    except Exception:
        return None, "Legacy cloud evidence returned an invalid JSON response."
    if not isinstance(body, dict):
        return None, "Legacy cloud evidence returned an invalid response shape."
    return body, None


@app.get("/health")
@app.get("/readyz")
async def health() -> dict[str, Any]:
    roboflow = roboflow_status()
    local = model_status()
    legacy_url, _ = _legacy_evidence_config()
    return {
        "ok": True,
        "reader": "hai-source-first-reader-v2",
        "version": app.version,
        "roboflow_configured": bool(roboflow.get("configured")),
        "local_segmentation_configured": bool(local.get("configured")),
        "legacy_evidence_configured": bool(legacy_url),
        "strategy": "source-pixel-vectorizer+room/opening-evidence+strict-fallback-gates",
        "ocr": cloud_ocr_engine_name(),
    }


@app.post("/v2/parse")
async def parse(
    payload: ParseRequest,
    authorization: str | None = Header(default=None),
    x_manzili_reader_contract: str | None = Header(default=None),
) -> dict[str, Any]:
    _authorize(authorization)
    if x_manzili_reader_contract not in {None, "v2"}:
        raise HTTPException(400, "unsupported reader contract")

    external_evidence, evidence_warning = await _fetch_legacy_evidence(payload)
    try:
        result = await asyncio.to_thread(
            parse_floorplan,
            payload.image_base64,
            external_evidence=external_evidence,
        )
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from exc

    if evidence_warning:
        warnings = list(result.get("warnings") or [])
        warnings.append(evidence_warning)
        result["warnings"] = list(dict.fromkeys(warnings))

    result["page_index"] = payload.page_index
    result["reader_path"] = "hai-source-first-reader-v2"
    result["local_inference"] = False
    return result