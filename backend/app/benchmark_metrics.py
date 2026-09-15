from __future__ import annotations

import math
from typing import Any, Callable


def f1_score(tp: int, fp: int, fn: int) -> float:
    if tp == 0:
        return 1.0 if fp == 0 and fn == 0 else 0.0
    precision = tp / (tp + fp)
    recall = tp / (tp + fn)
    return 2.0 * precision * recall / (precision + recall)


def _point(item: dict[str, Any]) -> tuple[float, float]:
    return float(item.get("x", 0.0)), float(item.get("y", 0.0))


def wall_distance(expected: dict[str, Any], actual: dict[str, Any]) -> float:
    es, ee = expected["start"], expected["end"]
    as_, ae = actual["start"], actual["end"]

    def distance(a: dict[str, Any], b: dict[str, Any]) -> float:
        return math.hypot(float(a["x"]) - float(b["x"]), float(a["y"]) - float(b["y"]))

    return min(
        distance(es, as_) + distance(ee, ae),
        distance(es, ae) + distance(ee, as_),
    ) / 2.0


def opening_distance(expected: dict[str, Any], actual: dict[str, Any]) -> float:
    ex, ey = _point(expected)
    ax, ay = _point(actual)
    center = math.hypot(ex - ax, ey - ay)
    width_delta = abs(float(expected.get("width", 0.0)) - float(actual.get("width", 0.0)))
    return center + width_delta * 0.35


def room_distance(expected: dict[str, Any], actual: dict[str, Any]) -> float:
    def center(item: dict[str, Any]) -> tuple[float, float]:
        x = float(item.get("x", 0.0))
        y = float(item.get("y", 0.0))
        return x + float(item.get("width", 0.0)) / 2.0, y + float(item.get("height", 0.0)) / 2.0

    ex, ey = center(expected)
    ax, ay = center(actual)
    center_delta = math.hypot(ex - ax, ey - ay)
    size_delta = (
        abs(float(expected.get("width", 0.0)) - float(actual.get("width", 0.0)))
        + abs(float(expected.get("height", 0.0)) - float(actual.get("height", 0.0)))
    ) / 2.0
    return center_delta + size_delta * 0.25


def greedy_f1(
    expected: list[dict[str, Any]],
    actual: list[dict[str, Any]],
    distance: Callable[[dict[str, Any], dict[str, Any]], float],
    limit: float,
    type_key: str | None = None,
    ignore_expected_types: set[str] | None = None,
) -> float:
    remaining = list(actual)
    tp = 0
    ignored = {value.lower() for value in (ignore_expected_types or set())}

    for target in expected:
        expected_type = str(target.get(type_key, "")).strip().lower() if type_key else ""
        candidates: list[tuple[int, dict[str, Any]]] = []
        for index, candidate in enumerate(remaining):
            if type_key and expected_type and expected_type not in ignored:
                actual_type = str(candidate.get(type_key, "")).strip().lower()
                if actual_type != expected_type:
                    continue
            candidates.append((index, candidate))
        if not candidates:
            continue
        index, best = min(candidates, key=lambda pair: distance(target, pair[1]))
        if distance(target, best) <= limit:
            tp += 1
            remaining.pop(index)

    return f1_score(tp, len(actual) - tp, len(expected) - tp)


def _dimension_value(item: Any) -> float | None:
    if isinstance(item, (int, float)):
        value = float(item)
    elif isinstance(item, dict):
        raw = item.get("value_m", item.get("value"))
        if raw is None:
            return None
        try:
            value = float(raw)
        except (TypeError, ValueError):
            return None
    else:
        return None
    return value if value > 0.0 and math.isfinite(value) else None


def dimension_accuracy(expected: list[Any], actual: list[Any]) -> float:
    expected_values = [value for item in expected if (value := _dimension_value(item)) is not None]
    actual_values = [value for item in actual if (value := _dimension_value(item)) is not None]
    if not expected_values:
        return 1.0 if not actual_values else 0.0

    remaining = list(actual_values)
    matched = 0
    for target in expected_values:
        if not remaining:
            break
        index, candidate = min(enumerate(remaining), key=lambda pair: abs(pair[1] - target))
        tolerance = max(target * 0.05, 0.12)
        if abs(candidate - target) <= tolerance:
            matched += 1
            remaining.pop(index)
    return matched / len(expected_values)


def dimensions_from(plan: dict[str, Any]) -> list[Any]:
    metric = plan.get("metric")
    if isinstance(metric, dict) and isinstance(metric.get("dimensions"), list):
        return list(metric["dimensions"])
    dimensions = plan.get("dimensions")
    return list(dimensions) if isinstance(dimensions, list) else []


def score_case(expected: dict[str, Any], actual: dict[str, Any]) -> dict[str, float]:
    walls = greedy_f1(expected.get("walls", []), actual.get("walls", []), wall_distance, 7.0)
    rooms = greedy_f1(
        expected.get("rooms", []),
        actual.get("rooms", []),
        room_distance,
        10.0,
        type_key="type",
        ignore_expected_types={"", "unknown", "other", "غير معروف"},
    )
    openings = greedy_f1(
        expected.get("openings", []),
        actual.get("openings", []),
        opening_distance,
        6.5,
        type_key="type",
    )
    dimensions = dimension_accuracy(dimensions_from(expected), dimensions_from(actual))
    return {
        "wall_f1": round(walls, 5),
        "room_f1": round(rooms, 5),
        "opening_f1": round(openings, 5),
        "dimension_accuracy": round(dimensions, 5),
    }
