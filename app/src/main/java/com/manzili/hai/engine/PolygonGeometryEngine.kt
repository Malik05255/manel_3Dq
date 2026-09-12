package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.PlanPoint
import com.manzili.hai.model.Room
import kotlin.math.abs

object PolygonGeometryEngine {
    data class Report(val plan: FloorPlan, val errors: List<String>, val warnings: List<String>) {
        val valid: Boolean get() = errors.isEmpty()
    }

    fun normalize(plan: FloorPlan): FloorPlan {
        val rooms = plan.rooms.map { room ->
            if (room.polygon.size >= 3) room.copy(polygon = clean(room.polygon))
            else room.copy(polygon = rectangle(room))
        }
        val footprint = when {
            plan.footprint.size >= 3 -> clean(plan.footprint)
            plan.walls.isNotEmpty() -> bounding(plan.walls.flatMap { listOf(it.start, it.end) })
            rooms.isNotEmpty() -> bounding(rooms.flatMap { it.polygon })
            else -> fullBounds()
        }
        return plan.copy(rooms = rooms, footprint = footprint)
    }

    fun inspect(plan: FloorPlan): Report {
        val hadMissing = plan.rooms.filter { it.polygon.size < 3 }.map { it.name }
        val normalized = normalize(plan)
        val errors = mutableListOf<String>()
        if (!validPolygon(normalized.footprint)) errors += "حدود المبنى ليست مضلعًا صالحًا."
        normalized.rooms.forEach { room ->
            if (!validPolygon(room.polygon)) errors += "مضلع «${room.name}» غير صالح."
            if (room.polygon.any { it.x !in 0f..100f || it.y !in 0f..100f }) errors += "مضلع «${room.name}» خارج حدود المخطط."
        }
        val warnings = hadMissing.map { "«$it» حُولت إلى مضلع رباعي من حدودها القديمة." }
        return Report(normalized, errors.distinct(), warnings.distinct())
    }

    fun polygonAreaM2(plan: FloorPlan, polygon: List<PlanPoint>): Double? {
        val width = plan.widthM ?: return null
        val height = plan.heightM ?: return null
        return abs(signedArea(polygon)) / 10000.0 * width * height
    }

    private fun rectangle(room: Room) = listOf(
        PlanPoint(room.x, room.y),
        PlanPoint((room.x + room.width).coerceIn(0f, 100f), room.y),
        PlanPoint((room.x + room.width).coerceIn(0f, 100f), (room.y + room.height).coerceIn(0f, 100f)),
        PlanPoint(room.x, (room.y + room.height).coerceIn(0f, 100f))
    )

    private fun clean(points: List<PlanPoint>): List<PlanPoint> = points
        .map { PlanPoint(it.x.coerceIn(0f, 100f), it.y.coerceIn(0f, 100f)) }
        .distinct()

    private fun bounding(points: List<PlanPoint>): List<PlanPoint> {
        if (points.isEmpty()) return fullBounds()
        val minX = points.minOf { it.x }.coerceIn(0f, 100f)
        val maxX = points.maxOf { it.x }.coerceIn(0f, 100f)
        val minY = points.minOf { it.y }.coerceIn(0f, 100f)
        val maxY = points.maxOf { it.y }.coerceIn(0f, 100f)
        if (maxX - minX < .2f || maxY - minY < .2f) return fullBounds()
        return listOf(PlanPoint(minX, minY), PlanPoint(maxX, minY), PlanPoint(maxX, maxY), PlanPoint(minX, maxY))
    }

    private fun fullBounds() = listOf(PlanPoint(0f, 0f), PlanPoint(100f, 0f), PlanPoint(100f, 100f), PlanPoint(0f, 100f))

    private fun validPolygon(points: List<PlanPoint>): Boolean = points.size >= 3 && abs(signedArea(points)) > .01

    private fun signedArea(points: List<PlanPoint>): Double {
        if (points.size < 3) return 0.0
        var sum = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            sum += a.x * b.y - b.x * a.y
        }
        return sum / 2.0
    }
}
