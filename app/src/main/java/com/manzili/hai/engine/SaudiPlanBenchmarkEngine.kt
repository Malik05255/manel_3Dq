package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.Opening
import com.manzili.hai.model.Room
import com.manzili.hai.model.Wall
import kotlin.math.abs
import kotlin.math.hypot

object SaudiPlanBenchmarkEngine {
    data class Sample(
        val id: String,
        val city: String,
        val reference: FloorPlan,
        val result: FloorPlan
    )

    data class Metrics(
        val rooms: Double,
        val walls: Double,
        val openings: Double,
        val dimensions: Double,
        val geometryValid: Boolean,
        val overall: Double
    )

    data class Evaluation(
        val id: String,
        val city: String,
        val metrics: Metrics
    )

    data class Report(val evaluations: List<Evaluation>) {
        val count: Int get() = evaluations.size
        val meanOverall: Double get() = averageOrZero(evaluations.map { it.metrics.overall })
        val geometryPassRate: Double
            get() = if (evaluations.isEmpty()) 0.0
            else evaluations.count { it.metrics.geometryValid }.toDouble() / evaluations.size
    }

    fun evaluate(sample: Sample): Evaluation {
        val roomMetric = roomScore(sample.reference, sample.result)
        val wallMetric = wallScore(sample.reference, sample.result)
        val openingMetric = openingScore(sample.reference, sample.result)
        val dimensionMetric = dimensionScore(sample.reference, sample.result)
        val geometry = GeometryV3Engine.inspect(sample.result).valid
        val overall = (
            roomMetric * 0.30 +
                wallMetric * 0.30 +
                openingMetric * 0.22 +
                dimensionMetric * 0.13 +
                (if (geometry) 1.0 else 0.0) * 0.05
            ).coerceIn(0.0, 1.0)

        return Evaluation(
            id = sample.id,
            city = sample.city,
            metrics = Metrics(
                rooms = roomMetric,
                walls = wallMetric,
                openings = openingMetric,
                dimensions = dimensionMetric,
                geometryValid = geometry,
                overall = overall
            )
        )
    }

    fun evaluateAll(samples: List<Sample>): Report = Report(samples.map(::evaluate))

    private fun roomScore(reference: FloorPlan, result: FloorPlan): Double {
        val expected = allRooms(reference).map { normalizeRoomType(it) }
        val actual = allRooms(result).map { normalizeRoomType(it) }
        val expectedCounts = expected.groupingBy { it }.eachCount()
        val actualCounts = actual.groupingBy { it }.eachCount()
        val truePositive = expectedCounts.keys.union(actualCounts.keys).sumOf { key ->
            minOf(expectedCounts[key] ?: 0, actualCounts[key] ?: 0)
        }
        return f1(
            truePositive,
            falsePositive = actual.size - truePositive,
            falseNegative = expected.size - truePositive
        )
    }

    private fun wallScore(reference: FloorPlan, result: FloorPlan): Double {
        val expected = allWalls(reference)
        val remaining = allWalls(result).toMutableList()
        var truePositive = 0

        expected.forEach { target ->
            val best = remaining.withIndex().minByOrNull { indexed ->
                wallDistance(target, indexed.value)
            }
            if (best != null && wallDistance(target, best.value) <= 7.0) {
                truePositive++
                remaining.removeAt(best.index)
            }
        }

        val actualCount = allWalls(result).size
        return f1(
            truePositive,
            falsePositive = actualCount - truePositive,
            falseNegative = expected.size - truePositive
        )
    }

    private fun openingScore(reference: FloorPlan, result: FloorPlan): Double {
        val expected = allOpenings(reference)
        val remaining = allOpenings(result).toMutableList()
        var truePositive = 0

        expected.forEach { target ->
            val best = remaining.withIndex()
                .filter { it.value.type.equals(target.type, ignoreCase = true) }
                .minByOrNull { indexed -> openingDistance(target, indexed.value) }

            if (best != null && openingDistance(target, best.value) <= 6.5) {
                truePositive++
                remaining.removeAt(best.index)
            }
        }

        val actualCount = allOpenings(result).size
        return f1(
            truePositive,
            falsePositive = actualCount - truePositive,
            falseNegative = expected.size - truePositive
        )
    }

    private fun dimensionScore(reference: FloorPlan, result: FloorPlan): Double {
        if (reference.dimensions.isEmpty()) {
            return if (result.dimensions.isEmpty()) 1.0 else 0.5
        }

        val remaining = result.dimensions.toMutableList()
        var matched = 0
        reference.dimensions.forEach { target ->
            val samePage = remaining.withIndex().filter { it.value.pageIndex == target.pageIndex }
            val searchPool = samePage.ifEmpty { remaining.withIndex().toList() }
            val best = searchPool.minByOrNull { abs(it.value.valueM - target.valueM) }
            if (best != null) {
                val tolerance = maxOf(0.12, target.valueM * 0.05)
                if (abs(best.value.valueM - target.valueM) <= tolerance) {
                    matched++
                    remaining.removeAt(best.index)
                }
            }
        }
        return matched.toDouble() / reference.dimensions.size
    }

    private fun allRooms(plan: FloorPlan): List<Room> =
        if (plan.floors.isNotEmpty()) plan.floors.flatMap { it.rooms } else plan.rooms

    private fun allWalls(plan: FloorPlan): List<Wall> =
        if (plan.floors.isNotEmpty()) plan.floors.flatMap { it.walls } else plan.walls

    private fun allOpenings(plan: FloorPlan): List<Opening> =
        if (plan.floors.isNotEmpty()) plan.floors.flatMap { it.openings } else plan.openings

    private fun normalizeRoomType(room: Room): String {
        val text = "${room.type} ${room.name}".lowercase()
        return when {
            text.contains("مجلس") || text.contains("majlis") -> "majlis"
            text.contains("صالة") || text.contains("living") || text.contains("family") -> "living"
            text.contains("مطبخ") || text.contains("kitchen") -> "kitchen"
            text.contains("نوم") || text.contains("bedroom") || text.contains("master") -> "bedroom"
            text.contains("حمام") || text.contains("bath") || text.contains("wc") || text.contains("دورة مياه") -> "bath"
            text.contains("غسيل") || text.contains("laundry") -> "laundry"
            text.contains("عاملة") || text.contains("maid") -> "maid"
            text.contains("مخزن") || text.contains("storage") -> "storage"
            else -> room.type.lowercase().ifBlank { "room" }
        }
    }

    private fun wallDistance(a: Wall, b: Wall): Double {
        fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Double =
            hypot((x1 - x2).toDouble(), (y1 - y2).toDouble())

        val direct =
            distance(a.start.x, a.start.y, b.start.x, b.start.y) +
                distance(a.end.x, a.end.y, b.end.x, b.end.y)
        val reversed =
            distance(a.start.x, a.start.y, b.end.x, b.end.y) +
                distance(a.end.x, a.end.y, b.start.x, b.start.y)
        return minOf(direct, reversed) / 2.0
    }

    private fun openingDistance(a: Opening, b: Opening): Double =
        hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()) + abs(a.width - b.width) * 0.35

    private fun f1(truePositive: Int, falsePositive: Int, falseNegative: Int): Double {
        if (truePositive == 0) {
            return if (falsePositive == 0 && falseNegative == 0) 1.0 else 0.0
        }
        val precision = truePositive.toDouble() / (truePositive + falsePositive)
        val recall = truePositive.toDouble() / (truePositive + falseNegative)
        return 2.0 * precision * recall / (precision + recall)
    }

    private fun averageOrZero(values: List<Double>): Double = if (values.isEmpty()) 0.0 else values.average()
}
