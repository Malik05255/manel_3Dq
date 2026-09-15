from fastapi import HTTPException
from fastapi.testclient import TestClient

import app.cloud_gateway as gateway
from app.cloud_public_gateway import app


client = TestClient(app)


def test_readyz_prefers_source_first_v4_without_modal(monkeypatch):
    monkeypatch.setenv("READER_PROVIDER_URL", "https://reader.example.com")
    monkeypatch.setenv("READER_PROVIDER_NAME", "hai-source-first-v4")
    monkeypatch.delenv("MODAL_READER_URL", raising=False)
    monkeypatch.delenv("MODAL_READER_TOKEN", raising=False)

    response = client.get("/readyz")

    assert response.status_code == 200
    body = response.json()
    assert body["version"] == "0.73.0"
    assert body["reader"] == "hai-source-first-v4"
    assert body["reader_provider_configured"] is True
    assert body["modal_fallback_configured"] is False
    assert body["legacy_evidence_configured"] is False
    assert body["source_first_required"] is True
    assert body["local_inference"] is False


def test_health_keeps_modal_as_evidence_only(monkeypatch):
    monkeypatch.setenv("READER_PROVIDER_URL", "https://reader.example.com/v2/parse")
    monkeypatch.setenv("READER_PROVIDER_NAME", "hai-source-first-v4")
    monkeypatch.setenv("MODAL_READER_URL", "https://modal.example.com/parse")
    monkeypatch.setenv("MODAL_READER_TOKEN", "test-token")

    response = client.get("/health")

    assert response.status_code == 200
    body = response.json()
    assert body["reader"] == "hai-source-first-v4"
    assert body["reader_provider_configured"] is True
    assert body["modal_fallback_configured"] is False
    assert body["legacy_evidence_configured"] is True
    assert body["source_first_required"] is True


def test_readyz_rejects_modal_only_configuration(monkeypatch):
    monkeypatch.delenv("READER_PROVIDER_URL", raising=False)
    monkeypatch.delenv("READER_PROVIDER_NAME", raising=False)
    monkeypatch.setenv("MODAL_READER_URL", "https://modal.example.com/parse")
    monkeypatch.setenv("MODAL_READER_TOKEN", "test-token")

    response = client.get("/readyz")

    assert response.status_code == 503
    assert "Source-First Reader V4" in response.json()["detail"]


def test_protected_parse_propagates_source_first_failure_without_legacy(monkeypatch):
    monkeypatch.setenv("MANZILI_API_TOKEN", "service-token")
    monkeypatch.setenv("READER_PROVIDER_URL", "https://reader.example.com")
    monkeypatch.setenv("READER_PROVIDER_NAME", "hai-source-first-v4")
    monkeypatch.setenv("MODAL_READER_URL", "https://modal.example.com/parse")
    monkeypatch.setenv("MODAL_READER_TOKEN", "test-token")

    async def fail_source_first(_: str, __: int):
        raise HTTPException(503, "source-first temporarily unavailable")

    async def forbidden_legacy(*_args, **_kwargs):
        raise AssertionError("legacy reader must never become final geometry")

    monkeypatch.setattr(gateway, "request_reader", fail_source_first)
    monkeypatch.setattr(gateway, "_request_modal_reader", forbidden_legacy)

    response = client.post(
        "/v1/parse-floorplan",
        headers={"Authorization": "Bearer service-token"},
        json={"image_base64": "a" * 64, "page_index": 0},
    )

    assert response.status_code == 503
    assert response.json()["detail"] == "source-first temporarily unavailable"
