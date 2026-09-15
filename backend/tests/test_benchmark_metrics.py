from app.benchmark_metrics import dimension_accuracy, score_case


def test_dimension_accuracy_uses_five_percent_or_twelve_centimeters():
    expected = [{"value_m": 4.0}, {"value_m": 2.0}]
    assert dimension_accuracy(expected, [{"value_m": 4.12}, {"value_m": 2.10}]) == 1.0
    assert dimension_accuracy(expected, [{"value_m": 4.50}, {"value_m": 2.10}]) == 0.5


def test_score_case_rewards_matching_geometry_and_types():
    expected = {
        "walls": [
            {"start": {"x": 10, "y": 10}, "end": {"x": 90, "y": 10}},
            {"start": {"x": 10, "y": 10}, "end": {"x": 10, "y": 90}},
        ],
        "rooms": [
            {"x": 12, "y": 12, "width": 35, "height": 30, "type": "bedroom"},
        ],
        "openings": [
            {"x": 50, "y": 10, "width": 4, "type": "door"},
        ],
        "metric": {"dimensions": [{"value_m": 4.0}]},
    }
    actual = {
        "walls": [
            {"start": {"x": 10.5, "y": 10.2}, "end": {"x": 89.5, "y": 10.1}},
            {"start": {"x": 10.2, "y": 10.1}, "end": {"x": 10.1, "y": 89.7}},
        ],
        "rooms": [
            {"x": 12.5, "y": 12.2, "width": 34.5, "height": 30.4, "type": "bedroom"},
        ],
        "openings": [
            {"x": 50.4, "y": 10.3, "width": 4.2, "type": "door"},
        ],
        "metric": {"dimensions": [{"value_m": 4.08}]},
    }

    scores = score_case(expected, actual)
    assert scores == {
        "wall_f1": 1.0,
        "room_f1": 1.0,
        "opening_f1": 1.0,
        "dimension_accuracy": 1.0,
    }


def test_room_type_mismatch_is_counted_as_failure():
    expected = {
        "walls": [],
        "rooms": [{"x": 10, "y": 10, "width": 30, "height": 30, "type": "bedroom"}],
        "openings": [],
        "metric": {"dimensions": []},
    }
    actual = {
        "walls": [],
        "rooms": [{"x": 10, "y": 10, "width": 30, "height": 30, "type": "kitchen"}],
        "openings": [],
        "metric": {"dimensions": []},
    }
    assert score_case(expected, actual)["room_f1"] == 0.0
