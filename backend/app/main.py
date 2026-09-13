from __future__ import annotations

import asyncio
import hmac
import os
from typing import Any

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, Response
from pydantic import BaseModel, Field

from .blender_renderer import blender_status, render_plan_glb
from .cubicasa_model import model_status
from .geometry import canonicalize_plan
from .parser_v2 import parse_floorplan

app = FastAPI(title="Manzili HAI Backend", version="0.64.0")


class ParseRequest(BaseModel):
    image_base64: str = Field(min_length=32, max_length=18_000_000)
    page_index: int = Field(default=0, ge=0, le=32)


class ProjectPayload(BaseModel):
    title: str = "مشروعي"
    revision: int = 1
    plan: dict[str, Any]


class Render3DRequest(BaseModel):
    plan: dict[str, Any]
    project_id: str | None = None


def _bearer(value: str | None) -> str:
    if not value or not value.startswith("Bearer "):
        raise HTTPException(401, "missing bearer token")
    token = value[7:].strip()
    if not token:
        raise HTTPException(401, "missing bearer token")
    return token


def _supabase_config() -> tuple[str, str]:
    return (
        os.getenv("SUPABASE_URL", "").rstrip("/"),
        os.getenv("SUPABASE_PUBLISHABLE_KEY", ""),
    )


def _render_worker_config() -> tuple[str, str]:
    return (
        os.getenv("BLENDER_WORKER_URL", "").rstrip("/"),
        os.getenv("RENDER_WORKER_TOKEN", "").strip(),
    )


def _deployed_git_commit() -> str | None:
    return os.getenv("RENDER_GIT_COMMIT", "").strip() or None


async def _supabase_user(token: str) -> dict[str, Any]:
    supabase_url, publishable = _supabase_config()
    if not supabase_url or not publishable:
        raise HTTPException(503, "Supabase authentication is not configured")

    async with httpx.AsyncClient(timeout=20) as client:
        res = await client.get(
            f"{supabase_url}/auth/v1/user",
            headers={"Authorization": f"Bearer {token}", "apikey": publishable},
        )
    if res.status_code != 200:
        raise HTTPException(401, "invalid Supabase session")

    body = res.json()
    if not body.get("id"):
        raise HTTPException(401, "invalid Supabase user")
    return body


async def backend_principal(authorization: str | None = Header(default=None)) -> dict[str, Any]:
    """Authenticate parser/AI/render calls with either the service token or a Supabase session."""
    token = _bearer(authorization)
    static_token = os.getenv("MANZILI_API_TOKEN", "").strip()
    if static_token and hmac.compare_digest(token, static_token):
        return {"id": "self-hosted", "auth": "service"}

    supabase_url, publishable = _supabase_config()
    if supabase_url and publishable:
        user = await _supabase_user(token)
        user["auth"] = "supabase"
        return user

    raise HTTPException(401, "backend authentication is not configured")


async def cloud_user(authorization: str | None = Header(default=None)) -> dict[str, Any]:
    """Cloud project routes must always use a real Supabase user session for RLS."""
    token = _bearer(authorization)
    return await _supabase_user(token)


@app.get("/health")
async def health() -> dict[str, Any]:
    floorplan = model_status()
    supabase_url, publishable = _supabase_config()
    worker_url, worker_token = _render_worker_config()
    local_blender = blender_status()
    return {
        "ok": True,
        "version": app.version,
        "git_commit": _deployed_git_commit(),
        "ai_configured": bool(os.getenv("AI_API_KEY")),
        "service_auth_configured": bool(os.getenv("MANZILI_API_TOKEN")),
        "supabase_configured": bool(supabase_url and publishable),
        "floorplan_model_configured": bool(floorplan.get("configured")),
        "deep_parser_ready": bool(floorplan.get("configured")),
        "floorplan_model": floorplan,
        "render_worker_configured": bool(worker_url and worker_token),
        "local_blender": local_blender,
    }


@app.get("/readyz")
async def readiness() -> dict[str, Any]:
    floorplan = model_status()
    if not floorplan.get("configured"):
        raise HTTPException(503, "Deep Parser model weights are not loaded")
    return {
        "ok": True,
        "version": app.version,
        "git_commit": _deployed_git_commit(),
        "deep_parser_ready": True,
        "model": floorplan,
    }


@app.get("/v1/parser/status")
async def parser_status(_: dict[str, Any] = Depends(backend_principal)) -> dict[str, Any]:
    status = model_status()
    return {
        "ready": bool(status.get("configured")),
        "model": status,
        "preferred_path": "cubicasa-tiled-consensus+accuracy-v3" if status.get("configured") else "fallback+accuracy-v3",
    }


