from __future__ import annotations

import base64
import hmac
import json
import os
import secrets
import time
from typing import Any

import httpx
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from fastapi import Depends, FastAPI, Header, HTTPException, Response
from pydantic import BaseModel, Field

from .geometry import canonicalize_plan

app = FastAPI(title="Manzili HAI Cloud Gateway", version="0.71.0")


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
        os.getenv("SUPABASE_PUBLISHABLE_KEY", "").strip(),
    )


def _modal_config() -> tuple[str, str, str]:
    return (
        os.getenv("MODAL_READER_URL", "").rstrip("/"),
        os.getenv("MODAL_READER_TOKEN", "").strip(),
        os.getenv("MODAL_GATEWAY_SIGNING_KEY", "").strip(),
    )


def _modal_ready() -> bool:
    url, proxy_token, signing_key = _modal_config()
    return bool(url and (proxy_token or signing_key))


def _b64url_decode(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def _modal_request_headers(body: bytes) -> dict[str, str]:
    _, proxy_token, signing_key = _modal_config()
    headers = {"Content-Type": "application/json"}
    if proxy_token:
        headers["Authorization"] = f"Bearer {proxy_token}"
        return headers
    if not signing_key:
        raise HTTPException(503, "Modal reader authentication is not configured")
    try:
        private_key = Ed25519PrivateKey.from_private_bytes(_b64url_decode(signing_key))
    except Exception as exc:
        raise HTTPException(503, "Modal gateway signing key is invalid") from exc
    timestamp = str(int(time.time()))
    nonce = secrets.token_urlsafe(16)
    message = timestamp.encode("ascii") + b"." + nonce.encode("ascii") + b"." + body
    signature = base64.urlsafe_b64encode(private_key.sign(message)).decode("ascii").rstrip("=")
    headers["X-Manzili-Timestamp"] = timestamp
    headers["X-Manzili-Nonce"] = nonce
    headers["X-Manzili-Signature"] = signature
    return headers


def _modal_body(payload: ParseRequest) -> bytes:
    return json.dumps(
        payload.model_dump(),
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


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
        raise HTTPException(401, "invalid backend token")
    async with httpx.AsyncClient(timeout=20) as client:
        response = await client.get(
            f"{supabase_url}/auth/v1/user",
            headers={"Authorization": f"Bearer {token}", "apikey": publishable},
        )
    if response.status_code != 200:
        raise HTTPException(401, "invalid backend token")
    body = response.json()
    if not isinstance(body, dict) or not body.get("id"):
        raise HTTPException(401, "invalid backend token")
    return body


async def backend_principal(authorization: str | None = Header(default=None)) -> dict[str, Any]:
    token = _bearer(authorization)
    static_token = os.getenv("MANZILI_API_TOKEN", "").strip()
    if static_token and hmac.compare_digest(token, static_token):
        return {"id": "service", "auth": "service"}
    user = await _supabase_user(token)
    user["auth"] = "supabase"
    return user


async def cloud_user(authorization: str | None = Header(default=None)) -> dict[str, Any]:
    return await _supabase_user(_bearer(authorization))


@app.get("/health")
async def health() -> dict[str, Any]:
    worker_url, worker_token = _render_worker_config()
    supabase_url, publishable = _supabase_config()
    return {
        "ok": True,
        "version": app.version,
        "git_commit": _deployed_git_commit(),
        "reader": "modal-raster2seq-cloud-only",
        "modal_reader_configured": _modal_ready(),
        "local_inference": False,
        "ai_configured": bool(os.getenv("AI_API_KEY")),
        "service_auth_configured": bool(os.getenv("MANZILI_API_TOKEN")),
        "supabase_configured": bool(supabase_url and publishable),
        "render_worker_configured": bool(worker_url and worker_token),
        "local_blender": False,
    }


@app.get("/readyz")
async def readyz() -> dict[str, Any]:
    if not _modal_ready():
        raise HTTPException(503, "Modal reader is not configured")
    return {
        "ok": True,
        "version": app.version,
        "git_commit": _deployed_git_commit(),
        "reader": "modal-raster2seq-cloud-only",
        "modal_reader_configured": True,
        "local_inference": False,
    }


@app.get("/v1/parser/status")
async def parser_status(_: dict[str, Any] = Depends(backend_principal)) -> dict[str, Any]:
    ready = _modal_ready()
    return {
        "ready": ready,
        "preferred_path": "modal-raster2seq-cloud-only" if ready else "unavailable",
        "local_inference": False,
    }


@app.get("/v1/render/status")
async def render_status(_: dict[str, Any] = Depends(backend_principal)) -> dict[str, Any]:
    worker_url, worker_token = _render_worker_config()
    ready = bool(worker_url and worker_token)
    return {
        "ready": ready,
        "preferred_path": "remote-blender-worker" if ready else "unavailable",
        "worker_configured": ready,
        "local": False,
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
        response = await client.post(
            endpoint,
            json=outgoing,
            headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
        )
    if response.status_code >= 400:
        raise HTTPException(response.status_code, response.text[:600])
    return response.json()


@app.post("/v1/parse-floorplan")
async def parse_floorplan(payload: ParseRequest, _: dict[str, Any] = Depends(backend_principal)) -> dict[str, Any]:
    modal_url, _, _ = _modal_config()
    if not _modal_ready():
        raise HTTPException(503, "Modal reader is not configured")
    body = _modal_body(payload)
    async with httpx.AsyncClient(timeout=330) as client:
        response = await client.post(
            modal_url,
            content=body,
            headers=_modal_request_headers(body),
        )
    if response.status_code >= 400:
        raise HTTPException(response.status_code, response.text[:1200])
    result = response.json()
    if not isinstance(result, dict):
        raise HTTPException(502, "Cloud reader returned an invalid response")
    result["page_index"] = payload.page_index
    result["reader_path"] = "modal-raster2seq-cloud-only"
    result["local_inference"] = False
    return result


@app.post("/v1/render-3d")
async def render_3d(payload: Render3DRequest, _: dict[str, Any] = Depends(backend_principal)) -> Response:
    canonical = canonicalize_plan(payload.plan)
    if not canonical.ready:
        raise HTTPException(422, {"errors": canonical.errors, "warnings": canonical.warnings})
    worker_url, worker_token = _render_worker_config()
    if not worker_url or not worker_token:
        raise HTTPException(503, "remote Blender worker is not configured")
    async with httpx.AsyncClient(timeout=360) as client:
        response = await client.post(
            f"{worker_url}/v1/render-3d",
            json=payload.model_dump(),
            headers={"Authorization": f"Bearer {worker_token}"},
        )
    if response.status_code >= 400:
        raise HTTPException(response.status_code, response.text[:1200])
    return Response(
        content=response.content,
        media_type="model/gltf-binary",
        headers={"X-Manzili-Renderer": response.headers.get("X-Manzili-Renderer", "remote-blender-worker")},
    )


def _supabase_headers(token: str, prefer: str | None = None) -> dict[str, str]:
    _, key = _supabase_config()
    headers = {"Authorization": f"Bearer {token}", "apikey": key, "Content-Type": "application/json"}
    if prefer:
        headers["Prefer"] = prefer
    return headers


@app.get("/v1/projects")
async def list_projects(
    authorization: str | None = Header(default=None),
    _: dict[str, Any] = Depends(cloud_user),
) -> Any:
    token = _bearer(authorization)
    base, _ = _supabase_config()
    async with httpx.AsyncClient(timeout=30) as client:
        response = await client.get(
            f"{base}/rest/v1/manzili_projects",
            params={"select": "id,title,revision,updated_at", "order": "updated_at.desc"},
            headers=_supabase_headers(token),
        )
    if response.status_code >= 400:
        raise HTTPException(response.status_code, response.text[:600])
    return response.json()


@app.get("/v1/projects/{project_id}")
async def get_project(
    project_id: str,
    authorization: str | None = Header(default=None),
    _: dict[str, Any] = Depends(cloud_user),
) -> Any:
    token = _bearer(authorization)
    base, _ = _supabase_config()
    async with httpx.AsyncClient(timeout=30) as client:
        response = await client.get(
            f"{base}/rest/v1/manzili_projects",
            params={"id": f"eq.{project_id}", "select": "id,title,revision,plan,updated_at", "limit": "1"},
            headers=_supabase_headers(token),
        )
    if response.status_code >= 400:
        raise HTTPException(response.status_code, response.text[:600])
    rows = response.json()
    if not rows:
        raise HTTPException(404, "project not found")
    return rows[0]


@app.put("/v1/projects/{project_id}")
async def put_project(
    project_id: str,
    payload: ProjectPayload,
    authorization: str | None = Header(default=None),
    user: dict[str, Any] = Depends(cloud_user),
) -> Any:
    token = _bearer(authorization)
    base, _ = _supabase_config()
    body = {
        "id": project_id,
        "user_id": user["id"],
        "title": payload.title[:160],
        "revision": max(1, payload.revision),
        "plan": payload.plan,
    }
    async with httpx.AsyncClient(timeout=30) as client:
        response = await client.post(
            f"{base}/rest/v1/manzili_projects",
            params={"on_conflict": "id"},
            json=body,
            headers=_supabase_headers(token, "resolution=merge-duplicates,return=representation"),
        )
    if response.status_code >= 400:
        raise HTTPException(response.status_code, response.text[:600])
    rows = response.json()
    return rows[0] if rows else body
