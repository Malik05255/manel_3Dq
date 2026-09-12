package com.manzili.hai.export

import com.manzili.hai.engine.MultiFloorGeometryEngine
import com.manzili.hai.model.*

/** ASCII DXF export. Geometry is emitted as vector entities, not a screenshot. */
object DxfPlanExporter {
    fun render(input: FloorPlan): String {
        val plan = MultiFloorGeometryEngine.persistActive(input)
        val metric = plan.widthM != null && plan.heightM != null && plan.scaleConfidence >= 70
        val sx = if (metric) plan.widthM!! / 100.0 else 1.0
        val sy = if (metric) plan.heightM!! / 100.0 else 1.0
        val floors = if (plan.floors.isEmpty()) listOf(
            FloorLevel("floor-0", "Ground", 0, 0.0, footprint = plan.footprint, rooms = plan.rooms, walls = plan.walls, openings = plan.openings, elements = plan.elements)
        ) else plan.floors.sortedBy { it.index }

        return buildString {
            pair(0, "SECTION"); pair(2, "HEADER")
            pair(9, "\$ACADVER"); pair(1, "AC1009")
            pair(9, "\$INSUNITS"); pair(70, if (metric) 6 else 0)
            pair(999, if (metric) "MANZILI_HAI_METERS" else "MANZILI_HAI_UNITLESS_SCALE_NOT_CONFIRMED")
            pair(0, "ENDSEC")
            pair(0, "SECTION"); pair(2, "ENTITIES")

            floors.forEach { floor ->
                val z = floor.elevationM
                val prefix = "F${floor.index}"
                floor.walls.forEach { wall ->
                    line("${prefix}_WALLS", wall.start, wall.end, z, sx, sy)
                }
                floor.rooms.forEach { room ->
                    val poly = room.polygon.ifEmpty { rect(room) }
                    polygon("${prefix}_ROOMS", poly, z, sx, sy)
                    val c = centroid(poly)
                    text("${prefix}_ROOM_NAMES", room.name, c, z, sx, sy)
                }
                floor.openings.forEach { opening ->
                    val half = opening.width.coerceAtLeast(1f) / 2f
                    val horizontal = opening.rotationDeg % 180f in -45f..45f || opening.rotationDeg % 180f > 135f
                    val a = if (horizontal) PlanPoint(opening.x - half, opening.y) else PlanPoint(opening.x, opening.y - half)
                    val b = if (horizontal) PlanPoint(opening.x + half, opening.y) else PlanPoint(opening.x, opening.y + half)
                    line("${prefix}_OPENINGS", a, b, z, sx, sy)
                }
                floor.elements.forEach { element ->
                    polygon("${prefix}_${layerFor(element.type)}", element.footprint, z, sx, sy)
                }
                floor.footprint.takeIf { it.size >= 3 }?.let { polygon("${prefix}_FOOTPRINT", it, z, sx, sy) }
            }

            plan.site.plotBoundary.takeIf { it.size >= 3 }?.let { polygon("SITE_BOUNDARY", it, 0.0, sx, sy) }
            plan.site.roads.forEach { road -> line("SITE_ROADS", road.start, road.end, 0.0, sx, sy) }
            pair(0, "ENDSEC"); pair(0, "EOF")
        }
    }

    private fun StringBuilder.line(layer: String, a: PlanPoint, b: PlanPoint, z: Double, sx: Double, sy: Double) {
        pair(0, "LINE"); pair(8, layer)
        pair(10, a.x * sx); pair(20, -a.y * sy); pair(30, z)
        pair(11, b.x * sx); pair(21, -b.y * sy); pair(31, z)
    }

    private fun StringBuilder.polygon(layer: String, points: List<PlanPoint>, z: Double, sx: Double, sy: Double) {
        if (points.size < 2) return
        points.indices.forEach { i -> line(layer, points[i], points[(i + 1) % points.size], z, sx, sy) }
    }

    private fun StringBuilder.text(layer: String, value: String, p: PlanPoint, z: Double, sx: Double, sy: Double) {
        pair(0, "TEXT"); pair(8, layer)
        pair(10, p.x * sx); pair(20, -p.y * sy); pair(30, z)
        pair(40, 0.25); pair(1, sanitize(value)); pair(7, "STANDARD")
    }

    private fun StringBuilder.pair(code: Int, value: Any) {
        append(code).append('\n').append(value).append('\n')
    }

    private fun rect(r: Room) = listOf(
        PlanPoint(r.x, r.y), PlanPoint(r.x + r.width, r.y),
        PlanPoint(r.x + r.width, r.y + r.height), PlanPoint(r.x, r.y + r.height)
    )

    private fun centroid(poly: List<PlanPoint>): PlanPoint = if (poly.isEmpty()) PlanPoint(0f, 0f)
        else PlanPoint(poly.map { it.x }.average().toFloat(), poly.map { it.y }.average().toFloat())

    private fun layerFor(type: String): String = when (type.lowercase()) {
        "column", "عمود" -> "COLUMNS"
        "stair", "stairs", "درج" -> "STAIRS"
        "elevator", "مصعد" -> "ELEVATORS"
        "shaft" -> "SHAFTS"
        else -> "ELEMENTS"
    }

    private fun sanitize(value: String): String = value.replace("\n", " ").replace("\r", " ").take(120)
}
