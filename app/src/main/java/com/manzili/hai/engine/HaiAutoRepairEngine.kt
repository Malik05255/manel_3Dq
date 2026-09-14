package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.Opening
import com.manzili.hai.model.PlanDimension
import com.manzili.hai.model.PlanPoint
import com.manzili.hai.model.Room
import com.manzili.hai.model.StructuralElement
import com.manzili.hai.model.Wall
import kotlin.math.abs
import kotlin.math.hypot

/**
 * HAI repair pass used from the plan review screen.
 *
 * It never hard-codes a target percentage. A second cloud read is compared with
 * the currently displayed geometry, consistent evidence is fused, and the new
 * plan is accepted only when verification/rank improves or it adds structural
 * evidence without making verification worse.
 */
object HaiAutoRepairEngine {
    data class Result(
        val plan: FloorPlan,
        val changed: Boolean,
        val beforeConfidence: Int,
        val afterConfidence: Int,
        val summary: String
    )

    fun repair(current: FloorPlan, reread: FloorPlan): Result {
        val before = PlanVerificationEngine.inspect(current)
        val rereadWithProjectMetadata = preserveProjectMetadata(current, reread)
        val fused = fuse(current, rereadWithProjectMetadata)

        val candidates = listOf(
            before,
            PlanVerificationEngine.inspect(rereadWithProjectMetadata),
            PlanVerificationEngine.inspect(fused)
        )
        val best = candidates.maxByOrNull(::rank) ?: before
        val bestRank = rank(best)
        val beforeRank = rank(before)
        val structuralGain = structuralCount(best.plan) > structuralCount(before.plan)
        val acceptable = bestRank > beforeRank ||
            (structuralGain && !best.blocking && best.readingConfidence >= before.readingConfidence)
        val chosen = if (acceptable) best else before
        val changed = geometrySignature(chosen.plan) != geometrySignature(before.plan)
        val summary = if (changed) changeSummary(before.plan, chosen.plan) else "لم يعتمد HAI تغييرًا غير موثوق"

        return Result(
            plan = chosen.plan.copy(revision = maxOf(current.revision + if (changed) 1 else 0, chosen.plan.revision)),
            changed = changed,
            beforeConfidence = before.readingConfidence,
            afterConfidence = chosen.readingConfidence,
            summary = summary
        )
    }

    private fun preserveProjectMetadata(base: FloorPlan, geometry: FloorPlan): FloorPlan = geometry.copy(
        title = base.title,
        observations = (base.observations + geometry.observations).distinct(),
        preferences = base.preferences,
        constraints = base.constraints,
        revision = maxOf(base.revision, geometry.revision),
        northDeg = base.northDeg ?: geometry.northDeg,
        site = base.site,
        saudiRulesEnabled = base.saudiRulesEnabled,
        elements = mergeElements(base.elements, geometry.elements),
        sourceSummary = buildString {
            append(geometry.sourceSummary.ifBlank { base.sourceSummary })
            if (isNotBlank()) append(" + ")
            append("HAI targeted reread")
        }
    )

    private fun fuse(base: FloorPlan, fresh: FloorPlan): FloorPlan {
        val rooms = chooseRooms(base.rooms, fresh.rooms)
        val walls = mergeWalls(base.walls, fresh.walls)
        val openings = mergeOpenings(base.openings, fresh.openings, walls)
        val dimensions = mergeDimensions(base.dimensions, fresh.dimensions)
        val uncertainties = when {
            fresh.uncertainties.isEmpty() -> base.uncertainties
            base.uncertainties.isEmpty() -> fresh.uncertainties
            fresh.uncertainties.size <= base.uncertainties.size -> fresh.uncertainties
            else -> base.uncertainties
        }
        return base.copy(
            widthM = base.widthM ?: fresh.widthM,
            heightM = base.heightM ?: fresh.heightM,
            rooms = rooms,
            walls = walls,
            openings = openings,
            dimensions = dimensions,
            uncertainties = uncertainties,
            scaleConfidence = maxOf(base.scaleConfidence, fresh.scaleConfidence),
            elements = mergeElements(base.elements, fresh.elements),
            sourceSummary = buildString {
                append(base.sourceSummary)
                if (isNotBlank()) append(" + ")
                append("HAI reread/fusion")
            }
        )
    }

