package com.manzili.hai.data

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.Opening
import com.manzili.hai.model.PlanPoint
import com.manzili.hai.model.Room
import com.manzili.hai.model.Wall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderCorrectionDiffTest {
    private val base = FloorPlan(
        widthM = 12.0,
        heightM = 20.0,
        rooms = listOf(Room("r1", "مجلس", "majlis", 10f, 10f, 30f, 30f, 20.0, confidence = 70)),
        walls = listOf(Wall("w1", PlanPoint(0f, 0f), PlanPoint(100f, 0f), 15.0, confidence = 70)),
        openings = listOf(Opening("o1", "door", 50f, 0f, 4f, wallId = "w1", confidence = 70))
    )

    @Test
    fun confidenceAndRevisionOnlyAreIgnored() {
        val after = base.copy(
            revision = 9,
            rooms = base.rooms.map { it.copy(confidence = 100) },
            walls = base.walls.map { it.copy(confidence = 100) },
            openings = base.openings.map { it.copy(confidence = 100) }
        )
        val delta = ReaderCorrectionDiff.summarize(base, after)
        assertFalse(delta.hasMeaningfulChange)
        assertEquals(0, delta.totalChanges)
    }

    @Test
    fun geometryAndScaleCorrectionsAreCounted() {
        val after = base.copy(
            widthM = 12.5,
            rooms = base.rooms.map { it.copy(width = 35f) },
            walls = base.walls + Wall("w2", PlanPoint(50f, 0f), PlanPoint(50f, 100f), 15.0),
            openings = emptyList()
        )
        val delta = ReaderCorrectionDiff.summarize(base, after)
        assertTrue(delta.hasMeaningfulChange)
        assertEquals(1, delta.roomsChanged)
        assertEquals(1, delta.wallsChanged)
        assertEquals(1, delta.openingsChanged)
        assertTrue(delta.scaleChanged)
        assertEquals(4, delta.totalChanges)
    }
}
