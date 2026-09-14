from __future__ import annotations

import base64
import json
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
    .run_commands("python -c \"from raster2seq_hub import download_checkpoint; print(download_checkpoint('raster2graph-512'))\"")
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
_MODEL_SIZE = 512


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


def _run_raster2seq(image_bytes: bytes) -> tuple[list[dict[str, Any]], int, int]:
    with tempfile.TemporaryDirectory(prefix="hai-r2s-") as temp:
        root = Path(temp)
        input_dir = root / "input"
        output_dir = root / "output"
        input_dir.mkdir()
        output_dir.mkdir()
        image_path = input_dir / "plan.png"

        from PIL import Image
        import io

        source = Image.open(io.BytesIO(image_bytes)).convert("RGB")
        source_w, source_h = source.size
        source.save(image_path)
        command = [
            "python",
            "predict.py",
            "--dataset_name=r2g",
            f"--dataset_root={input_dir}",
            "--checkpoint=hf:raster2graph-512",
            f"--output_dir={output_dir}",
            "--semantic_classes=13",
            "--input_channels=3",
            "--poly2seq",
            "--image_size=512",
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
        completed = subprocess.run(
            command,
            cwd="/opt/raster2seq",
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=240,
            check=False,
        )
        if completed.returncode != 0:
            raise RuntimeError("Raster2Seq inference failed: " + completed.stdout[-1800:])

        candidates = list(output_dir.rglob("plan.json"))
        if not candidates:
            raise RuntimeError("Raster2Seq produced no polygon JSON")
        result = json.loads(candidates[0].read_text())
        if not isinstance(result, list):
            raise RuntimeError("Raster2Seq polygon JSON is invalid")
        return result, source_w, source_h


def _normalize_polygon(segmentation: Any, source_w: int, source_h: int) -> list[dict[str, float]]:
    if not isinstance(segmentation, list) or len(segmentation) < 3 or source_w <= 0 or source_h <= 0:
        return []
    scale = min(_MODEL_SIZE / source_h, _MODEL_SIZE / source_w)
    resized_h = int(source_h * scale)
    resized_w = int(source_w * scale)
    top = (_MODEL_SIZE - resized_h) // 2
    left = (_MODEL_SIZE - resized_w) // 2

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
    return points if len(points) >= 3 else []


def _rooms(predictions: list[dict[str, Any]], source_w: int, source_h: int) -> list[dict[str, Any]]:
    rooms: list[dict[str, Any]] = []
    for index, item in enumerate(predictions):
        polygon = _normalize_polygon(item.get("segmentation"), source_w, source_h)
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
    predictions, source_w, source_h = _run_raster2seq(image_bytes)
    rooms = _rooms(predictions, source_w, source_h)
    walls = _walls_from_rooms(rooms)
    ocr_lines = _cloud_ocr(image_bytes)
    return {
        "page_index": int(payload.get("page_index") or 0),
        "model_used": "modal:raster2seq-raster2graph-512+easyocr-ar-en",
        "confidence": 0,
        "rooms": rooms,
        "walls": walls,
        "openings": [],
        "ocr_lines": ocr_lines,
        "quality": {
            "geometry": 0,
            "ocr": int(sum(x["confidence"] for x in ocr_lines) / len(ocr_lines)) if ocr_lines else 0,
            "scale_evidence": 0,
            "wall_topology": 0,
            "dimension_evidence": 0,
        },
        "warnings": [
            "Cloud reader uses Raster2Seq room polygons and cloud OCR; confidence is intentionally uncalibrated.",
            "Door/window extraction is not enabled in this first cloud-only reader revision.",
        ],
        "reader_path": "modal-raster2seq-cloud-only",
        "local_inference": False,
    }
