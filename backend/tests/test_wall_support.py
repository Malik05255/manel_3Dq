import base64
import math

import cv2
import numpy as np

from app.blue_detail_vectorizer import vectorize_blue_detail_walls
from app.room_recovery import enhance_blue_room_topology
from app.source_vectorizer import vectorize_source_walls
from app.wall_detail_recovery import enhance_blue_wall_details
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
    wall_blue = (220, 145, 80)

    cv2.rectangle(image, (80, 70), (740, 530), wall_blue, 12)
    cv2.line(image, (350, 70), (350, 360), wall_blue, 12)
    cv2.line(image, (80, 260), (350, 260), wall_blue, 12)
    cv2.line(image, (520, 260), (740, 260), wall_blue, 12)
    cv2.line(image, (520, 260), (520, 530), wall_blue, 12)

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
    assert len(walls) <= 8
    assert meta["horizontal_count"] <= 3
    assert meta["vertical_count"] <= 4


def test_blue_room_topology_closes_door_gaps_without_changing_wall_vectors():
    image = np.full((900, 1200, 3), 250, dtype=np.uint8)
    blue = (185, 105, 55)
    cv2.rectangle(image, (100, 100), (1100, 800), blue, 12)

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


def _blue_detail_fixture() -> np.ndarray:
    image = np.full((900, 1200, 3), 250, dtype=np.uint8)
    blue = (185, 105, 55)
    cv2.rectangle(image, (100, 100), (1100, 800), blue, 12)

    cv2.line(image, (430, 100), (430, 300), blue, 12)
    cv2.line(image, (430, 365), (430, 800), blue, 12)

    cv2.line(image, (430, 610), (565, 610), blue, 12)

    cv2.line(image, (690, 705), (770, 765), blue, 12)
    return image


def test_blue_detail_vectorizer_preserves_door_gap_short_wall_and_diagonal():
    image = _blue_detail_fixture()
    walls, meta = vectorize_blue_detail_walls(image)

    assert meta["usable"] is True
    assert meta["door_gap_heal_px"] < 20
    assert meta["diagonal_count"] >= 1

    divider_x = 430 / 1200 * 100.0
    divider = [
        wall for wall in walls
        if wall.get("axis") == "v"
        and abs(float(wall["start"]["x"]) - divider_x) <= 1.5
    ]
    assert len(divider) >= 2
    door_top = 300 / 900 * 100.0
    door_bottom = 365 / 900 * 100.0
    assert not any(
        min(float(wall["start"]["y"]), float(wall["end"]["y"])) < door_top
        and max(float(wall["start"]["y"]), float(wall["end"]["y"])) > door_bottom
        for wall in divider
    )

    short_wall = [
        wall for wall in walls
        if wall.get("axis") == "h"
        and 64.0 <= float(wall["start"]["y"]) <= 72.0
        and 8.0 <= abs(float(wall["end"]["x"]) - float(wall["start"]["x"])) <= 16.0
    ]
    assert short_wall


def test_blue_detail_vectorizer_rejects_thin_door_leaf_and_arc():
    image = _blue_detail_fixture()
    blue = (185, 105, 55)

    # Door graphics use the same blue as walls but are intentionally thin. These were
    # previously promoted into 10+ extra diagonal walls in the reported production plan.
    cv2.line(image, (430, 300), (470, 340), blue, 2)
    cv2.ellipse(image, (430, 300), (45, 45), 0, 0, 55, blue, 2)

    walls, meta = vectorize_blue_detail_walls(image)
    diagonals = [wall for wall in walls if wall.get("axis") == "d"]

    assert meta["usable"] is True
    assert meta["diagonal_count"] == 1
    assert len(diagonals) == 1
    assert float(diagonals[0].get("structural_core_support", 0.0)) >= 0.72


def test_blue_wall_detail_recovery_keeps_rooms_and_refines_only_blue_path():
    image = _blue_detail_fixture()
    detailed, meta = vectorize_blue_detail_walls(image)
    assert meta["usable"] is True
    assert len(detailed) >= 7

    ok, encoded = cv2.imencode(".png", image)
    assert ok
    image_base64 = base64.b64encode(encoded.tobytes()).decode("ascii")

    coarse = detailed[: max(5, len(detailed) - 2)]
    rooms = [{"id": "room-1", "name": "غرفة", "polygon": [], "confidence": 78}]
    result = {
        "walls": coarse,
        "rooms": rooms,
        "openings": [],
        "geometry_recovery": {"selected": "blue-raster", "room_recovery_used": True},
        "quality": {"wall_topology": 20},
        "warnings": [],
    }

    updated = enhance_blue_wall_details(image_base64, result)

    assert updated["rooms"] == rooms
    assert updated["geometry_recovery"]["wall_detail_recovery_used"] is True
    assert updated["geometry_recovery"]["wall_count_after_detail"] == len(detailed)
    assert updated["quality"]["wall_detail_recovery"] == "blue-source-door-gap-aware"
