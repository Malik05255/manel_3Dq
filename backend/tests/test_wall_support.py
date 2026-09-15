import base64
import math

import cv2
import numpy as np

from app.room_recovery import enhance_blue_room_topology
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


def test_blue_room_topology_closes_door_gaps_without_changing_wall_vectors():
    image = np.full((900, 1200, 3), 250, dtype=np.uint8)
    blue = (185, 105, 55)
    cv2.rectangle(image, (100, 100), (1100, 800), blue, 12)

    # 3 x 3 rooms with door-sized gaps through every internal partition.
    for x in (433, 766):
        cv2.line(image, (x, 100), (x, 285), blue, 12)
        cv2.line(image, (x, 335), (x, 585), blue, 12)
        cv2.line(image, (x, 635), (x, 800), blue, 12)
    for y in (333, 566):
        cv2.line(image, (100, y), (280, y), blue, 12)
        cv2.line(image, (330, y), (620, y), blue, 12)
        cv2.line(image, (670, y), (950, y), blue, 12)
        cv2.line(image, (1000, y), (1100, y), blue, 12)

    ok, encoded = cv2.imencode(".png", image)
    assert ok
    image_base64 = base64.b64encode(encoded.tobytes()).decode("ascii")
    original_walls = [
        _wall(f"wall-{index}", 5, float(index % 90), 95, float(index % 90))
        for index in range(30)
    ]
    result = {
        "walls": original_walls,
        "rooms": [
            {"id": "r1", "name": "مساحة مكتشفة 1", "type": "unknown", "x": 10.0, "y": 10.0, "width": 20.0, "height": 20.0, "area_m2": 0.0, "confidence": 70, "polygon": []},
            {"id": "r2", "name": "مساحة مكتشفة 2", "type": "unknown", "x": 40.0, "y": 10.0, "width": 20.0, "height": 20.0, "area_m2": 0.0, "confidence": 70, "polygon": []},
        ],
        "ocr_lines": [],
        "geometry_recovery": {"selected": "blue-raster"},
        "quality": {},
        "warnings": [],
    }

    updated = enhance_blue_room_topology(image_base64, result)

    assert updated["walls"] == original_walls
    assert len(updated["rooms"]) >= 8
    assert updated["geometry_recovery"]["room_recovery_used"] is True
    assert updated["geometry_recovery"]["room_count_before"] == 2
    assert updated["geometry_recovery"]["room_count_after"] >= 8
