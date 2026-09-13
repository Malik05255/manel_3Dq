from pathlib import Path

from app import blender_renderer


def test_pbr_asset_status_reports_missing_maps(monkeypatch, tmp_path: Path):
    monkeypatch.setenv("PBR_ASSET_DIR", str(tmp_path))
    (tmp_path / "manifest.json").write_text("{}", encoding="utf-8")
    (tmp_path / "plaster_basecolor.jpg").write_bytes(b"x")
    (tmp_path / "plaster_roughness.jpg").write_bytes(b"x")

    status = blender_renderer.pbr_asset_status()

    assert status["manifest_present"] is True
    assert status["complete"] is False
    assert status["complete_material_sets"] == 0
    assert "plaster:normal" in status["missing_maps"]


def test_pbr_asset_status_accepts_supported_extensions(monkeypatch, tmp_path: Path):
    monkeypatch.setenv("PBR_ASSET_DIR", str(tmp_path))
    (tmp_path / "manifest.json").write_text("{}", encoding="utf-8")
    extensions = ("jpg", "png", "webp")

    for material_index, key in enumerate(blender_renderer.PBR_TEXTURE_KEYS):
        for map_index, suffix in enumerate(blender_renderer.PBR_MAP_SUFFIXES):
            ext = extensions[(material_index + map_index) % len(extensions)]
            (tmp_path / f"{key}_{suffix}.{ext}").write_bytes(b"asset")

    status = blender_renderer.pbr_asset_status()

    assert status["complete"] is True
    assert status["complete_material_sets"] == len(blender_renderer.PBR_TEXTURE_KEYS)
    assert status["missing_maps"] == []


def test_blender_status_exposes_pbr_completeness(monkeypatch, tmp_path: Path):
    monkeypatch.setenv("PBR_ASSET_DIR", str(tmp_path))
    monkeypatch.setenv("BLENDER_BIN", "/usr/bin/blender")
    monkeypatch.delenv("REQUIRE_COMPLETE_PBR", raising=False)

    status = blender_renderer.blender_status()

    assert status["renderer"] == "blender-pbr-v3"
    assert status["ready"] is True
    assert status["pbr_assets_complete"] is False
    assert status["pbr_complete_material_sets"] == 0


def test_blender_status_can_require_complete_pbr(monkeypatch, tmp_path: Path):
    monkeypatch.setenv("PBR_ASSET_DIR", str(tmp_path))
    monkeypatch.setenv("BLENDER_BIN", "/usr/bin/blender")
    monkeypatch.setenv("REQUIRE_COMPLETE_PBR", "true")

    status = blender_renderer.blender_status()

    assert status["require_complete_pbr"] is True
    assert status["ready"] is False
    assert status["pbr_assets_complete"] is False
