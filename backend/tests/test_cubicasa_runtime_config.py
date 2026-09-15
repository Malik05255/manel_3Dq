from app.cubicasa_model import LOW_MEMORY_IMAGE_SIZE, _configured_dtype_name, _configured_image_size


def test_low_memory_profile_defaults_to_352_fp32(monkeypatch):
    monkeypatch.setenv("FLOORPLAN_LOW_MEMORY", "true")
    monkeypatch.delenv("FLOORPLAN_IMAGE_SIZE", raising=False)
    monkeypatch.delenv("FLOORPLAN_DTYPE", raising=False)
    assert LOW_MEMORY_IMAGE_SIZE == 352
    assert _configured_image_size() == 352
    assert _configured_dtype_name() == "float32"


def test_image_size_is_bounded_and_aligned(monkeypatch):
    monkeypatch.setenv("FLOORPLAN_LOW_MEMORY", "true")

    monkeypatch.setenv("FLOORPLAN_IMAGE_SIZE", "367")
    assert _configured_image_size() == 352

    monkeypatch.setenv("FLOORPLAN_IMAGE_SIZE", "900")
    assert _configured_image_size() == 512

    monkeypatch.setenv("FLOORPLAN_IMAGE_SIZE", "100")
    assert _configured_image_size() == 256


def test_full_profile_keeps_512_default(monkeypatch):
    monkeypatch.setenv("FLOORPLAN_LOW_MEMORY", "false")
    monkeypatch.delenv("FLOORPLAN_IMAGE_SIZE", raising=False)
    assert _configured_image_size() == 512


def test_dtype_aliases_are_explicit(monkeypatch):
    monkeypatch.setenv("FLOORPLAN_DTYPE", "fp16")
    assert _configured_dtype_name() == "float16"
    monkeypatch.setenv("FLOORPLAN_DTYPE", "bf16")
    assert _configured_dtype_name() == "bfloat16"
    monkeypatch.setenv("FLOORPLAN_DTYPE", "fp32")
    assert _configured_dtype_name() == "float32"
