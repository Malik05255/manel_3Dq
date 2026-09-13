from __future__ import annotations

import os
from functools import lru_cache
from pathlib import Path
from typing import Any

import cv2
import numpy as np

MODEL_NAME = "Yytsi/floorplan-to-3d-walls"
MODEL_LICENSE = "MIT"
MODEL_SHA256 = "d7f6a0fd06e2931aecfc8c4849192c5e153701578026efc78d9a6246731a8d6c"
DEFAULT_MODEL_PATH = "/opt/manzili/models/floorplan/best.safetensors"
IMAGE_SIZE = 512
IMAGENET_MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
IMAGENET_STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)
CLASS_NAMES = ("floor", "wall", "door", "window")


def _truthy(name: str, default: bool = True) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def _axis_starts(length: int, tile: int, overlap: float) -> list[int]:
    if tile >= length:
        return [0]
    step = max(1, int(round(tile * (1.0 - overlap))))
    starts = list(range(0, max(1, length - tile + 1), step))
    tail = max(0, length - tile)
    if not starts or starts[-1] != tail:
        starts.append(tail)
    return sorted(set(starts))


class CubiCasaRuntime:
    def __init__(self, model: Any, torch_module: Any, device: Any, path: str) -> None:
        self.model = model
        self.torch = torch_module
        self.device = device
        self.path = path

    def _predict_single(self, image_bgr: np.ndarray) -> np.ndarray:
        h, w = image_bgr.shape[:2]
        rgb = cv2.cvtColor(image_bgr, cv2.COLOR_BGR2RGB)
        scale = min(IMAGE_SIZE / max(w, 1), IMAGE_SIZE / max(h, 1))
        inner_w = max(1, int(round(w * scale)))
        inner_h = max(1, int(round(h * scale)))
        interpolation = cv2.INTER_AREA if scale <= 1.0 else cv2.INTER_CUBIC
        resized = cv2.resize(rgb, (inner_w, inner_h), interpolation=interpolation)

        normalized = resized.astype(np.float32) / 255.0
        normalized = (normalized - IMAGENET_MEAN) / IMAGENET_STD
        canvas = np.zeros((IMAGE_SIZE, IMAGE_SIZE, 3), dtype=np.float32)
        top = (IMAGE_SIZE - inner_h) // 2
        left = (IMAGE_SIZE - inner_w) // 2
        canvas[top:top + inner_h, left:left + inner_w] = normalized

        tensor = self.torch.from_numpy(np.transpose(canvas, (2, 0, 1))[None, ...]).to(self.device)
        with self.torch.no_grad():
            logits = self.model(tensor)
            prediction = logits.argmax(dim=1).squeeze(0).cpu().numpy().astype(np.uint8)

        crop = prediction[top:top + inner_h, left:left + inner_w]
        return cv2.resize(crop, (w, h), interpolation=cv2.INTER_NEAREST)

    def predict(self, image_bgr: np.ndarray) -> np.ndarray:
        """Fuse global context with overlapping high-resolution tiles.

        A single 512px resize loses thin walls, dimension-sized openings and old-scan
        details on phone screenshots/PDF pages. The global pass keeps room context,
        while overlapping tile passes preserve local geometry. Tile votes are weighted
        higher than the global pass because they contain substantially more source pixels.
        """
        h, w = image_bgr.shape[:2]
        global_prediction = self._predict_single(image_bgr)
        if not _truthy("FLOORPLAN_TILED_INFERENCE", True) or max(h, w) < 1200:
            return global_prediction

        min_side = min(h, w)
        tile = min(1500, max(820, int(round(min_side * 0.62))))
        overlap = max(0.18, min(0.42, float(os.getenv("FLOORPLAN_TILE_OVERLAP", "0.30"))))
        xs = _axis_starts(w, min(tile, w), overlap)
        ys = _axis_starts(h, min(tile, h), overlap)

        # Bound worst-case CPU time while keeping broad coverage on very large scans.
        windows = [(x, y, min(w, x + tile), min(h, y + tile)) for y in ys for x in xs]
        max_tiles = max(4, min(16, int(os.getenv("FLOORPLAN_MAX_TILES", "12"))))
        if len(windows) > max_tiles:
            indices = np.linspace(0, len(windows) - 1, max_tiles).round().astype(int)
            windows = [windows[int(i)] for i in sorted(set(indices.tolist()))]

        votes = np.zeros((len(CLASS_NAMES), h, w), dtype=np.uint8)
        for class_index in range(len(CLASS_NAMES)):
            votes[class_index] += (global_prediction == class_index).astype(np.uint8)

        for x0, y0, x1, y1 in windows:
            crop = image_bgr[y0:y1, x0:x1]
            if crop.size == 0:
                continue
            tile_prediction = self._predict_single(crop)
            for class_index in range(len(CLASS_NAMES)):
                region = votes[class_index, y0:y1, x0:x1]
                region += ((tile_prediction == class_index).astype(np.uint8) * 2)

        return votes.argmax(axis=0).astype(np.uint8)


@lru_cache(maxsize=1)
def load_cubicasa_runtime() -> CubiCasaRuntime | None:
    path = os.getenv("FLOORPLAN_SAFETENSORS_MODEL", DEFAULT_MODEL_PATH).strip()
    if not path or not Path(path).is_file():
        return None
    try:
        import torch
        import segmentation_models_pytorch as smp
        from safetensors.torch import load_file

        torch.set_num_threads(max(1, int(os.getenv("FLOORPLAN_TORCH_THREADS", "2"))))
        device = torch.device(os.getenv("FLOORPLAN_DEVICE", "cpu"))
        model = smp.Unet(
            encoder_name="resnet34",
            encoder_weights=None,
            in_channels=3,
            classes=len(CLASS_NAMES),
        ).to(device)
        state = load_file(path, device="cpu")
        model.load_state_dict(state, strict=True)
        model.eval()
        return CubiCasaRuntime(model, torch, device, path)
    except Exception:
        return None


def model_status() -> dict[str, Any]:
    runtime = load_cubicasa_runtime()
    return {
        "configured": runtime is not None,
        "name": MODEL_NAME,
        "license": MODEL_LICENSE,
        "sha256": MODEL_SHA256,
        "path": runtime.path if runtime is not None else None,
        "classes": list(CLASS_NAMES),
        "tiled_inference": _truthy("FLOORPLAN_TILED_INFERENCE", True),
    }
