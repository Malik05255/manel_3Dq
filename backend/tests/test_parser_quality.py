from __future__ import annotations

import numpy as np

from app.parser_quality import adaptive_ocr, assign_openings_to_walls, merge_ocr_lines, verification_scores


def wall(wall_id: str, x1: float, y1: float, x2: float, y2: float, confidence: int = 90):
    return {
        "id": wall_id,
        "start": {"x": x1, "y": y1},
        "end": {"x": x2, "y": y2},
        "confidence": confidence,
        "kind": "remote-segmentation-evidence",
    }


def test_opening_is_attached_to_nearest_verified_wall():
    walls = [wall("top", 10, 10, 90, 10), wall("left", 10, 10, 10, 90)]
    openings = [{"id": "door-1", "type": "door", "x": 48.0, "y": 11.2, "confidence": 92}]
    result = assign_openings_to_walls(openings, walls)
    assert result[0]["wallId"] == "top"
    assert result[0]["wall_distance_pct"] < 2.0


def test_far_opening_is_not_forced_onto_a_wall():
    result = assign_openings_to_walls(
        [{"id": "door-1", "type": "door", "x": 50.0, "y": 50.0, "confidence": 92}],
        [wall("top", 10, 10, 90, 10)],
    )
    assert "wallId" not in result[0]


def test_ocr_dedupe_keeps_best_confidence_at_same_location():
    lines = [
        {"text": "4.20 م", "left_pct": 10, "right_pct": 15, "top_pct": 20, "bottom_pct": 23, "confidence": 51},
        {"text": "4.20 م", "left_pct": 10.3, "right_pct": 15.3, "top_pct": 20.2, "bottom_pct": 23.2, "confidence": 88},
    ]
    merged = merge_ocr_lines(lines)
    assert len(merged) == 1
    assert merged[0]["confidence"] == 88


def test_adaptive_ocr_preserves_seed_when_engine_is_unavailable():
    seed = [{"text": "غرفة نوم 18 m2", "left_pct": 20, "right_pct": 35, "top_pct": 20, "bottom_pct": 25, "confidence": 82}]
    image = np.zeros((100, 100, 3), dtype=np.uint8)
    lines, meta = adaptive_ocr(image, None, seed_lines=seed)
    assert lines == seed
    assert meta["passes"] == 0
    assert meta["numeric_line_count"] == 1


def test_fallback_parser_cannot_claim_ninety_percent():
    walls = [
        wall("w1", 0, 0, 100, 0, 72),
        wall("w2", 100, 0, 100, 100, 72),
        wall("w3", 100, 100, 0, 100, 72),
        wall("w4", 0, 100, 0, 0, 72),
    ]
    quality = verification_scores(
        model_used="opencv-blue-aware-fallback",
        walls=walls,
        rooms=[{"confidence": 70}],
        openings=[],
        ocr_lines=[{"text": "12.5", "confidence": 88}],
        base_confidence=95,
    )
    assert quality["overall_verified"] <= 76


def test_high_confidence_still_requires_numeric_evidence():
    quality = verification_scores(
        model_used="cubicasa-unet-resnet34",
        walls=[wall(f"w{i}", i, 10, i + 20, 10, 92) for i in range(12)],
        rooms=[{"confidence": 92} for _ in range(6)],
        openings=[],
        ocr_lines=[],
        base_confidence=95,
    )
    assert quality["overall_verified"] <= 80
