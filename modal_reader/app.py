from __future__ import annotations

import base64
import io
import json
import math
import subprocess
import tempfile
from pathlib import Path
from typing import Any

import modal

app = modal.App("manzili-hai-floorplan-reader")

reader_image = (
    modal.Image.from_registry("pytorch/pytorch:2.3.1-cuda11.8-cudnn8-devel")
    .apt_install("git", "build-essential", "libgl1", "libglib2.0-0")
    .run_commands("git clone --depth 1 https://github.com/Cornell-VAILab/Raster2Seq.git /opt/raster2seq")
    .workdir("/opt/raster2seq")
    .run_commands("pip install -r requirements.txt")
    .run_commands("cd models/ops && sh make.sh")
    .run_commands("cd diff_ras && python setup.py build develop")
    .pip_install("fastapi==0.115.12", "pydantic==2.11.3", "easyocr==1.7.2")
    .run_commands(
        "python -c \"from raster2seq_hub import download_checkpoint; "
        "print(download_checkpoint('raster2graph-512')); "
        "print(download_checkpoint('cubicasa5k'))\""
    )
    .run_commands("python -c \"import easyocr; easyocr.Reader(['ar','en'], gpu=False)\"")
)

_R2G_LABELS = {
    0: ("unknown", "مساحة"),
    1: ("living_room", "صالة"),
    2: ("kitchen", "مطبخ"),
    3: ("bedroom", "غرفة نوم"),
    4: ("bathroom", "حمام"),
    5: ("restroom", "دورة مياه"),
    6: ("balcony", "شرفة"),
    7: ("closet", "خزانة"),
    8: ("corridor", "ممر"),
    9: ("washing_room", "غرفة غسيل"),
    10: ("service", "خدمات"),
    11: ("outside", "خارجي"),
}

# CubiCasa5K labels in Raster2Seq: 9=Window, 10=Door.
_CC5K_OPENING_LABELS = {9: "window", 10: "door"}
_R2G_SIZE = 512
_CC5K_SIZE = 256
_PREDICT_TIMEOUT_SECONDS = 135


def _decode_image(payload: dict[str, Any]) -> bytes:
    from fastapi import HTTPException

    raw = str(payload.get("image_base64") or "").split(",", 1)[-1]
    try:
        data = base64.b64decode(raw, validate=True)
    except Exception as exc:
        raise HTTPException(400, "invalid image") from exc
    if len(data) < 128:
        raise HTTPException(400, "image is empty")
    return data


def _prediction_command(
    input_dir: Path,
    output_dir: Path,
    *,
    dataset_name: str,
    checkpoint: str,
    semantic_classes: int,
    image_size: int,
) -> list[str]:
    return [
        "python",
        "predict.py",
        f"--dataset_name={dataset_name}",
        f"--dataset_root={input_dir}",
        f"--checkpoint=hf:{checkpoint}",
        f"--output_dir={output_dir}",
        f"--semantic_classes={semantic_classes}",
        "--input_channels=3",
        "--poly2seq",
        f"--image_size={image_size}",
        "--seq_len=512",
        "--num_bins=32",
        "--disable_poly_refine",
        "--dec_attn_concat_src",
        "--ema4eval",
        "--use_anchor",
        "--per_token_sem_loss",
        "--save_pred",
        "--batch_size=1",
        "--num_workers=0",
    ]


