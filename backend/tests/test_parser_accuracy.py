from __future__ import annotations

import cv2
import numpy as np

from app.cubicasa_model import _axis_starts
from app.parser_accuracy import dimension_evidence, precision_quality, precision_wall_evidence, wall_topology_score


def _wall(wall_id: str, x1: float, y1: float, x2: float, y2: float, confidence: int = 88):
    return {
        "id": wall_id,
        "start": {"x": x1, "y": y1},
        "end": {"x": x2, "y": y2},
        "confidence": confidence,
        "kind": "test",
    }


def test_tiled_inference_axis_always_covers_tail():
    starts = _axis_starts(2600, 1200, 0.30)
    assert starts[0] == 0
    assert starts[-1] == 1400
    assert len(starts) >= 3


def test_precision_wall_evidence_recovers_blue_and_diagonal_walls():
    image = np.full((900, 1200, 3), 248, dtype=np.uint8)
    blue = (185, 105, 55)  # BGR architectural blue
    cv2.line(image, (100, 120), (1080, 120), blue, 12)
    cv2.line(image, (1080, 120), (1080, 760), blue, 12)
    cv2.line(image, (100, 760), (1080, 760), blue, 12)
    cv2.line(image, (100, 120), (100, 760), blue, 12)
    cv2.line(image, (260, 620), (710, 330), blue, 10)

    walls = precision_wall_evidence(image)

    assert len(walls) >= 5
    assert any(item.get("mask_support", 0) >= 0.58 for item in walls)
    assert any(item.get("band_support", 0) >= 0.22 for item in walls)
    assert any(
        12 <= abs(np.degrees(np.arctan2(
            item["end"]["y"] - item["start"]["y"],
            item["end"]["x"] - item["start"]["x"],
        ))) <= 78
        for item in walls
    )


def test_dimension_evidence_understands_arabic_and_decimal_values():
    lines = [
        {"text": "٤٫٢٠ م", "confidence": 91, "left_pct": 5, "top_pct": 10, "right_pct": 12, "bottom_pct": 13},
        {"text": "3.60", "confidence": 87, "left_pct": 40, "top_pct": 10, "right_pct": 46, "bottom_pct": 13},
        {"text": "2026", "confidence": 99, "left_pct": 80, "top_pct": 80, "right_pct": 90, "bottom_pct": 84},
    ]

    evidence = dimension_evidence(lines)
    values = [round(float(item["value"]), 2) for item in evidence]

    assert 4.20 in values
    assert 3.60 in values
    assert 2026.0 not in values


def test_area_labels_are_not_misused_as_linear_scale_evidence():
    lines = [
        {"text": "19.88 m²", "confidence": 95, "left_pct": 20, "top_pct": 20, "right_pct": 30, "bottom_pct": 24},
        {"text": "مساحة ٣٢٫٥ م2", "confidence": 92, "left_pct": 40, "top_pct": 40, "right_pct": 52, "bottom_pct": 45},
    ]
    assert dimension_evidence(lines) == []


def test_wall_topology_rewards_closed_plan_junctions():
    closed = [
        _wall("top", 10, 10, 90, 10),
        _wall("right", 90, 10, 90, 90),
        _wall("bottom", 90, 90, 10, 90),
        _wall("left", 10, 90, 10, 10),
    ]
    fragments = [
        _wall("a", 5, 5, 20, 5),
        _wall("b", 40, 25, 55, 25),
        _wall("c", 75, 60, 90, 60),
    ]

    assert wall_topology_score(closed) >= 95
    assert wall_topology_score(fragments) <= 20


def test_precision_quality_cannot_claim_high_confidence_for_fragmented_reading():
    base = {"geometry": 92, "ocr": 90, "scale_evidence": 90, "overall_verified": 94, "mapped_openings": 0, "numeric_lines": 8}
    fragments = [
        _wall("a", 5, 5, 20, 5),
        _wall("b", 40, 25, 55, 25),
        _wall("c", 75, 60, 90, 60),
        _wall("d", 15, 80, 30, 80),
    ]
    dimensions = [
        {"value": 4.2, "confidence": 90, "left_pct": 10, "top_pct": 10},
        {"value": 3.6, "confidence": 88, "left_pct": 60, "top_pct": 60},
    ]

    quality = precision_quality(
        base,
        model_used="cubicasa-unet-resnet34",
        walls=fragments,
        rooms=[{"confidence": 90}],
        precision_walls=[],
        dimensions=dimensions,
    )

    assert quality["wall_topology"] < 28
    assert quality["overall_verified"] <= 72
