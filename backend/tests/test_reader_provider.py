from app.reader_provider import _RETRY_DELAYS_SECONDS, _normalize_reader_url, reader_provider


def test_reader_provider_prefers_new_platform_env(monkeypatch):
    monkeypatch.setenv("READER_PROVIDER_URL", "https://reader.example.com/v2/parse")
    monkeypatch.setenv("READER_PROVIDER_NAME", "hai-segmentation-v2")
    monkeypatch.setenv("MODAL_READER_URL", "https://legacy.example.com")

    provider = reader_provider()

    assert provider.ready is True
    assert provider.url == "https://reader.example.com/v2/parse"
    assert provider.name == "hai-segmentation-v2"


def test_reader_provider_normalizes_service_root_to_v2_parse(monkeypatch):
    monkeypatch.setenv("READER_PROVIDER_URL", "https://reader.example.com/")
    monkeypatch.setenv("READER_PROVIDER_NAME", "hai-reader-v2")

    provider = reader_provider()

    assert provider.ready is True
    assert provider.url == "https://reader.example.com/v2/parse"


def test_normalize_reader_url_preserves_explicit_endpoint():
    assert _normalize_reader_url("https://reader.example.com/custom/parse/") == "https://reader.example.com/custom/parse"


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


def test_source_first_retry_budget_covers_render_cold_start():
    assert _RETRY_DELAYS_SECONDS[0] == 0.0
    assert len(_RETRY_DELAYS_SECONDS) >= 6
    assert sum(_RETRY_DELAYS_SECONDS) >= 90.0
    assert _RETRY_DELAYS_SECONDS[-1] >= 30.0