    private fun chooseRooms(base: List<Room>, fresh: List<Room>): List<Room> {
        if (fresh.isEmpty()) return base
        if (base.isEmpty()) return fresh
        val baseManual = base.filter { it.id.startsWith("manual-room-") || it.confidence >= 100 }
        val freshScore = fresh.sumOf { roomPolygonScore(it) } + fresh.size * 8
        val baseScore = base.sumOf { roomPolygonScore(it) } + base.size * 8
        val selected = if (freshScore > baseScore) fresh.toMutableList() else base.toMutableList()
        baseManual.forEach { manual ->
            if (selected.none { roomsOverlap(manual, it) }) selected += manual
        }
        return selected.distinctBy { it.id }
    }

    private fun roomPolygonScore(room: Room): Int = when {
        room.polygon.size >= 6 -> 12
        room.polygon.size >= 4 -> 9
        room.polygon.size >= 3 -> 6
        else -> 0
    }

    private fun roomsOverlap(a: Room, b: Room): Boolean {
        val ax = a.x + a.width / 2f
        val ay = a.y + a.height / 2f
        val bx = b.x + b.width / 2f
        val by = b.y + b.height / 2f
        return hypot((ax - bx).toDouble(), (ay - by).toDouble()) < 4.0
    }

    private fun mergeWalls(base: List<Wall>, fresh: List<Wall>): List<Wall> {
        val out = base.toMutableList()
        fresh.forEach { candidate ->
            val index = out.indexOfFirst { sameWall(it, candidate) }
            if (index < 0) {
                out += candidate.copy(
                    id = uniqueId("hai-wall", candidate.id, out.map { it.id }.toSet()),
                    kind = "hai-repair:${candidate.kind}"
                )
            } else {
                val old = out[index]
                if (!old.kind.contains("manual", true) && !old.kind.contains("consensus", true)) {
                    out[index] = old.copy(
                        kind = "hai-consensus:${old.kind}",
                        confidence = maxOf(old.confidence, candidate.confidence)
                    )
                }
            }
        }
        return out
    }

    private fun mergeOpenings(base: List<Opening>, fresh: List<Opening>, walls: List<Wall>): List<Opening> {
        val out = base.toMutableList()
        fresh.forEach { candidate ->
            val duplicate = out.any { sameOpening(it, candidate) }
            if (!duplicate) {
                val nearest = walls.minByOrNull { wallDistance(candidate.x, candidate.y, it) }
                out += candidate.copy(
                    id = uniqueId("hai-opening", candidate.id, out.map { it.id }.toSet()),
                    wallId = nearest?.takeIf { wallDistance(candidate.x, candidate.y, it) <= 3.0 }?.id ?: candidate.wallId
                )
            }
        }
        return out
    }

    private fun mergeDimensions(base: List<PlanDimension>, fresh: List<PlanDimension>): List<PlanDimension> {
        val out = base.toMutableList()
        fresh.forEach { candidate ->
            val duplicate = out.any {
                it.axis == candidate.axis && abs(it.valueM - candidate.valueM) <= maxOf(.03, candidate.valueM * .008)
            }
            if (!duplicate) out += candidate
        }
        return out.distinctBy { it.id }
    }

    private fun mergeElements(base: List<StructuralElement>, fresh: List<StructuralElement>): List<StructuralElement> {
        val out = base.toMutableList()
        fresh.forEach { candidate ->
            val duplicate = out.any { existing ->
                existing.type.equals(candidate.type, true) && elementCenterDistance(existing, candidate) < 2.0
            }
            if (!duplicate) out += candidate
        }
        return out.distinctBy { it.id }
    }

