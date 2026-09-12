package com.manzili.hai.engine

import com.manzili.hai.model.*
import kotlin.math.abs
import kotlin.math.hypot

/** Geometry V3: room-wall topology plus columns/stairs/elevators/shafts across floors. */
object GeometryV3Engine {
    data class Report(
        val plan: FloorPlan,
        val errors: List<String>,
        val warnings: List<String>
    ) {
        val valid: Boolean get() = errors.isEmpty()
    }

    fun normalize(input: FloorPlan): FloorPlan {
        val walls = attachTopology(input.rooms, input.walls)
        val floors = input.floors.map { floor ->
            floor.copy(walls = attachTopology(floor.rooms, floor.walls))
        }
        return input.copy(walls = walls, floors = floors)
    }

    fun inspect(input: FloorPlan): Report {
        val plan = normalize(input)
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val activeBoundary = plan.footprint.ifEmpty { plan.site.plotBoundary }
        inspectElements(plan.elements, activeBoundary, "الدور النشط", errors, warnings)
        plan.floors.forEach { floor ->
            val boundary = floor.footprint.ifEmpty { plan.site.plotBoundary }
            inspectElements(floor.elements, boundary, floor.name, errors, warnings)
        }
        inspectCoreContinuity(plan, warnings)
        plan.walls.filter { it.adjacentRoomIds.size > 2 }.forEach { errors += "الجدار ${it.id} مرتبط بأكثر من غرفتين؛ راجع الـTopology." }
        plan.walls.filter { it.kind != "external" && it.adjacentRoomIds.isEmpty() && it.confidence >= 75 }.forEach {
            warnings += "الجدار ${it.id} موثوق لكنه غير مرتبط بأي غرفة."
        }
        return Report(plan, errors.distinct(), warnings.distinct())
    }

    private fun attachTopology(rooms: List<Room>, walls: List<Wall>): List<Wall> = walls.map { wall ->
        val adjacent = rooms.filter { room -> touchesBoundary(room, wall) }.map { it.id }.distinct().take(3)
        wall.copy(adjacentRoomIds = adjacent)
    }

    private fun touchesBoundary(room: Room, wall: Wall): Boolean {
        val poly = room.polygon.ifEmpty {
            listOf(
                PlanPoint(room.x, room.y), PlanPoint(room.x + room.width, room.y),
                PlanPoint(room.x + room.width, room.y + room.height), PlanPoint(room.x, room.y + room.height)
            )
        }
        if (poly.size < 3) return false
        return poly.indices.any { i ->
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            parallelOverlap(a, b, wall.start, wall.end)
        }
    }

    private fun parallelOverlap(a: PlanPoint, b: PlanPoint, c: PlanPoint, d: PlanPoint): Boolean {
        val avx = b.x - a.x; val avy = b.y - a.y
        val bvx = d.x - c.x; val bvy = d.y - c.y
        val cross = abs(avx * bvy - avy * bvx)
        val scale = (hypot(avx.toDouble(), avy.toDouble()) * hypot(bvx.toDouble(), bvy.toDouble())).coerceAtLeast(.001)
        if (cross / scale > .08) return false
        val am = PlanPoint((a.x + b.x) / 2f, (a.y + b.y) / 2f)
        val bm = PlanPoint((c.x + d.x) / 2f, (c.y + d.y) / 2f)
        val distance = pointToSegment(am, c, d).coerceAtMost(pointToSegment(bm, a, b))
        return distance <= 1.4f && segmentOverlap(a, b, c, d) >= 2.0f
    }

    private fun segmentOverlap(a: PlanPoint, b: PlanPoint, c: PlanPoint, d: PlanPoint): Float {
        val horizontal = abs(a.x - b.x) >= abs(a.y - b.y)
        val a1 = if (horizontal) a.x else a.y; val a2 = if (horizontal) b.x else b.y
        val b1 = if (horizontal) c.x else c.y; val b2 = if (horizontal) d.x else d.y
        return (minOf(maxOf(a1, a2), maxOf(b1, b2)) - maxOf(minOf(a1, a2), minOf(b1, b2))).coerceAtLeast(0f)
    }

    private fun pointToSegment(p: PlanPoint, a: PlanPoint, b: PlanPoint): Float {
        val dx = b.x - a.x; val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 <= .0001f) return hypot((p.x - a.x).toDouble(), (p.y - a.y).toDouble()).toFloat()
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f)
        val x = a.x + t * dx; val y = a.y + t * dy
        return hypot((p.x - x).toDouble(), (p.y - y).toDouble()).toFloat()
    }

    private fun inspectElements(elements: List<StructuralElement>, boundary: List<PlanPoint>, label: String, errors: MutableList<String>, warnings: MutableList<String>) {
        elements.forEach { e ->
            if (e.footprint.size < 3) errors += "$label: العنصر ${e.id} (${e.type}) بلا مضلع صالح."
            if (e.confidence < 60) warnings += "$label: ${e.type} ${e.id} منخفض الثقة (${e.confidence}%)."
            if (boundary.size >= 3 && e.footprint.any { !inside(it, boundary) }) errors += "$label: ${e.type} ${e.id} خرج عن حدود الدور/الأرض."
            if (e.type.lowercase() in setOf("column", "عمود") && polygonArea(e.footprint) > 6f) warnings += "$label: مساحة العمود ${e.id} كبيرة نسبيًا؛ راجع الاستخراج."
        }
    }

    private fun inspectCoreContinuity(plan: FloorPlan, warnings: MutableList<String>) {
        if (plan.floors.size < 2) return
        val coreTypes = setOf("stair", "stairs", "elevator", "shaft", "درج", "مصعد")
        val floors = plan.floors.sortedBy { it.index }
        floors.zipWithNext().forEach { (a, b) ->
            a.elements.filter { it.type.lowercase() in coreTypes }.forEach { ea ->
                val same = b.elements.filter { it.type.equals(ea.type, true) }
                if (same.isEmpty()) {
                    warnings += "${ea.type} موجود في ${a.name} ولا يوجد عنصر مطابق في ${b.name}."
                } else {
                    val ca = centroid(ea.footprint)
                    val nearest = same.minByOrNull { distance(ca, centroid(it.footprint)) }
                    if (nearest != null && distance(ca, centroid(nearest.footprint)) > 5f) warnings += "محور ${ea.type} بين ${a.name} و${b.name} مزاح أكثر من 5% من المخطط."
                }
            }
        }
    }

    private fun centroid(poly: List<PlanPoint>): PlanPoint = if (poly.isEmpty()) PlanPoint(0f, 0f)
        else PlanPoint(poly.map { it.x }.average().toFloat(), poly.map { it.y }.average().toFloat())

    private fun distance(a: PlanPoint, b: PlanPoint): Float = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()

    private fun polygonArea(poly: List<PlanPoint>): Float {
        if (poly.size < 3) return 0f
        var s = 0f
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[(i + 1) % poly.size]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2f
    }

    private fun inside(p: PlanPoint, poly: List<PlanPoint>): Boolean {
        var inside = false
        var j = poly.lastIndex
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[j]
            val crosses = ((a.y > p.y) != (b.y > p.y)) && (p.x < (b.x - a.x) * (p.y - a.y) / ((b.y - a.y).takeIf { abs(it) > .0001f } ?: .0001f) + a.x)
            if (crosses) inside = !inside
            j = i
        }
        return inside
    }
}
