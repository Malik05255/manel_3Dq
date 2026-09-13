package com.manzili.hai

import com.manzili.hai.engine.HaiReviewResolutionEngine
import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.PlanDimension
import com.manzili.hai.model.PlanPoint
import com.manzili.hai.model.Room
import com.manzili.hai.model.Wall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HaiReviewResolutionEngineTest {
    @Test
    fun localResolverFillsMissingWidthAndHeightFromStrongDimensionEvidence() {
        val plan = FloorPlan(
            dimensions = listOf(
                PlanDimension("w", "عرض", 12.4, axis = "horizontal", confidence = 92),
                PlanDimension("h", "طول", 18.7, axis = "vertical", confidence = 91)
            )
        )

        val resolved = HaiReviewResolutionEngine.localResolve(plan)

        assertEquals(12.4, resolved.widthM ?: 0.0, 0.001)
        assertEquals(18.7, resolved.heightM ?: 0.0, 0.001)
        assertTrue(resolved.revision > plan.revision)
    }

    @Test
    fun visualEvidenceFillsMissingGeometryWithoutOverwritingLockedUserRoom() {
        val locked = Room("r1", "مجلس", "living", 5f, 5f, 20f, 20f, 24.0, confidence = 70, locked = true)
        val current = FloorPlan(rooms = listOf(locked))
        val visual = FloorPlan(
            widthM = 14.0,
            heightM = 20.0,
            rooms = listOf(
                locked.copy(name = "غرفة خاطئة", confidence = 99, locked = false),
                Room("r2", "مطبخ", "kitchen", 40f, 5f, 20f, 20f, 16.0, confidence = 90)
            ),
            walls = listOf(
                Wall("w1", PlanPoint(5f, 5f), PlanPoint(95f, 5f), confidence = 90),
                Wall("w2", PlanPoint(95f, 5f), PlanPoint(95f, 95f), confidence = 90),
                Wall("w3", PlanPoint(95f, 95f), PlanPoint(5f, 95f), confidence = 90)
            )
        )

        val resolved = HaiReviewResolutionEngine.mergeVisualEvidence(current, visual)

        assertEquals("مجلس", resolved.rooms.first { it.id == "r1" }.name)
        assertTrue(resolved.rooms.any { it.id == "r2" })
        assertEquals(3, resolved.walls.size)
        assertEquals(14.0, resolved.widthM ?: 0.0, 0.001)
        assertEquals(20.0, resolved.heightM ?: 0.0, 0.001)
    }

    @Test
    fun visualEvidenceNeverOverwritesExistingUserScale() {
        val current = FloorPlan(widthM = 11.0, heightM = 17.0)
        val visual = FloorPlan(widthM = 13.0, heightM = 19.0)

        val resolved = HaiReviewResolutionEngine.mergeVisualEvidence(current, visual)

        assertEquals(11.0, resolved.widthM ?: 0.0, 0.001)
        assertEquals(17.0, resolved.heightM ?: 0.0, 0.001)
    }
}
