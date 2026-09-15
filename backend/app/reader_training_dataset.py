from __future__ import annotations

import json
import pathlib
from typing import Any

import cv2
import numpy as np

from .reader_training_masks import CLASS_NAMES, render_mask


def _one_manifest(path: pathlib.Path) -> dict[str, Any]:
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            rows.append(json.loads(line))
    if len(rows) != 1 or not isinstance(rows[0], dict):
        raise ValueError("expected one manifest row")
    return rows[0]


def materialize(inbox: pathlib.Path, output: pathlib.Path) -> dict[str, Any]:
    images = output / "images"
    masks = output / "masks"
    images.mkdir(parents=True, exist_ok=True)
    masks.mkdir(parents=True, exist_ok=True)
    rows = []
    skipped = []
    class_pixels = {name: 0 for name in CLASS_NAMES}

    for case_dir in sorted(path for path in inbox.iterdir() if path.is_dir()) if inbox.is_dir() else []:
        try:
            manifest = _one_manifest(case_dir / "manifest.jsonl")
            report = json.loads((case_dir / "ingest-report.json").read_text(encoding="utf-8"))
            if manifest.get("split") != "train":
                raise ValueError("only train corrections are allowed")
            if int(manifest.get("floors", 0)) != 1:
                raise ValueError("multi-floor correction needs page alignment")
            if report.get("fine_tune_ready") is not True:
                raise ValueError(str(report.get("fine_tune_blocker") or "not ready"))
            asset = case_dir / pathlib.PurePosixPath(str(manifest["asset"]))
            reference_path = case_dir / pathlib.PurePosixPath(str(manifest["training_reference"]))
            if asset.suffix.lower() not in {".png", ".jpg", ".jpeg", ".webp"}:
                raise ValueError("first fine-tune version accepts single-page images only")
            image = cv2.imread(str(asset), cv2.IMREAD_COLOR)
            if image is None:
                raise ValueError("cannot decode image")
            reference = json.loads(reference_path.read_text(encoding="utf-8"))
            mask = render_mask(reference, image.shape)
            image_name = f"{case_dir.name}.png"
            mask_name = f"{case_dir.name}.png"
            cv2.imwrite(str(images / image_name), image)
            cv2.imwrite(str(masks / mask_name), mask)
            for index, name in enumerate(CLASS_NAMES):
                class_pixels[name] += int(np.count_nonzero(mask == index))
            rows.append({"id": case_dir.name, "image": f"images/{image_name}", "mask": f"masks/{mask_name}", "split": "train"})
        except Exception as exc:
            skipped.append({"id": case_dir.name, "reason": str(exc)})

    (output / "dataset.jsonl").write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")
    report = {"trainable_cases": len(rows), "skipped_cases": skipped, "class_pixels": class_pixels}
    (output / "materialize-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    return report
