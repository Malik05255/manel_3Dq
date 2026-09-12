package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.Opening
import com.manzili.hai.model.Room
import com.manzili.hai.model.Wall
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Converts an architectural objection into nearby deterministic alternatives.
 * It never invents geometry: every returned option is produced and validated by GeometrySolver.
 */
object ArchitecturalRepairEngine {
    data class RepairOption(
        val candidate: GeometrySolver.Candidate,
        val movement: String,
        val resolvedObjections: Int,
        val remainingObjections: Int,
        val improvement: Int
    )

    fun alternatives(base: FloorPlan, attempted: FloorPlan, limit: Int = 2): List<RepairOption> {
        val attemptedReview = ArchitecturalEngine.architecturalReview(base, attempted)
        if (!attemptedReview.hasMaterialObjection) return emptyList()

        val pool = mutableListOf<GeometrySolver.Candidate>()
        changedOpenings(base, attempted).forEach { id ->
            pool += GeometrySolver.actionCandidates(base, "opening", id, "MOVE")
        }
        changedWalls(base, attempted).forEach { id ->
            pool += GeometrySolver.actionCandidates(base, "wall", id, "MOVE")
        }
        changedRooms(base, attempted).forEach { id ->
            val old = base.rooms.firstOrNull { it.id == id } ?: return@forEach
            val fresh = attempted.rooms.firstOrNull { it.id == id } ?: return@forEach
            val action = when {
                fresh.areaM2 > old.areaM2 + .10 -> "EXPAND"
                fresh.areaM2 + .10 < old.areaM2 -> "SHRINK"
                else -> "MOVE"
            }
            pool += GeometrySolver.actionCandidates(base, "room", id, action)
        }

        val attemptedScore = ArchitecturalEngine.score(attempted).overall
        return pool.distinctBy { signature(it.plan) }.map { candidate ->
            val review = ArchitecturalEngine.architecturalReview(base, candidate.plan)
            val remaining = review.objections.size
            val resolved = (attemptedReview.objections.size - remaining).coerceAtLeast(0)
            val improvement = ArchitecturalEngine.score(candidate.plan).overall - attemptedScore
            val movement = movementSummary(base, candidate.plan)
            val clean = remaining == 0
            val reason = buildString {
                if (clean) append("يعالج الاعتراض الحالي بدون اعتراض معماري جديد")
                else append("يخفف الاعتراض الحالي؛ بقيت $remaining ملاحظة جوهرية")
                if (movement.isNotBlank()) append(" • $movement")
                review.notes.firstOrNull()?.let { append(" • $it") }
            }
            RepairOption(
                candidate = candidate.copy(
                    title = if (clean) "بديل HAI الآمن" else "بديل HAI محسّن",
                    reason = reason
                ),
                movement = movement,
                resolvedObjections = resolved,
                remainingObjections = remaining,
                improvement = improvement
            )
        }.sortedWith(
            compareBy<RepairOption> { it.remainingObjections }
                .thenByDescending { it.resolvedObjections }
                .thenByDescending { it.improvement }
                .thenByDescending { it.candidate.score }
        ).take(limit)
    }

    private fun changedOpenings(a: FloorPlan, b: FloorPlan): List<String> {
        val byId = b.openings.associateBy { it.id }
        return a.openings.filter { old ->
            val n = byId[old.id] ?: return@filter false
            hypot((old.x - n.x).toDouble(), (old.y - n.y).toDouble()) > .12 || old.wallId != n.wallId
        }.map { it.id }
    }

    private fun changedWalls(a: FloorPlan, b: FloorPlan): List<String> {
        val byId = b.walls.associateBy { it.id }
        return a.walls.filter { old ->
            val n = byId[old.id] ?: return@filter false
            hypot((old.start.x - n.start.x).toDouble(), (old.start.y - n.start.y).toDouble()) > .12 ||
                hypot((old.end.x - n.end.x).toDouble(), (old.end.y - n.end.y).toDouble()) > .12
        }.map { it.id }
    }

    private fun changedRooms(a: FloorPlan, b: FloorPlan): List<String> {
        val byId = b.rooms.associateBy { it.id }
        return a.rooms.filter { old ->
            val n = byId[old.id] ?: return@filter false
            abs(old.x - n.x) > .12f || abs(old.y - n.y) > .12f ||
                abs(old.width - n.width) > .12f || abs(old.height - n.height) > .12f
        }.map { it.id }
    }

