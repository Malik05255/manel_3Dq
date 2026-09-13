package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.Opening
import com.manzili.hai.model.PlanPoint
import com.manzili.hai.model.Wall
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Deterministic parser refinement after vision/OCR/raster extraction. */
object FloorplanParserEngine {
    data class Result(val plan: FloorPlan, val notes: List<String>)

    fun refine(input: FloorPlan, rasterWalls: List<Wall> = emptyList()): Result {
        var plan = PolygonGeometryEngine.normalize(input)
        val notes = mutableListOf<String>()
        val uncertainties = plan.uncertainties.toMutableList()

        val evidence = PlanVerificationEngine.deriveDimensionEvidence(
            listOf(plan.sourceSummary) + plan.observations + plan.uncertainties + plan.dimensions.map { it.sourceText }
        )
        plan = plan.copy(dimensions = (plan.dimensions + evidence).distinctBy { "${it.pageIndex}:${it.id}:${it.valueM}" })

        if (rasterWalls.isNotEmpty()) {
            val fused = fuseWalls(plan, rasterWalls, notes, uncertainties)
            plan = plan.copy(walls = fused)
        }

        val adjustedOpenings = plan.openings.map { opening -> refineOpening(opening, plan.walls, notes, uncertainties) }
        plan = plan.copy(openings = adjustedOpenings, uncertainties = uncertainties.distinct())

        val roomRecovery = RoomTopologyEngine.recover(plan)
        if (roomRecovery.inferredRooms > 0) {
            plan = roomRecovery.plan
            notes += "استعيدت ${roomRecovery.inferredRooms} مساحة مغلقة من طوبولوجيا الجدران."
        }

        val verified = PlanVerificationEngine.inspect(plan)
        return Result(verified.plan, notes.distinct())
    }

    private fun fuseWalls(plan: FloorPlan, rasterWalls: List<Wall>, notes: MutableList<String>, uncertainties: MutableList<String>): List<Wall> {
        val current = plan.walls.toMutableList()

        // A newly imported plan can legitimately start with no structured rooms/walls yet.
        // Previously every parser wall was rejected in that state because boundarySupport() is
        // necessarily zero when plan.rooms is empty. That made successful Deep Parser / Raster
        // channels collapse back to an empty plan and triggered the "no reviewable geometry" gate.
        // Bootstrap only from multiple strong, non-degenerate lines and keep the result explicitly
        // marked as evidence that still needs user review.
        if (current.isEmpty() && plan.rooms.isEmpty()) {
            val seeds = rasterWalls
                .filter { it.confidence >= 68 && wallLength(it) >= 2.5f }
                .distinctBy { signature(it) }
                .sortedWith(compareByDescending<Wall> { it.confidence }.thenByDescending { wallLength(it) })
                .take(220)
            if (seeds.size >= 3) {
                current += seeds
                notes += "بدأت هندسة المخطط من ${seeds.size} خطًا عالي الثقة من Deep Parser/Raster لأن الاستيراد لم يحتوِ هندسة سابقة."
                uncertainties += "الهندسة الأولية بُنيت من أدلة parser قوية وتبقى قابلة للمراجعة قبل الاعتماد النهائي."
                return current.distinctBy { signature(it) }
            }
        }

        rasterWalls.forEach { raster ->
            val matchIndex = current.indexOfFirst { similarLine(it, raster) }
            if (matchIndex >= 0) {
                val old = current[matchIndex]
                current[matchIndex] = old.copy(confidence = max(old.confidence, min(96, raster.confidence + 16)))
            } else {
                val support = boundarySupport(plan, raster)
                if (support >= 2 && raster.confidence >= 60) {
                    current += raster.copy(id = "rv2-${current.size}", kind = "raster-confirmed", confidence = min(78, raster.confidence + 7))
                    notes += "أضيف جدار مرشح من البكسلات لأنه يتطابق مع حدّي غرفتين على الأقل."
                } else if (raster.confidence >= 68) {
                    uncertainties += "وجد Raster parser خطًا جداريًا غير مطابق كفاية للهندسة الحالية؛ بقي كدليل يحتاج مراجعة."
                }
            }
        }
        return current.distinctBy { signature(it) }
    }

