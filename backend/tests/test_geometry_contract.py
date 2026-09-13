from app.geometry import canonicalize_plan


def _plan(scale_confidence: int = 90):
    return {
        "title": "test",
        "widthM": 20.0,
        "heightM": 25.0,
        "scaleConfidence": scale_confidence,
        "walls": [
            {"id": "w1", "start": {"x": 0, "y": 0}, "end": {"x": 100, "y": 0}, "confidence": 90},
            {"id": "w2", "start": {"x": 100, "y": 0}, "end": {"x": 100, "y": 100}, "confidence": 90},
            {"id": "w3", "start": {"x": 100, "y": 100}, "end": {"x": 0, "y": 100}, "confidence": 90},
            {"id": "w4", "start": {"x": 0, "y": 100}, "end": {"x": 0, "y": 0}, "confidence": 90},
        ],
        "openings": [
            {"id": "d1", "type": "door", "x": 50, "y": 0, "width": 4.0, "wallId": "w1", "confidence": 92}
        ],
        "rooms": [{"id": "r1"}],
    }


def test_metric_plan_becomes_blender_ready_geometry():
    result = canonicalize_plan(_plan())
    assert result.ready
    assert result.geometry["metrics"]["wallCount"] == 4
    assert result.geometry["metrics"]["openingCount"] == 1
    floor = result.geometry["floors"][0]
    assert floor["walls"][0]["lengthM"] == 20.0
    assert floor["openings"][0]["wallId"] == "w1"


def test_unverified_scale_is_blocked_before_blender():
    result = canonicalize_plan(_plan(scale_confidence=40))
    assert not result.ready
    assert any("scale confidence" in error for error in result.errors)


def test_orphan_opening_is_ignored_not_cut_into_wrong_wall():
    plan = _plan()
    plan["openings"][0]["wallId"] = "missing"
    result = canonicalize_plan(plan)
    assert result.geometry["metrics"]["openingCount"] == 0
    assert any("opening" in warning for warning in result.warnings)