@app.get("/v1/render/status")
async def render_status(_: dict[str, Any] = Depends(backend_principal)) -> dict[str, Any]:
    worker_url, worker_token = _render_worker_config()
    local = blender_status()
    return {
        "ready": bool(worker_url and worker_token) or bool(local.get("configured")),
        "preferred_path": "remote-blender-worker" if worker_url and worker_token else "local-blender",
        "worker_configured": bool(worker_url and worker_token),
        "local": local,
    }


@app.post("/v1/ai/chat")
async def ai_chat(payload: dict[str, Any], _: dict[str, Any] = Depends(backend_principal)) -> Any:
    endpoint = os.getenv("AI_ENDPOINT", "https://openrouter.ai/api/v1/chat/completions")
    api_key = os.getenv("AI_API_KEY", "")
    if not api_key:
        raise HTTPException(503, "AI provider is not configured on the server")

    outgoing = dict(payload)
    if not outgoing.get("model"):
        outgoing["model"] = os.getenv("AI_MODEL_DEFAULT", "google/gemini-2.5-flash")

    async with httpx.AsyncClient(timeout=180) as client:
        res = await client.post(
            endpoint,
            json=outgoing,
            headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
        )
    if res.status_code >= 400:
        raise HTTPException(res.status_code, res.text[:600])
    return res.json()


@app.post("/v1/parse-floorplan")
async def parse_plan(
    payload: ParseRequest,
    _: dict[str, Any] = Depends(backend_principal),
) -> dict[str, Any]:
    try:
        result = parse_floorplan(payload.image_base64)
        result["page_index"] = payload.page_index
        return result
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from exc


@app.post("/v1/render-3d")
async def render_3d(
    payload: Render3DRequest,
    _: dict[str, Any] = Depends(backend_principal),
) -> Response:
    canonical = canonicalize_plan(payload.plan)
    if not canonical.ready:
        raise HTTPException(422, {"errors": canonical.errors, "warnings": canonical.warnings})

    worker_url, worker_token = _render_worker_config()
    if worker_url:
        if not worker_token:
            raise HTTPException(503, "render worker token is not configured")
        async with httpx.AsyncClient(timeout=360) as client:
            res = await client.post(
                f"{worker_url}/v1/render-3d",
                json=payload.model_dump(),
                headers={"Authorization": f"Bearer {worker_token}"},
            )
        if res.status_code >= 400:
            raise HTTPException(res.status_code, res.text[:1200])
        return Response(
            content=res.content,
            media_type="model/gltf-binary",
            headers={"X-Manzili-Renderer": res.headers.get("X-Manzili-Renderer", "remote-blender-worker")},
        )

    try:
        glb, metadata = await asyncio.to_thread(render_plan_glb, payload.plan)
    except ValueError as exc:
        raise HTTPException(422, str(exc)) from exc
    except RuntimeError as exc:
        raise HTTPException(503, str(exc)) from exc
    return Response(
        content=glb,
        media_type="model/gltf-binary",
        headers={
            "X-Manzili-Renderer": str(metadata.get("renderer", "local-blender")),
            "X-Manzili-Bytes": str(metadata.get("bytes", len(glb))),
        },
    )


class ProjectSyncRequest(BaseModel):
    project: ProjectPayload


@app.post("/v1/projects/sync")
async def sync_project(
    payload: ProjectSyncRequest,
    user: dict[str, Any] = Depends(cloud_user),
    authorization: str | None = Header(default=None),
) -> dict[str, Any]:
    supabase_url, publishable = _supabase_config()
    token = _bearer(authorization)
    user_id = str(user["id"])
    project = payload.project.model_dump()
    project_id = str(project.get("plan", {}).get("id") or project.get("title") or "project")
    row = {
        "user_id": user_id,
        "project_id": project_id,
        "title": project.get("title") or "مشروعي",
        "revision": int(project.get("revision") or 1),
        "payload": project,
    }
    headers = {"Authorization": f"Bearer {token}", "apikey": publishable, "Content-Type": "application/json", "Prefer": "resolution=merge-duplicates,return=representation"}
    async with httpx.AsyncClient(timeout=30) as client:
        res = await client.post(
            f"{supabase_url}/rest/v1/hai_projects?on_conflict=user_id,project_id",
            json=row,
            headers=headers,
        )
    if res.status_code >= 400:
        raise HTTPException(res.status_code, res.text[:800])
    return {"ok": True, "project_id": project_id, "revision": row["revision"]}
