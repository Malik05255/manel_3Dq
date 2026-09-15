from __future__ import annotations

import cv2
import numpy as np

from app.parser import _extract_enclosed_rooms
from app.parser_accuracy import wall_topology_score
from app.parser_v3 import _recover_wall_geometry


def _wall(wall_id: str, x1: float, y1: float, x2: float, y2: float):
    return {
        "id": wall_id,
        "start": {"x": x1, "y": y1},
        "end": {"x": x2, "y": y2},
        "confidence": 92,
        "kind": "cubicasa-test",
    }


def test_v3_recovers_clear_blue_plan_when_semantic_walls_are_empty():
    image = np.full((900, 1200, 3), 250, dtype=np.uint8)
    blue = (185, 105, 55)

    # Outer shell plus two internal partitions, representative of common Saudi CAD exports.
    cv2.line(image, (110, 110), (1090, 110), blue, 12)
    cv2.line(image, (1090, 110), (1090, 790), blue, 12)
    cv2.line(image, (1090, 790), (110, 790), blue, 12)
    cv2.line(image, (110, 790), (110, 110), blue, 12)
    cv2.line(image, (590, 110), (590, 790), blue, 12)
    cv2.line(image, (110, 450), (590, 450), blue, 12)

    semantic_mask = np.zeros(image.shape[:2], dtype=np.uint8)
    walls, room_mask, recovery = _recover_wall_geometry(image, [], semantic_mask)

    assert recovery["used"] is True
    assert recovery["selected"] in {"precision-raster", "cubicasa+precision-raster"}
    assert recovery["precision_walls"] >= 4
    assert len(walls) >= 4
    assert wall_topology_score(walls) >= 25
    assert np.count_nonzero(room_mask) > 0

    rooms = _extract_enclosed_rooms(room_mask, confidence=80)
    assert len(rooms) >= 1


def test_v3_keeps_strong_semantic_geometry_without_raster_recovery():
    image = np.full((600, 800, 3), 255, dtype=np.uint8)
    semantic_mask = np.zeros(image.shape[:2], dtype=np.uint8)
    semantic = [
        _wall("top", 10, 10, 90, 10),
        _wall("right", 90, 10, 90, 90),
        _wall("bottom", 90, 90, 10, 90),
        _wall("left", 10, 90, 10, 10),
    ]

    walls, room_mask, recovery = _recover_wall_geometry(image, semantic, semantic_mask)

    assert recovery["used"] is False
    assert recovery["selected"] == "cubicasa-semantic"
    assert walls == semantic
    assert room_mask is semantic_mask
