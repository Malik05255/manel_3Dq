from __future__ import annotations

import base64

import cv2
import numpy as np

from app.room_recovery import enhance_blue_room_topology


def _encoded_blue_grid() -> str:
    image = np.full((900, 1200, 3), 250, dtype=np.uint8)
    blue = (185, 105, 55)

    cv2.rectangle(image, (100, 100), (1100, 800), blue, 12)

    # 3 x 3 room grid. Internal walls deliberately contain door-sized gaps that the
    # ordinary thin-vector room mask tends to leave open.
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
    return base64.b64encode(encoded.tobytes()).decode("ascii")


def _walls(count: int) -> list[dict]:
    return [
        {
            "id": f"wall-{index}",
            "start": {"x": 5.0, "y": float(index % 90)},
            "end": {"x": 95.0, "y": float(index % 90)},
            "confidence": 82,
        }
        for index in range(count)
    ]


def test_blue_room_topology_recovers_multiple_spaces_without_changing_walls():
    original_walls = _walls(30)
    result = {
        "walls": original_walls,
        "rooms": [
            {
                "id": "old-room-0",
                "name": "مساحة مكتشفة 1",
                "type": "unknown",
                "x": 10.0,
                "y": 10.0,
                "width": 20.0,
                "height": 20.0,
                "area_m2": 0.0,
                "confidence": 70,
                "polygon": [],
            },
            {
                "id": "old-room-1",
                "name": "مساحة مكتشفة 2",
                "type": "unknown",
                "x": 40.0,
                "y": 10.0,
                "width": 20.0,
                "height": 20.0,
                "area_m2": 0.0,
                "confidence": 70,
                "polygon": [],
            },
        ],
        "ocr_lines": [],
        "geometry_recovery": {"selected": "blue-raster"},
        "quality": {},
        "warnings": [],
    }

    updated = enhance_blue_room_topology(_encoded_blue_grid(), result)

    assert updated["walls"] == original_walls
    assert len(updated["rooms"]) >= 8
    assert updated["geometry_recovery"]["room_recovery_used"] is True
    assert updated["geometry_recovery"]["room_count_before"] == 2
    assert updated["geometry_recovery"]["room_count_after"] >= 8
    assert 0.05 <= updated["geometry_recovery"]["room_bridge_ratio"] <= 0.065
    assert updated["quality"]["room_recovery"] == "blue-raster-door-gap-topology"


def test_room_topology_does_not_touch_non_blue_recovery_paths():
    result = {
        "walls": _walls(20),
        "rooms": [],
        "geometry_recovery": {"selected": "cubicasa-semantic"},
    }

    updated = enhance_blue_room_topology(_encoded_blue_grid(), result)

    assert updated is result
