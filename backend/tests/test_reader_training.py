from __future__ import annotations

import json
import pathlib

import cv2
import numpy as np

from app.reader_training_dataset import materialize
from app.reader_training_masks import CLASS_INDEX, render_mask
from scripts.compare_reader_candidate import decide


def _reference():
    return {
        "schema_version": 1,
        "coordinate_space": "percent-0-100",
        "width_m": 12.0,
        "height_m": 20.0,
        "rooms": [],
        "walls": [
            {
                "id": "w1",
                "start": {"x": 10, "y": 50},
                "end": {"x": 90, "y": 50},
                "thickness_cm": 20,
            }
        ],
        "openings": [
            {"id": "d1", "type": "door", "x": 50, "y": 50, "width": 12, "rotation_deg": 0, "wall_id": "w1"},
            {"id": "o2", "type": "window", "x": 25, "y": 50, "width": 8, "rotation_deg": 0, "wall_id": "w1"},
        ],
    }


def test_corrected_geometry_rasterizes_reader_classes():
    mask = render_mask(_reference(), (200, 400, 3))
    assert mask.shape == (200, 400)
    assert np.count_nonzero(mask == CLASS_INDEX["wall"]) > 0
    assert np.count_nonzero(mask == CLASS_INDEX["door"]) > 0
    assert np.count_nonzero(mask == CLASS_INDEX["window"]) > 0
    assert mask[100, 200] == CLASS_INDEX["door"]


def test_single_floor_correction_materializes_image_and_mask(tmp_path: pathlib.Path):
    inbox = tmp_path / "inbox"
    case = inbox / "hai-train-case1"
    (case / "assets").mkdir(parents=True)
    (case / "training_labels").mkdir()
    image = np.full((120, 180, 3), 255, dtype=np.uint8)
    cv2.line(image, (20, 60), (160, 60), (0, 0, 0), 5)
    cv2.imwrite(str(case / "assets" / "hai-train-case1.png"), image)
    (case / "training_labels" / "hai-train-case1.json").write_text(json.dumps(_reference()), encoding="utf-8")
    manifest = {
        "id": "hai-train-case1",
        "asset": "assets/hai-train-case1.png",
        "training_reference": "training_labels/hai-train-case1.json",
        "split": "train",
        "floors": 1,
    }
    (case / "manifest.jsonl").write_text(json.dumps(manifest) + "\n", encoding="utf-8")
    (case / "ingest-report.json").write_text(json.dumps({"fine_tune_ready": True}), encoding="utf-8")

    output = tmp_path / "dataset"
    report = materialize(inbox, output)
    assert report["trainable_cases"] == 1
    assert (output / "images" / "hai-train-case1.png").is_file()
    mask = cv2.imread(str(output / "masks" / "hai-train-case1.png"), cv2.IMREAD_GRAYSCALE)
    assert mask is not None
    assert np.count_nonzero(mask == CLASS_INDEX["door"]) > 0


def _report(overall: float, opening: float = 0.93, count: int = 30, gate: str = "PASS"):
    return {
        "minimum_test_cases_for_claims": 30,
        "executed_test_cases": count,
        "gate_status": gate,
        "test_means": {
            "wall_f1": 0.96,
            "room_f1": 0.96,
            "opening_f1": opening,
            "dimension_accuracy": 0.96,
            "overall": overall,
        },
    }


def test_candidate_promotes_only_after_real_held_out_gain():
    result = decide(_report(0.95), _report(0.955))
    assert result["promote"] is True
    assert result["overall_gain"] == 0.005


def test_candidate_is_blocked_on_metric_regression_or_small_test_set():
    regressed = decide(_report(0.95), _report(0.956, opening=0.90))
    assert regressed["promote"] is False
    assert any("opening_f1 regressed" in reason for reason in regressed["reasons"])

    insufficient = decide(_report(0.95, count=12), _report(0.96, count=12))
    assert insufficient["promote"] is False
    assert any("held-out test cases" in reason for reason in insufficient["reasons"])
