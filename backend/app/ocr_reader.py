from __future__ import annotations

import os
from functools import lru_cache
from pathlib import Path
from typing import Any

import numpy as np

BACKEND_ROOT = Path(__file__).resolve().parents[1]
PORTABLE_TESSERACT_ROOT = BACKEND_ROOT / ".portable-tesseract"


class TesseractReaderAdapter:
    """Expose pytesseract through the small EasyOCR-compatible readtext surface we use."""

    def __init__(self, pytesseract_module: Any) -> None:
        self.pytesseract = pytesseract_module

    def readtext(self, image: np.ndarray, detail: int = 1, paragraph: bool = False) -> list[Any]:
        output = self.pytesseract.image_to_data(
            image,
            lang=os.getenv("TESSERACT_LANG", "ara+eng"),
            config=os.getenv("TESSERACT_CONFIG", "--oem 1 --psm 11"),
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


def _find_tessdata(root: Path) -> Path | None:
    for candidate in (
        root / "usr/share/tesseract-ocr/5/tessdata",
        root / "usr/share/tesseract-ocr/4.00/tessdata",
        root / "usr/share/tessdata",
    ):
        if (candidate / "ara.traineddata").is_file() and (candidate / "eng.traineddata").is_file():
            return candidate
    return None


def _configure_portable_tesseract(pytesseract_module: Any) -> bool:
    root = Path(os.getenv("TESSERACT_PORTABLE_ROOT", str(PORTABLE_TESSERACT_ROOT))).resolve()
    binary = root / "usr/bin/tesseract"
    tessdata = _find_tessdata(root)
    if not binary.is_file() or tessdata is None:
        return False

    lib_dirs = [
        root / "usr/lib/x86_64-linux-gnu",
        root / "lib/x86_64-linux-gnu",
        root / "usr/lib",
        root / "lib",
    ]
    current = os.environ.get("LD_LIBRARY_PATH", "")
    local = ":".join(str(path) for path in lib_dirs if path.is_dir())
    os.environ["LD_LIBRARY_PATH"] = ":".join(part for part in (local, current) if part)
    os.environ["TESSDATA_PREFIX"] = str(tessdata)
    pytesseract_module.pytesseract.tesseract_cmd = str(binary)
    return True


@lru_cache(maxsize=1)
def cloud_ocr_reader() -> Any | None:
    """Use lightweight Tesseract Arabic+English; avoid a second Torch OCR model in Reader V3."""
    try:
        import pytesseract

        _configure_portable_tesseract(pytesseract)
        languages = set(pytesseract.get_languages(config=""))
        if {"ara", "eng"}.issubset(languages):
            return TesseractReaderAdapter(pytesseract)
    except Exception:
        pass

    # Developer/full environments may already provide EasyOCR. Production does not
    # depend on it because it would compete with CubiCasa for the 512 MB memory budget.
    try:
        import easyocr

        return easyocr.Reader(["ar", "en"], gpu=False, verbose=False)
    except Exception:
        return None


def cloud_ocr_engine_name() -> str:
    reader = cloud_ocr_reader()
    if reader is None:
        return "unavailable"
    if isinstance(reader, TesseractReaderAdapter):
        return "tesseract-ara-eng"
    return "easyocr-ar-en"
