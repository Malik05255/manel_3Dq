from __future__ import annotations

import hmac
import os
from typing import Any

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

app = FastAPI(title="Manzili HAI Cloud Gateway", version="0.70.0")


class ParseRequest(BaseModel):
    image_base64: str = Field(min_length=32, max_length=18_000_000)
    page_index: int = Field(default=0, ge=0, le=32)


def _bearer(value: str | None) -> str:
    if not value or not value.startswith("Bearer "):
        raise HTTPException(401, "missing bearer token")
    token = value[7:].strip()
    if not token:
        raise HTTPException(401, "missing bearer token")
    return token


async def backend_principal(authorization: str | None = Header(default=None)) -> dict[str, Any]:
    token = _bearer(authorization)
    static_token = os.getenv("MANZILI_API_TOKEN", "").strip()
    if static_token and hmac.compare_digest(token, static_token):
        return {"id": "service", "auth": "service"}
    raise HTTPException(401, "invalid backend token")


def _modal_config() -> tuple[str, str]:
    return (
        os.getenv("MODAL_READER_URL", "").rstrip("/"),
        os.getenv("MODAL_READER_TOKEN", "").strip(),
    )


@app.get("/health")
async def health() -> dict[str, Any]:
    modal_url, modal_token = _modal_config()
    return {
        "ok": True,
        "version": app.version,
        "git_commit": os.getenv("RENDER_GIT_COMMIT", "").strip() or None,
        "reader": "modal-cloud-only",
        "modal_reader_configured": bool(modal_url and modal_token),
        "local_inference": False,
    }


@app.get("/readyz")
async def readyz() -> dict[str, Any]:
    modal_url, modal_token = _modal_config()
    if not modal_url or not modal_token:
        raise HTTPException(503, "Modal reader is not configured")
    return {
        "ok": True,
        "version": app.version,
        "git_commit": os.getenv("RENDER_GIT_COMMIT", "").strip() or None,
        "reader": "modal-cloud-only",
        "local_inference": False,
    }


@app.post("/v1/parse-floorplan")
async def parse_floorplan(
    payload: ParseRequest,
    _: dict[str, Any] = Depends(backend_principal),
) -> Any:
    modal_url, modal_token = _modal_config()
    if not modal_url or not modal_token:
        raise HTTPException(503, "Modal reader is not configured")

    async with httpx.AsyncClient(timeout=330) as client:
        response = await client.post(
            modal_url,
            json=payload.model_dump(),
            headers={
                "Authorization": f"Bearer {modal_token}",
                "Content-Type": "application/json",
            },
        )

    if response.status_code >= 400:
        detail = response.text[:1200]
        raise HTTPException(response.status_code, detail)

    body = response.json()
    if not isinstance(body, dict):
        raise HTTPException(502, "Cloud reader returned an invalid response")
    body["page_index"] = payload.page_index
    body["reader_path"] = "modal-cloud-only"
    body["local_inference"] = False
    return body
