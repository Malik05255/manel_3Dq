import base64

import cv2
import numpy as np

from app import parser


class EmptyRuntime:
    def predict(self, image):
        h, w = image.shape[:2]
        return np.zeros((h, w), dtype=np.uint8)


def test_sparse_segmentation_recovers_reviewable_walls_and_spaces(monkeypatch):
    image = np.full((600, 800, 3), 255, dtype=np.uint8)
    cv2.rectangle(image, (80, 70), (720, 530), (0, 0, 0), 10)
    cv2.line(image, (400, 70), (400, 530), (0, 0, 0), 8)
    ok, encoded = cv2.imencode(".jpg", image)
    assert ok
    payload = base64.b64encode(encoded.tobytes()).decode("ascii")

    monkeypatch.setattr(parser, "load_cubicasa_runtime", lambda: EmptyRuntime())
    monkeypatch.setattr(parser, "_ocr", lambda _image: [])

    result = parser.parse_floorplan(payload)

    assert len(result["walls"]) >= 3
    assert len(result["rooms"]) >= 1
    assert all(len(room["polygon"]) >= 3 for room in result["rooms"])
    assert "opencv-recovery" in result["model_used"]
    assert any("insufficient wall geometry" in warning for warning in result["warnings"])
