package com.manzili.hai

import com.manzili.hai.engine.FloorplanParserEngine
import com.manzili.hai.engine.PlanVerificationEngine
import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.PlanPoint
import com.manzili.hai.model.Wall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportGeometryBootstrapRegressionTest {
    @Test
    fun strongParserEvidenceSeedsEmptyImportedPlan() {
        val evidence = listOf(
            Wall("r1", PlanPoint(10f, 10f), PlanPoint(90f, 10f), kind = "remote-segmentation-evidence", confidence = 90),
            Wall("r2", PlanPoint(90f, 10f), PlanPoint(90f, 90f), kind = "remote-segmentation-evidence", confidence = 90),
            Wall("r3", PlanPoint(90f, 90f), PlanPoint(10f, 90f), kind = "remote-segmentation-evidence", confidence = 90),
            Wall("r4", PlanPoint(10f, 90f), PlanPoint(10f, 10f), kind = "remote-segmentation-evidence", confidence = 90)
        )

        val result = FloorplanParserEngine.refine(FloorPlan(), evidence).plan

        assertEquals(4, result.walls.size)
        assertTrue(result.uncertainties.any { it.contains("قابلة للمراجعة") })
        assertFalse(PlanVerificationEngine.inspect(result).blocking)
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
}
