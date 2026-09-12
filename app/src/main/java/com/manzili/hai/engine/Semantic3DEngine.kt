package com.manzili.hai.engine

import com.manzili.hai.model.FloorLevel
import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.Opening
import com.manzili.hai.model.PlanPoint
import com.manzili.hai.model.Room
import com.manzili.hai.model.StructuralElement
import com.manzili.hai.model.Wall
import kotlin.math.hypot

/**
 * Deterministic 3D projection of the canonical 2D plan.
 *
 * This engine never invents a second floorplan. Walls, openings, rooms and cores are all derived
 * from the exact Geometry V3 entities. When metric scale is unavailable the scene remains useful
 * for a relative preview, but metric/BIM exporters must reject it.
 */
object Semantic3DEngine {
    data class Vec3(val x: Double, val y: Double, val z: Double)
    data class Face(val indices: List<Int>)

    data class Mesh(
        val id: String,
        val kind: String,
        val name: String,
        val floorId: String,
        val sourceId: String,
        val vertices: List<Vec3>,
        val faces: List<Face>
    )

    data class OpeningNode(
        val id: String,
        val type: String,
        val floorId: String,
        val wallId: String?,
        val center: Vec3,
        val width: Double,
        val sillHeight: Double,
        val height: Double,
        val confidence: Int,
        val verticalVerified: Boolean = false
    )

    data class RoomNode(
        val id: String,
        val name: String,
        val type: String,
        val floorId: String,
        val polygon: List<Vec3>,
        val areaM2: Double,
        val confidence: Int
    )

    data class Scene(
        val title: String,
        val metricReady: Boolean,
        val units: String,
        val meshes: List<Mesh>,
        val openings: List<OpeningNode>,
        val rooms: List<RoomNode>,
        val floorIds: List<String>,
        val warnings: List<String>
    ) {
        val wallMeshCount: Int get() = meshes.count { it.kind == "wall" }
        val structuralMeshCount: Int get() = meshes.count { it.kind == "structural" }
    }

    private data class FloorSource(
        val id: String,
        val name: String,
        val index: Int,
        val elevationM: Double,
        val clearHeightM: Double?,
        val footprint: List<PlanPoint>,
        val rooms: List<Room>,
        val walls: List<Wall>,
        val openings: List<Opening>,
        val elements: List<StructuralElement>
    )

    private data class Vec2(val x: Double, val y: Double)
    private data class VerticalOpening(val sill: Double, val height: Double, val verified: Boolean)

