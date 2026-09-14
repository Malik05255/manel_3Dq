from __future__ import annotations

import asyncio
import hmac
import os
from typing import Any

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

from .cubicasa_model import model_status
from .ocr_reader import cloud_ocr_engine_name
from .parser_v3 import parse_floorplan

app = FastAPI(title="Manzili HAI Reader V3", version="3.0.0")


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


@app.get("/health")
@app.get("/readyz")
async def health() -> dict[str, Any]:
    runtime = model_status()
    ready = bool(runtime.get("configured"))
    return {
        "ok": ready,
        "ready": ready,
        "reader": "cubicasa-unet-resnet34-v3",
        "version": app.version,
        "local_segmentation_configured": ready,
        "strategy": "semantic-segmentation-wall-centrelines+door-window-masks+ocr",
        "model": runtime,
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
    if not bool(model_status().get("configured")):
        raise HTTPException(503, "CubiCasa segmentation model is not loaded")

    try:
        result = await asyncio.to_thread(parse_floorplan, payload.image_base64)
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from exc
    except Exception as exc:
        raise HTTPException(500, f"CubiCasa reader failed: {type(exc).__name__}") from exc

    result["page_index"] = payload.page_index
    result["reader_path"] = "cubicasa-unet-resnet34-v3"
    result["local_inference"] = False
    return result
