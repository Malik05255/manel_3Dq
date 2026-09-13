package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.Opening
import com.manzili.hai.model.PlanDimension
import com.manzili.hai.model.Room
import com.manzili.hai.model.Wall
import kotlin.math.abs

/**
 * Resolves review-stage gaps without redesigning the house.
 * Local evidence is used first for speed; visual HAI evidence can then fill only missing/weak data.
 */
object HaiReviewResolutionEngine {
    fun localResolve(input: FloorPlan): FloorPlan {
        var width = input.widthM
        var height = input.heightM

        val pair = strongestDimensionPair(input.dimensions)
        if (width == null && height == null && pair != null) {
            width = pair.first
            height = pair.second
        }

        if (width == null) width = strongestOverallDimension(input.dimensions, "horizontal")
        if (height == null) height = strongestOverallDimension(input.dimensions, "vertical")

        if (pair != null) {
            if (width != null && height == null) {
                height = pairedOtherValue(pair, width!!)
            } else if (height != null && width == null) {
                width = pairedOtherValue(pair, height!!)
            }
        }

        if (width == input.widthM && height == input.heightM) return input
        return input.copy(
            widthM = width,
            heightM = height,
            scaleConfidence = maxOf(input.scaleConfidence, 72),
            revision = input.revision + 1,
            observations = (input.observations + "أكمل HAI المقياس تلقائيًا من أدلة الأبعاد المقروءة في المخطط.").distinct()
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

    private fun strongestDimensionPair(dimensions: List<PlanDimension>): Pair<Double, Double>? {
        return dimensions
            .filter { it.confidence >= 70 && it.id.startsWith("pair-") }
            .groupBy { it.id.removeSuffix("-a").removeSuffix("-b") }
            .mapNotNull { (_, group) ->
                val a = group.firstOrNull { it.id.endsWith("-a") }
                val b = group.firstOrNull { it.id.endsWith("-b") }
                if (a == null || b == null) null
                else Triple(a.valueM, b.valueM, minOf(a.confidence, b.confidence))
            }
            .maxByOrNull { it.third }
            ?.let { it.first to it.second }
    }

    private fun pairedOtherValue(pair: Pair<Double, Double>, known: Double): Double? {
        val tolerance = (known * .12).coerceAtLeast(.35)
        return when {
            abs(pair.first - known) <= tolerance -> pair.second
            abs(pair.second - known) <= tolerance -> pair.first
            else -> null
        }
    }

    private fun strongestOverallDimension(dimensions: List<PlanDimension>, axis: String): Double? {
        val axisCandidates = dimensions
            .filter { it.confidence >= 70 && it.axis.equals(axis, true) && it.valueM in .5..250.0 }
        if (axisCandidates.isEmpty()) return null

        val explicit = axisCandidates.filter { dimension ->
            val text = "${dimension.label} ${dimension.sourceText}"
            val namedOverall = if (axis == "horizontal") {
                text.contains("عرض") || text.contains("width", true)
            } else {
                text.contains("طول") || text.contains("height", true) || text.contains("length", true)
            }
            val spanPct = dimension.start?.let { start ->
                dimension.end?.let { end ->
                    if (axis == "horizontal") abs(end.x - start.x) else abs(end.y - start.y)
                }
            } ?: 0f
            namedOverall || spanPct >= 70f
        }

        val pool = if (explicit.isNotEmpty()) explicit else axisCandidates
        return pool
            .maxWithOrNull(compareBy<PlanDimension> { it.valueM }.thenBy { it.confidence })
            ?.valueM
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
        if (current.isEmpty()) return visual.filter { confidence(it) >= 70 }
        if (visual.isEmpty()) return current

        val visualById = visual.associateBy(id)
        val out = current.map { existing ->
            if (locked(existing)) return@map existing
            val candidate = visualById[id(existing)] ?: return@map existing
            if (confidence(candidate) >= 75 && confidence(candidate) > confidence(existing)) candidate else existing
        }.toMutableList()

        val existingIds = current.map(id).toHashSet()
        visual.filter { id(it) !in existingIds && confidence(it) >= 75 }.forEach(out::add)
        return out
    }
}
