package com.manzili.hai.engine

import com.manzili.hai.model.FloorLevel
import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.Opening
import com.manzili.hai.model.PlanPoint
import com.manzili.hai.model.Wall
import kotlin.math.hypot

/**
 * Geometry-driven Saudi facade layer.
 *
 * It decorates only verified wall/opening positions from Geometry V3. It never creates a new
 * architectural opening, moves a wall, or changes the canonical 2D plan. Every generated piece
 * keeps the source opening/wall id so 3D, BIM and 4D can trace it back to the plan.
 */
object SaudiFacadeGeometryEngine {
    data class Result(
        val meshes: List<Semantic3DEngine.Mesh>,
        val warnings: List<String>,
        val style: String,
        val generatedElements: Int
    )

    fun build(plan: FloorPlan, scene: Semantic3DEngine.Scene): Result {
        if (!plan.site.countryCode.equals("SA", true)) {
            return Result(emptyList(), emptyList(), "غير سعودي", 0)
        }
        val metric = scene.metricReady
        val sx = if (metric) (plan.widthM ?: 100.0) / 100.0 else 1.0
        val sy = if (metric) (plan.heightM ?: 100.0) / 100.0 else 1.0
        val visual = SaudiVisualRenderEngine.build(plan)
        val style = visual.facade.style
        val profile = style.lowercase()
        val result = mutableListOf<Semantic3DEngine.Mesh>()
        val warnings = mutableListOf<String>()

        floorSources(plan).forEachIndexed { floorIndex, floor ->
            val wallById = floor.walls.associateBy { it.id }
            val elevation = if (metric) floor.elevationM else floorIndex * 12.0
            val clearHeight = if (metric) (floor.clearHeightM ?: 2.80).coerceIn(2.0, 6.0) else 10.0
            val footprint = floor.footprint.ifEmpty { plan.footprint }
            val center = footprintCenter(footprint, sx, sy)

            floor.openings.forEach { opening ->
                val wall = opening.wallId?.let(wallById::get) ?: return@forEach
                val node = scene.openings.firstOrNull { it.id == opening.id && it.floorId == floor.id } ?: return@forEach
                val window = OpeningVerticalProfileEngine.isWindow(opening.type)
                if (window) {
                    addWindowTreatment(
                        target = result,
                        style = profile,
                        floorId = floor.id,
                        sourceId = opening.id,
                        wall = wall,
                        centerX = node.center.x,
                        centerY = node.center.y,
                        sill = elevation + node.sillHeight,
                        height = node.height,
                        width = node.width,
                        buildingCenter = center,
                        sx = sx,
                        sy = sy,
                        metric = metric,
                        shadingPriority = visual.facade.shadingPriority
                    )
                } else {
                    addEntrancePortal(
                        target = result,
                        style = profile,
                        floorId = floor.id,
                        sourceId = opening.id,
                        wall = wall,
                        centerX = node.center.x,
                        centerY = node.center.y,
                        zMin = elevation + node.sillHeight,
                        zMax = elevation + node.sillHeight + node.height,
                        width = node.width,
                        buildingCenter = center,
                        sx = sx,
                        sy = sy,
                        metric = metric
                    )
                }
            }

            // A style band is generated only from the real outer footprint and stays above openings.
            if (footprint.size >= 3 && (profile.contains("نجدي") || profile.contains("عسيري") || profile.contains("حجازي"))) {
                val z = elevation + clearHeight * if (profile.contains("عسيري")) 0.78 else 0.86
                val bandHeight = if (metric) 0.11 else 0.38
                val bandDepth = if (metric) 0.07 else 0.24
                val points = footprint.map { it.x * sx to it.y * sy }
                points.indices.forEach { i ->
                    edgePrism(
                        id = "facade-band-${floor.id}-$i",
                        kind = if (profile.contains("عسيري")) "facade-accent" else "facade-stone",
                        name = "شريط واجهة هندسي",
                        floorId = floor.id,
                        sourceId = floor.id,
                        a = points[i],
                        b = points[(i + 1) % points.size],
                        zMin = z,
                        zMax = z + bandHeight,
                        thickness = bandDepth
                    )?.let(result::add)
                }
            }
        }

        warnings += "الواجهة ${visual.facade.style} مولدة هندسيًا حول الفتحات والجدران الموجودة فقط؛ لا تغيّر Geometry V3."
        warnings += "عناصر الظل والإطارات مقترحات تصميمية وليست اشتراطات رسمية أو تفاصيل تنفيذية."
        if (!metric) warnings += "المقياس غير مؤكد؛ نسب تفاصيل الواجهة للعرض فقط حتى تأكيد الأبعاد."
        return Result(result, warnings, visual.facade.style, result.size)
    }

