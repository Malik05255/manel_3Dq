import cv2
import numpy as np

from app.blue_input_normalizer import normalize_blue_plan_for_cubicasa


def test_blue_plan_is_normalized_to_black_without_blackening_other_colours():
    image = np.full((240, 320, 3), 255, dtype=np.uint8)
    cv2.line(image, (30, 40), (290, 40), (220, 90, 30), 10)  # blue CAD wall in BGR
    cv2.line(image, (30, 190), (290, 190), (40, 170, 40), 2)  # green dimension line
    cv2.putText(image, "ROOM", (80, 130), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (30, 30, 180), 2)

    normalized, meta = normalize_blue_plan_for_cubicasa(image)

    assert meta["applied"] is True
    assert meta["mode"] == "blue-to-black"
    assert tuple(int(v) for v in normalized[40, 100]) == (0, 0, 0)
    # Green dimension evidence is preserved instead of being turned into a wall.
    assert int(normalized[190, 100, 1]) > int(normalized[190, 100, 0])
    assert int(normalized[190, 100, 1]) > int(normalized[190, 100, 2])
    # The source image must remain untouched for OCR and preview.
    assert tuple(int(v) for v in image[40, 100]) != (0, 0, 0)


def test_non_blue_plan_stays_on_original_path():
    image = np.full((160, 200, 3), 255, dtype=np.uint8)
    cv2.rectangle(image, (20, 20), (180, 140), (0, 0, 0), 6)

    normalized, meta = normalize_blue_plan_for_cubicasa(image)

    assert meta["applied"] is False
    assert meta["mode"] == "original"
    assert np.array_equal(normalized, image)
