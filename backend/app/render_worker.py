from __future__ import annotations

import asyncio
import hmac
import os
from typing import Any

from fastapi import FastAPI, Header, HTTPException, Response
from pydantic import BaseModel

from .blender_renderer import blender_status, render_plan_glb
from .geometry import canonicalize_plan

app = FastAPI(title="Manzili HAI Blender Worker", version="0.63.0")


class RenderRequest(BaseModel):
    plan: dict[str, Any]
    project_id: str | None = None


def _authorize(authorization: str | None) -> None:
    expected = os.getenv("RENDER_WORKER_TOKEN", "").strip()
    if not expected:
        raise HTTPException(503, "render worker authentication is not configured")
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "missing bearer token")
    supplied = authorization[7:].strip()
    if not hmac.compare_digest(supplied, expected):
        raise HTTPException(401, "invalid render worker token")


@app.get("/health")
async def health() -> dict[str, Any]:
    status = blender_status()
    return {
        "ok": bool(status.get("configured")),
        "renderer": "blender-headless",
        "renderer_status": status,
        "auth_configured": bool(os.getenv("RENDER_WORKER_TOKEN", "").strip()),
    }


@app.post("/v1/render-3d")
async def render_3d(payload: RenderRequest, authorization: str | None = Header(default=None)) -> Response:
    _authorize(authorization)
    canonical = canonicalize_plan(payload.plan)
    if not canonical.ready:
        raise HTTPException(422, {"errors": canonical.errors, "warnings": canonical.warnings})
    try:
        glb, metadata = await asyncio.to_thread(render_plan_glb, payload.plan)
    except ValueError as exc:
        raise HTTPException(422, str(exc)) from exc
    except RuntimeError as exc:
        raise HTTPException(503, str(exc)) from exc

    headers = {
        "X-Manzili-Renderer": "blender-headless",
        "X-Manzili-Bytes": str(metadata.get("bytes", len(glb))),
        "Cache-Control": "private, no-store",
    }
    return Response(content=glb, media_type="model/gltf-binary", headers=headers)
