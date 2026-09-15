from __future__ import annotations

import json
import pathlib
import zipfile

import pytest

from scripts.ingest_reader_learning_bundle import EXPECTED_MODEL, ingest, validate_bundle


def _write_bundle(
    path: pathlib.Path,
    *,
    split: str = "train",
    deidentified: bool = True,
    floors: int = 1,
    rich_training_reference: bool = True,
) -> None:
    case_id = "hai-train-test001"
    manifest = {
        "id": case_id,
        "city": "الرياض",
        "region": "central",
        "source": "user-consented-private",
        "asset": f"assets/{case_id}.png",
        "reference": f"labels/{case_id}.json",
        "floors": floors,
        "license": "explicit-owner-or-license-consent",
        "deidentified": deidentified,
        "split": split,
        "project_type": "villa_two",
    }
    if rich_training_reference:
        manifest["training_reference"] = f"training_labels/{case_id}.json"
    reference = {"walls": [], "rooms": [], "openings": [], "metric": {"dimensions": []}}
    training_reference = {
        "schema_version": 1,
        "coordinate_space": "percent-0-100",
        "width_m": 12.0,
        "height_m": 20.0,
        "walls": [],
        "rooms": [],
        "openings": [],
    }
    consent = {
        "case_id": case_id,
        "reader_model": EXPECTED_MODEL,
        "owns_or_licensed_attested": True,
        "deidentified_attested": True,
        "automatic_upload": False,
        "benchmark_split": "train",
    }
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("manifest.jsonl", json.dumps(manifest, ensure_ascii=False) + "\n")
        archive.writestr(f"assets/{case_id}.png", b"fake-image-for-contract-test")
        archive.writestr(f"labels/{case_id}.json", json.dumps(reference))
        archive.writestr(f"baselines/{case_id}.json", json.dumps(reference))
        if rich_training_reference:
            archive.writestr(f"training_labels/{case_id}.json", json.dumps(training_reference))
        archive.writestr(f"consent/{case_id}.json", json.dumps(consent))


def test_valid_bundle_is_train_only_and_fine_tune_ready(tmp_path: pathlib.Path) -> None:
    bundle = tmp_path / "case.zip"
    inbox = tmp_path / "inbox"
    _write_bundle(bundle)

    validated = validate_bundle(bundle)
    assert validated.case_id == "hai-train-test001"
    assert validated.manifest["split"] == "train"
    assert validated.training_reference_name == "training_labels/hai-train-test001.json"

    report = ingest(bundle, inbox)
    assert report["status"] == "accepted-private-training-inbox"
    assert report["automatic_training_started"] is False
    assert report["fine_tune_ready"] is True
    assert (inbox / validated.case_id / "training_labels" / "hai-train-test001.json").is_file()


def test_legacy_bundle_stays_accepted_but_is_not_fine_tune_ready(tmp_path: pathlib.Path) -> None:
    bundle = tmp_path / "case.zip"
    inbox = tmp_path / "inbox"
    _write_bundle(bundle, rich_training_reference=False)
    report = ingest(bundle, inbox)
    assert report["fine_tune_ready"] is False
    assert "re-export" in report["fine_tune_blocker"]


def test_multifloor_bundle_is_not_used_for_fine_tuning_without_alignment(tmp_path: pathlib.Path) -> None:
    bundle = tmp_path / "case.zip"
    inbox = tmp_path / "inbox"
    _write_bundle(bundle, floors=2)
    report = ingest(bundle, inbox)
    assert report["fine_tune_ready"] is False
    assert "page/floor alignment" in report["fine_tune_blocker"]


def test_test_split_is_rejected_to_prevent_benchmark_leakage(tmp_path: pathlib.Path) -> None:
    bundle = tmp_path / "case.zip"
    _write_bundle(bundle, split="test")
    with pytest.raises(ValueError, match="train only"):
        validate_bundle(bundle)


def test_missing_deidentification_is_rejected(tmp_path: pathlib.Path) -> None:
    bundle = tmp_path / "case.zip"
    _write_bundle(bundle, deidentified=False)
    with pytest.raises(ValueError, match="deidentified=true"):
        validate_bundle(bundle)
