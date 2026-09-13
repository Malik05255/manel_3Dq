from app.roboflow_parser import _normalize_predictions, merge_room_evidence


def test_normalizes_wall_room_door_window_predictions():
    payload = {
        "predictions": [
            {
                "class": "WALL",
                "confidence": 0.91,
                "points": [
                    {"x": 100, "y": 100},
                    {"x": 900, "y": 100},
                    {"x": 900, "y": 130},
                    {"x": 100, "y": 130},
                ],
            },
            {
                "class": "ROOM",
                "confidence": 0.88,
                "points": [
                    {"x": 120, "y": 150},
                    {"x": 480, "y": 150},
                    {"x": 480, "y": 500},
                    {"x": 120, "y": 500},
                ],
            },
            {"class": "door", "confidence": 0.81, "x": 500, "y": 500, "width": 60, "height": 20},
            {"class": "window", "confidence": 0.79, "x": 700, "y": 120, "width": 90, "height": 18},
        ]
    }

    walls, rooms, openings = _normalize_predictions(payload, 1000, 1000, "test-model/1")

    assert len(walls) == 1
    assert len(rooms) == 1
    assert {item["type"] for item in openings} == {"door", "window"}
    assert walls[0]["kind"] == "roboflow-primary-wall"
    assert rooms[0]["confidence"] == 88


def test_primary_rooms_win_and_verifier_only_fills_missing_regions():
    primary = [
        {"id": "rf-1", "x": 10.0, "y": 10.0, "width": 30.0, "height": 30.0, "confidence": 90},
        {"id": "rf-2", "x": 50.0, "y": 10.0, "width": 30.0, "height": 30.0, "confidence": 87},
    ]
    verifier = [
        {"id": "local-same", "x": 11.0, "y": 11.0, "width": 29.0, "height": 29.0, "confidence": 99},
        {"id": "local-extra", "x": 10.0, "y": 55.0, "width": 25.0, "height": 25.0, "confidence": 96},
    ]

    merged = merge_room_evidence(primary, verifier)

    assert len(merged) == 3
    assert merged[0]["id"].startswith("rf-")
    extra = next(item for item in merged if str(item["id"]).startswith("verifier-"))
    assert extra["confidence"] <= 68
