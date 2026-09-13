import asyncio

import pytest
from fastapi import HTTPException

from app.main import backend_principal, health


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


def test_health_exposes_real_integration_configuration(monkeypatch):
    monkeypatch.setenv("MANZILI_API_TOKEN", "service-secret")
    monkeypatch.setenv("AI_API_KEY", "ai-secret")
    monkeypatch.setenv("SUPABASE_URL", "https://example.supabase.co")
    monkeypatch.setenv("SUPABASE_PUBLISHABLE_KEY", "publishable-key")
    monkeypatch.setattr("app.main.model_status", lambda: {"configured": True, "backend": "test"})

    status = asyncio.run(health())

    assert status["ai_configured"] is True
    assert status["service_auth_configured"] is True
    assert status["supabase_configured"] is True
    assert status["deep_parser_ready"] is True


def test_health_reports_missing_integrations_without_false_success(monkeypatch):
    monkeypatch.delenv("MANZILI_API_TOKEN", raising=False)
    monkeypatch.delenv("AI_API_KEY", raising=False)
    monkeypatch.delenv("SUPABASE_URL", raising=False)
    monkeypatch.delenv("SUPABASE_PUBLISHABLE_KEY", raising=False)
    monkeypatch.setattr("app.main.model_status", lambda: {"configured": True, "backend": "test"})

    status = asyncio.run(health())

    assert status["deep_parser_ready"] is True
    assert status["ai_configured"] is False
    assert status["service_auth_configured"] is False
    assert status["supabase_configured"] is False