def _run_prediction(
    input_dir: Path,
    output_dir: Path,
    *,
    dataset_name: str,
    checkpoint: str,
    semantic_classes: int,
    image_size: int,
) -> list[dict[str, Any]]:
    output_dir.mkdir(parents=True, exist_ok=True)
    completed = subprocess.run(
        _prediction_command(
            input_dir,
            output_dir,
            dataset_name=dataset_name,
            checkpoint=checkpoint,
            semantic_classes=semantic_classes,
            image_size=image_size,
        ),
        cwd="/opt/raster2seq",
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        timeout=_PREDICT_TIMEOUT_SECONDS,
        check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError(f"Raster2Seq {checkpoint} inference failed: " + completed.stdout[-1800:])

    candidates = list(output_dir.rglob("plan.json"))
    if not candidates:
        raise RuntimeError(f"Raster2Seq {checkpoint} produced no polygon JSON")
    result = json.loads(candidates[0].read_text())
    if not isinstance(result, list):
        raise RuntimeError(f"Raster2Seq {checkpoint} polygon JSON is invalid")
    return result


def _run_cloud_models(
    image_bytes: bytes,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], int, int, str | None]:
    from PIL import Image

    with tempfile.TemporaryDirectory(prefix="hai-r2s-") as temp:
        root = Path(temp)
        input_dir = root / "input"
        input_dir.mkdir()
        image_path = input_dir / "plan.png"

        source = Image.open(io.BytesIO(image_bytes)).convert("RGB")
        source_w, source_h = source.size
        source.save(image_path)

        room_predictions = _run_prediction(
            input_dir,
            root / "r2g-output",
            dataset_name="r2g",
            checkpoint="raster2graph-512",
            semantic_classes=13,
            image_size=_R2G_SIZE,
        )

        opening_error: str | None = None
        try:
            opening_predictions = _run_prediction(
                input_dir,
                root / "cc5k-output",
                dataset_name="cubicasa",
                checkpoint="cubicasa5k",
                semantic_classes=12,
                image_size=_CC5K_SIZE,
            )
        except Exception as exc:
            # Room reconstruction is the primary result. Do not discard a usable page solely
            # because the independent door/window pass failed; surface the failure explicitly.
            opening_predictions = []
            opening_error = str(exc)[:500]

        return room_predictions, opening_predictions, source_w, source_h, opening_error


def _normalize_polygon(
    segmentation: Any,
    source_w: int,
    source_h: int,
    model_size: int,
) -> list[dict[str, float]]:
    if not isinstance(segmentation, list) or len(segmentation) < 2 or source_w <= 0 or source_h <= 0:
        return []
    scale = min(model_size / source_h, model_size / source_w)
    resized_h = int(source_h * scale)
    resized_w = int(source_w * scale)
    top = (model_size - resized_h) // 2
    left = (model_size - resized_w) // 2

    points: list[dict[str, float]] = []
    for point in segmentation:
        if not isinstance(point, (list, tuple)) or len(point) < 2:
            continue
        try:
            padded_x, padded_y = float(point[0]), float(point[1])
        except (TypeError, ValueError):
            continue
        source_x = (padded_x - left) / max(scale, 1e-6)
        source_y = (padded_y - top) / max(scale, 1e-6)
        points.append(
            {
                "x": max(0.0, min(100.0, source_x / source_w * 100.0)),
                "y": max(0.0, min(100.0, source_y / source_h * 100.0)),
            }
        )
    return points


def _rooms(predictions: list[dict[str, Any]], source_w: int, source_h: int) -> list[dict[str, Any]]:
    rooms: list[dict[str, Any]] = []
    for index, item in enumerate(predictions):
        polygon = _normalize_polygon(item.get("segmentation"), source_w, source_h, _R2G_SIZE)
        if len(polygon) < 3:
            continue
        cls = int(item.get("category_id", 0) or 0)
        room_type, name = _R2G_LABELS.get(cls, ("unknown", "مساحة"))
        if room_type == "outside":
            continue
        xs = [p["x"] for p in polygon]
        ys = [p["y"] for p in polygon]
        x, y = min(xs), min(ys)
        width, height = max(xs) - x, max(ys) - y
        if width < 0.5 or height < 0.5:
            continue
        rooms.append(
            {
                "id": f"r2s-room-{index}",
                "name": name,
                "type": room_type,
                "x": x,
                "y": y,
                "width": width,
                "height": height,
                "area_m2": 0.0,
                "confidence": 0,
                "polygon": polygon,
            }
        )
    return rooms


