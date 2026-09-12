package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.PlanPoint
import com.manzili.hai.model.Room
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object PolygonGeometryEngine {
    data class Report(val plan: FloorPlan, val errors: List<String>, val warnings: List<String>) {
        val valid: Boolean get() = errors.isEmpty()
    }

    fun normalize(plan: FloorPlan): FloorPlan {
        val rooms = plan.rooms.map { room ->
            if (room.polygon.size >= 3) room.copy(polygon = clean(room.polygon)) else room.copy(polygon = rectangle(room))
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
            if (selfIntersects(room.polygon)) errors += "مضلع «${room.name}» يتقاطع مع نفسه."
            if (room.polygon.any { it.x !in 0f..100f || it.y !in 0f..100f }) errors += "مضلع «${room.name}» خارج حدود المخطط."
            if (normalized.footprint.size >= 3 && room.polygon.any { !pointInsideOrOn(it, normalized.footprint) }) errors += "جزء من «${room.name}» خرج عن حدود المبنى."
        }
        val warnings = hadMissing.map { "«$it» حُولت إلى مضلع رباعي من حدودها القديمة." }
        return Report(normalized, errors.distinct(), warnings.distinct())
    }

    fun editVertex(plan: FloorPlan, roomId: String, vertexIndex: Int, target: PlanPoint): Report {
        val base = normalize(plan)
        val room = base.rooms.firstOrNull { it.id == roomId }
            ?: return Report(base, listOf("الغرفة غير موجودة."), emptyList())
        if (room.locked) return Report(base, listOf("الغرفة «${room.name}» مقفلة."), emptyList())
        if (vertexIndex !in room.polygon.indices) return Report(base, listOf("رأس المضلع غير موجود."), emptyList())
        val moved = room.polygon.toMutableList().apply {
            this[vertexIndex] = PlanPoint(snap(target.x), snap(target.y))
        }
        if (!validPolygon(moved) || selfIntersects(moved)) return Report(base, listOf("هذه الحركة تجعل مضلع «${room.name}» غير صالح أو متقاطعًا."), emptyList())
        if (base.footprint.size >= 3 && moved.any { !pointInsideOrOn(it, base.footprint) }) return Report(base, listOf("هذه الحركة تخرج جزءًا من «${room.name}» خارج حدود المبنى."), emptyList())

        val minX = moved.minOf { it.x }; val maxX = moved.maxOf { it.x }
        val minY = moved.minOf { it.y }; val maxY = moved.maxOf { it.y }
        val area = polygonAreaM2(base, moved) ?: room.areaM2
        val updatedRoom = room.copy(x = minX, y = minY, width = maxX - minX, height = maxY - minY, polygon = moved, areaM2 = area)
        val next = base.copy(rooms = base.rooms.map { if (it.id == roomId) updatedRoom else it })
        return inspect(next)
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

    private fun clean(points: List<PlanPoint>): List<PlanPoint> = points.map { PlanPoint(it.x.coerceIn(0f,100f), it.y.coerceIn(0f,100f)) }.distinct()
    private fun snap(v: Float): Float = ((v.coerceIn(0f,100f) * 4f).toInt() / 4f)

    private fun bounding(points: List<PlanPoint>): List<PlanPoint> {
        if (points.isEmpty()) return fullBounds()
        val minX = points.minOf { it.x }.coerceIn(0f,100f); val maxX = points.maxOf { it.x }.coerceIn(0f,100f)
        val minY = points.minOf { it.y }.coerceIn(0f,100f); val maxY = points.maxOf { it.y }.coerceIn(0f,100f)
        if (maxX - minX < .2f || maxY - minY < .2f) return fullBounds()
        return listOf(PlanPoint(minX,minY), PlanPoint(maxX,minY), PlanPoint(maxX,maxY), PlanPoint(minX,maxY))
    }

    private fun fullBounds() = listOf(PlanPoint(0f,0f), PlanPoint(100f,0f), PlanPoint(100f,100f), PlanPoint(0f,100f))
    private fun validPolygon(points: List<PlanPoint>): Boolean = points.size >= 3 && abs(signedArea(points)) > .01

    private fun signedArea(points: List<PlanPoint>): Double {
        if (points.size < 3) return 0.0
        var sum = 0.0
        for (i in points.indices) {
            val a = points[i]; val b = points[(i + 1) % points.size]
            sum += a.x * b.y - b.x * a.y
        }
        return sum / 2.0
    }

    private fun selfIntersects(p: List<PlanPoint>): Boolean {
        if (p.size < 4) return false
        for (i in p.indices) {
            val a1 = p[i]; val a2 = p[(i + 1) % p.size]
            for (j in i + 1 until p.size) {
                if (j == i || j == (i + 1) % p.size || (j + 1) % p.size == i) continue
                val b1 = p[j]; val b2 = p[(j + 1) % p.size]
                if (segmentsCross(a1,a2,b1,b2)) return true
            }
        }
        return false
    }

    private fun segmentsCross(a: PlanPoint, b: PlanPoint, c: PlanPoint, d: PlanPoint): Boolean {
        fun orient(p: PlanPoint, q: PlanPoint, r: PlanPoint) = (q.x-p.x)*(r.y-p.y) - (q.y-p.y)*(r.x-p.x)
        val o1 = orient(a,b,c); val o2 = orient(a,b,d); val o3 = orient(c,d,a); val o4 = orient(c,d,b)
        return o1 * o2 < -0.0001f && o3 * o4 < -0.0001f
    }

    private fun pointInsideOrOn(point: PlanPoint, poly: List<PlanPoint>): Boolean {
        if (poly.size < 3) return true
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[(i+1)%poly.size]
            val cross = abs((point.y-a.y)*(b.x-a.x) - (point.x-a.x)*(b.y-a.y))
            if (cross < .08f && point.x in (min(a.x,b.x)-.05f)..(max(a.x,b.x)+.05f) && point.y in (min(a.y,b.y)-.05f)..(max(a.y,b.y)+.05f)) return true
        }
        var inside = false
        var j = poly.lastIndex
        for (i in poly.indices) {
            val pi = poly[i]; val pj = poly[j]
            if ((pi.y > point.y) != (pj.y > point.y)) {
                val x = (pj.x-pi.x) * (point.y-pi.y) / (pj.y-pi.y).coerceAtLeast(.00001f) + pi.x
                if (point.x < x) inside = !inside
            }
            j = i
        }
        return inside
    }
}
