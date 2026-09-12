package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import kotlin.math.hypot

object ProjectMemoryRepair {
    fun nearestSafe(
        base: FloorPlan,
        attempted: FloorPlan,
        kind: String,
        id: String
    ): GeometrySolver.Candidate? {
        return GeometrySolver.actionCandidates(base, kind, id, "MOVE")
            .asSequence()
            .filter { !it.review.hasMaterialObjection }
            .filter { !ProjectMemoryEngine.review(base, it.plan).hasObjection }
            .minByOrNull { distance(attempted, it.plan, kind, id) }
    }

    private fun distance(a: FloorPlan, b: FloorPlan, kind: String, id: String): Double = when (kind) {
        "opening" -> {
            val x = a.openings.firstOrNull { it.id == id }
            val y = b.openings.firstOrNull { it.id == id }
            if (x == null || y == null) Double.MAX_VALUE else hypot((x.x - y.x).toDouble(), (x.y - y.y).toDouble())
        }
        "wall" -> {
            val x = a.walls.firstOrNull { it.id == id }
            val y = b.walls.firstOrNull { it.id == id }
            if (x == null || y == null) Double.MAX_VALUE else {
                val ax = (x.start.x + x.end.x) / 2f
                val ay = (x.start.y + x.end.y) / 2f
                val bx = (y.start.x + y.end.x) / 2f
                val by = (y.start.y + y.end.y) / 2f
                hypot((ax - bx).toDouble(), (ay - by).toDouble())
            }
        }
        "room" -> {
            val x = a.rooms.firstOrNull { it.id == id }
            val y = b.rooms.firstOrNull { it.id == id }
            if (x == null || y == null) Double.MAX_VALUE else {
                val ax = x.x + x.width / 2f
                val ay = x.y + x.height / 2f
                val bx = y.x + y.width / 2f
                val by = y.y + y.height / 2f
                hypot((ax - bx).toDouble(), (ay - by).toDouble())
            }
        }
        else -> Double.MAX_VALUE
    }
}
