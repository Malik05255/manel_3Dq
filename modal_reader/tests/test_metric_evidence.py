from __future__ import annotations

import unittest

from modal_reader.metric_evidence import metric_evidence, normalize_digits


class MetricEvidenceTest(unittest.TestCase):
    def test_explicit_edge_dimensions_can_reach_verified_scale(self) -> None:
        result = metric_evidence([
            {
                "text": "12.50 m",
                "left_pct": 31.0,
                "top_pct": 5.0,
                "right_pct": 48.0,
                "bottom_pct": 8.0,
                "confidence": 96,
            },
            {
                "text": "18.00 m",
                "left_pct": 5.0,
                "top_pct": 30.0,
                "right_pct": 8.0,
                "bottom_pct": 58.0,
                "confidence": 95,
            },
        ])
        self.assertAlmostEqual(result["width_m"], 12.5)
        self.assertAlmostEqual(result["height_m"], 18.0)
        self.assertGreaterEqual(result["confidence"], 65)
        self.assertTrue(result["verified_for_3d"])

    def test_area_labels_do_not_become_linear_scale(self) -> None:
        result = metric_evidence([
            {
                "text": "120 m²",
                "left_pct": 30.0,
                "top_pct": 5.0,
                "right_pct": 48.0,
                "bottom_pct": 8.0,
                "confidence": 99,
            },
            {
                "text": "18 m",
                "left_pct": 5.0,
                "top_pct": 30.0,
                "right_pct": 8.0,
                "bottom_pct": 58.0,
                "confidence": 95,
            },
        ])
        self.assertIsNone(result["width_m"])
        self.assertEqual(result["confidence"], 0)
        self.assertFalse(result["verified_for_3d"])

    def test_unitless_dimensions_never_auto_verify_3d(self) -> None:
        result = metric_evidence([
            {
                "text": "12500",
                "left_pct": 31.0,
                "top_pct": 5.0,
                "right_pct": 48.0,
                "bottom_pct": 8.0,
                "confidence": 99,
            },
            {
                "text": "18000",
                "left_pct": 5.0,
                "top_pct": 30.0,
                "right_pct": 8.0,
                "bottom_pct": 58.0,
                "confidence": 99,
            },
        ])
        self.assertAlmostEqual(result["width_m"], 12.5)
        self.assertAlmostEqual(result["height_m"], 18.0)
        self.assertLessEqual(result["confidence"], 64)
        self.assertFalse(result["verified_for_3d"])

    def test_arabic_and_persian_digits_are_normalized(self) -> None:
        self.assertEqual(normalize_digits("١٢٫٥ م"), "12.5 م")
        result = metric_evidence([
            {
                "text": "١٢٫٥ م",
                "left_pct": 31.0,
                "top_pct": 5.0,
                "right_pct": 48.0,
                "bottom_pct": 8.0,
                "confidence": 96,
            },
            {
                "text": "۱۸٫۰ م",
                "left_pct": 5.0,
                "top_pct": 30.0,
                "right_pct": 8.0,
                "bottom_pct": 58.0,
                "confidence": 95,
            },
        ])
        self.assertAlmostEqual(result["width_m"], 12.5)
        self.assertAlmostEqual(result["height_m"], 18.0)
        self.assertTrue(result["verified_for_3d"])


if __name__ == "__main__":
    unittest.main()
