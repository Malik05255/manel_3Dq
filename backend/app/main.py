from __future__ import annotations

import hmac
import os
from typing import Any

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

from .parser import parse_floorplan

app = FastAPI(title="Manzili HAI Backend", version="0.20.0")


class ParseRequest(BaseModel):
    image_base64: str = Field(min_length=32, max_length=18_000_000)


class ProjectPayload(BaseModel):
    title: str = "مشروعي"
    revision: int = 1
    plan: dict[str, Any]


def _bearer(value: str | None) -> str:
    if not value or not value.startswith("Bearer "):
        raise HTTPException(401, "missing bearer token")
    return value[7:].strip()


async def current_user(authorization: str | None = Header(default=None)) -> dict[str, Any]:
    token = _bearer(authorization)
    supabase_url = os.getenv("SUPABASE_URL", "").rstrip("/")
    publishable = os.getenv("SUPABASE_PUBLISHABLE_KEY", "")
    if supabase_url and publishable:
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

    static_token = os.getenv("MANZILI_API_TOKEN", "")
    if not static_token or not hmac.compare_digest(token, static_token):
        raise HTTPException(401, "backend authentication is not configured")
    return {"id": "self-hosted"}


@app.get("/health")
async def health() -> dict[str, Any]:
    return {
        "ok": True,
        "ai_configured": bool(os.getenv("AI_API_KEY")),
        "supabase_configured": bool(os.getenv("SUPABASE_URL") and os.getenv("SUPABASE_PUBLISHABLE_KEY")),
        "floorplan_model_configured": bool(os.getenv("FLOORPLAN_ONNX_MODEL")),
    }


@app.post("/v1/ai/chat")
async def ai_chat(payload: dict[str, Any], _: dict[str, Any] = Depends(current_user)) -> Any:
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
async def parse_plan(payload: ParseRequest, _: dict[str, Any] = Depends(current_user)) -> dict[str, Any]:
    try:
        return parse_floorplan(payload.image_base64)
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from exc


def _supabase_headers(token: str, prefer: str | None = None) -> dict[str, str]:
    key = os.getenv("SUPABASE_PUBLISHABLE_KEY", "")
    headers = {"Authorization": f"Bearer {token}", "apikey": key, "Content-Type": "application/json"}
    if prefer:
        headers["Prefer"] = prefer
    return headers


@app.get("/v1/projects")
async def list_projects(authorization: str | None = Header(default=None), user: dict[str, Any] = Depends(current_user)) -> Any:
    token = _bearer(authorization)
    base = os.getenv("SUPABASE_URL", "").rstrip("/")
    if not base:
        raise HTTPException(503, "cloud sync is not configured")
    async with httpx.AsyncClient(timeout=30) as client:
        res = await client.get(
            f"{base}/rest/v1/manzili_projects?select=id,title,revision,updated_at&order=updated_at.desc",
            headers=_supabase_headers(token),
        )
    if res.status_code >= 400:
        raise HTTPException(res.status_code, res.text[:600])
    return res.json()


@app.get("/v1/projects/{project_id}")
async def get_project(project_id: str, authorization: str | None = Header(default=None), user: dict[str, Any] = Depends(current_user)) -> Any:
    token = _bearer(authorization)
    base = os.getenv("SUPABASE_URL", "").rstrip("/")
    if not base:
        raise HTTPException(503, "cloud sync is not configured")
    async with httpx.AsyncClient(timeout=30) as client:
        res = await client.get(
            f"{base}/rest/v1/manzili_projects?id=eq.{project_id}&select=id,title,revision,plan,updated_at&limit=1",
            headers=_supabase_headers(token),
        )
    if res.status_code >= 400:
        raise HTTPException(res.status_code, res.text[:600])
    rows = res.json()
    if not rows:
        raise HTTPException(404, "project not found")
    return rows[0]


@app.put("/v1/projects/{project_id}")
async def put_project(
    project_id: str,
    payload: ProjectPayload,
    authorization: str | None = Header(default=None),
    user: dict[str, Any] = Depends(current_user),
) -> Any:
    token = _bearer(authorization)
    base = os.getenv("SUPABASE_URL", "").rstrip("/")
    if not base:
        raise HTTPException(503, "cloud sync is not configured")
    body = {
        "id": project_id,
        "user_id": user["id"],
        "title": payload.title[:160],
        "revision": max(1, payload.revision),
        "plan": payload.plan,
    }
    async with httpx.AsyncClient(timeout=30) as client:
        res = await client.post(
            f"{base}/rest/v1/manzili_projects?on_conflict=id",
            json=body,
            headers=_supabase_headers(token, "resolution=merge-duplicates,return=representation"),
        )
    if res.status_code >= 400:
        raise HTTPException(res.status_code, res.text[:600])
    rows = res.json()
    return rows[0] if rows else body
