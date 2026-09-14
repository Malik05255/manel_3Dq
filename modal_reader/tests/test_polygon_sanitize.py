import unittest

from modal_reader.polygon_sanitize import R2G_EMPTY_CLASS, sanitize_polygon, self_intersects, wall_edges_from_polygon


class PolygonSanitizeTests(unittest.TestCase):
    def test_r2g_empty_semantic_class_is_explicit(self):
        self.assertEqual(R2G_EMPTY_CLASS, 12)

    def test_bow_tie_polygon_is_untangled(self):
        raw = [
            {"x": 10.0, "y": 10.0},
            {"x": 40.0, "y": 40.0},
            {"x": 10.0, "y": 40.0},
            {"x": 40.0, "y": 10.0},
        ]
        polygon, status = sanitize_polygon(raw)
        self.assertIn(status, {"repaired", "bbox-fallback"})
        self.assertGreaterEqual(len(polygon), 3)
        self.assertFalse(self_intersects(polygon))

    def test_closing_duplicate_is_removed_without_losing_room(self):
        raw = [
            {"x": 15.0, "y": 15.0},
            {"x": 45.0, "y": 15.0},
            {"x": 45.0, "y": 35.0},
            {"x": 15.0, "y": 35.0},
            {"x": 15.0, "y": 15.0},
        ]
        polygon, status = sanitize_polygon(raw)
        self.assertEqual(status, "clean")
        self.assertEqual(len(polygon), 4)
        self.assertEqual(len(wall_edges_from_polygon(polygon)), 4)

    def test_large_broken_diagonal_edge_is_not_emitted_as_wall(self):
        polygon = [
            {"x": 10.0, "y": 10.0},
            {"x": 80.0, "y": 10.0},
            {"x": 80.0, "y": 40.0},
            {"x": 76.0, "y": 40.0},
            {"x": 12.0, "y": 82.0},
            {"x": 10.0, "y": 82.0},
        ]
        edges = wall_edges_from_polygon(polygon)
        self.assertLess(len(edges), len(polygon))
        self.assertFalse(
            any(
                abs(end["x"] - start["x"]) > 40.0 and abs(end["y"] - start["y"]) > 25.0
                for start, end in edges
            )
        )


if __name__ == "__main__":
    unittest.main()
