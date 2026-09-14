from app.reader_provider import reader_provider


def test_reader_provider_prefers_new_platform_env(monkeypatch):
    monkeypatch.setenv("READER_PROVIDER_URL", "https://reader.example.com/v2/parse")
    monkeypatch.setenv("READER_PROVIDER_NAME", "hai-segmentation-v2")
    monkeypatch.setenv("MODAL_READER_URL", "https://legacy.example.com")

    provider = reader_provider()

    assert provider.ready is True
    assert provider.url == "https://reader.example.com/v2/parse"
    assert provider.name == "hai-segmentation-v2"


def test_reader_provider_keeps_modal_as_migration_fallback(monkeypatch):
    monkeypatch.delenv("READER_PROVIDER_URL", raising=False)
    monkeypatch.delenv("READER_PROVIDER_NAME", raising=False)
    monkeypatch.setenv("MODAL_READER_URL", "https://legacy.example.com")

    provider = reader_provider()

    assert provider.ready is True
    assert provider.name == "modal-raster2seq-legacy"


def test_reader_provider_rejects_non_https_endpoint(monkeypatch):
    monkeypatch.setenv("READER_PROVIDER_URL", "http://reader.internal/parse")
    monkeypatch.delenv("MODAL_READER_URL", raising=False)

    assert reader_provider().ready is False
