from __future__ import annotations

import math
from typing import Any

R2G_EMPTY_CLASS = 12


def _point(value: Any) -> dict[str, float] | None:
    if not isinstance(value, dict):
        return None
    try:
        x = float(value.get("x"))
        y = float(value.get("y"))
    except (TypeError, ValueError):
        return None
    if not math.isfinite(x) or not math.isfinite(y):
        return None
    return {"x": max(0.0, min(100.0, x)), "y": max(0.0, min(100.0, y))}


def _distance(a: dict[str, float], b: dict[str, float]) -> float:
    return math.hypot(b["x"] - a["x"], b["y"] - a["y"])


def _signed_area(points: list[dict[str, float]]) -> float:
    if len(points) < 3:
        return 0.0
    total = 0.0
    for index, current in enumerate(points):
        nxt = points[(index + 1) % len(points)]
        total += current["x"] * nxt["y"] - nxt["x"] * current["y"]
    return total / 2.0


def _orientation(a: dict[str, float], b: dict[str, float], c: dict[str, float]) -> float:
    return (b["x"] - a["x"]) * (c["y"] - a["y"]) - (b["y"] - a["y"]) * (c["x"] - a["x"])


def _segments_cross(
    a: dict[str, float],
    b: dict[str, float],
    c: dict[str, float],
    d: dict[str, float],
) -> bool:
    o1 = _orientation(a, b, c)
    o2 = _orientation(a, b, d)
    o3 = _orientation(c, d, a)
    o4 = _orientation(c, d, b)
    return o1 * o2 < -1e-6 and o3 * o4 < -1e-6


def self_intersects(points: list[dict[str, float]]) -> bool:
    count = len(points)
    if count < 4:
        return False
    for i in range(count):
        a = points[i]
        b = points[(i + 1) % count]
        for j in range(i + 1, count):
            if j == i or j == (i + 1) % count or (j + 1) % count == i:
                continue
            if i == 0 and j == count - 1:
                continue
            c = points[j]
            d = points[(j + 1) % count]
            if _segments_cross(a, b, c, d):
                return True
    return False


def _dedupe(points: list[dict[str, float]], epsilon: float = 0.12) -> list[dict[str, float]]:
    output: list[dict[str, float]] = []
    for item in points:
        point = _point(item)
        if point is None:
            continue
        if not output or _distance(output[-1], point) >= epsilon:
            output.append(point)
    if len(output) >= 2 and _distance(output[0], output[-1]) < epsilon:
        output.pop()
    return output


def _remove_collinear(points: list[dict[str, float]]) -> list[dict[str, float]]:
    if len(points) <= 3:
        return points
    current = list(points)
    changed = True
    while changed and len(current) > 3:
        changed = False
        keep: list[dict[str, float]] = []
        count = len(current)
        for i, point in enumerate(current):
            previous = current[(i - 1) % count]
            nxt = current[(i + 1) % count]
            base = _distance(previous, nxt)
            if base < 1e-6:
                changed = True
                continue
            cross = abs(_orientation(previous, point, nxt))
            height = cross / base
            if height < 0.08 and min(_distance(previous, point), _distance(point, nxt)) < 2.0:
                changed = True
                continue
            keep.append(point)
        if len(keep) >= 3:
            current = keep
        else:
            break
    return current


def _untangle(points: list[dict[str, float]]) -> tuple[list[dict[str, float]], bool]:
    current = list(points)
    repaired = False
    for _ in range(64):
        count = len(current)
        crossing: tuple[int, int] | None = None
        for i in range(count):
            a = current[i]
            b = current[(i + 1) % count]
            for j in range(i + 2, count):
                if i == 0 and j == count - 1:
                    continue
                c = current[j]
                d = current[(j + 1) % count]
                if _segments_cross(a, b, c, d):
                    crossing = (i, j)
                    break
            if crossing:
                break
        if crossing is None:
            break
        i, j = crossing
        current[i + 1 : j + 1] = reversed(current[i + 1 : j + 1])
        repaired = True
    return current, repaired


def _bbox(points: list[dict[str, float]]) -> list[dict[str, float]]:
    if not points:
        return []
    min_x = min(item["x"] for item in points)
    max_x = max(item["x"] for item in points)
    min_y = min(item["y"] for item in points)
    max_y = max(item["y"] for item in points)
    if max_x - min_x < 0.35 or max_y - min_y < 0.35:
        return []
    return [
        {"x": min_x, "y": min_y},
        {"x": max_x, "y": min_y},
        {"x": max_x, "y": max_y},
        {"x": min_x, "y": max_y},
    ]


def sanitize_polygon(points: list[dict[str, float]]) -> tuple[list[dict[str, float]], str]:
    """Return a simple room polygon without inventing model confidence.

    The status is diagnostic only: clean, repaired, bbox-fallback, or invalid.
    A bounding box is used only after an otherwise usable room polygon cannot be
    untangled safely; this prevents a malformed cycle from becoming diagonal
    wall segments across the entire plan.
    """

    cleaned = _remove_collinear(_dedupe(points))
    if len(cleaned) < 3:
        return [], "invalid"

    repaired, changed = _untangle(cleaned)
    repaired = _remove_collinear(_dedupe(repaired))
    area = abs(_signed_area(repaired))
    if len(repaired) >= 3 and area >= 0.08 and not self_intersects(repaired):
        return repaired, "repaired" if changed else "clean"

    fallback = _bbox(cleaned)
    if len(fallback) >= 3 and abs(_signed_area(fallback)) >= 0.08:
        return fallback, "bbox-fallback"
    return [], "invalid"


def wall_edges_from_polygon(points: list[dict[str, float]]) -> list[tuple[dict[str, float], dict[str, float]]]:
    """Create only sane consecutive edges from a sanitized polygon.

    A very long diagonal that is also a strong median-length outlier is treated
    as a broken cycle edge rather than a wall. Short/intentional diagonal walls
    remain valid.
    """

    if len(points) < 3:
        return []
    raw: list[tuple[dict[str, float], dict[str, float], float]] = []
    for index, start in enumerate(points):
        end = points[(index + 1) % len(points)]
        length = _distance(start, end)
        if length >= 0.35:
            raw.append((start, end, length))
    if not raw:
        return []

    lengths = sorted(item[2] for item in raw)
    median = lengths[len(lengths) // 2]
    min_x = min(item["x"] for item in points)
    max_x = max(item["x"] for item in points)
    min_y = min(item["y"] for item in points)
    max_y = max(item["y"] for item in points)
    bbox_diagonal = math.hypot(max_x - min_x, max_y - min_y)

    output: list[tuple[dict[str, float], dict[str, float]]] = []
    for start, end, length in raw:
        dx = abs(end["x"] - start["x"])
        dy = abs(end["y"] - start["y"])
        diagonal = dx > 0.18 * max(length, 1e-6) and dy > 0.18 * max(length, 1e-6)
        suspicious_jump = (
            diagonal
            and len(raw) >= 4
            and length > max(8.0, median * 3.5)
            and length > bbox_diagonal * 0.62
        )
        if not suspicious_jump:
            output.append((start, end))
    return output
