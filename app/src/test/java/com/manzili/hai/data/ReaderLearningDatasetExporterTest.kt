package com.manzili.hai.data

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.Opening
import com.manzili.hai.model.PlanDimension
import com.manzili.hai.model.PlanPoint
import com.manzili.hai.model.Room
import com.manzili.hai.model.Wall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderLearningDatasetExporterTest {
    @Test
    fun consentMustBeExplicitAndComplete() {
        val invalid = ReaderLearningConsent(
            ownsOrLicensed = false,
            deidentified = false,
            city = "",
            region = "",
            projectType = "",
            floors = 0
        )
        assertFalse(invalid.valid)
        assertTrue(invalid.validationErrors().size >= 6)

        val valid = ReaderLearningConsent(
            ownsOrLicensed = true,
            deidentified = true,
            city = "الرياض",
            region = "central",
            projectType = "villa_two",
            floors = 2
        )
        assertTrue(valid.valid)
        assertTrue(valid.validationErrors().isEmpty())
    }

    @Test
    fun approvedPlanBecomesBenchmarkReferenceShape() {
        val plan = FloorPlan(
            widthM = 12.0,
            heightM = 20.0,
            rooms = listOf(Room("r1", "غرفة", "bedroom", 10f, 10f, 30f, 25f, 18.0)),
            walls = listOf(Wall("w1", PlanPoint(0f, 0f), PlanPoint(100f, 0f), 15.0)),
            openings = listOf(Opening("o1", "door", 50f, 0f, 4f, wallId = "w1")),
            dimensions = listOf(PlanDimension("d1", "4.00", 4.0))
        )

        val reference = ReaderLearningDatasetExporter.benchmarkReference(plan)
        assertEquals(1, reference.getJSONArray("walls").length())
        assertEquals(1, reference.getJSONArray("rooms").length())
        assertEquals("bedroom", reference.getJSONArray("rooms").getJSONObject(0).getString("type"))
        assertEquals(1, reference.getJSONArray("openings").length())
        assertEquals(
            4.0,
            reference.getJSONObject("metric").getJSONArray("dimensions").getJSONObject(0).getDouble("value_m"),
            0.0001
        )
    }

    @Test
    fun trainingReferencePreservesGeometryNeededForMasks() {
        val plan = FloorPlan(
            widthM = 12.0,
            heightM = 20.0,
            rooms = listOf(
                Room(
                    id = "r1",
                    name = "غرفة",
                    type = "bedroom",
                    x = 10f,
                    y = 10f,
                    width = 30f,
                    height = 25f,
                    areaM2 = 18.0,
                    polygon = listOf(PlanPoint(10f, 10f), PlanPoint(40f, 10f), PlanPoint(40f, 35f))
                )
            ),
            walls = listOf(Wall("w1", PlanPoint(5f, 20f), PlanPoint(95f, 20f), 17.5, "external")),
            openings = listOf(Opening("o1", "door", 50f, 20f, 8f, 15f, "w1"))
        )

        val reference = ReaderLearningDatasetExporter.trainingReference(plan)
        assertEquals("percent-0-100", reference.getString("coordinate_space"))
        assertEquals(17.5, reference.getJSONArray("walls").getJSONObject(0).getDouble("thickness_cm"), 0.0001)
        assertEquals(15.0, reference.getJSONArray("openings").getJSONObject(0).getDouble("rotation_deg"), 0.0001)
        assertEquals("w1", reference.getJSONArray("openings").getJSONObject(0).getString("wall_id"))
        assertEquals(3, reference.getJSONArray("rooms").getJSONObject(0).getJSONArray("polygon").length())
    }
}
