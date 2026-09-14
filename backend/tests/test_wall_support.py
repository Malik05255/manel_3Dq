import cv2
import numpy as np

from app.wall_support import validate_wall_image_support


def _wall(identifier, x1, y1, x2, y2):
    return {
        "id": identifier,
        "start": {"x": x1, "y": y1},
        "end": {"x": x2, "y": y2},
        "confidence": 90,
    }


def test_rejects_long_unsupported_diagonal():
    image = np.full((500, 700, 3), 255, dtype=np.uint8)
    cv2.rectangle(image, (70, 60), (630, 440), (0, 0, 0), 8)
    cv2.line(image, (350, 60), (350, 440), (0, 0, 0), 8)

    real_vertical = _wall("real", 50, 12, 50, 88)
    phantom = _wall("phantom", 12, 18, 88, 82)

    kept, rejected = validate_wall_image_support(image, [real_vertical, phantom])

    assert any(item["id"] == "real" for item in kept)
    assert any(item["id"] == "phantom" for item in rejected)


def test_keeps_supported_diagonal():
    image = np.full((500, 700, 3), 255, dtype=np.uint8)
    cv2.line(image, (70, 80), (630, 420), (0, 0, 0), 10)
    supported = _wall("diagonal", 10, 16, 90, 84)

    kept, rejected = validate_wall_image_support(image, [supported])

    assert any(item["id"] == "diagonal" for item in kept)
    assert not rejected
