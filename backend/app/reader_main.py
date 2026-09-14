from __future__ import annotations

import asyncio
import hmac
import os
from typing import Any

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

from .cubicasa_model import model_status
from .parser_v2 import parse_floorplan
from .roboflow_parser import roboflow_status

app = FastAPI(title="Manzili HAI Reader V2", version="2.0.0")


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
    roboflow = roboflow_status()
    local = model_status()
    return {
        "ok": True,
        "reader": "hai-hybrid-reader-v2",
        "roboflow_configured": bool(roboflow.get("configured")),
        "local_segmentation_configured": bool(local.get("configured")),
        "strategy": "roboflow-first+opencv-verifier+geometry-quality-gates",
        "ocr": "optional-easyocr-when-installed",
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

    try:
        result = await asyncio.to_thread(parse_floorplan, payload.image_base64)
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from exc

    result["page_index"] = payload.page_index
    result["reader_path"] = "hai-hybrid-reader-v2"
    result["local_inference"] = False
    return result