    fun build(input: FloorPlan): Scene {
        val plan = GeometryV3Engine.normalize(input)
        val metricReady = (plan.widthM ?: 0.0) > 0.0 && (plan.heightM ?: 0.0) > 0.0 && plan.scaleConfidence >= 50
        val sx = if (metricReady) plan.widthM!! / 100.0 else 1.0
        val sy = if (metricReady) plan.heightM!! / 100.0 else 1.0
        val floors = floorSources(plan)
        val meshes = mutableListOf<Mesh>()
        val openingNodes = mutableListOf<OpeningNode>()
        val roomNodes = mutableListOf<RoomNode>()
        val warnings = mutableListOf<String>()

        floors.forEach { floor ->
            val elevation = if (metricReady) floor.elevationM else floor.index * 12.0
            val clearHeight = if (metricReady) (floor.clearHeightM ?: 2.80).coerceIn(2.0, 6.0) else 10.0
            val slabThickness = if (metricReady) 0.15 else 0.55
            val footprint = floor.footprint.ifEmpty { deriveFootprint(floor.rooms, floor.walls) }
            if (footprint.size >= 3) {
                meshes += prism(
                    id = "slab-${floor.id}",
                    kind = "slab",
                    name = "${floor.name} slab",
                    floorId = floor.id,
                    sourceId = floor.id,
                    polygon = footprint.map { Vec2(it.x * sx, it.y * sy) },
                    zMin = elevation - slabThickness,
                    zMax = elevation
                )
            } else {
                warnings += "${floor.name}: لا توجد حدود كافية لإنشاء بلاطة 3D."
            }

            floor.rooms.forEach { room ->
                val poly = room.polygon.ifEmpty { roomRectangle(room) }
                roomNodes += RoomNode(
                    id = room.id,
                    name = room.name,
                    type = room.type,
                    floorId = floor.id,
                    polygon = poly.map { Vec3(it.x * sx, it.y * sy, elevation) },
                    areaM2 = room.areaM2,
                    confidence = room.confidence
                )
            }

            val openingsByWall = floor.openings.filter { !it.wallId.isNullOrBlank() }.groupBy { it.wallId!! }
            floor.walls.forEach { wall ->
                val wallOpenings = openingsByWall[wall.id].orEmpty().sortedBy { projectionFraction(it, wall) }
                val built = buildWall(
                    plan = plan,
                    wall = wall,
                    openings = wallOpenings,
                    floorId = floor.id,
                    elevation = elevation,
                    clearHeight = clearHeight,
                    sx = sx,
                    sy = sy,
                    metricReady = metricReady
                )
                meshes += built.first
                openingNodes += built.second
            }

            floor.openings.filter { it.wallId.isNullOrBlank() || floor.walls.none { w -> w.id == it.wallId } }.forEach { opening ->
                val vertical = verticalOpening(plan, opening, clearHeight, metricReady)
                openingNodes += OpeningNode(
                    id = opening.id,
                    type = opening.type,
                    floorId = floor.id,
                    wallId = opening.wallId,
                    center = Vec3(opening.x * sx, opening.y * sy, elevation + vertical.sill + vertical.height / 2.0),
                    width = if (metricReady) opening.width * (sx + sy) / 2.0 else opening.width.toDouble(),
                    sillHeight = vertical.sill,
                    height = vertical.height,
                    confidence = opening.confidence,
                    verticalVerified = vertical.verified
                )
                warnings += "${floor.name}: الفتحة ${opening.id} غير مرتبطة بجدار؛ تظهر كدليل فقط ولا تقطع المجسم."
            }

            floor.elements.forEach { element ->
                if (element.footprint.size < 3) return@forEach
                val type = element.type.lowercase()
                val elementHeight = when {
                    type in setOf("column", "عمود", "shaft", "elevator", "مصعد") -> clearHeight
                    type in setOf("stair", "stairs", "درج", "سلم") -> clearHeight * 0.55
                    else -> clearHeight * 0.35
                }
                meshes += prism(
                    id = "element-${element.id}",
                    kind = "structural",
                    name = element.type,
                    floorId = floor.id,
                    sourceId = element.id,
                    polygon = element.footprint.map { Vec2(it.x * sx, it.y * sy) },
                    zMin = elevation,
                    zMax = elevation + elementHeight
                )
            }
        }

        if (!metricReady) warnings += "المقياس غير مؤكد؛ العرض الثلاثي نسبي فقط وIFC المتري معطّل حتى تأكيد الأبعاد."
        val unverifiedVertical = openingNodes.filter { !it.verticalVerified }
        if (metricReady && unverifiedVertical.isNotEmpty()) {
            warnings += "${unverifiedVertical.size} فتحة بلا ارتفاع رأسي مؤكد؛ تظهر بافتراض معاينة فقط ولا تُعد بيانات BIM موثوقة."
        }
        return Scene(
            title = plan.title,
            metricReady = metricReady,
            units = if (metricReady) "m" else "normalized",
            meshes = meshes,
            openings = openingNodes,
            rooms = roomNodes,
            floorIds = floors.map { it.id },
            warnings = warnings.distinct()
        )
    }

    private fun floorSources(plan: FloorPlan): List<FloorSource> {
        if (plan.floors.isNotEmpty()) {
            return plan.floors.sortedBy { it.index }.map { it.toSource() }
        }
        return listOf(
            FloorSource(
                id = plan.activeFloorId ?: "ground",
                name = "الدور الأرضي",
                index = 0,
                elevationM = 0.0,
                clearHeightM = null,
                footprint = plan.footprint.ifEmpty { plan.site.plotBoundary },
                rooms = plan.rooms,
                walls = plan.walls,
                openings = plan.openings,
                elements = plan.elements
            )
        )
    }

    private fun FloorLevel.toSource() = FloorSource(
        id = id,
        name = name,
        index = index,
        elevationM = elevationM,
        clearHeightM = clearHeightM,
        footprint = footprint,
        rooms = rooms,
        walls = walls,
        openings = openings,
        elements = elements
    )

