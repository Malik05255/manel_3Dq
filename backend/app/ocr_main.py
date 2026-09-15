from __future__ import annotations

import base64
import hmac
import os
from typing import Any

import cv2
import numpy as np
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

from .ocr_reader import cloud_ocr_engine_name, cloud_ocr_reader

app = FastAPI(title="Manzili HAI Arabic OCR", version="1.0.0")


class OCRRequest(BaseModel):
    image_base64: str = Field(min_length=16, max_length=18_000_000)


def _authorize(authorization: str | None) -> None:
    expected = os.getenv("OCR_SERVICE_TOKEN", "").strip()
    if not expected:
        return
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "missing OCR bearer token")
    supplied = authorization[7:].strip()
    if not hmac.compare_digest(supplied, expected):
        raise HTTPException(401, "invalid OCR bearer token")


def _decode_image(value: str) -> np.ndarray:
    raw = value.split(",", 1)[-1].strip()
    try:
        payload = base64.b64decode(raw, validate=True)
    except Exception as exc:
        raise HTTPException(400, "invalid base64 image") from exc
    if len(payload) > 14_000_000:
        raise HTTPException(413, "OCR image is too large")
    encoded = np.frombuffer(payload, dtype=np.uint8)
    image = cv2.imdecode(encoded, cv2.IMREAD_COLOR)
    if image is None or image.size == 0:
        raise HTTPException(400, "unsupported OCR image")
    return image


@app.on_event("startup")
def warm_ocr() -> None:
    reader = cloud_ocr_reader()
    if reader is None or cloud_ocr_engine_name() != "tesseract-ara-eng":
        raise RuntimeError("Arabic/English Tesseract OCR is unavailable")


@app.get("/health")
@app.get("/readyz")
async def health() -> dict[str, Any]:
    engine = cloud_ocr_engine_name()
    ready = engine == "tesseract-ara-eng" and cloud_ocr_reader() is not None
    return {
        "ok": ready,
        "ready": ready,
        "service": "manzili-hai-ocr-ar-en",
        "engine": engine,
        "languages": ["ara", "eng"] if ready else [],
        "version": app.version,
    }


@app.post("/v1/ocr")
async def ocr(payload: OCRRequest, authorization: str | None = Header(default=None)) -> dict[str, Any]:
    _authorize(authorization)
    reader = cloud_ocr_reader()
    if reader is None:
        raise HTTPException(503, "OCR engine is unavailable")
    image = _decode_image(payload.image_base64)
    try:
        raw = reader.readtext(image, detail=1, paragraph=False)
    except Exception as exc:
        raise HTTPException(500, f"OCR failed: {type(exc).__name__}") from exc

    results: list[dict[str, Any]] = []
    for item in raw[:900]:
        if not isinstance(item, (list, tuple)) or len(item) < 3:
            continue
        box, text, score = item
        try:
            normalized_box = [[float(point[0]), float(point[1])] for point in box]
            normalized_score = float(score)
        except Exception:
            continue
        results.append({
            "box": normalized_box,
            "text": str(text),
            "score": max(0.0, min(1.0, normalized_score)),
        })
    return {"engine": "tesseract-ara-eng", "results": results}
