from fastapi import HTTPException
from fastapi.testclient import TestClient

import app.cloud_public_gateway as public_gateway


client = TestClient(public_gateway.app)
ANDROID_HEADERS = {"X-Manzili-Parser-Client": "android-source-first-v4"}


def test_public_status_never_promotes_modal_to_final_reader(monkeypatch):
    monkeypatch.delenv("READER_PROVIDER_URL", raising=False)
    monkeypatch.delenv("READER_PROVIDER_NAME", raising=False)
    monkeypatch.setenv("MODAL_READER_URL", "https://legacy.example.com/parse")
    monkeypatch.setenv("MODAL_READER_TOKEN", "legacy-token")

    response = client.get("/v1/public/parser/status", headers=ANDROID_HEADERS)

    assert response.status_code == 200
    body = response.json()
    assert body["ready"] is False
    assert body["reader"] == "unavailable"
    assert body["preferred_path"] == "unavailable"
    assert body["reader_configured"] is False
    assert body["modal_reader_configured"] is False
    assert body["legacy_evidence_configured"] is True
    assert body["source_first_required"] is True


def test_public_status_reports_source_first_v4_when_configured(monkeypatch):
    monkeypatch.setenv("READER_PROVIDER_URL", "https://reader.example.com")
    monkeypatch.setenv("READER_PROVIDER_NAME", "hai-source-first-v4")
    monkeypatch.setenv("MODAL_READER_URL", "https://legacy.example.com/parse")
    monkeypatch.setenv("MODAL_READER_TOKEN", "legacy-token")

    response = client.get("/v1/public/parser/status", headers=ANDROID_HEADERS)

    assert response.status_code == 200
    body = response.json()
    assert body["ready"] is True
    assert body["reader"] == "hai-source-first-v4"
    assert body["preferred_path"] == "hai-source-first-v4"
    assert body["legacy_evidence_configured"] is True


def test_public_parse_propagates_v4_failure_instead_of_using_legacy(monkeypatch):
    monkeypatch.setenv("READER_PROVIDER_URL", "https://reader.example.com")
    monkeypatch.setenv("READER_PROVIDER_NAME", "hai-source-first-v4")
    monkeypatch.setenv("MODAL_READER_URL", "https://legacy.example.com/parse")
    monkeypatch.setenv("MODAL_READER_TOKEN", "legacy-token")

    async def fail_source_first(_: str, __: int):
        raise HTTPException(503, "source-first temporarily unavailable")

    async def forbidden_legacy(*_args, **_kwargs):
        raise AssertionError("legacy reader must never become final Android geometry")

    monkeypatch.setattr(public_gateway, "request_reader", fail_source_first)
    monkeypatch.setattr(public_gateway, "_request_legacy_modal", forbidden_legacy)

    response = client.post(
        "/v1/public/parse-floorplan",
        headers=ANDROID_HEADERS,
        json={"image_base64": "a" * 64, "page_index": 0},
    )

    assert response.status_code == 503
    assert response.json()["detail"] == "source-first temporarily unavailable"
