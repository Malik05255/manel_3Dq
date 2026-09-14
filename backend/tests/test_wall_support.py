import math

import cv2
import numpy as np

from app.source_vectorizer import vectorize_source_walls
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


def test_blue_source_vectorizer_ignores_black_phantom_overlay():
    image = np.full((600, 820, 3), 255, dtype=np.uint8)
    wall_blue = (220, 145, 80)  # BGR: architectural blue/purple stroke

    cv2.rectangle(image, (80, 70), (740, 530), wall_blue, 12)
    cv2.line(image, (350, 70), (350, 360), wall_blue, 12)
    cv2.line(image, (80, 260), (350, 260), wall_blue, 12)
    cv2.line(image, (520, 260), (740, 260), wall_blue, 12)
    cv2.line(image, (520, 260), (520, 530), wall_blue, 12)

    # Simulate exactly the old failure mode: a stale black diagonal/polyline overlay
    # crosses real rooms. It must never become source geometry on a coloured CAD plan.
    cv2.line(image, (120, 120), (680, 500), (20, 20, 20), 4)
    cv2.line(image, (130, 500), (680, 330), (20, 20, 20), 4)

    walls, meta = vectorize_source_walls(image)

    assert meta["mode"] == "blue-source"
    assert meta["authoritative"] is True
    assert 6 <= len(walls) <= 20

    long_diagonals = 0
    for wall in walls:
        a, b = wall["start"], wall["end"]
        dx = float(b["x"]) - float(a["x"])
        dy = float(b["y"]) - float(a["y"])
        length = math.hypot(dx, dy)
        angle = abs(math.degrees(math.atan2(dy, dx))) % 180.0
        acute = min(angle, 180.0 - angle)
        if 10.0 < acute < 80.0 and length > 20.0:
            long_diagonals += 1
    assert long_diagonals == 0


def test_source_vectorizer_collapses_thick_wall_edges_to_centerlines():
    image = np.full((500, 700, 3), 255, dtype=np.uint8)
    wall_blue = (220, 145, 80)
    cv2.rectangle(image, (60, 50), (640, 450), wall_blue, 14)
    cv2.line(image, (350, 50), (350, 450), wall_blue, 14)

    walls, meta = vectorize_source_walls(image)

    assert meta["authoritative"] is True
    # A thick rectangle plus one divider is five structural centrelines, not ten-plus
    # Hough edges. Small segmentation details may add one or two fragments.
    assert len(walls) <= 8
    assert meta["horizontal_count"] <= 3
    assert meta["vertical_count"] <= 4
