from __future__ import annotations

import base64
import math
import os
from typing import Any

import cv2
import httpx
import numpy as np

DEFAULT_MODELS = (
    "harsh-bagadiya/floor-plan-detector-19-rfdetr-seg-medium-t1",
    "floor-plan-nnoub-ngvnw/1",
)


def roboflow_status() -> dict[str, Any]:
    api_key = os.getenv("ROBOFLOW_API_KEY", "").strip()
    models = _configured_models()
    return {
        "configured": bool(api_key and models),
        "api_url": os.getenv("ROBOFLOW_API_URL", "https://serverless.roboflow.com").rstrip("/"),
        "models": models,
        "primary": True,
    }


def roboflow_floorplan(image_base64: str) -> dict[str, Any]:
    """Run public Roboflow Universe floor-plan models server-side and normalize their output.

    Roboflow is the primary detector when configured. Local CubiCasa/OpenCV remains a verifier/fallback.
    The private Roboflow API key never leaves the backend.
    """
    status = roboflow_status()
    if not status["configured"]:
        return {
            "used": False,
            "model_used": "roboflow-unconfigured",
            "walls": [],
            "rooms": [],
            "openings": [],
            "warnings": ["Roboflow primary reader is not configured; local parser remains active."],
            "models": status["models"],
        }

    raw = image_base64.split(",", 1)[-1]
    try:
        image_bytes = base64.b64decode(raw, validate=True)
    except Exception as exc:
        raise ValueError("invalid image") from exc
    image = cv2.imdecode(np.frombuffer(image_bytes, dtype=np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        raise ValueError("invalid image")
    h, w = image.shape[:2]

    walls: list[dict[str, Any]] = []
    rooms: list[dict[str, Any]] = []
    openings: list[dict[str, Any]] = []
    warnings: list[str] = []
    succeeded: list[str] = []

    for model_id in status["models"]:
        try:
            payload = _infer_model(model_id, image_bytes, raw)
            model_walls, model_rooms, model_openings = _normalize_predictions(payload, w, h, model_id)
            walls.extend(model_walls)
            rooms.extend(model_rooms)
            openings.extend(model_openings)
            succeeded.append(model_id)
        except Exception as exc:
            warnings.append(f"Roboflow model {model_id} failed: {str(exc)[:180]}")

    walls = _dedupe_walls(walls)
    rooms = merge_room_evidence(rooms, [])
    openings = _dedupe_openings(openings)

    if not succeeded:
        warnings.append("All Roboflow models failed; local parser must be used as fallback.")
        return {
            "used": False,
            "model_used": "roboflow-failed",
            "walls": [],
            "rooms": [],
            "openings": [],
            "warnings": warnings,
            "models": status["models"],
        }

    if not walls:
        warnings.append("Roboflow returned no wall geometry; local wall verifier remains authoritative.")
    if not rooms:
        warnings.append("Roboflow returned no room regions; local room evidence remains review-only.")

    return {
        "used": True,
        "model_used": "roboflow-universe[" + ",".join(succeeded) + "]",
        "walls": walls,
        "rooms": rooms,
        "openings": openings,
        "warnings": warnings,
        "models": succeeded,
    }


def merge_room_evidence(primary: list[dict[str, Any]], verifier: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Keep Roboflow room regions primary and add only unmatched verifier rooms at capped confidence."""
    if not primary:
        return list(verifier)

    out: list[dict[str, Any]] = []
    for room in sorted(primary, key=lambda item: int(item.get("confidence", 0)), reverse=True):
        if any(_room_iou(room, kept) >= 0.55 for kept in out):
            continue
        out.append(room)
        if len(out) >= 80:
            break

    for room in verifier:
        if any(_room_iou(room, kept) >= 0.30 for kept in out):
            continue
        extra = dict(room)
        extra["confidence"] = min(int(extra.get("confidence", 60)), 68)
        extra["id"] = f"verifier-{extra.get('id', len(out))}"
        out.append(extra)
        if len(out) >= 80:
            break
    return out


def _configured_models() -> list[str]:
    raw = os.getenv("ROBOFLOW_MODEL_IDS", "").strip()
    values = [item.strip().strip("/") for item in raw.split(",") if item.strip()] if raw else list(DEFAULT_MODELS)
    return list(dict.fromkeys(values))[:4]


def _infer_model(model_id: str, image_bytes: bytes, base64_value: str) -> dict[str, Any]:
    api_key = os.getenv("ROBOFLOW_API_KEY", "").strip()
    base_url = os.getenv("ROBOFLOW_API_URL", "https://serverless.roboflow.com").rstrip("/")
    confidence = float(os.getenv("ROBOFLOW_CONFIDENCE", "0.25"))
    url = f"{base_url}/{model_id.lstrip('/')}"
    params = {"confidence": max(0.01, min(0.99, confidence))}
    timeout = httpx.Timeout(75.0, connect=20.0)
    auth_headers = {"Authorization": f"Bearer {api_key}"}

    # Current Roboflow Serverless models prefer bearer-header authentication.
    # Keep the raw-image request first because it matches the hosted inference REST contract;
    # retry with explicit base64 JSON for inference-server-compatible deployments.
    with httpx.Client(timeout=timeout) as client:
        response = client.post(
            url,
            params=params,
            content=image_bytes,
            headers={**auth_headers, "Content-Type": "application/x-www-form-urlencoded"},
        )
        if response.status_code >= 400:
            response = client.post(
                url,
                params=params,
                json={"image": {"type": "base64", "value": base64_value}},
                headers=auth_headers,
            )
    if response.status_code >= 400:
        raise RuntimeError(f"HTTP {response.status_code}: {response.text[:220]}")
    body = response.json()
    if not isinstance(body, dict):
        raise RuntimeError("unexpected Roboflow response")
    return body


def _normalize_predictions(
    payload: dict[str, Any],
    image_w: int,
    image_h: int,
    model_id: str,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
    predictions = _prediction_items(payload)
    walls: list[dict[str, Any]] = []
    rooms: list[dict[str, Any]] = []
    openings: list[dict[str, Any]] = []

    for index, pred in enumerate(predictions):
        label = str(pred.get("class") or pred.get("class_name") or pred.get("label") or "").strip().lower()
        if not label:
            continue
        confidence = _confidence(pred.get("confidence", pred.get("class_confidence", 0.0)))
        if confidence < 20:
            continue
        points = _points(pred.get("points"), image_w, image_h)

        if "wall" in label and "curtain" not in label:
            wall = _wall_from_prediction(pred, points, image_w, image_h, confidence, model_id, index)
            if wall:
                walls.append(wall)
        elif "room" in label or label.startswith("space") or label.startswith("zone"):
            room = _room_from_prediction(pred, points, image_w, image_h, confidence, model_id, index)
            if room:
                rooms.append(room)
        elif "door" in label or "window" in label:
            opening = _opening_from_prediction(pred, image_w, image_h, confidence, label, model_id, index)
            if opening:
                openings.append(opening)

    return walls, rooms, openings


def _prediction_items(payload: Any) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []

    def walk(value: Any) -> None:
        if isinstance(value, dict):
            if any(key in value for key in ("class", "class_name", "label")) and any(
                key in value for key in ("x", "points", "width", "height")
            ):
                out.append(value)
            for key, nested in value.items():
                if key in {"visualization", "image"}:
                    continue
                walk(nested)
        elif isinstance(value, list):
            for nested in value:
                walk(nested)

    walk(payload)
    return out[:2000]


def _confidence(value: Any) -> int:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return 0
    if number <= 1.0:
        number *= 100.0
    return max(0, min(92, int(round(number))))


def _points(value: Any, image_w: int, image_h: int) -> list[tuple[float, float]]:
    if not isinstance(value, list):
        return []
    out: list[tuple[float, float]] = []
    for point in value:
        if isinstance(point, dict):
            try:
                x, y = float(point["x"]), float(point["y"])
            except (KeyError, TypeError, ValueError):
                continue
        elif isinstance(point, (list, tuple)) and len(point) >= 2:
            try:
                x, y = float(point[0]), float(point[1])
            except (TypeError, ValueError):
                continue
        else:
            continue
        out.append((x / max(image_w, 1) * 100.0, y / max(image_h, 1) * 100.0))
    return out


def _bbox(pred: dict[str, Any], image_w: int, image_h: int) -> tuple[float, float, float, float] | None:
    try:
        cx = float(pred.get("x"))
        cy = float(pred.get("y"))
        width = float(pred.get("width"))
        height = float(pred.get("height"))
    except (TypeError, ValueError):
        return None
    if width <= 0 or height <= 0:
        return None
    return (
        (cx - width / 2.0) / max(image_w, 1) * 100.0,
        (cy - height / 2.0) / max(image_h, 1) * 100.0,
        width / max(image_w, 1) * 100.0,
        height / max(image_h, 1) * 100.0,
    )


def _wall_from_prediction(
    pred: dict[str, Any],
    points: list[tuple[float, float]],
    image_w: int,
    image_h: int,
    confidence: int,
    model_id: str,
    index: int,
) -> dict[str, Any] | None:
    if len(points) >= 2:
        pts = np.asarray(points, dtype=np.float32)
        rect = cv2.minAreaRect(pts)
        (cx, cy), (rw, rh), angle = rect
        major = max(float(rw), float(rh))
        if major < 0.45:
            return None
        if rh > rw:
            angle += 90.0
        radians = math.radians(angle)
        dx = math.cos(radians) * major / 2.0
        dy = math.sin(radians) * major / 2.0
        start = {"x": max(0.0, min(100.0, cx - dx)), "y": max(0.0, min(100.0, cy - dy))}
        end = {"x": max(0.0, min(100.0, cx + dx)), "y": max(0.0, min(100.0, cy + dy))}
    else:
        box = _bbox(pred, image_w, image_h)
        if not box:
            return None
        x, y, width, height = box
        if max(width, height) < 0.45:
            return None
        if width >= height:
            start, end = {"x": x, "y": y + height / 2.0}, {"x": x + width, "y": y + height / 2.0}
        else:
            start, end = {"x": x + width / 2.0, "y": y}, {"x": x + width / 2.0, "y": y + height}
    return {
        "id": f"rf-wall-{index}",
        "start": start,
        "end": end,
        "confidence": confidence,
        "kind": "roboflow-primary-wall",
        "source": model_id,
    }


def _room_from_prediction(
    pred: dict[str, Any],
    points: list[tuple[float, float]],
    image_w: int,
    image_h: int,
    confidence: int,
    model_id: str,
    index: int,
) -> dict[str, Any] | None:
    if points:
        xs, ys = zip(*points)
        x, y = min(xs), min(ys)
        width, height = max(xs) - x, max(ys) - y
        polygon = [{"x": max(0.0, min(100.0, px)), "y": max(0.0, min(100.0, py))} for px, py in points[:48]]
    else:
        box = _bbox(pred, image_w, image_h)
        if not box:
            return None
        x, y, width, height = box
        polygon = [
            {"x": x, "y": y},
            {"x": x + width, "y": y},
            {"x": x + width, "y": y + height},
            {"x": x, "y": y + height},
        ]
    if width < 0.8 or height < 0.8:
        return None
    return {
        "id": f"rf-room-{index}",
        "name": "مساحة مكتشفة",
        "type": "unknown",
        "x": max(0.0, min(100.0, x)),
        "y": max(0.0, min(100.0, y)),
        "width": max(0.1, min(100.0, width)),
        "height": max(0.1, min(100.0, height)),
        "area_m2": 0.0,
        "confidence": confidence,
        "polygon": polygon,
        "source": model_id,
    }


def _opening_from_prediction(
    pred: dict[str, Any],
    image_w: int,
    image_h: int,
    confidence: int,
    label: str,
    model_id: str,
    index: int,
) -> dict[str, Any] | None:
    try:
        cx = float(pred.get("x")) / max(image_w, 1) * 100.0
        cy = float(pred.get("y")) / max(image_h, 1) * 100.0
        width_px = max(float(pred.get("width", 0.0)), float(pred.get("height", 0.0)))
    except (TypeError, ValueError):
        return None
    if width_px <= 0:
        return None
    return {
        "id": f"rf-opening-{index}",
        "type": "window" if "window" in label else "door",
        "x": max(0.0, min(100.0, cx)),
        "y": max(0.0, min(100.0, cy)),
        "width": max(0.2, min(20.0, width_px / max(image_w, image_h, 1) * 100.0)),
        "rotation_deg": float(pred.get("rotation", pred.get("angle", 0.0)) or 0.0),
        "confidence": confidence,
        "source": model_id,
    }


def _dedupe_walls(walls: list[dict[str, Any]]) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    for wall in sorted(walls, key=lambda item: int(item.get("confidence", 0)), reverse=True):
        a, b = wall.get("start", {}), wall.get("end", {})
        duplicate = False
        for kept in out:
            c, d = kept.get("start", {}), kept.get("end", {})
            direct = _distance(a, c) <= 1.3 and _distance(b, d) <= 1.3
            reverse = _distance(a, d) <= 1.3 and _distance(b, c) <= 1.3
            if direct or reverse:
                duplicate = True
                break
        if not duplicate:
            out.append(wall)
        if len(out) >= 300:
            break
    return out


def _dedupe_openings(openings: list[dict[str, Any]]) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    for item in sorted(openings, key=lambda value: int(value.get("confidence", 0)), reverse=True):
        duplicate = any(
            kept.get("type") == item.get("type")
            and math.hypot(float(kept.get("x", 0.0)) - float(item.get("x", 0.0)), float(kept.get("y", 0.0)) - float(item.get("y", 0.0))) <= 1.8
            for kept in out
        )
        if not duplicate:
            out.append(item)
        if len(out) >= 120:
            break
    return out


def _distance(a: dict[str, Any], b: dict[str, Any]) -> float:
    return math.hypot(float(a.get("x", 0.0)) - float(b.get("x", 0.0)), float(a.get("y", 0.0)) - float(b.get("y", 0.0)))


def _room_iou(a: dict[str, Any], b: dict[str, Any]) -> float:
    ax1, ay1 = float(a.get("x", 0.0)), float(a.get("y", 0.0))
    bx1, by1 = float(b.get("x", 0.0)), float(b.get("y", 0.0))
    ax2, ay2 = ax1 + float(a.get("width", 0.0)), ay1 + float(a.get("height", 0.0))
    bx2, by2 = bx1 + float(b.get("width", 0.0)), by1 + float(b.get("height", 0.0))
    ix = max(0.0, min(ax2, bx2) - max(ax1, bx1))
    iy = max(0.0, min(ay2, by2) - max(ay1, by1))
    intersection = ix * iy
    union = max(0.001, (ax2 - ax1) * (ay2 - ay1) + (bx2 - bx1) * (by2 - by1) - intersection)
    return max(0.0, min(1.0, intersection / union))
