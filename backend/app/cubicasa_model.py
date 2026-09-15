from __future__ import annotations

import ctypes
import gc
import os
from functools import lru_cache
from pathlib import Path
from typing import Any

import cv2
import numpy as np

from .blue_input_normalizer import normalize_blue_plan_for_cubicasa

MODEL_NAME = "Yytsi/floorplan-to-3d-walls"
MODEL_LICENSE = "MIT"
MODEL_SHA256 = "d7f6a0fd06e2931aecfc8c4849192c5e153701578026efc78d9a6246731a8d6c"
DEFAULT_MODEL_PATH = "/opt/manzili/models/floorplan/best.safetensors"
DEFAULT_IMAGE_SIZE = 512
LOW_MEMORY_IMAGE_SIZE = 352
IMAGENET_MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
IMAGENET_STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)
CLASS_NAMES = ("floor", "wall", "door", "window")


def _truthy(name: str, default: bool = True) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def _configured_image_size() -> int:
    """Return an architecture-safe inference size divisible by 32."""
    default = LOW_MEMORY_IMAGE_SIZE if _truthy("FLOORPLAN_LOW_MEMORY", False) else DEFAULT_IMAGE_SIZE
    raw = os.getenv("FLOORPLAN_IMAGE_SIZE", str(default)).strip()
    try:
        requested = int(raw)
    except ValueError:
        requested = default
    requested = max(256, min(DEFAULT_IMAGE_SIZE, requested))
    return max(256, (requested // 32) * 32)


def _configured_dtype_name() -> str:
    # CPU FP16 is materially slower on Render-class CPUs. Keep FP32 and save memory
    # through a smaller canvas, packed tile votes, one thread, and allocator trimming.
    default = "float32"
    value = os.getenv("FLOORPLAN_DTYPE", default).strip().lower()
    aliases = {
        "fp16": "float16",
        "half": "float16",
        "float16": "float16",
        "bf16": "bfloat16",
        "bfloat16": "bfloat16",
        "fp32": "float32",
        "float": "float32",
        "float32": "float32",
    }
    return aliases.get(value, default)


def _current_rss_kib() -> int | None:
    """Return current resident memory, not historical peak RSS."""
    try:
        for line in Path("/proc/self/status").read_text(encoding="utf-8").splitlines():
            if line.startswith("VmRSS:"):
                return int(line.split()[1])
    except Exception:
        return None
    return None


def _trim_process_memory() -> None:
    """Return free glibc arenas to the OS after checkpoint loading/inference."""
    gc.collect()
    try:
        ctypes.CDLL("libc.so.6").malloc_trim(0)
    except Exception:
        pass


def _axis_starts(length: int, tile: int, overlap: float) -> list[int]:
    if tile >= length:
        return [0]
    step = max(1, int(round(tile * (1.0 - overlap))))
    starts = list(range(0, max(1, length - tile + 1), step))
    tail = max(0, length - tile)
    if not starts or starts[-1] != tail:
        starts.append(tail)
    return sorted(set(starts))


def _add_packed_vote(packed: np.ndarray, prediction: np.ndarray, weight: int) -> None:
    """Store four class counters in one uint16 (4 bits per class).

    With 30% overlap, one pixel is covered by at most four neighbouring tiles in the
    regular grid. Global vote 1 + four tile votes * 2 = 9, safely below a 4-bit counter.
    This halves fusion memory versus a 4 x H x W uint8 vote tensor.
    """
    for class_index in range(len(CLASS_NAMES)):
        shift = class_index * 4
        packed += ((prediction == class_index).astype(np.uint16) * weight) << shift


def _packed_argmax(packed: np.ndarray) -> np.ndarray:
    h, w = packed.shape
    best_class = np.zeros((h, w), dtype=np.uint8)
    best_score = np.zeros((h, w), dtype=np.uint8)
    for class_index in range(len(CLASS_NAMES)):
        score = ((packed >> (class_index * 4)) & 0xF).astype(np.uint8)
        better = score > best_score
        best_class[better] = class_index
        best_score[better] = score[better]
        del score, better
    return best_class


class CubiCasaRuntime:
    def __init__(
        self,
        model: Any,
        torch_module: Any,
        device: Any,
        path: str,
        image_size: int,
        dtype: Any,
        dtype_name: str,
    ) -> None:
        self.model = model
        self.torch = torch_module
        self.device = device
        self.path = path
        self.image_size = image_size
        self.dtype = dtype
        self.dtype_name = dtype_name
        self.last_input_normalization: dict[str, Any] = {
            "applied": False,
            "mode": "original",
            "blue_coverage": 0.0,
        }

    def _predict_single(self, image_bgr: np.ndarray) -> np.ndarray:
        h, w = image_bgr.shape[:2]
        image_size = self.image_size
        rgb = cv2.cvtColor(image_bgr, cv2.COLOR_BGR2RGB)
        scale = min(image_size / max(w, 1), image_size / max(h, 1))
        inner_w = max(1, int(round(w * scale)))
        inner_h = max(1, int(round(h * scale)))
        interpolation = cv2.INTER_AREA if scale <= 1.0 else cv2.INTER_CUBIC
        resized = cv2.resize(rgb, (inner_w, inner_h), interpolation=interpolation)

        normalized = resized.astype(np.float32) / 255.0
        normalized = (normalized - IMAGENET_MEAN) / IMAGENET_STD
        canvas = np.zeros((image_size, image_size, 3), dtype=np.float32)
        top = (image_size - inner_h) // 2
        left = (image_size - inner_w) // 2
        canvas[top:top + inner_h, left:left + inner_w] = normalized

        tensor = self.torch.from_numpy(np.transpose(canvas, (2, 0, 1))[None, ...]).to(
            device=self.device,
            dtype=self.dtype,
        )
        with self.torch.inference_mode():
            logits = self.model(tensor)
            prediction = logits.argmax(dim=1).squeeze(0).cpu().numpy().astype(np.uint8)
        del logits, tensor, canvas, normalized, resized, rgb

        crop = prediction[top:top + inner_h, left:left + inner_w]
        result = cv2.resize(crop, (w, h), interpolation=cv2.INTER_NEAREST)
        del crop, prediction
        return result

    def predict(self, image_bgr: np.ndarray) -> np.ndarray:
        """Fuse global context with overlapping high-resolution tiles.

        Blue/purple CAD plans are first recoloured to black line-art for CubiCasa only.
        The caller's source image remains unchanged for OCR, dimensions and UI preview.
        If the normalized global pass produces virtually no wall class, inference safely
        falls back to the original colour image before tiled fusion.
        """
        model_image, normalization = normalize_blue_plan_for_cubicasa(image_bgr)

        h, w = model_image.shape[:2]
        global_prediction = self._predict_single(model_image)
        wall_fraction = float(np.count_nonzero(global_prediction == 1)) / max(float(global_prediction.size), 1.0)
        normalization = dict(normalization)
        normalization["global_wall_fraction"] = round(wall_fraction, 6)

        if bool(normalization.get("applied")) and wall_fraction < 0.001:
            del global_prediction
            if model_image is not image_bgr:
                del model_image
            _trim_process_memory()
            model_image = image_bgr
            global_prediction = self._predict_single(model_image)
            fallback_fraction = float(np.count_nonzero(global_prediction == 1)) / max(float(global_prediction.size), 1.0)
            normalization["fallback_original"] = True
            normalization["mode"] = "blue-to-black-fallback-original"
            normalization["fallback_global_wall_fraction"] = round(fallback_fraction, 6)
        else:
            normalization["fallback_original"] = False

        self.last_input_normalization = normalization

        if not _truthy("FLOORPLAN_TILED_INFERENCE", True) or max(h, w) < 1200:
            if model_image is not image_bgr:
                del model_image
            _trim_process_memory()
            return global_prediction

        min_side = min(h, w)
        size_ratio = self.image_size / DEFAULT_IMAGE_SIZE
        min_tile = max(520, int(round(820 * size_ratio)))
        max_tile = max(min_tile, int(round(1500 * size_ratio)))
        tile = min(max_tile, max(min_tile, int(round(min_side * 0.62))))
        overlap = max(0.18, min(0.42, float(os.getenv("FLOORPLAN_TILE_OVERLAP", "0.30"))))
        xs = _axis_starts(w, min(tile, w), overlap)
        ys = _axis_starts(h, min(tile, h), overlap)

        windows = [(x, y, min(w, x + tile), min(h, y + tile)) for y in ys for x in xs]
        max_tiles = max(4, min(16, int(os.getenv("FLOORPLAN_MAX_TILES", "12"))))
        if len(windows) > max_tiles:
            indices = np.linspace(0, len(windows) - 1, max_tiles).round().astype(int)
            windows = [windows[int(i)] for i in sorted(set(indices.tolist()))]

        packed_votes = np.zeros((h, w), dtype=np.uint16)
        _add_packed_vote(packed_votes, global_prediction, 1)
        del global_prediction

        for x0, y0, x1, y1 in windows:
            crop = model_image[y0:y1, x0:x1]
            if crop.size == 0:
                continue
            tile_prediction = self._predict_single(crop)
            _add_packed_vote(packed_votes[y0:y1, x0:x1], tile_prediction, 2)
            del tile_prediction, crop

        result = _packed_argmax(packed_votes)
        del packed_votes
        if model_image is not image_bgr:
            del model_image
        _trim_process_memory()
        return result


@lru_cache(maxsize=1)
def load_cubicasa_runtime() -> CubiCasaRuntime | None:
    path = os.getenv("FLOORPLAN_SAFETENSORS_MODEL", DEFAULT_MODEL_PATH).strip()
    if not path or not Path(path).is_file():
        return None
    try:
        import torch
        import segmentation_models_pytorch as smp
        from safetensors.torch import load_file

        low_memory = _truthy("FLOORPLAN_LOW_MEMORY", False)
        default_threads = "1" if low_memory else "2"
        torch.set_num_threads(max(1, int(os.getenv("FLOORPLAN_TORCH_THREADS", default_threads))))
        try:
            torch.set_num_interop_threads(1)
        except RuntimeError:
            pass

        device = torch.device(os.getenv("FLOORPLAN_DEVICE", "cpu"))
        dtype_name = _configured_dtype_name()
        dtype = {
            "float16": torch.float16,
            "bfloat16": torch.bfloat16,
            "float32": torch.float32,
        }[dtype_name]

        model = smp.Unet(
            encoder_name="resnet34",
            encoder_weights=None,
            in_channels=3,
            classes=len(CLASS_NAMES),
        ).to(device=device, dtype=dtype)
        state = load_file(path, device="cpu")

        if dtype_name == "float32" and device.type == "cpu":
            try:
                model.load_state_dict(state, strict=True, assign=True)
            except TypeError:
                model.load_state_dict(state, strict=True)
        else:
            model.load_state_dict(state, strict=True)

        del state
        _trim_process_memory()
        model.eval()
        return CubiCasaRuntime(
            model=model,
            torch_module=torch,
            device=device,
            path=path,
            image_size=_configured_image_size(),
            dtype=dtype,
            dtype_name=dtype_name,
        )
    except Exception:
        return None


def model_status() -> dict[str, Any]:
    runtime = load_cubicasa_runtime()
    low_memory = _truthy("FLOORPLAN_LOW_MEMORY", False)
    return {
        "configured": runtime is not None,
        "name": MODEL_NAME,
        "license": MODEL_LICENSE,
        "sha256": MODEL_SHA256,
        "path": runtime.path if runtime is not None else None,
        "classes": list(CLASS_NAMES),
        "image_size": runtime.image_size if runtime is not None else _configured_image_size(),
        "dtype": runtime.dtype_name if runtime is not None else _configured_dtype_name(),
        "low_memory": low_memory,
        "torch_threads": max(1, int(os.getenv("FLOORPLAN_TORCH_THREADS", "1" if low_memory else "2"))),
        "tiled_inference": _truthy("FLOORPLAN_TILED_INFERENCE", True),
        "input_normalization": dict(runtime.last_input_normalization) if runtime is not None else {
            "applied": False,
            "mode": "original",
            "blue_coverage": 0.0,
        },
        "rss_kib": _current_rss_kib(),
    }
