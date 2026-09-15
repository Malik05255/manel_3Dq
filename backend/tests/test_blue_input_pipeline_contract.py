import cv2
import numpy as np

from app.blue_input_normalizer import normalize_blue_plan_for_cubicasa


def test_normalized_model_input_preserves_door_sized_gap():
    image = np.full((300, 500, 3), 255, dtype=np.uint8)
    blue = (220, 90, 30)
    cv2.line(image, (40, 150), (205, 150), blue, 10)
    cv2.line(image, (295, 150), (460, 150), blue, 10)

    normalized, meta = normalize_blue_plan_for_cubicasa(image)

    assert meta["applied"] is True
    assert tuple(int(v) for v in normalized[150, 120]) == (0, 0, 0)
    assert tuple(int(v) for v in normalized[150, 380]) == (0, 0, 0)
    # Normalization recolours strokes; it must never bridge a real opening.
    assert int(normalized[150, 250].min()) >= 240