    private fun addWindowTreatment(
        target: MutableList<Semantic3DEngine.Mesh>,
        style: String,
        floorId: String,
        sourceId: String,
        wall: Wall,
        centerX: Double,
        centerY: Double,
        sill: Double,
        height: Double,
        width: Double,
        buildingCenter: Pair<Double, Double>,
        sx: Double,
        sy: Double,
        metric: Boolean,
        shadingPriority: Int
    ) {
        val frame = if (metric) 0.12 else 0.42
        val depth = if (metric) (if (shadingPriority >= 90) 0.42 else 0.28) else 1.15
        val thin = if (metric) 0.07 else 0.24
        val top = sill + height
        val kind = when {
            style.contains("نجدي") -> "facade-stone"
            style.contains("حجازي") -> "facade-screen"
            style.contains("عسيري") -> "facade-accent"
            style.contains("نيوكلاسيك") -> "facade-frame"
            else -> "facade-shade"
        }

        fun panel(id: String, alongShift: Double, alongLength: Double, zMin: Double, zMax: Double, projection: Double = depth) {
            wallPanel(
                id = id, kind = kind, name = "معالجة نافذة سعودية", floorId = floorId, sourceId = sourceId,
                wall = wall, centerX = centerX, centerY = centerY, alongShift = alongShift,
                alongLength = alongLength, zMin = zMin, zMax = zMax, depth = projection,
                thickness = thin, buildingCenter = buildingCenter, sx = sx, sy = sy
            )?.let(target::add)
        }

        when {
            style.contains("حجازي") -> {
                val fin = if (metric) 0.08 else 0.28
                listOf(-0.42, 0.0, 0.42).forEachIndexed { i, fraction ->
                    panel("hijazi-fin-$floorId-$sourceId-$i", width * fraction, fin, sill - frame * 0.35, top + frame * 0.35, depth * 0.78)
                }
                panel("hijazi-hood-$floorId-$sourceId", 0.0, width + frame * 2.0, top, top + thin, depth)
            }
            style.contains("نجدي") -> {
                panel("najdi-left-$floorId-$sourceId", -(width / 2.0 + frame / 2.0), frame, sill - frame, top + frame, depth)
                panel("najdi-right-$floorId-$sourceId", width / 2.0 + frame / 2.0, frame, sill - frame, top + frame, depth)
                panel("najdi-top-$floorId-$sourceId", 0.0, width + frame * 2.0, top, top + frame, depth)
                panel("najdi-sill-$floorId-$sourceId", 0.0, width + frame * 2.0, sill - frame, sill, depth * 0.55)
            }
            style.contains("نيوكلاسيك") -> {
                panel("neo-left-$floorId-$sourceId", -(width / 2.0 + frame / 2.0), frame, sill - frame, top + frame, depth * 0.35)
                panel("neo-right-$floorId-$sourceId", width / 2.0 + frame / 2.0, frame, sill - frame, top + frame, depth * 0.35)
                panel("neo-top-$floorId-$sourceId", 0.0, width + frame * 2.0, top, top + frame, depth * 0.35)
                panel("neo-sill-$floorId-$sourceId", 0.0, width + frame * 2.0, sill - frame, sill, depth * 0.35)
            }
            style.contains("عسيري") -> {
                panel("asiri-rain-hood-$floorId-$sourceId", 0.0, width + frame * 1.4, top, top + thin, depth * 0.9)
                panel("asiri-side-$floorId-$sourceId", -(width / 2.0 + frame / 3.0), frame * 0.65, sill, top, depth * 0.42)
            }
            else -> {
                panel("saudi-hood-$floorId-$sourceId", 0.0, width + frame, top, top + thin, depth)
                if (shadingPriority >= 90) {
                    panel("saudi-fin-$floorId-$sourceId", width / 2.0 + frame / 3.0, frame * 0.65, sill, top, depth * 0.72)
                }
            }
        }
    }

    private fun addEntrancePortal(
        target: MutableList<Semantic3DEngine.Mesh>,
        style: String,
        floorId: String,
        sourceId: String,
        wall: Wall,
        centerX: Double,
        centerY: Double,
        zMin: Double,
        zMax: Double,
        width: Double,
        buildingCenter: Pair<Double, Double>,
        sx: Double,
        sy: Double,
        metric: Boolean
    ) {
        val side = if (metric) 0.18 else 0.62
        val depth = if (metric) 0.36 else 1.05
        val cap = if (metric) 0.16 else 0.55
        val kind = if (style.contains("نجدي")) "facade-stone" else "facade-frame"
        listOf(-1.0, 1.0).forEachIndexed { i, sign ->
            wallPanel(
                id = "portal-side-$floorId-$sourceId-$i", kind = kind, name = "بوابة مدخل", floorId = floorId, sourceId = sourceId,
                wall = wall, centerX = centerX, centerY = centerY,
                alongShift = sign * (width / 2.0 + side / 2.0), alongLength = side,
                zMin = zMin, zMax = zMax + cap, depth = depth, thickness = side,
                buildingCenter = buildingCenter, sx = sx, sy = sy
            )?.let(target::add)
        }
        wallPanel(
            id = "portal-cap-$floorId-$sourceId", kind = kind, name = "تاج مدخل", floorId = floorId, sourceId = sourceId,
            wall = wall, centerX = centerX, centerY = centerY,
            alongShift = 0.0, alongLength = width + side * 2.0,
            zMin = zMax, zMax = zMax + cap, depth = depth, thickness = cap,
            buildingCenter = buildingCenter, sx = sx, sy = sy
        )?.let(target::add)
    }