    private fun elementCenterDistance(a: StructuralElement, b: StructuralElement): Double {
        fun center(points: List<PlanPoint>): PlanPoint {
            if (points.isEmpty()) return PlanPoint(0f, 0f)
            return PlanPoint(points.map { it.x }.average().toFloat(), points.map { it.y }.average().toFloat())
        }
        val ac = center(a.footprint)
        val bc = center(b.footprint)
        return hypot((ac.x - bc.x).toDouble(), (ac.y - bc.y).toDouble())
    }

    private fun sameWall(a: Wall, b: Wall): Boolean {
        val direct = pointDistance(a.start, b.start) + pointDistance(a.end, b.end)
        val reversed = pointDistance(a.start, b.end) + pointDistance(a.end, b.start)
        return minOf(direct, reversed) <= 2.8
    }

    private fun sameOpening(a: Opening, b: Opening): Boolean =
        a.type.equals(b.type, true) && hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()) <= 1.8

    private fun pointDistance(a: PlanPoint, b: PlanPoint): Double =
        hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())

    private fun wallDistance(x: Float, y: Float, wall: Wall): Double {
        val ax = wall.start.x.toDouble()
        val ay = wall.start.y.toDouble()
        val bx = wall.end.x.toDouble()
        val by = wall.end.y.toDouble()
        val dx = bx - ax
        val dy = by - ay
        val denom = dx * dx + dy * dy
        if (denom <= 1e-9) return hypot(x - ax, y - ay)
        val t = (((x - ax) * dx + (y - ay) * dy) / denom).coerceIn(0.0, 1.0)
        return hypot(x - (ax + t * dx), y - (ay + t * dy))
    }

    private fun uniqueId(prefix: String, sourceId: String, used: Set<String>): String {
        val safe = sourceId.ifBlank { "candidate" }.replace(Regex("[^A-Za-z0-9_-]"), "-")
        var id = "$prefix-$safe"
        var suffix = 2
        while (id in used) id = "$prefix-$safe-${suffix++}"
        return id
    }

    private fun rank(report: PlanVerificationEngine.Report): Int {
        val plan = report.plan
        var score = report.readingConfidence * 100 + report.scaleConfidence * 12
        score += plan.rooms.size * 20 + plan.walls.size * 3 + plan.openings.size * 7 + plan.elements.size * 6
        score -= report.issues.count { it.level == "warning" } * 3
        if (report.blocking) score -= 100_000
        return score
    }

    private fun structuralCount(plan: FloorPlan): Int =
        plan.rooms.size + plan.walls.size + plan.openings.size + plan.elements.size

    private fun geometrySignature(plan: FloorPlan): String = buildString {
        plan.rooms.forEach { append("r:").append(it.id).append(':').append("%.2f".format(it.x)).append(',').append("%.2f".format(it.y)).append(';') }
        plan.walls.forEach { append("w:").append(it.id).append(':').append("%.2f".format(it.start.x)).append(',').append("%.2f".format(it.start.y)).append('-').append("%.2f".format(it.end.x)).append(',').append("%.2f".format(it.end.y)).append(';') }
        plan.openings.forEach { append("o:").append(it.type).append(':').append("%.2f".format(it.x)).append(',').append("%.2f".format(it.y)).append(';') }
        plan.elements.forEach { append("e:").append(it.type).append(':').append(it.id).append(';') }
    }

    private fun changeSummary(before: FloorPlan, after: FloorPlan): String {
        val roomDelta = after.rooms.size - before.rooms.size
        val wallDelta = after.walls.size - before.walls.size
        val openingDelta = after.openings.size - before.openings.size
        val elementDelta = after.elements.size - before.elements.size
        val parts = buildList {
            if (roomDelta > 0) add("أضاف $roomDelta غرفة/مساحة")
            if (wallDelta > 0) add("أضاف $wallDelta جدار")
            if (openingDelta > 0) add("أضاف $openingDelta فتحة")
            if (elementDelta > 0) add("أضاف $elementDelta عنصر إنشائي")
            if (isEmpty()) add("ثبّت هندسة أكثر اتساقًا مع إعادة القراءة")
        }
        return parts.joinToString(" • ")
    }
}
