from fastapi.testclient import TestClient

from app.cloud_public_gateway import app


client = TestClient(app)


def test_readyz_prefers_reader_v3_without_modal(monkeypatch):
    monkeypatch.setenv("READER_PROVIDER_URL", "https://reader.example.com")
    monkeypatch.setenv("READER_PROVIDER_NAME", "hai-reader-v3")
    monkeypatch.delenv("MODAL_READER_URL", raising=False)
    monkeypatch.delenv("MODAL_READER_TOKEN", raising=False)

    response = client.get("/readyz")

    assert response.status_code == 200
    body = response.json()
    assert body["version"] == "0.73.0"
    assert body["reader"] == "hai-reader-v3"
    assert body["reader_provider_configured"] is True
    assert body["modal_fallback_configured"] is False
    assert body["local_inference"] is False


def test_health_reports_modal_only_as_fallback(monkeypatch):
    monkeypatch.setenv("READER_PROVIDER_URL", "https://reader.example.com/v2/parse")
    monkeypatch.setenv("READER_PROVIDER_NAME", "hai-reader-v3")
    monkeypatch.setenv("MODAL_READER_URL", "https://modal.example.com/parse")
    monkeypatch.setenv("MODAL_READER_TOKEN", "test-token")

    response = client.get("/health")

    assert response.status_code == 200
    body = response.json()
    assert body["reader"] == "hai-reader-v3"
    assert body["reader_provider_configured"] is True
    assert body["modal_fallback_configured"] is True


def test_readyz_uses_modal_only_when_reader_v3_is_not_configured(monkeypatch):
    monkeypatch.delenv("READER_PROVIDER_URL", raising=False)
    monkeypatch.delenv("READER_PROVIDER_NAME", raising=False)
    monkeypatch.setenv("MODAL_READER_URL", "https://modal.example.com/parse")
    monkeypatch.setenv("MODAL_READER_TOKEN", "test-token")

    response = client.get("/readyz")

    assert response.status_code == 200
    body = response.json()
    assert body["reader"] == "modal-raster2seq-legacy"
    assert body["reader_provider_configured"] is True
    assert body["modal_fallback_configured"] is True
