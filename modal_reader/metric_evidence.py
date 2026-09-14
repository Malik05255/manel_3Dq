from __future__ import annotations

import math
import re
from dataclasses import dataclass
from typing import Any

_DIGIT_TRANSLATION = str.maketrans(
    "٠١٢٣٤٥٦٧٨٩۰۱۲۳۴۵۶۷۸۹٫٬",
    "01234567890123456789.,",
)
_AREA_RE = re.compile(r"(?:m\s*[²2]|م\s*[²2]|متر\s*مربع|مساح(?:ة|ه)|area)", re.IGNORECASE)
_RATIO_RE = re.compile(r"\b\d+\s*[:/]\s*\d+\b")
_NUMBER_RE = re.compile(
    r"(?<!\d)(\d{1,5}(?:[.]\d{1,3})?)\s*(mm|cm|m|مم|سم|م)?(?!\d)",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class DimensionCandidate:
    axis: str
    value_m: float
    text: str
    confidence: int
    explicit_unit: bool
    center_x: float
    center_y: float
    edge_side: str


def normalize_digits(text: str) -> str:
    return str(text or "").translate(_DIGIT_TRANSLATION).replace(",", "").strip()


def _linear_value_m(text: str) -> tuple[float, bool] | None:
    normalized = normalize_digits(text)
    if not normalized or _AREA_RE.search(normalized) or _RATIO_RE.search(normalized) or "°" in normalized:
        return None

    for match in _NUMBER_RE.finditer(normalized):
        raw = float(match.group(1))
        unit = (match.group(2) or "").lower()
        explicit = bool(unit)
        if unit in {"mm", "مم"}:
            value_m = raw / 1000.0
        elif unit in {"cm", "سم"}:
            value_m = raw / 100.0
        elif unit in {"m", "م"}:
            value_m = raw
        else:
            # Unit-less architectural dimensions are common. Interpret only conventional
            # drawing ranges, but never let these alone produce a trusted scale.
            if 0.5 <= raw <= 80.0:
                value_m = raw
            elif 100.0 <= raw <= 999.0:
                value_m = raw / 100.0
            elif 1000.0 <= raw <= 80000.0:
                value_m = raw / 1000.0
            else:
                continue
        if 0.5 <= value_m <= 80.0:
            return value_m, explicit
    return None


def _axis_and_side(line: dict[str, Any]) -> tuple[str, str, int] | None:
    left = float(line.get("left_pct", 0.0) or 0.0)
    top = float(line.get("top_pct", 0.0) or 0.0)
    right = float(line.get("right_pct", left) or left)
    bottom = float(line.get("bottom_pct", top) or top)
    width = max(0.05, right - left)
    height = max(0.05, bottom - top)
    cx = (left + right) / 2.0
    cy = (top + bottom) / 2.0

    near_top = cy <= 20.0
    near_bottom = cy >= 80.0
    near_left = cx <= 20.0
    near_right = cx >= 80.0
    horizontal_shape = width >= height * 1.25
    vertical_shape = height >= width * 1.25

    if (near_top or near_bottom) and (not (near_left or near_right) or horizontal_shape):
        return "horizontal", "top" if near_top else "bottom", 24 + (9 if horizontal_shape else 0)
    if (near_left or near_right) and (not (near_top or near_bottom) or vertical_shape):
        return "vertical", "left" if near_left else "right", 24 + (9 if vertical_shape else 0)
    if horizontal_shape and (cy <= 28.0 or cy >= 72.0):
        return "horizontal", "top" if cy < 50.0 else "bottom", 17
    if vertical_shape and (cx <= 28.0 or cx >= 72.0):
        return "vertical", "left" if cx < 50.0 else "right", 17
    return None


def dimension_candidates(ocr_lines: list[dict[str, Any]]) -> list[DimensionCandidate]:
    candidates: list[DimensionCandidate] = []
    for line in ocr_lines:
        parsed = _linear_value_m(str(line.get("text") or ""))
        if parsed is None:
            continue
        axis = _axis_and_side(line)
        if axis is None:
            continue
        value_m, explicit = parsed
        orientation, side, edge_score = axis
        ocr_conf = max(0, min(100, int(line.get("confidence", 0) or 0)))
        score = 18 + edge_score + round(ocr_conf * 0.20)
        if explicit:
            score += 22
        if value_m >= 2.0:
            score += 5
        if not explicit:
            score = min(score, 62)
        candidates.append(
            DimensionCandidate(
                axis=orientation,
                value_m=value_m,
                text=str(line.get("text") or "").strip(),
                confidence=max(0, min(92, score)),
                explicit_unit=explicit,
                center_x=(float(line.get("left_pct", 0.0) or 0.0) + float(line.get("right_pct", 0.0) or 0.0)) / 2.0,
                center_y=(float(line.get("top_pct", 0.0) or 0.0) + float(line.get("bottom_pct", 0.0) or 0.0)) / 2.0,
                edge_side=side,
            )
        )
    return candidates


def _with_repeat_bonus(candidates: list[DimensionCandidate], axis: str) -> list[DimensionCandidate]:
    selected = [candidate for candidate in candidates if candidate.axis == axis]
    boosted: list[DimensionCandidate] = []
    opposite = {"top": "bottom", "bottom": "top", "left": "right", "right": "left"}
    for candidate in selected:
        repeat = any(
            other.edge_side == opposite.get(candidate.edge_side)
            and math.isclose(other.value_m, candidate.value_m, rel_tol=0.035, abs_tol=0.08)
            for other in selected
            if other is not candidate
        )
        confidence = candidate.confidence + (8 if repeat else 0)
        if not candidate.explicit_unit:
            # Repeated unit-less evidence helps, but remains below the automatic 3D threshold.
            confidence = min(confidence, 64)
        boosted.append(
            DimensionCandidate(
                axis=candidate.axis,
                value_m=candidate.value_m,
                text=candidate.text,
                confidence=min(96, confidence),
                explicit_unit=candidate.explicit_unit,
                center_x=candidate.center_x,
                center_y=candidate.center_y,
                edge_side=candidate.edge_side,
            )
        )
    return boosted


def metric_evidence(ocr_lines: list[dict[str, Any]]) -> dict[str, Any]:
    candidates = dimension_candidates(ocr_lines)
    horizontal = sorted(_with_repeat_bonus(candidates, "horizontal"), key=lambda item: item.confidence, reverse=True)
    vertical = sorted(_with_repeat_bonus(candidates, "vertical"), key=lambda item: item.confidence, reverse=True)
    width = horizontal[0] if horizontal else None
    height = vertical[0] if vertical else None

    if width and height:
        confidence = min(width.confidence, height.confidence)
        # Never certify automatic 3D from unit-less OCR alone.
        if not width.explicit_unit or not height.explicit_unit:
            confidence = min(confidence, 64)
    else:
        confidence = 0

    dimensions = [
        {
            "axis": item.axis,
            "value_m": round(item.value_m, 4),
            "text": item.text,
            "confidence": item.confidence,
            "explicit_unit": item.explicit_unit,
            "center_x": round(item.center_x, 3),
            "center_y": round(item.center_y, 3),
            "edge_side": item.edge_side,
        }
        for item in sorted(candidates, key=lambda value: value.confidence, reverse=True)[:20]
    ]

    return {
        "width_m": round(width.value_m, 4) if width else None,
        "height_m": round(height.value_m, 4) if height else None,
        "confidence": int(max(0, min(96, confidence))),
        "evidence_count": len(candidates),
        "dimensions": dimensions,
        "verified_for_3d": bool(width and height and confidence >= 65),
    }