    private fun buildWall(
        plan: FloorPlan,
        wall: Wall,
        openings: List<Opening>,
        floorId: String,
        elevation: Double,
        clearHeight: Double,
        sx: Double,
        sy: Double,
        metricReady: Boolean
    ): Pair<List<Mesh>, List<OpeningNode>> {
        val rawDx = (wall.end.x - wall.start.x).toDouble()
        val rawDy = (wall.end.y - wall.start.y).toDouble()
        val rawLength = hypot(rawDx, rawDy).coerceAtLeast(1e-6)
        val a = Vec2(wall.start.x * sx, wall.start.y * sy)
        val b = Vec2(wall.end.x * sx, wall.end.y * sy)
        val length = hypot(b.x - a.x, b.y - a.y).coerceAtLeast(1e-6)
        val widthScale = length / rawLength
        val thickness = if (metricReady) {
            (wall.thicknessCm?.div(100.0) ?: if (wall.kind.equals("external", true)) 0.20 else 0.15).coerceIn(0.08, 0.60)
        } else 1.0

        if (openings.isEmpty()) {
            return listOf(boxAlong("wall-${wall.id}", wall.id, wall.id, floorId, a, b, thickness, elevation, elevation + clearHeight)) to emptyList()
        }

        val meshes = mutableListOf<Mesh>()
        val nodes = mutableListOf<OpeningNode>()
        var cursor = 0.0
        openings.forEachIndexed { index, opening ->
            val centerDist = projectionFraction(opening, wall).coerceIn(0.0, 1.0) * length
            val width = (opening.width * widthScale).coerceIn(if (metricReady) 0.45 else 1.0, length)
            val startDist = (centerDist - width / 2.0).coerceIn(0.0, length)
            val endDist = (centerDist + width / 2.0).coerceIn(0.0, length)
            val effectiveStart = maxOf(cursor, startDist)
            if (effectiveStart > cursor + 1e-4) {
                meshes += wallSection(wall, floorId, a, b, length, cursor, effectiveStart, thickness, elevation, elevation + clearHeight, "full-$index")
            }
            if (endDist <= effectiveStart + 1e-4) return@forEachIndexed

            val vertical = verticalOpening(plan, opening, clearHeight, metricReady)
            val openingTop = (vertical.sill + vertical.height).coerceAtMost(clearHeight)
            if (vertical.sill > 1e-4) {
                meshes += wallSection(wall, floorId, a, b, length, effectiveStart, endDist, thickness, elevation, elevation + vertical.sill, "sill-$index")
            }
            if (openingTop < clearHeight - 1e-4) {
                meshes += wallSection(wall, floorId, a, b, length, effectiveStart, endDist, thickness, elevation + openingTop, elevation + clearHeight, "lintel-$index")
            }

            val center = pointAt(a, b, length, (effectiveStart + endDist) / 2.0)
            nodes += OpeningNode(
                id = opening.id,
                type = opening.type,
                floorId = floorId,
                wallId = wall.id,
                center = Vec3(center.x, center.y, elevation + vertical.sill + vertical.height / 2.0),
                width = (endDist - effectiveStart).coerceAtLeast(0.0),
                sillHeight = vertical.sill,
                height = vertical.height,
                confidence = opening.confidence,
                verticalVerified = vertical.verified
            )
            cursor = maxOf(cursor, endDist)
        }
        if (cursor < length - 1e-4) {
            meshes += wallSection(wall, floorId, a, b, length, cursor, length, thickness, elevation, elevation + clearHeight, "tail")
        }
        if (meshes.isEmpty()) {
            meshes += boxAlong("wall-${wall.id}", wall.id, wall.id, floorId, a, b, thickness, elevation, elevation + clearHeight)
        }
        return meshes to nodes
    }

    private fun wallSection(
        wall: Wall,
        floorId: String,
        a: Vec2,
        b: Vec2,
        length: Double,
        startDist: Double,
        endDist: Double,
        thickness: Double,
        zMin: Double,
        zMax: Double,
        suffix: String
    ): Mesh {
        val s = pointAt(a, b, length, startDist)
        val e = pointAt(a, b, length, endDist)
        return boxAlong("wall-${wall.id}-$suffix", wall.id, wall.id, floorId, s, e, thickness, zMin, zMax)
    }

