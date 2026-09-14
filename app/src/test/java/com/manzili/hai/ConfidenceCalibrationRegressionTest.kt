package com.manzili.hai

import com.manzili.hai.engine.MultiPageEvidenceFusionEngine
import com.manzili.hai.engine.PlanVerificationEngine
import com.manzili.hai.engine.RemoteFloorplanEvidenceClient
import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.PlanDimension
import com.manzili.hai.model.PlanPoint
import com.manzili.hai.model.Room
import com.manzili.hai.model.Wall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfidenceCalibrationRegressionTest {
    private fun room(id: String, x: Float): Room = Room(
        id = id,
        name = "غرفة",
        type = "room",
        x = x,
        y = 10f,
        width = 35f,
        height = 35f,
        areaM2 = 20.0,
        confidence = 96,
        polygon = listOf(
            PlanPoint(x, 10f),
            PlanPoint(x + 35f, 10f),
            PlanPoint(x + 35f, 45f),
            PlanPoint(x, 45f)
        )
    )

    private fun boundaryWalls(kind: String = "vision", confidence: Int = 96): List<Wall> = listOf(
        Wall("w1", PlanPoint(5f, 5f), PlanPoint(95f, 5f), kind = kind, confidence = confidence),
        Wall("w2", PlanPoint(95f, 5f), PlanPoint(95f, 95f), kind = kind, confidence = confidence),
        Wall("w3", PlanPoint(95f, 95f), PlanPoint(5f, 95f), kind = kind, confidence = confidence),
        Wall("w4", PlanPoint(5f, 95f), PlanPoint(5f, 5f), kind = kind, confidence = confidence)
    )

    @Test
    fun modelSelfConfidenceCannotProduceFakeNinetyWithoutIndependentEvidence() {
        val plan = FloorPlan(
            widthM = 20.0,
            heightM = 25.0,
            rooms = listOf(room("r1", 10f), room("r2", 55f)),
            walls = boundaryWalls(),
            scaleConfidence = 100
        )

        val report = PlanVerificationEngine.inspect(plan)

        assertTrue("self confidence must be capped", report.readingConfidence < 90)
        assertTrue("unverified scale must stay below metric-ready threshold", report.scaleConfidence < 50)
    }

    @Test
    fun visionOnlyDimensionIdsDoNotCertifyScale() {
        val plan = FloorPlan(
            widthM = 20.0,
            heightM = 25.0,
            rooms = listOf(room("r1", 10f)),
            walls = boundaryWalls(),
            dimensions = listOf(
                PlanDimension("d1", "عرض", 20.0, "horizontal", confidence = 99, sourceText = "20.0"),
                PlanDimension("d2", "طول", 25.0, "vertical", confidence = 99, sourceText = "25.0")
            )
        )

        assertTrue(PlanVerificationEngine.inspect(plan).scaleConfidence < 50)
    }

    @Test
    fun userEnteredPlotDimensionsRemainFullyTrusted() {
        val plan = FloorPlan(
            widthM = 20.0,
            heightM = 25.0,
            rooms = listOf(room("r1", 10f)),
            walls = boundaryWalls("generated", 100),
            dimensions = listOf(
                PlanDimension("plot-width", "عرض الأرض", 20.0, "horizontal", confidence = 100, sourceText = "إدخال المستخدم"),
                PlanDimension("plot-depth", "طول الأرض", 25.0, "vertical", confidence = 100, sourceText = "إدخال المستخدم")
            )
        )

        assertEquals(100, PlanVerificationEngine.inspect(plan).scaleConfidence)
    }

    @Test
    fun roomConsensusPreservesPrimaryRoomIdentity() {
        val primary = room("vision-bedroom", 10f)
        val remote = primary.copy(id = "p0-remote-bedroom", name = "غرفة نوم", confidence = 91)
        val page = RemoteFloorplanEvidenceClient.PageResult(
            pageIndex = 0,
            rooms = listOf(remote),
            walls = boundaryWalls("remote-segmentation-evidence", 90),
            openings = emptyList(),
            ocrLines = emptyList(),
            dimensions = emptyList(),
            widthM = null,
            heightM = null,
            modelUsed = "test",
            confidence = 90,
            geometryConfidence = 88,
            ocrConfidence = 0,
            scaleConfidence = 0,
            wallTopology = 100,
            dimensionEvidenceCount = 0,
            warnings = emptyList()
        )

        val result = MultiPageEvidenceFusionEngine.apply(
            FloorPlan(rooms = listOf(primary), walls = boundaryWalls()),
            listOf(page)
        )

        assertTrue(result.rooms.any { it.id == "vision-bedroom" })
    }
}
