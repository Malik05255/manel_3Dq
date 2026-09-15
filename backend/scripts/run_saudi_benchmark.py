#!/usr/bin/env python3
from __future__ import annotations

import base64
import json
import os
import pathlib
import sys
from statistics import mean
from typing import Any

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "backend"))

from app.benchmark_metrics import score_case

DEFAULT_POLICY = ROOT / "benchmarks/saudi-floorplans/quality-gate.json"
DEFAULT_MANIFEST = ROOT / "benchmarks/saudi-floorplans/manifest.jsonl"
DEFAULT_REPORT = ROOT / "benchmark-execution-report.json"


def _load_json(path: pathlib.Path) -> dict[str, Any]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError(f"{path}: expected JSON object")
    return payload


def _load_manifest(path: pathlib.Path) -> tuple[list[dict[str, Any]], list[str]]:
    rows: list[dict[str, Any]] = []
    errors: list[str] = []
    if not path.exists():
        return rows, [f"manifest missing: {path}"]
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        try:
            item = json.loads(line)
            if not isinstance(item, dict):
                raise ValueError("row must be an object")
            rows.append(item)
        except Exception as exc:
            errors.append(f"line {number}: {type(exc).__name__}: {exc}")
    return rows, errors


def _resolve_data_path(value: Any, override_root: pathlib.Path | None = None) -> pathlib.Path:
    raw = pathlib.Path(str(value or ""))
    if raw.is_absolute():
        return raw
    repo_path = ROOT / raw
    if repo_path.exists() or override_root is None:
        return repo_path
    return override_root / raw


def _mean_metric(results: list[dict[str, Any]], key: str) -> float:
    values = [float(item[key]) for item in results if key in item]
    return round(mean(values), 5) if values else 0.0


def _weighted_overall(metrics: dict[str, float], weights: dict[str, float]) -> float:
    total_weight = sum(float(value) for value in weights.values())
    if total_weight <= 0:
        raise ValueError("quality gate weights must sum to > 0")
    score = sum(float(metrics.get(key, 0.0)) * float(weight) for key, weight in weights.items()) / total_weight
    return round(score, 5)


def main() -> int:
    manifest = pathlib.Path(os.getenv("SAUDI_BENCHMARK_MANIFEST", str(DEFAULT_MANIFEST)))
    policy_path = pathlib.Path(os.getenv("SAUDI_BENCHMARK_POLICY", str(DEFAULT_POLICY)))
    report_path = pathlib.Path(os.getenv("SAUDI_BENCHMARK_EXEC_REPORT", str(DEFAULT_REPORT)))
    asset_root_raw = os.getenv("SAUDI_BENCHMARK_ASSET_ROOT", "").strip()
    reference_root_raw = os.getenv("SAUDI_BENCHMARK_REFERENCE_ROOT", "").strip()
    asset_root = pathlib.Path(asset_root_raw) if asset_root_raw else None
    reference_root = pathlib.Path(reference_root_raw) if reference_root_raw else None

    policy = _load_json(policy_path)
    expected_model = str(policy.get("expected_model_used", "cubicasa-unet-resnet34-v3"))
    min_test_cases = int(policy.get("minimum_test_cases_for_claims", 30))
    thresholds = {key: float(value) for key, value in dict(policy.get("thresholds", {})).items()}
    weights = {key: float(value) for key, value in dict(policy.get("weights", {})).items()}

    rows, errors = _load_manifest(manifest)
    results: list[dict[str, Any]] = []

    # Do not import the heavy production reader unless there is real licensed data to execute.
    if rows:
        from app.parser_v3 import parse_floorplan

        for row in rows:
            sample_id = str(row.get("id", "unknown"))
            asset = _resolve_data_path(row.get("asset"), asset_root)
            reference = _resolve_data_path(row.get("reference"), reference_root)
            if not asset.is_file() or not reference.is_file():
                errors.append(f"{sample_id}: asset/reference missing ({asset}, {reference})")
                continue
            try:
                expected = _load_json(reference)
                encoded = base64.b64encode(asset.read_bytes()).decode("ascii")
                actual = parse_floorplan(encoded)
                model_used = str(actual.get("model_used", ""))
                if model_used != expected_model:
                    errors.append(f"{sample_id}: reader drift: expected {expected_model!r}, got {model_used!r}")
                    continue

                metrics = score_case(expected, actual)
                overall = _weighted_overall(metrics, weights)
                results.append(
                    {
                        "id": sample_id,
                        "city": row.get("city"),
                        "region": row.get("region"),
                        "project_type": row.get("project_type"),
                        "split": str(row.get("split", "")).lower(),
                        "model_used": model_used,
                        **metrics,
                        "overall": overall,
                        "verified_confidence": actual.get("confidence"),
                        "quality": actual.get("quality", {}),
                        "warnings": actual.get("warnings", []),
                    }
                )
            except Exception as exc:
                errors.append(f"{sample_id}: {type(exc).__name__}: {exc}")

    test_results = [item for item in results if item.get("split") == "test"]
    metric_names = ["wall_f1", "room_f1", "opening_f1", "dimension_accuracy", "overall"]
    means = {key: _mean_metric(test_results, key) for key in metric_names}

    if not rows:
        gate_status = "NO_DATA"
        claims_enabled = False
        gate_failures: list[str] = []
    elif errors:
        gate_status = "EXECUTION_ERROR"
        claims_enabled = False
        gate_failures = []
    elif len(test_results) < min_test_cases:
        gate_status = "INSUFFICIENT_TEST_CASES"
        claims_enabled = False
        gate_failures = []
    else:
        gate_failures = [
            f"{metric}={means.get(metric, 0.0):.5f} < {minimum:.5f}"
            for metric, minimum in thresholds.items()
            if means.get(metric, 0.0) < minimum
        ]
        gate_status = "PASS" if not gate_failures else "FAIL"
        claims_enabled = gate_status == "PASS"

    report = {
        "benchmark": "Saudi Floorplan Benchmark",
        "reader_engine_locked_to": expected_model,
        "licensed_cases_in_manifest": len(rows),
        "executed_cases": len(results),
        "executed_test_cases": len(test_results),
        "minimum_test_cases_for_claims": min_test_cases,
        "gate_status": gate_status,
        "claims_enabled": claims_enabled,
        "test_means": means,
        "thresholds": thresholds,
        "weights": weights,
        "gate_failures": gate_failures,
        "results": results,
        "errors": errors,
        "claim_policy": (
            "Accuracy claims are enabled only when the locked production Reader V3 executes the required number "
            "of licensed, de-identified test cases and every quality threshold passes."
        ),
    }
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))

    if errors:
        return 2
    if gate_status == "FAIL":
        return 4
    if gate_status == "NO_DATA":
        print("No licensed Saudi cases are present; real-world accuracy claims remain disabled.")
    elif gate_status == "INSUFFICIENT_TEST_CASES":
        print(f"Only {len(test_results)} test case(s); need {min_test_cases} before accuracy claims can be enabled.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
