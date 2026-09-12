package com.manzili.hai

import com.manzili.hai.engine.OpeningEvidenceFusion
import com.manzili.hai.model.Opening
import com.manzili.hai.model.PlanPoint
import com.manzili.hai.model.Wall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningEvidenceFusionTest {
    private val wall = Wall("w1", PlanPoint(0f, 20f), PlanPoint(100f, 20f), confidence = 92)

    @Test fun acceptsHighConfidenceOpeningNearWall() {
        val evidence = Opening("deep-door", "door", 42f, 20.8f, 4f, confidence = 90)
        val result = OpeningEvidenceFusion.merge(emptyList(), listOf(evidence), listOf(wall))
        assertEquals(1, result.accepted)
        assertEquals(0, result.rejected)
        assertTrue(result.openings.any { it.id == "deep-door" })
    }

    @Test fun rejectsOpeningFarFromAnyWall() {
        val evidence = Opening("deep-window", "window", 42f, 35f, 4f, confidence = 94)
        val result = OpeningEvidenceFusion.merge(emptyList(), listOf(evidence), listOf(wall))
        assertEquals(0, result.accepted)
        assertEquals(1, result.rejected)
        assertTrue(result.openings.isEmpty())
    }

    @Test fun doesNotDuplicateExistingOpening() {
        val current = Opening("existing", "door", 42f, 20f, 4f, confidence = 95)
        val evidence = Opening("deep-door", "door", 42.5f, 20.2f, 4f, confidence = 92)
        val result = OpeningEvidenceFusion.merge(listOf(current), listOf(evidence), listOf(wall))
        assertEquals(0, result.accepted)
        assertEquals(1, result.openings.size)
        assertEquals("existing", result.openings.single().id)
    }
}
