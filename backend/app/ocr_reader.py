from __future__ import annotations

import os
from functools import lru_cache
from typing import Any

import numpy as np


class TesseractReaderAdapter:
    """Expose pytesseract through the small EasyOCR-compatible readtext surface we use."""

    def __init__(self, pytesseract_module: Any) -> None:
        self.pytesseract = pytesseract_module

    def readtext(self, image: np.ndarray, detail: int = 1, paragraph: bool = False) -> list[Any]:
        output = self.pytesseract.image_to_data(
            image,
            lang=os.getenv("TESSERACT_LANG", "ara+eng"),
            config=os.getenv("TESSERACT_CONFIG", "--psm 11"),
            output_type=self.pytesseract.Output.DICT,
        )
        result: list[Any] = []
        count = len(output.get("text", []))
        for index in range(count):
            text = str(output["text"][index] or "").strip()
            if not text:
                continue
            try:
                confidence = float(output["conf"][index])
                left = float(output["left"][index])
                top = float(output["top"][index])
                width = float(output["width"][index])
                height = float(output["height"][index])
            except (TypeError, ValueError, KeyError, IndexError):
                continue
            if confidence < 0.0 or width <= 0.0 or height <= 0.0:
                continue
            box = [
                [left, top],
                [left + width, top],
                [left + width, top + height],
                [left, top + height],
            ]
            score = max(0.0, min(1.0, confidence / 100.0))
            result.append((box, text, score))
        return result


@lru_cache(maxsize=1)
def cloud_ocr_reader() -> Any | None:
    """Prefer EasyOCR when installed; otherwise use system Tesseract Arabic+English."""
    try:
        import easyocr

        return easyocr.Reader(["ar", "en"], gpu=False, verbose=False)
    except Exception:
        pass

    try:
        import pytesseract

        languages = set(pytesseract.get_languages(config=""))
        if "ara" not in languages and "eng" not in languages:
            return None
        return TesseractReaderAdapter(pytesseract)
    except Exception:
        return None


def cloud_ocr_engine_name() -> str:
    reader = cloud_ocr_reader()
    if reader is None:
        return "unavailable"
    if isinstance(reader, TesseractReaderAdapter):
        return "tesseract-ara-eng"
    return "easyocr-ar-en"
