package com.manzili.hai

import com.manzili.hai.engine.Semantic3DEngine
import com.manzili.hai.export.IfcPlanExporter
import com.manzili.hai.export.ObjPlanExporter
import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.Opening
import com.manzili.hai.model.PlanPoint
import com.manzili.hai.model.ProjectConstraint
import com.manzili.hai.model.Room
import com.manzili.hai.model.Wall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Semantic3DExportTest {
    private fun plan(): FloorPlan = FloorPlan(
        title = "اختبار HAI",
        widthM = 10.0,
        heightM = 8.0,
        scaleConfidence = 92,
        footprint = listOf(
            PlanPoint(0f, 0f), PlanPoint(100f, 0f),
            PlanPoint(100f, 100f), PlanPoint(0f, 100f)
        ),
        rooms = listOf(
            Room("r1", "مجلس", "majlis", 0f, 0f, 50f, 100f, 40.0),
            Room("r2", "صالة", "living", 50f, 0f, 50f, 100f, 40.0)
        ),
        walls = listOf(
            Wall("w1", PlanPoint(0f, 0f), PlanPoint(100f, 0f), thicknessCm = 20.0, kind = "external"),
            Wall("w2", PlanPoint(100f, 0f), PlanPoint(100f, 100f), thicknessCm = 20.0, kind = "external"),
            Wall("w3", PlanPoint(100f, 100f), PlanPoint(0f, 100f), thicknessCm = 20.0, kind = "external"),
            Wall("w4", PlanPoint(0f, 100f), PlanPoint(0f, 0f), thicknessCm = 20.0, kind = "external")
        ),
        openings = listOf(
            Opening("d1", "door", 50f, 0f, 10f, wallId = "w1", confidence = 95),
            Opening("win1", "window", 100f, 50f, 12f, wallId = "w2", confidence = 90)
        ),
        constraints = listOf(
            ProjectConstraint("opening-height-d1", "OPENING_HEIGHT_M", "confirmed", listOf("d1"), 2.20, true, 100, true),
            ProjectConstraint("opening-height-win1", "OPENING_HEIGHT_M", "confirmed", listOf("win1"), 1.40, true, 100, true),
            ProjectConstraint("opening-sill-win1", "OPENING_SILL_M", "confirmed", listOf("win1"), 0.85, true, 100, true)
        )
    )

    @Test
    fun semanticSceneUsesCanonicalWallsAndOpeningCuts() {
        val scene = Semantic3DEngine.build(plan())
        assertTrue(scene.metricReady)
        assertEquals("m", scene.units)
        assertEquals(2, scene.openings.size)
        assertTrue(scene.openings.all { it.verticalVerified })
        assertEquals(2, scene.rooms.size)
        assertTrue(scene.wallMeshCount > 4)
        assertTrue(scene.meshes.any { it.kind == "slab" })
        assertTrue(scene.meshes.all { mesh -> mesh.vertices.isNotEmpty() && mesh.faces.isNotEmpty() })
    }

    @Test
    fun objContainsActualMeshAndSemanticOpeningEvidence() {
        val obj = ObjPlanExporter.render(plan())
        assertTrue(obj.contains("# units=m"))
        assertTrue(obj.contains("\nv "))
        assertTrue(obj.contains("\nf "))
        assertTrue(obj.contains("# semantic openings"))
        assertTrue(obj.contains("id=d1"))
    }

    @Test
    fun ifc4ContainsSpatialProductsAndVoidRelations() {
        val ifc = IfcPlanExporter.render(plan())
        assertTrue(ifc.startsWith("ISO-10303-21;"))
        assertTrue(ifc.contains("FILE_SCHEMA(('IFC4'));"))
        assertTrue(ifc.contains("IFCPROJECT("))
        assertTrue(ifc.contains("IFCBUILDINGSTOREY("))
        assertTrue(ifc.contains("IFCWALL("))
        assertTrue(ifc.contains("IFCSPACE("))
        assertTrue(ifc.contains("IFCOPENINGELEMENT("))
        assertTrue(ifc.contains("IFCRELVOIDSELEMENT("))
        assertTrue(ifc.endsWith("END-ISO-10303-21;\n"))
    }

    @Test
    fun ifcIsDisabledWhenMetricScaleIsNotConfirmed() {
        val uncertain = plan().copy(widthM = null, heightM = null, scaleConfidence = 0)
        assertFalse(IfcPlanExporter.canExport(uncertain))
        val failed = runCatching { IfcPlanExporter.render(uncertain) }
        assertTrue(failed.isFailure)
    }
}