def _walls_from_rooms(rooms: list[dict[str, Any]]) -> list[dict[str, Any]]:
    edges: dict[tuple[int, int, int, int], dict[str, Any]] = {}
    for room in rooms:
        polygon = room.get("polygon") or []
        for i, start in enumerate(polygon):
            end = polygon[(i + 1) % len(polygon)]
            ax, ay = float(start["x"]), float(start["y"])
            bx, by = float(end["x"]), float(end["y"])
            if abs(ax - bx) + abs(ay - by) < 0.35:
                continue
            qa = (round(ax * 5), round(ay * 5))
            qb = (round(bx * 5), round(by * 5))
            if qb < qa:
                qa, qb = qb, qa
                ax, ay, bx, by = bx, by, ax, ay
            key = (qa[0], qa[1], qb[0], qb[1])
            if key not in edges:
                edges[key] = {
                    "id": f"r2s-wall-{len(edges)}",
                    "start": {"x": ax, "y": ay},
                    "end": {"x": bx, "y": by},
                    "kind": "raster2seq-room-boundary",
                    "confidence": 0,
                }
    return list(edges.values())


def _farthest_pair(points: list[dict[str, float]]) -> tuple[dict[str, float], dict[str, float], float] | None:
    if len(points) < 2:
        return None
    best: tuple[dict[str, float], dict[str, float], float] | None = None
    for i, first in enumerate(points[:-1]):
        for second in points[i + 1 :]:
            dx = second["x"] - first["x"]
            dy = second["y"] - first["y"]
            distance = math.hypot(dx, dy)
            if best is None or distance > best[2]:
                best = (first, second, distance)
    return best


def _distance_to_segment(px: float, py: float, wall: dict[str, Any]) -> float:
    a = wall.get("start") or {}
    b = wall.get("end") or {}
    ax, ay = float(a.get("x", 0.0)), float(a.get("y", 0.0))
    bx, by = float(b.get("x", 0.0)), float(b.get("y", 0.0))
    dx, dy = bx - ax, by - ay
    denom = dx * dx + dy * dy
    if denom <= 1e-9:
        return math.hypot(px - ax, py - ay)
    t = ((px - ax) * dx + (py - ay) * dy) / denom
    t = max(0.0, min(1.0, t))
    qx, qy = ax + t * dx, ay + t * dy
    return math.hypot(px - qx, py - qy)


def _nearest_wall_id(x: float, y: float, walls: list[dict[str, Any]]) -> str | None:
    if not walls:
        return None
    best_wall: dict[str, Any] | None = None
    best_distance = float("inf")
    for wall in walls:
        distance = _distance_to_segment(x, y, wall)
        if distance < best_distance:
            best_distance = distance
            best_wall = wall
    # 2.5 normalized plan units is intentionally conservative. If no wall is sufficiently
    # close, leave wallId unset rather than fabricating an association.
    if best_wall is None or best_distance > 2.5:
        return None
    wall_id = str(best_wall.get("id") or "").strip()
    return wall_id or None


def _dedupe_openings(openings: list[dict[str, Any]]) -> list[dict[str, Any]]:
    kept: list[dict[str, Any]] = []
    for item in sorted(openings, key=lambda value: float(value.get("width", 0.0)), reverse=True):
        duplicate = any(
            other.get("type") == item.get("type")
            and math.hypot(
                float(other.get("x", 0.0)) - float(item.get("x", 0.0)),
                float(other.get("y", 0.0)) - float(item.get("y", 0.0)),
            )
            < 1.0
            for other in kept
        )
        if not duplicate:
            kept.append(item)
    for index, item in enumerate(kept):
        item["id"] = f"cc5k-opening-{index}"
    return kept


