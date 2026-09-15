#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import pathlib
import shutil
import sys
import tempfile
import zipfile
from dataclasses import dataclass
from typing import Any

ROOT = pathlib.Path(__file__).resolve().parents[2]
EXPECTED_MODEL = "cubicasa-unet-resnet34-v3"
ALLOWED_ASSET_SUFFIXES = {".png", ".jpg", ".jpeg", ".webp", ".heic", ".heif", ".pdf"}


@dataclass(frozen=True)
class ValidatedBundle:
    case_id: str
    manifest: dict[str, Any]
    asset_name: str
    reference_name: str
    consent_name: str
    baseline_name: str | None
    training_reference_name: str | None


def _safe_member(name: str) -> bool:
    path = pathlib.PurePosixPath(name)
    return bool(name) and not path.is_absolute() and ".." not in path.parts and "\\" not in name


def _load_json(raw: bytes, label: str) -> dict[str, Any]:
    try:
        value = json.loads(raw.decode("utf-8"))
    except Exception as exc:
        raise ValueError(f"{label}: invalid JSON: {exc}") from exc
    if not isinstance(value, dict):
        raise ValueError(f"{label}: expected JSON object")
    return value


def _load_manifest(raw: bytes) -> dict[str, Any]:
    rows = []
    for number, line in enumerate(raw.decode("utf-8").splitlines(), 1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        try:
            item = json.loads(stripped)
        except Exception as exc:
            raise ValueError(f"manifest.jsonl line {number}: {exc}") from exc
        if not isinstance(item, dict):
            raise ValueError(f"manifest.jsonl line {number}: expected object")
        rows.append(item)
    if len(rows) != 1:
        raise ValueError("learning bundle must contain exactly one manifest row")
    return rows[0]


def _validate_training_reference(reference: dict[str, Any], label: str) -> None:
    if int(reference.get("schema_version", 0)) != 1:
        raise ValueError(f"{label}: unsupported training reference schema")
    if str(reference.get("coordinate_space", "")) != "percent-0-100":
        raise ValueError(f"{label}: coordinate_space must be percent-0-100")
    for key in ("walls", "rooms", "openings"):
        if not isinstance(reference.get(key), list):
            raise ValueError(f"{label}: {key} must be a list")


def validate_bundle(bundle_path: pathlib.Path) -> ValidatedBundle:
    if not bundle_path.is_file():
        raise ValueError(f"bundle not found: {bundle_path}")
    with zipfile.ZipFile(bundle_path) as archive:
        names = archive.namelist()
        if any(not _safe_member(name) for name in names):
            raise ValueError("bundle contains unsafe ZIP paths")
        if "manifest.jsonl" not in names:
            raise ValueError("manifest.jsonl is missing")

        manifest = _load_manifest(archive.read("manifest.jsonl"))
        case_id = str(manifest.get("id", "")).strip()
        if not case_id or not all(ch.isalnum() or ch in "-_" for ch in case_id):
            raise ValueError("manifest id is invalid")
        if manifest.get("deidentified") is not True:
            raise ValueError("deidentified=true is required")
        if str(manifest.get("split", "")).lower() != "train":
            raise ValueError("user-correction bundles are accepted as train only")
        if str(manifest.get("source", "")) != "user-consented-private":
            raise ValueError("unexpected learning bundle source")
        if str(manifest.get("license", "")) != "explicit-owner-or-license-consent":
            raise ValueError("explicit owner/license consent is required")
        for required in ("city", "region", "project_type"):
            if len(str(manifest.get(required, "")).strip()) < 2:
                raise ValueError(f"manifest {required} is required")
        floors = int(manifest.get("floors", 0))
        if floors < 1 or floors > 20:
            raise ValueError("manifest floors must be between 1 and 20")

        asset_name = str(manifest.get("asset", ""))
        reference_name = str(manifest.get("reference", ""))
        if asset_name not in names or reference_name not in names:
            raise ValueError("manifest asset/reference is missing from bundle")
        if pathlib.PurePosixPath(asset_name).suffix.lower() not in ALLOWED_ASSET_SUFFIXES:
            raise ValueError("unsupported floor-plan asset format")

        training_reference_name = str(manifest.get("training_reference", "")).strip() or None
        if training_reference_name is not None:
            if training_reference_name not in names:
                raise ValueError("manifest training_reference is missing from bundle")
            training_reference = _load_json(archive.read(training_reference_name), training_reference_name)
            _validate_training_reference(training_reference, training_reference_name)

        consent_name = f"consent/{case_id}.json"
        if consent_name not in names:
            raise ValueError("consent record is missing")
        consent = _load_json(archive.read(consent_name), consent_name)
        if consent.get("owns_or_licensed_attested") is not True:
            raise ValueError("ownership/license attestation is missing")
        if consent.get("deidentified_attested") is not True:
            raise ValueError("de-identification attestation is missing")
        if consent.get("automatic_upload") is not False:
            raise ValueError("automatic_upload must remain false")
        if str(consent.get("benchmark_split", "")).lower() != "train":
            raise ValueError("consent benchmark_split must be train")
        if str(consent.get("reader_model", "")) != EXPECTED_MODEL:
            raise ValueError("reader model drift detected")

        reference = _load_json(archive.read(reference_name), reference_name)
        for key in ("walls", "rooms", "openings", "metric"):
            if key not in reference:
                raise ValueError(f"reference missing {key}")

        baseline_name = f"baselines/{case_id}.json"
        if baseline_name in names:
            _load_json(archive.read(baseline_name), baseline_name)
        else:
            baseline_name = None

        return ValidatedBundle(
            case_id,
            manifest,
            asset_name,
            reference_name,
            consent_name,
            baseline_name,
            training_reference_name,
        )


def ingest(bundle_path: pathlib.Path, inbox_root: pathlib.Path) -> dict[str, Any]:
    validated = validate_bundle(bundle_path)
    inbox_root.mkdir(parents=True, exist_ok=True)
    target = inbox_root / validated.case_id
    if target.exists():
        raise ValueError(f"case already exists: {validated.case_id}")

    with tempfile.TemporaryDirectory(prefix="hai-learning-") as temp_dir:
        temp = pathlib.Path(temp_dir) / validated.case_id
        temp.mkdir(parents=True)
        with zipfile.ZipFile(bundle_path) as archive:
            wanted = ["manifest.jsonl", validated.asset_name, validated.reference_name, validated.consent_name]
            if validated.baseline_name:
                wanted.append(validated.baseline_name)
            if validated.training_reference_name:
                wanted.append(validated.training_reference_name)
            for name in wanted:
                destination = temp / pathlib.PurePosixPath(name)
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(archive.read(name))

        floors = int(validated.manifest.get("floors", 0))
        fine_tune_ready = validated.training_reference_name is not None and floors == 1
        report = {
            "case_id": validated.case_id,
            "status": "accepted-private-training-inbox",
            "reader_model": EXPECTED_MODEL,
            "split": "train",
            "deidentified_attested": True,
            "rights_attested": True,
            "automatic_training_started": False,
            "benchmark_ready": pathlib.PurePosixPath(validated.asset_name).suffix.lower() != ".pdf",
            "fine_tune_ready": fine_tune_ready,
            "fine_tune_blocker": None if fine_tune_ready else (
                "multi-floor corrections need page/floor alignment before training" if floors != 1
                else "rich training_reference missing; re-export this correction from the current app"
            ),
            "warning": "De-identification is user-attested and must be reviewed again before any broader use.",
        }
        (temp / "ingest-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        shutil.move(str(temp), str(target))

    index = inbox_root / "index.jsonl"
    with index.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps({"id": validated.case_id, **validated.manifest}, ensure_ascii=False) + "\n")
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate and ingest a consented Manzili HAI Reader V3 learning bundle.")
    parser.add_argument("bundle", type=pathlib.Path)
    parser.add_argument(
        "--inbox",
        type=pathlib.Path,
        default=ROOT / ".private" / "reader-learning-inbox",
        help="Private destination outside the public benchmark by default.",
    )
    args = parser.parse_args()
    try:
        report = ingest(args.bundle, args.inbox)
    except Exception as exc:
        print(json.dumps({"status": "rejected", "error": str(exc)}, ensure_ascii=False, indent=2), file=sys.stderr)
        return 2
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
