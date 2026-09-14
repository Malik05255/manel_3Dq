from __future__ import annotations

import json
import os
from dataclasses import dataclass
from typing import Any

import httpx
from fastapi import HTTPException


@dataclass(frozen=True)
class ReaderProvider:
    url: str
    name: str
    token: str

    @property
    def ready(self) -> bool:
        return self.url.startswith("https://")


def reader_provider() -> ReaderProvider:
    """Resolve the active HAI floor-plan reader without coupling Android to a vendor.

    READER_PROVIDER_* is the new contract. MODAL_READER_* remains a migration
    fallback only so production can move platforms without an app release.
    """
    url = os.getenv("READER_PROVIDER_URL", "").strip().rstrip("/")
    token = os.getenv("READER_PROVIDER_TOKEN", "").strip()
    name = os.getenv("READER_PROVIDER_NAME", "").strip() or "hai-reader-v2"
    if url:
        return ReaderProvider(url=url, name=name, token=token)

    legacy_url = os.getenv("MODAL_READER_URL", "").strip().rstrip("/")
    legacy_token = os.getenv("MODAL_READER_TOKEN", "").strip()
    if legacy_url:
        return ReaderProvider(
            url=legacy_url,
            name="modal-raster2seq-legacy",
            token=legacy_token,
        )
    return ReaderProvider(url="", name="unavailable", token="")


def provider_headers(provider: ReaderProvider) -> dict[str, str]:
    headers = {
        "Content-Type": "application/json",
        "X-Manzili-Reader-Contract": "v2",
    }
    if provider.token:
        headers["Authorization"] = f"Bearer {provider.token}"
    return headers


async def request_reader(image_base64: str, page_index: int) -> dict[str, Any]:
    provider = reader_provider()
    if not provider.ready:
        raise HTTPException(503, "Floor-plan reader provider is not configured")

    body = json.dumps(
        {"image_base64": image_base64, "page_index": page_index},
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    async with httpx.AsyncClient(timeout=330) as client:
        response = await client.post(
            provider.url,
            content=body,
            headers=provider_headers(provider),
        )
    if response.status_code >= 400:
        raise HTTPException(response.status_code, response.text[:1200])
    result = response.json()
    if not isinstance(result, dict):
        raise HTTPException(502, "Cloud reader returned an invalid response")
    result["page_index"] = page_index
    result["reader_path"] = provider.name
    result["local_inference"] = False
    return result
