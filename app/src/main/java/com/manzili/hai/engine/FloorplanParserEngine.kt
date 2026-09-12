package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.Opening
import com.manzili.hai.model.PlanPoint
import com.manzili.hai.model.Wall
import kotlin.math.hypot

/**
 * Deterministic refinement after vision extraction.
 * It never invents dimensions or topology: it normalizes polygons, merges dimension evidence,
 * and only snaps an opening to a wall when the geometric evidence is already close.
 */
object FloorplanParserEngine {
    data class Result(val plan: FloorPlan, val notes: List<String>)

    fun refine(input: FloorPlan): Result {
        var plan = PolygonGeometryEngine.normalize(input)
        val notes = mutableListOf<String>()
        val uncertainties = plan.uncertainties.toMutableList()

        val evidence = PlanVerificationEngine.deriveDimensionEvidence(
            listOf(plan.sourceSummary) + plan.observations + plan.uncertainties + plan.dimensions.map { it.sourceText }
        )
        plan = plan.copy(dimensions = (plan.dimensions + evidence).distinctBy { it.id + ":" + it.valueM })

        val adjustedOpenings = plan.openings.map { opening ->
            refineOpening(opening, plan.walls, notes, uncertainties)
        }
        plan = plan.copy(openings = adjustedOpenings, uncertainties = uncertainties.distinct())

        val verified = PlanVerificationEngine.inspect(plan)
        return Result(verified.plan, notes.distinct())
    }

    private fun refineOpening(
        opening: Opening,
        walls: List<Wall>,
        notes: MutableList<String>,
        uncertainties: MutableList<String>
    ): Opening {
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
            .minByOrNull { (_, p) -> hypot((opening.x - p.x).toDouble(), (opening.y - p.y).toDouble()) }
            ?: return opening
        val distance = hypot((opening.x - nearest.second.x).toDouble(), (opening.y - nearest.second.y).toDouble())
        return if (distance <= 1.25 && nearest.first.confidence >= 65 && opening.confidence >= 60) {
            notes += "رُبطت الفتحة ${opening.id} بالجدار ${nearest.first.id} لأن التطابق الهندسي قريب وموثوق."
            opening.copy(x = nearest.second.x, y = nearest.second.y, wallId = nearest.first.id)
        } else opening
    }

    private fun project(px: Float, py: Float, wall: Wall): PlanPoint {
        val ax = wall.start.x
        val ay = wall.start.y
        val bx = wall.end.x
        val by = wall.end.y
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 <= .0001f) return wall.start
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return PlanPoint(ax + dx * t, ay + dy * t)
    }
}
