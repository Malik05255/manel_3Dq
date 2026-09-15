from __future__ import annotations

import cv2
import numpy as np

from app.source_first_reader import _select_source_geometry


def _wall(wall_id: str, x1: float, y1: float, x2: float, y2: float):
    return {
        "id": wall_id,
        "start": {"x": x1, "y": y1},
        "end": {"x": x2, "y": y2},
        "confidence": 94,
        "kind": "semantic-test",
    }


def _clean_blue_plan() -> np.ndarray:
    image = np.full((900, 1200, 3), 252, dtype=np.uint8)
    blue = (190, 105, 55)
    green = (95, 165, 95)
    red = (70, 70, 200)

    # Thick CAD wall network with door-sized breaks.
    cv2.line(image, (100, 100), (1100, 100), blue, 13)
    cv2.line(image, (1100, 100), (1100, 800), blue, 13)
    cv2.line(image, (1100, 800), (100, 800), blue, 13)
    cv2.line(image, (100, 800), (100, 100), blue, 13)
    cv2.line(image, (520, 100), (520, 360), blue, 13)
    cv2.line(image, (520, 430), (520, 800), blue, 13)
    cv2.line(image, (520, 420), (760, 420), blue, 13)
    cv2.line(image, (840, 420), (1100, 420), blue, 13)
    cv2.line(image, (760, 100), (760, 330), blue, 13)

    # Non-structural annotations must not become walls.
    cv2.line(image, (80, 55), (1120, 55), green, 2)
    cv2.line(image, (55, 80), (55, 820), green, 2)
    cv2.putText(image, "16 m2", (850, 260), cv2.FONT_HERSHEY_SIMPLEX, 1.0, red, 2)
    return image


def test_source_pixels_override_even_strong_but_wrong_semantic_geometry():
    image = _clean_blue_plan()
    semantic_mask = np.zeros(image.shape[:2], dtype=np.uint8)
    # This is intentionally coherent but in the wrong place. V3 used to keep it simply
    # because semantic topology was high enough, which caused visible plan distortion.
    semantic = [
        _wall("top", 25, 25, 75, 25),
        _wall("right", 75, 25, 75, 75),
        _wall("bottom", 75, 75, 25, 75),
        _wall("left", 25, 75, 25, 25),
    ]

    walls, room_mask, meta = _select_source_geometry(image, semantic, semantic_mask)

    assert meta["source_authoritative"] is True
    assert str(meta["selected"]).startswith("source-first-blue-source")
    assert meta["source_wall_count"] >= 5
    assert meta["source_pixel_alignment"] >= 82
    assert np.count_nonzero(room_mask) > 0

    # The source shell is near x=8% and x=92%, not the semantic x=25%/75% square.
    xs = [float(w["start"]["x"]) for w in walls] + [float(w["end"]["x"]) for w in walls]
    assert min(xs) < 12.0
    assert max(xs) > 88.0


def test_green_dimensions_and_red_text_do_not_pollute_source_wall_graph():
    image = _clean_blue_plan()
    semantic_mask = np.zeros(image.shape[:2], dtype=np.uint8)

    walls, _, meta = _select_source_geometry(image, [], semantic_mask)

    assert meta["source_authoritative"] is True
    assert 5 <= len(walls) <= 60

    # The green horizontal dimension leader is around y=6.1%. A false wall there would
    # demonstrate that color-layer separation has regressed.
    horizontal_y = []
    for wall in walls:
        start, end = wall["start"], wall["end"]
        if abs(float(start["y"]) - float(end["y"])) < 0.5:
            horizontal_y.append((float(start["y"]) + float(end["y"])) / 2.0)
    assert all(abs(y - 6.1) > 1.2 for y in horizontal_y)
