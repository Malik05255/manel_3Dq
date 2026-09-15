from __future__ import annotations

from typing import Any

import cv2
import numpy as np


def _blue_wall_mask_for_model(image: np.ndarray) -> np.ndarray:
    """Detect saturated blue/purple CAD strokes without importing parser modules.

    This lives next to the model input path deliberately: cubicasa_model must not import
    parser.py because parser.py already imports cubicasa_model, which would create a cycle.
    """
    hsv = cv2.cvtColor(image, cv2.COLOR_BGR2HSV)
    hsv_blue = cv2.inRange(
        hsv,
        np.array([85, 45, 35], dtype=np.uint8),
        np.array([150, 255, 255], dtype=np.uint8),
    )
    b, g, r = cv2.split(image)
    dominant = (
        (b.astype(np.int16) >= r.astype(np.int16) + 18)
        & (b.astype(np.int16) >= g.astype(np.int16) + 6)
        & (b >= 65)
    ).astype(np.uint8) * 255
    blue = cv2.bitwise_or(hsv_blue, dominant)
    return cv2.morphologyEx(blue, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8))


def normalize_blue_plan_for_cubicasa(image: np.ndarray) -> tuple[np.ndarray, dict[str, Any]]:
    """Recolour blue/purple CAD strokes to black before CubiCasa inference.

    The source image is never modified. OCR, dimensions and the user preview keep using the
    original colour image. Only meaningful blue CAD plans are normalized. Green dimensions,
    red room labels and other non-blue evidence are left untouched rather than blackened.
    """
    if image.size == 0:
        return image, {"applied": False, "mode": "original", "blue_coverage": 0.0}

    blue = _blue_wall_mask_for_model(image)
    coverage = float(np.count_nonzero(blue)) / max(float(blue.size), 1.0)
    if coverage < 0.0012:
        return image, {
            "applied": False,
            "mode": "original",
            "blue_coverage": round(coverage, 6),
        }

    normalized = image.copy()
    normalized[blue > 0] = (0, 0, 0)

    # Remove only light compression halos touching the blue stroke. This never bridges a
    # door-sized opening and therefore cannot manufacture a wall where the source has a gap.
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
