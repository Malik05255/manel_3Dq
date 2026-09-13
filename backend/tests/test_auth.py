import asyncio

import pytest
from fastapi import HTTPException

from app.main import backend_principal


def test_service_token_still_works_when_supabase_is_configured(monkeypatch):
    monkeypatch.setenv("MANZILI_API_TOKEN", "service-secret")
    monkeypatch.setenv("SUPABASE_URL", "https://example.supabase.co")
    monkeypatch.setenv("SUPABASE_PUBLISHABLE_KEY", "publishable-key")

    principal = asyncio.run(backend_principal("Bearer service-secret"))

    assert principal["id"] == "self-hosted"
    assert principal["auth"] == "service"


def test_backend_auth_rejects_missing_bearer(monkeypatch):
    monkeypatch.setenv("MANZILI_API_TOKEN", "service-secret")
    monkeypatch.delenv("SUPABASE_URL", raising=False)
    monkeypatch.delenv("SUPABASE_PUBLISHABLE_KEY", raising=False)

    with pytest.raises(HTTPException) as exc:
        asyncio.run(backend_principal(None))

    assert exc.value.status_code == 401