    private fun boundarySupport(plan: FloorPlan, wall: Wall): Int = plan.rooms.count { room ->
        val poly = room.polygon
        if (poly.size < 2) false else poly.indices.any { i ->
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            segmentDistance(a, b, wall.start, wall.end) <= 1.6f
        }
    }

    private fun segmentDistance(a1: PlanPoint, a2: PlanPoint, b1: PlanPoint, b2: PlanPoint): Float {
        val am = PlanPoint((a1.x + a2.x) / 2f, (a1.y + a2.y) / 2f)
        val bm = PlanPoint((b1.x + b2.x) / 2f, (b1.y + b2.y) / 2f)
        return hypot((am.x - bm.x).toDouble(), (am.y - bm.y).toDouble()).toFloat()
    }

    private fun wallLength(wall: Wall): Float =
        hypot((wall.end.x - wall.start.x).toDouble(), (wall.end.y - wall.start.y).toDouble()).toFloat()

    private fun similarLine(a: Wall, b: Wall): Boolean {
        val ah = abs(a.start.y - a.end.y) < 1.4f
        val bh = abs(b.start.y - b.end.y) < 1.4f
        if (ah != bh) return false
        return if (ah) abs(a.start.y - b.start.y) <= 1.8f && overlap(a.start.x, a.end.x, b.start.x, b.end.x) >= 6f
        else abs(a.start.x - b.start.x) <= 1.8f && overlap(a.start.y, a.end.y, b.start.y, b.end.y) >= 6f
    }

    private fun overlap(a1: Float, a2: Float, b1: Float, b2: Float): Float =
        (min(max(a1, a2), max(b1, b2)) - max(min(a1, a2), min(b1, b2))).coerceAtLeast(0f)

    private fun signature(w: Wall): String = "${(w.start.x * 2).toInt()}:${(w.start.y * 2).toInt()}:${(w.end.x * 2).toInt()}:${(w.end.y * 2).toInt()}"

    private fun refineOpening(opening: Opening, walls: List<Wall>, notes: MutableList<String>, uncertainties: MutableList<String>): Opening {
        if (walls.isEmpty()) return opening
        val declared = opening.wallId?.let { id -> walls.firstOrNull { it.id == id } }
        if (declared != null) {
            val projected = project(opening.x, opening.y, declared)
            val distance = hypot((opening.x - projected.x).toDouble(), (opening.y - projected.y).toDouble())
            return if (distance <= 1.8) {
                if (distance > .15) notes += "ثُبتت الفتحة ${opening.id} على الجدار ${declared.id} هندسيًا."
                opening.copy(x = projected.x, y = projected.y)
            } else {
                uncertainties += "الفتحة ${opening.id} منسوبة للجدار ${declared.id} لكن موضعها بعيد عنه؛ تحتاج مراجعة."
                opening
            }
        }
        val nearest = walls.map { it to project(opening.x, opening.y, it) }
            .minByOrNull { (_, p) -> hypot((opening.x - p.x).toDouble(), (opening.y - p.y).toDouble()) } ?: return opening
        val distance = hypot((opening.x - nearest.second.x).toDouble(), (opening.y - nearest.second.y).toDouble())
        return if (distance <= 1.25 && nearest.first.confidence >= 65 && opening.confidence >= 60) {
            notes += "رُبطت الفتحة ${opening.id} بالجدار ${nearest.first.id} لأن التطابق الهندسي قريب وموثوق."
            opening.copy(x = nearest.second.x, y = nearest.second.y, wallId = nearest.first.id)
        } else opening
    }

    private fun project(px: Float, py: Float, wall: Wall): PlanPoint {
        val ax = wall.start.x; val ay = wall.start.y; val bx = wall.end.x; val by = wall.end.y
        val dx = bx - ax; val dy = by - ay; val len2 = dx * dx + dy * dy
        if (len2 <= .0001f) return wall.start
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return PlanPoint(ax + dx * t, ay + dy * t)
    }
}
