#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import pathlib
from typing import Any

METRICS = ("wall_f1", "room_f1", "opening_f1", "dimension_accuracy")


def decide(
    baseline: dict[str, Any],
    candidate: dict[str, Any],
    min_gain: float = 0.003,
    max_regression: float = 0.01,
) -> dict[str, Any]:
    required = max(
        int(baseline.get("minimum_test_cases_for_claims", 30)),
        int(candidate.get("minimum_test_cases_for_claims", 30)),
    )
    before_count = int(baseline.get("executed_test_cases", 0))
    after_count = int(candidate.get("executed_test_cases", 0))
    before = dict(baseline.get("test_means") or {})
    after = dict(candidate.get("test_means") or {})
    reasons = []
    if before_count < required or after_count < required:
        reasons.append(f"need {required} held-out test cases for both readers")
    if candidate.get("gate_status") != "PASS":
        reasons.append(f"candidate gate is {candidate.get('gate_status')}")
    deltas = {}
    for metric in METRICS:
        delta = round(float(after.get(metric, 0.0)) - float(before.get(metric, 0.0)), 5)
        deltas[metric] = delta
        if delta < -abs(max_regression):
            reasons.append(f"{metric} regressed by {abs(delta):.5f}")
    overall_gain = round(float(after.get("overall", 0.0)) - float(before.get("overall", 0.0)), 5)
    fail_to_pass = baseline.get("gate_status") != "PASS" and candidate.get("gate_status") == "PASS"
    if not fail_to_pass and overall_gain < min_gain:
        reasons.append(f"overall gain {overall_gain:.5f} is below {min_gain:.5f}")
    return {
        "promote": not reasons,
        "baseline_gate": baseline.get("gate_status"),
        "candidate_gate": candidate.get("gate_status"),
        "baseline_test_cases": before_count,
        "candidate_test_cases": after_count,
        "required_test_cases": required,
        "overall_gain": overall_gain,
        "metric_deltas": deltas,
        "reasons": reasons,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("baseline", type=pathlib.Path)
    parser.add_argument("candidate", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--min-gain", type=float, default=0.003)
    parser.add_argument("--max-regression", type=float, default=0.01)
    args = parser.parse_args()
    baseline = json.loads(args.baseline.read_text(encoding="utf-8"))
    candidate = json.loads(args.candidate.read_text(encoding="utf-8"))
    result = decide(baseline, candidate, args.min_gain, args.max_regression)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["promote"] else 5


if __name__ == "__main__":
    raise SystemExit(main())