def _openings(
    predictions: list[dict[str, Any]],
    source_w: int,
    source_h: int,
    walls: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    candidates: list[dict[str, Any]] = []
    for item in predictions:
        cls = int(item.get("category_id", -1) or -1)
        opening_type = _CC5K_OPENING_LABELS.get(cls)
        if opening_type is None:
            continue
        polygon = _normalize_polygon(item.get("segmentation"), source_w, source_h, _CC5K_SIZE)
        farthest = _farthest_pair(polygon)
        if farthest is None:
            continue
        first, second, width = farthest
        if width < 0.2 or width > 25.0:
            continue
        x = (first["x"] + second["x"]) / 2.0
        y = (first["y"] + second["y"]) / 2.0
        rotation = math.degrees(math.atan2(second["y"] - first["y"], second["x"] - first["x"]))
        while rotation <= -90.0:
            rotation += 180.0
        while rotation > 90.0:
            rotation -= 180.0
        candidates.append(
            {
                "id": "pending",
                "type": opening_type,
                "x": x,
                "y": y,
                "width": width,
                "rotation_deg": rotation,
                "wallId": _nearest_wall_id(x, y, walls),
                "confidence": 0,
            }
        )
    return _dedupe_openings(candidates)


def _cloud_ocr(image_bytes: bytes) -> list[dict[str, Any]]:
    import cv2
    import easyocr
    import numpy as np

    image = cv2.imdecode(np.frombuffer(image_bytes, dtype=np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        return []
    h, w = image.shape[:2]
    reader = easyocr.Reader(["ar", "en"], gpu=True, verbose=False)
    output: list[dict[str, Any]] = []
    for box, text, confidence in reader.readtext(image, detail=1, paragraph=False):
        value = str(text).strip()
        if not value:
            continue
        xs = [float(p[0]) for p in box]
        ys = [float(p[1]) for p in box]
        output.append(
            {
                "text": value,
                "left_pct": min(xs) / max(w, 1) * 100.0,
                "top_pct": min(ys) / max(h, 1) * 100.0,
                "right_pct": max(xs) / max(w, 1) * 100.0,
                "bottom_pct": max(ys) / max(h, 1) * 100.0,
                "confidence": max(0, min(100, int(round(float(confidence) * 100.0)))),
            }
        )
    return output[:500]


@app.function(
    image=reader_image,
    gpu="T4",
    timeout=300,
    scaledown_window=60,
)
@modal.fastapi_endpoint(method="POST", requires_proxy_auth=True)
def parse_floorplan(payload: dict[str, Any]) -> dict[str, Any]:
    image_bytes = _decode_image(payload)
    room_predictions, opening_predictions, source_w, source_h, opening_error = _run_cloud_models(image_bytes)
    rooms = _rooms(room_predictions, source_w, source_h)
    walls = _walls_from_rooms(rooms)
    openings = _openings(opening_predictions, source_w, source_h, walls)
    ocr_lines = _cloud_ocr(image_bytes)

    warnings = [
        "Cloud reader uses Raster2Seq room polygons and cloud OCR; confidence is intentionally uncalibrated.",
        "Doors/windows are an independent CubiCasa5K Raster2Seq pass; wall association is proximity-based and remains uncalibrated.",
    ]
    if opening_error:
        warnings.append("Door/window cloud pass failed: " + opening_error)
    elif not openings:
        warnings.append("CubiCasa5K returned no usable door/window polygons for this page.")

    model_used = "modal:raster2seq-raster2graph-512"
    if not opening_error:
        model_used += "+cubicasa5k-openings"
    model_used += "+easyocr-ar-en"

    return {
        "page_index": int(payload.get("page_index") or 0),
        "model_used": model_used,
        "confidence": 0,
        "rooms": rooms,
        "walls": walls,
        "openings": openings,
        "ocr_lines": ocr_lines,
        "quality": {
            "geometry": 0,
            "ocr": int(sum(x["confidence"] for x in ocr_lines) / len(ocr_lines)) if ocr_lines else 0,
            "scale_evidence": 0,
            "wall_topology": 0,
            "dimension_evidence": 0,
        },
        "warnings": warnings,
        "reader_path": "modal-raster2seq-cloud-only",
        "local_inference": False,
    }
