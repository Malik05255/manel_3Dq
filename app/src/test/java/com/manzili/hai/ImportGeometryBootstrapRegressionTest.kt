package com.manzili.hai

import com.manzili.hai.engine.FloorplanParserEngine
import com.manzili.hai.engine.PlanNumberEvidenceEngine
import com.manzili.hai.engine.PlanTextOcrEngine
import com.manzili.hai.engine.PlanVerificationEngine
import com.manzili.hai.engine.RoomTopologyEngine
import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.PlanPoint
import com.manzili.hai.model.Wall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportGeometryBootstrapRegressionTest {
    @Test
    fun strongParserEvidenceSeedsEmptyImportedPlanButStillRequiresReview() {
        val evidence = listOf(
            Wall("r1", PlanPoint(10f, 10f), PlanPoint(90f, 10f), kind = "remote-segmentation-evidence", confidence = 90),
            Wall("r2", PlanPoint(90f, 10f), PlanPoint(90f, 90f), kind = "remote-segmentation-evidence", confidence = 90),
            Wall("r3", PlanPoint(90f, 90f), PlanPoint(10f, 90f), kind = "remote-segmentation-evidence", confidence = 90),
            Wall("r4", PlanPoint(10f, 90f), PlanPoint(10f, 10f), kind = "remote-segmentation-evidence", confidence = 90)
        )

        val result = FloorplanParserEngine.refine(FloorPlan(), evidence).plan
        val report = PlanVerificationEngine.inspect(result)

        assertEquals(4, result.walls.size)
        assertTrue(result.uncertainties.any { it.contains("قابلة للمراجعة") })
        // Four strong boundary walls are enough to review, but not enough to approve.
        // Rooms/scale/openings still need to be recovered or confirmed before 3D.
        assertTrue(report.blocking)
        assertTrue(report.issues.any { it.title.contains("القراءة غير كافية") })
    }

    @Test
    fun emptyAnalysisCanBeReviewedButCannotBeApprovedFor3d() {
        val report = PlanVerificationEngine.inspect(FloorPlan())

        assertTrue(report.blocking)
        assertTrue(report.issues.any { it.title.contains("الهندسة غير مكتملة") })
    }

    @Test
    fun weakSingleLineDoesNotBypassGeometryGate() {
        val evidence = listOf(
            Wall("weak", PlanPoint(10f, 10f), PlanPoint(90f, 10f), kind = "raster-evidence", confidence = 69)
        )

        val result = FloorplanParserEngine.refine(FloorPlan(), evidence).plan

        assertTrue(result.walls.isEmpty())
        assertTrue(PlanVerificationEngine.inspect(result).blocking)
    }

    @Test
    fun topologyClosesDoorSizedGapsAndCountsFourSpaces() {
        val walls = listOf(
            Wall("top", PlanPoint(10f, 10f), PlanPoint(90f, 10f), confidence = 90),
            Wall("right", PlanPoint(90f, 10f), PlanPoint(90f, 90f), confidence = 90),
            Wall("bottom", PlanPoint(90f, 90f), PlanPoint(10f, 90f), confidence = 90),
            Wall("left", PlanPoint(10f, 90f), PlanPoint(10f, 10f), confidence = 90),
            Wall("v1", PlanPoint(50f, 10f), PlanPoint(50f, 47f), confidence = 88),
            Wall("v2", PlanPoint(50f, 53f), PlanPoint(50f, 90f), confidence = 88),
            Wall("h1", PlanPoint(10f, 50f), PlanPoint(47f, 50f), confidence = 88),
            Wall("h2", PlanPoint(53f, 50f), PlanPoint(90f, 50f), confidence = 88)
        )

        val result = RoomTopologyEngine.recover(FloorPlan(walls = walls))

        assertEquals(4, result.plan.rooms.size)
        assertEquals(4, result.inferredRooms)
        assertTrue(result.plan.rooms.all { it.confidence >= 68 })
    }

    @Test
    fun allVisibleNumbersArePreservedWithoutTreatingAreaAsLength() {
        val lines = listOf(
            PlanTextOcrEngine.SpatialLine("غرفة نوم ١٩٫٨٨ م²", 0, 30f, 25f, 55f, 29f, 84),
            PlanTextOcrEngine.SpatialLine("4.20", 0, 35f, 4f, 42f, 6f, 80)
        )

        val labels = PlanNumberEvidenceEngine.extract(lines)

        assertTrue(labels.any { kotlin.math.abs(it.valueM - 19.88) < .001 && it.axis == "label-area" })
        assertTrue(labels.any { kotlin.math.abs(it.valueM - 4.20) < .001 && it.axis == "label-dimension" })
    }
}