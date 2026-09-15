import cv2
import numpy as np

from app.blue_wall_cleanup import clean_normalized_blue_walls


def _wall(identifier, axis, x1, y1, x2, y2):
    return {
        "id": identifier,
        "axis": axis,
        "start": {"x": x1 / 12.0, "y": y1 / 9.0},
        "end": {"x": x2 / 12.0, "y": y2 / 9.0},
        "image_support": 1.0,
        "confidence": 90,
    }


def test_cleanup_removes_thin_axis_door_graphics_and_merges_duplicates():
    mask = np.zeros((900, 1200), dtype=np.uint8)
    cv2.line(mask, (100, 100), (1100, 100), 255, 12)
    cv2.line(mask, (430, 100), (430, 300), 255, 12)
    cv2.line(mask, (430, 365), (430, 800), 255, 12)
    cv2.line(mask, (430, 610), (565, 610), 255, 12)

    # Thin same-colour door/jamb graphics that must not become structural walls.
    cv2.line(mask, (600, 300), (600, 335), 255, 2)
    cv2.line(mask, (600, 335), (638, 335), 255, 2)

    walls = [
        _wall("top-a", "h", 100, 100, 1100, 100),
        _wall("top-duplicate", "h", 102, 102, 1098, 102),
        _wall("divider-top", "v", 430, 100, 430, 300),
        _wall("divider-bottom", "v", 430, 365, 430, 800),
        _wall("short-real", "h", 430, 610, 565, 610),
        _wall("fake-v", "v", 600, 300, 600, 335),
        _wall("fake-h", "h", 600, 335, 638, 335),
    ]

    cleaned, meta = clean_normalized_blue_walls(mask, walls)

    assert meta["rejected_thin"] >= 2
    assert meta["merged"] >= 1
    assert len(cleaned) == 4
    assert not any(wall["id"] in {"fake-v", "fake-h"} for wall in cleaned)

    divider = [wall for wall in cleaned if wall.get("axis") == "v"]
    assert len(divider) == 2
    assert not any(
        min(float(wall["start"]["y"]), float(wall["end"]["y"])) < (300 / 9.0)
        and max(float(wall["start"]["y"]), float(wall["end"]["y"])) > (365 / 9.0)
        for wall in divider
    )


def test_cleanup_keeps_very_short_thick_partition_but_rejects_longer_thin_door_mark():
    mask = np.zeros((900, 1200), dtype=np.uint8)
    # A genuinely structural short stub, typical around bathrooms/corridors.
    cv2.line(mask, (300, 500), (312, 500), 255, 12)
    # A longer door/jamb graphic drawn in the same colour but only 2 px thick.
    cv2.line(mask, (600, 300), (600, 340), 255, 2)

    walls = [
        _wall("short-thick", "h", 300, 500, 312, 500),
        _wall("long-thin", "v", 600, 300, 600, 340),
    ]

    cleaned, meta = clean_normalized_blue_walls(mask, walls)

    assert len(cleaned) == 1
    assert cleaned[0]["start"]["x"] <= 300 / 12.0 + 0.2
    assert cleaned[0]["end"]["x"] >= 312 / 12.0 - 0.2
    assert cleaned[0]["axis"] == "h"
    assert meta["rejected_thin"] == 1
    assert meta["short_structural_kept"] == 1


def test_cleanup_keeps_real_diagonal_unchanged():
    mask = np.zeros((900, 1200), dtype=np.uint8)
    cv2.line(mask, (690, 705), (770, 765), 255, 12)
    diagonal = _wall("diag", "d", 690, 705, 770, 765)

    cleaned, meta = clean_normalized_blue_walls(mask, [diagonal])

    assert len(cleaned) == 1
    assert cleaned[0]["axis"] == "d"
    assert meta["diagonal_kept"] == 1