    private fun wallPanel(
        id: String,
        kind: String,
        name: String,
        floorId: String,
        sourceId: String,
        wall: Wall,
        centerX: Double,
        centerY: Double,
        alongShift: Double,
        alongLength: Double,
        zMin: Double,
        zMax: Double,
        depth: Double,
        thickness: Double,
        buildingCenter: Pair<Double, Double>,
        sx: Double,
        sy: Double
    ): Semantic3DEngine.Mesh? {
        if (alongLength <= 0.0 || zMax <= zMin) return null
        val ax = wall.start.x * sx; val ay = wall.start.y * sy
        val bx = wall.end.x * sx; val by = wall.end.y * sy
        val dx = bx - ax; val dy = by - ay
        val len = hypot(dx, dy)
        if (len < 1e-6) return null
        val ux = dx / len; val uy = dy / len
        var px = -uy; var py = ux
        val mx = (ax + bx) / 2.0; val my = (ay + by) / 2.0
        val toCenterX = buildingCenter.first - mx; val toCenterY = buildingCenter.second - my
        if (px * toCenterX + py * toCenterY > 0.0) { px = -px; py = -py }

        val cx = centerX + ux * alongShift + px * depth / 2.0
        val cy = centerY + uy * alongShift + py * depth / 2.0
        val halfAlong = alongLength / 2.0
        val halfDepth = depth / 2.0
        val halfThickness = thickness / 2.0
        val effectiveDepth = maxOf(halfDepth, halfThickness)
        val p = listOf(
            cx - ux * halfAlong - px * effectiveDepth to cy - uy * halfAlong - py * effectiveDepth,
            cx + ux * halfAlong - px * effectiveDepth to cy + uy * halfAlong - py * effectiveDepth,
            cx + ux * halfAlong + px * effectiveDepth to cy + uy * halfAlong + py * effectiveDepth,
            cx - ux * halfAlong + px * effectiveDepth to cy - uy * halfAlong + py * effectiveDepth
        )
        val vertices = p.map { Semantic3DEngine.Vec3(it.first, it.second, zMin) } + p.map { Semantic3DEngine.Vec3(it.first, it.second, zMax) }
        return Semantic3DEngine.Mesh(id, kind, name, floorId, sourceId, vertices, boxFaces())
    }

    private fun edgePrism(
        id: String, kind: String, name: String, floorId: String, sourceId: String,
        a: Pair<Double, Double>, b: Pair<Double, Double>, zMin: Double, zMax: Double, thickness: Double
    ): Semantic3DEngine.Mesh? {
        val dx = b.first - a.first; val dy = b.second - a.second; val len = hypot(dx, dy)
        if (len < 1e-6 || zMax <= zMin) return null
        val px = -dy / len * thickness / 2.0; val py = dx / len * thickness / 2.0
        val p = listOf(a.first + px to a.second + py, b.first + px to b.second + py, b.first - px to b.second - py, a.first - px to a.second - py)
        val v = p.map { Semantic3DEngine.Vec3(it.first, it.second, zMin) } + p.map { Semantic3DEngine.Vec3(it.first, it.second, zMax) }
        return Semantic3DEngine.Mesh(id, kind, name, floorId, sourceId, v, boxFaces())
    }

    private fun boxFaces() = listOf(
        Semantic3DEngine.Face(listOf(0, 3, 2, 1)), Semantic3DEngine.Face(listOf(4, 5, 6, 7)),
        Semantic3DEngine.Face(listOf(0, 1, 5, 4)), Semantic3DEngine.Face(listOf(1, 2, 6, 5)),
        Semantic3DEngine.Face(listOf(2, 3, 7, 6)), Semantic3DEngine.Face(listOf(3, 0, 4, 7))
    )

    private data class FloorSource(
        val id: String, val index: Int, val elevationM: Double, val clearHeightM: Double?,
        val footprint: List<PlanPoint>, val walls: List<Wall>, val openings: List<Opening>
    )

    private fun floorSources(plan: FloorPlan): List<FloorSource> = if (plan.floors.isNotEmpty()) {
        plan.floors.sortedBy { it.index }.map { FloorSource(it.id, it.index, it.elevationM, it.clearHeightM, it.footprint, it.walls, it.openings) }
    } else {
        listOf(FloorSource(plan.activeFloorId ?: "ground", 0, 0.0, null, plan.footprint, plan.walls, plan.openings))
    }

    private fun footprintCenter(points: List<PlanPoint>, sx: Double, sy: Double): Pair<Double, Double> {
        if (points.isEmpty()) return (50.0 * sx) to (50.0 * sy)
        return points.map { it.x * sx }.average() to points.map { it.y * sy }.average()
    }
}
