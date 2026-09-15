from __future__ import annotations

from typing import Any

import cv2
import numpy as np

from .parser import _blue_wall_mask


def normalize_blue_plan_for_cubicasa(image: np.ndarray) -> tuple[np.ndarray, dict[str, Any]]:
    """Recolour blue/purple CAD strokes to black before CubiCasa inference.

    The original image is intentionally left untouched for OCR, dimensions and UI preview.
    Only plans with meaningful blue architectural coverage are normalized. Other colours are
    preserved so green dimension lines and red/gray labels are not turned into wall evidence.
    """
    if image.size == 0:
        return image, {"applied": False, "mode": "original", "blue_coverage": 0.0}

    blue = _blue_wall_mask(image)
    coverage = float(np.count_nonzero(blue)) / max(float(blue.size), 1.0)
    if coverage < 0.0012:
        return image, {
            "applied": False,
            "mode": "original",
            "blue_coverage": round(coverage, 6),
        }

    normalized = image.copy()
    normalized[blue > 0] = (0, 0, 0)

    # Keep the white/background appearance stable while removing tiny compression halos
    # immediately around the recoloured CAD strokes. This does not bridge door-sized gaps.
    halo = cv2.dilate(blue, np.ones((3, 3), np.uint8), iterations=1)
    near_blue = (halo > 0) & (blue == 0)
    if np.any(near_blue):
        local = normalized[near_blue]
        very_light = np.min(local, axis=1) >= 225
        if np.any(very_light):
            local[very_light] = 255
            normalized[near_blue] = local

    return normalized, {
        "applied": True,
        "mode": "blue-to-black",
        "blue_coverage": round(coverage, 6),
        "changed_pixels": int(np.count_nonzero(blue)),
    }