    private fun boxAlong(
        id: String,
        name: String,
        sourceId: String,
        floorId: String,
        start: Vec2,
        end: Vec2,
        thickness: Double,
        zMin: Double,
        zMax: Double
    ): Mesh {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val len = hypot(dx, dy).coerceAtLeast(1e-9)
        val px = -dy / len * thickness / 2.0
        val py = dx / len * thickness / 2.0
        val base = listOf(
            Vec3(start.x + px, start.y + py, zMin),
            Vec3(end.x + px, end.y + py, zMin),
            Vec3(end.x - px, end.y - py, zMin),
            Vec3(start.x - px, start.y - py, zMin)
        )
        val vertices = base + base.map { it.copy(z = zMax) }
        val faces = listOf(
            Face(listOf(0, 3, 2, 1)),
            Face(listOf(4, 5, 6, 7)),
            Face(listOf(0, 1, 5, 4)),
            Face(listOf(1, 2, 6, 5)),
            Face(listOf(2, 3, 7, 6)),
            Face(listOf(3, 0, 4, 7))
        )
        return Mesh(id, "wall", name, floorId, sourceId, vertices, faces)
    }

    private fun prism(
        id: String,
        kind: String,
        name: String,
        floorId: String,
        sourceId: String,
        polygon: List<Vec2>,
        zMin: Double,
        zMax: Double
    ): Mesh {
        val n = polygon.size
        val vertices = polygon.map { Vec3(it.x, it.y, zMin) } + polygon.map { Vec3(it.x, it.y, zMax) }
        val faces = mutableListOf<Face>()
        faces += Face((0 until n).reversed().toList())
        faces += Face((n until 2 * n).toList())
        for (i in 0 until n) {
            val j = (i + 1) % n
            faces += Face(listOf(i, j, n + j, n + i))
        }
        return Mesh(id, kind, name, floorId, sourceId, vertices, faces)
    }

    private fun verticalOpening(plan: FloorPlan, opening: Opening, clearHeight: Double, metricReady: Boolean): VerticalOpening {
        if (!metricReady) {
            return if (isWindow(opening.type)) VerticalOpening(3.0, minOf(4.0, clearHeight - 3.0), false)
            else VerticalOpening(0.0, minOf(7.4, clearHeight), false)
        }
        val profile = OpeningVerticalProfileEngine.profile(plan, opening)
        if (profile.verified && profile.heightM != null) {
            val sill = if (isWindow(opening.type)) profile.sillHeightM ?: 0.0 else 0.0
            val height = profile.heightM
            if (sill >= 0.0 && height > 0.0 && sill + height <= clearHeight + 0.02) {
                return VerticalOpening(sill, height, true)
            }
        }
        return if (isWindow(opening.type)) {
            val sill = minOf(0.90, clearHeight * 0.40)
            VerticalOpening(sill, minOf(1.20, (clearHeight - sill).coerceAtLeast(0.40)), false)
        } else {
            VerticalOpening(0.0, minOf(2.10, clearHeight), false)
        }
    }

    private fun isWindow(type: String): Boolean = OpeningVerticalProfileEngine.isWindow(type)

    private fun projectionFraction(opening: Opening, wall: Wall): Double {
        val dx = (wall.end.x - wall.start.x).toDouble()
        val dy = (wall.end.y - wall.start.y).toDouble()
        val len2 = dx * dx + dy * dy
        if (len2 <= 1e-9) return 0.5
        return (((opening.x - wall.start.x) * dx + (opening.y - wall.start.y) * dy) / len2).coerceIn(0.0, 1.0)
    }

    private fun pointAt(a: Vec2, b: Vec2, length: Double, distance: Double): Vec2 {
        val t = (distance / length).coerceIn(0.0, 1.0)
        return Vec2(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
    }

    private fun roomRectangle(room: Room) = listOf(
        PlanPoint(room.x, room.y),
        PlanPoint(room.x + room.width, room.y),
        PlanPoint(room.x + room.width, room.y + room.height),
        PlanPoint(room.x, room.y + room.height)
    )

    private fun deriveFootprint(rooms: List<Room>, walls: List<Wall>): List<PlanPoint> {
        val points = buildList {
            walls.forEach { add(it.start); add(it.end) }
            rooms.forEach { room -> addAll(room.polygon.ifEmpty { roomRectangle(room) }) }
        }
        if (points.size < 3) return emptyList()
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        if (maxX - minX < 0.01f || maxY - minY < 0.01f) return emptyList()
        return listOf(PlanPoint(minX, minY), PlanPoint(maxX, minY), PlanPoint(maxX, maxY), PlanPoint(minX, maxY))
    }
}
