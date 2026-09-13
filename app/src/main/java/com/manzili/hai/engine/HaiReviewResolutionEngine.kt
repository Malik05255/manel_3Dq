package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.Opening
import com.manzili.hai.model.Room
import com.manzili.hai.model.Wall

/**
 * Resolves review-stage gaps without redesigning the house.
 * Local evidence is used first for speed; visual HAI evidence can then fill only missing/weak data.
 */
object HaiReviewResolutionEngine {
    fun localResolve(input: FloorPlan): FloorPlan {
        var width = input.widthM
        var height = input.heightM

        if (width == null) {
            width = input.dimensions
                .filter { it.confidence >= 70 && it.axis.equals("horizontal", true) }
                .maxByOrNull { it.valueM }
                ?.valueM
        }
        if (height == null) {
            height = input.dimensions
                .filter { it.confidence >= 70 && it.axis.equals("vertical", true) }
                .maxByOrNull { it.valueM }
                ?.valueM
        }

        if (width == input.widthM && height == input.heightM) return input
        return input.copy(
            widthM = width,
            heightM = height,
            revision = input.revision + 1,
            observations = (input.observations + "أكمل HAI المقياس من أدلة الأبعاد الموجودة في المخطط.").distinct()
        )
    }

    fun mergeVisualEvidence(current: FloorPlan, visual: FloorPlan): FloorPlan {
        val rooms = mergeRooms(current.rooms, visual.rooms)
        val walls = mergeWalls(current.walls, visual.walls)
        val openings = mergeOpenings(current.openings, visual.openings)
        val visualAddsEvidence = visual.widthM != null || visual.heightM != null ||
            visual.rooms.isNotEmpty() || visual.walls.isNotEmpty() || visual.openings.isNotEmpty()

        return current.copy(
            title = current.title.ifBlank { visual.title },
            widthM = current.widthM ?: visual.widthM?.takeIf { it > .5 },
            heightM = current.heightM ?: visual.heightM?.takeIf { it > .5 },
            rooms = rooms,
            walls = walls,
            openings = openings,
            footprint = current.footprint.ifEmpty { visual.footprint },
            dimensions = (current.dimensions + visual.dimensions).distinctBy { it.id },
            observations = (current.observations + visual.observations + "راجع HAI المصدر الأصلي وأكمل أدلة المراجعة المتاحة دون تغيير التصميم.").distinct(),
            uncertainties = if (visualAddsEvidence) visual.uncertainties.distinct() else current.uncertainties,
            sourceSummary = listOf(current.sourceSummary, visual.sourceSummary).filter { it.isNotBlank() }.distinct().joinToString(" • "),
            scaleConfidence = maxOf(current.scaleConfidence, visual.scaleConfidence),
            revision = current.revision + 1
        )
    }

    private fun mergeRooms(current: List<Room>, visual: List<Room>): List<Room> = mergeById(
        current,
        visual,
        id = { it.id },
        locked = { it.locked },
        confidence = { it.confidence }
    )

    private fun mergeWalls(current: List<Wall>, visual: List<Wall>): List<Wall> = mergeById(
        current,
        visual,
        id = { it.id },
        locked = { it.locked },
        confidence = { it.confidence }
    )

    private fun mergeOpenings(current: List<Opening>, visual: List<Opening>): List<Opening> = mergeById(
        current,
        visual,
        id = { it.id },
        locked = { it.locked },
        confidence = { it.confidence }
    )

    private fun <T> mergeById(
        current: List<T>,
        visual: List<T>,
        id: (T) -> String,
        locked: (T) -> Boolean,
        confidence: (T) -> Int
    ): List<T> {
        if (current.isEmpty()) return visual.filter { confidence(it) >= 55 }
        if (visual.isEmpty()) return current

        val visualById = visual.associateBy(id)
        val out = current.map { existing ->
            if (locked(existing)) return@map existing
            val candidate = visualById[id(existing)] ?: return@map existing
            if (confidence(candidate) > confidence(existing)) candidate else existing
        }.toMutableList()

        val existingIds = current.map(id).toHashSet()
        visual.filter { id(it) !in existingIds && confidence(it) >= 70 }.forEach(out::add)
        return out
    }
}
