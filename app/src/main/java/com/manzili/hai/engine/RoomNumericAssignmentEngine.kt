package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.Room

/** Assigns spatial area labels such as 19.88 m² to the room that contains the label. */
object RoomNumericAssignmentEngine {
    fun apply(input: FloorPlan): FloorPlan {
        if (input.rooms.isEmpty()) return input
        val areas = PlanNumberEvidenceEngine.numericLabels(input.dimensions)
            .filter { it.axis == "label-area" && it.valueM in 0.2..2_000.0 && it.start != null }
        if (areas.isEmpty()) return input

        var assigned = 0
        val rooms = input.rooms.map { room ->
            val matches = areas.filter { evidence ->
                val p = evidence.start ?: return@filter false
                contains(room, p.x, p.y)
            }
            val best = matches.maxByOrNull { it.confidence } ?: return@map room
            val currentLooksMissing = room.areaM2 <= 0.0
            val closeToCurrent = room.areaM2 > 0.0 && kotlin.math.abs(room.areaM2 - best.valueM) <= maxOf(1.0, room.areaM2 * .12)
            if (!currentLooksMissing && !closeToCurrent) return@map room
            assigned++
            room.copy(
                areaM2 = best.valueM,
                confidence = maxOf(room.confidence, (best.confidence - 2).coerceIn(0, 100))
            )
        }
        if (assigned == 0) return input
        return input.copy(
            rooms = rooms,
            observations = (input.observations + "رُبطت $assigned مساحة مكتوبة بالغرفة التي تقع بداخلها بدل تخمين المساحة من الرسم.").distinct()
        )
    }

    private fun contains(room: Room, x: Float, y: Float): Boolean =
        x >= room.x - .8f && x <= room.x + room.width + .8f &&
            y >= room.y - .8f && y <= room.y + room.height + .8f
}
