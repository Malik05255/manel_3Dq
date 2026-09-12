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


class CubiCasaRuntime:
    def __init__(self, model: Any, torch_module: Any, device: Any, path: str) -> None:
        self.model = model
        self.torch = torch_module
        self.device = device
        self.path = path

    def predict(self, image_bgr: np.ndarray) -> np.ndarray:
        h, w = image_bgr.shape[:2]
        rgb = cv2.cvtColor(image_bgr, cv2.COLOR_BGR2RGB)
        scale = min(IMAGE_SIZE / max(w, 1), IMAGE_SIZE / max(h, 1))
        inner_w = max(1, int(round(w * scale)))
        inner_h = max(1, int(round(h * scale)))
        resized = cv2.resize(rgb, (inner_w, inner_h), interpolation=cv2.INTER_AREA)

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
    }
