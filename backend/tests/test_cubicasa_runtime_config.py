import cv2
import numpy as np

from app.blue_input_normalizer import normalize_blue_plan_for_cubicasa
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


def test_blue_cad_input_is_recoloured_black_without_touching_other_colours():
    image = np.full((240, 320, 3), 255, dtype=np.uint8)
    cv2.line(image, (30, 40), (290, 40), (220, 90, 30), 10)
    cv2.line(image, (30, 190), (290, 190), (40, 170, 40), 2)
    source = image.copy()

    normalized, meta = normalize_blue_plan_for_cubicasa(image)

    assert meta["applied"] is True
    assert meta["mode"] == "blue-to-black"
    assert tuple(int(v) for v in normalized[40, 100]) == (0, 0, 0)
    assert int(normalized[190, 100, 1]) > int(normalized[190, 100, 0])
    assert int(normalized[190, 100, 1]) > int(normalized[190, 100, 2])
    assert np.array_equal(image, source)


def test_black_line_plan_does_not_get_needless_colour_normalization():
    image = np.full((160, 200, 3), 255, dtype=np.uint8)
    cv2.rectangle(image, (20, 20), (180, 140), (0, 0, 0), 6)

    normalized, meta = normalize_blue_plan_for_cubicasa(image)

    assert meta["applied"] is False
    assert meta["mode"] == "original"
    assert np.array_equal(normalized, image)
