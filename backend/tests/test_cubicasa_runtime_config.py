from app.cubicasa_model import LOW_MEMORY_IMAGE_SIZE, _configured_image_size


def test_low_memory_profile_defaults_to_384(monkeypatch):
    monkeypatch.setenv("FLOORPLAN_LOW_MEMORY", "true")
    monkeypatch.delenv("FLOORPLAN_IMAGE_SIZE", raising=False)
    assert LOW_MEMORY_IMAGE_SIZE == 384
    assert _configured_image_size() == 384


def test_image_size_is_bounded_and_aligned(monkeypatch):
    monkeypatch.setenv("FLOORPLAN_LOW_MEMORY", "true")

    monkeypatch.setenv("FLOORPLAN_IMAGE_SIZE", "401")
    assert _configured_image_size() == 384

    monkeypatch.setenv("FLOORPLAN_IMAGE_SIZE", "900")
    assert _configured_image_size() == 512

    monkeypatch.setenv("FLOORPLAN_IMAGE_SIZE", "100")
    assert _configured_image_size() == 256


def test_full_profile_keeps_512_default(monkeypatch):
    monkeypatch.setenv("FLOORPLAN_LOW_MEMORY", "false")
    monkeypatch.delenv("FLOORPLAN_IMAGE_SIZE", raising=False)
    assert _configured_image_size() == 512