    private fun movementSummary(before: FloorPlan, after: FloorPlan): String {
        changedOpeningSummary(before, after)?.let { return it }
        changedWallSummary(before, after)?.let { return it }
        changedRoomSummary(before, after)?.let { return it }
        return ""
    }

    private fun changedOpeningSummary(before: FloorPlan, after: FloorPlan): String? {
        val byId = after.openings.associateBy { it.id }
        val old = before.openings.firstOrNull { o -> byId[o.id]?.let { openingChanged(o, it) } == true } ?: return null
        val fresh = byId[old.id] ?: return null
        val d = distanceMeters(before, old.x, old.y, fresh.x, fresh.y)
        return if (d != null) "تحريك ${openingLabel(old)} نحو ${fmt2(d)}م على الجدار"
        else "تحريك ${openingLabel(old)} هندسيًا على الجدار"
    }

    private fun changedWallSummary(before: FloorPlan, after: FloorPlan): String? {
        val byId = after.walls.associateBy { it.id }
        val old = before.walls.firstOrNull { w -> byId[w.id]?.let { wallChanged(w, it) } == true } ?: return null
        val fresh = byId[old.id] ?: return null
        val ox = (old.start.x + old.end.x) / 2f; val oy = (old.start.y + old.end.y) / 2f
        val nx = (fresh.start.x + fresh.end.x) / 2f; val ny = (fresh.start.y + fresh.end.y) / 2f
        val d = distanceMeters(before, ox, oy, nx, ny)
        return if (d != null) "تحريك الجدار ${old.id} نحو ${fmt2(d)}م" else "تحريك الجدار ${old.id} مع تحديث الفراغات المرتبطة"
    }

    private fun changedRoomSummary(before: FloorPlan, after: FloorPlan): String? {
        val byId = after.rooms.associateBy { it.id }
        val old = before.rooms.firstOrNull { r -> byId[r.id]?.let { roomChanged(r, it) } == true } ?: return null
        val fresh = byId[old.id] ?: return null
        val areaDelta = fresh.areaM2 - old.areaM2
        val bx = old.x + old.width / 2f; val by = old.y + old.height / 2f
        val ax = fresh.x + fresh.width / 2f; val ay = fresh.y + fresh.height / 2f
        val moved = distanceMeters(before, bx, by, ax, ay)
        return when {
            abs(areaDelta) >= .10 -> "تغيير «${old.name}» بمقدار ${if (areaDelta > 0) "+" else ""}${fmt2(areaDelta)}م²"
            moved != null && moved >= .05 -> "تحريك «${old.name}» نحو ${fmt2(moved)}م"
            else -> "إعادة تموضع «${old.name}»"
        }
    }

    private fun distanceMeters(plan: FloorPlan, x1: Float, y1: Float, x2: Float, y2: Float): Double? {
        val w = plan.widthM ?: return null
        val h = plan.heightM ?: return null
        return hypot((x2 - x1) / 100.0 * w, (y2 - y1) / 100.0 * h)
    }

    private fun openingChanged(a: Opening, b: Opening) =
        hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()) > .12 || a.wallId != b.wallId

    private fun wallChanged(a: Wall, b: Wall) =
        hypot((a.start.x - b.start.x).toDouble(), (a.start.y - b.start.y).toDouble()) > .12 ||
            hypot((a.end.x - b.end.x).toDouble(), (a.end.y - b.end.y).toDouble()) > .12

    private fun roomChanged(a: Room, b: Room) =
        abs(a.x - b.x) > .12f || abs(a.y - b.y) > .12f || abs(a.width - b.width) > .12f || abs(a.height - b.height) > .12f

    private fun openingLabel(o: Opening) = if (o.type.lowercase().contains("window") || o.type.contains("ناف")) "النافذة ${o.id}" else "الباب ${o.id}"
    private fun fmt2(v: Double) = "%.2f".format(v)

    private fun signature(plan: FloorPlan): String = buildString {
        plan.rooms.forEach { append(it.id).append(':').append("%.1f".format(it.x)).append(',').append("%.1f".format(it.y)).append(',').append("%.1f".format(it.width)).append(',').append("%.1f".format(it.height)).append(';') }
        plan.openings.forEach { append(it.id).append('@').append("%.1f".format(it.x)).append(',').append("%.1f".format(it.y)).append(';') }
        plan.walls.forEach { append(it.id).append('#').append("%.1f".format(it.start.x)).append(',').append("%.1f".format(it.start.y)).append(';') }
    }
}
