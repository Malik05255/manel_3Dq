package com.manzili.hai.engine

import com.manzili.hai.model.Opening
import com.manzili.hai.model.Wall
import kotlin.math.hypot

object OpeningEvidenceFusion {
    data class Result(val openings: List<Opening>, val accepted: Int, val rejected: Int)

    fun merge(existing: List<Opening>, evidence: List<Opening>, walls: List<Wall>): Result {
        if (evidence.isEmpty() || walls.isEmpty()) return Result(existing, 0, evidence.size)
        val result = existing.toMutableList()
        var accepted = 0
        var rejected = 0
        evidence.filter { it.confidence >= 80 }.forEach { candidate ->
            val nearest = walls.minOfOrNull { pointToSegment(candidate.x, candidate.y, it) } ?: Double.MAX_VALUE
            val duplicate = result.any {
                it.type.equals(candidate.type, ignoreCase = true) &&
                    hypot((it.x - candidate.x).toDouble(), (it.y - candidate.y).toDouble()) <= 2.2
            }
            if (nearest <= 1.65 && !duplicate) {
                result += candidate
                accepted++
            } else {
                rejected++
            }
        }
        return Result(result, accepted, rejected)
    }

    private fun pointToSegment(px: Float, py: Float, wall: Wall): Double {
        val ax = wall.start.x; val ay = wall.start.y
        val bx = wall.end.x; val by = wall.end.y
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 <= .0001f) return hypot((px - ax).toDouble(), (py - ay).toDouble())
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot((px - (ax + dx * t)).toDouble(), (py - (ay + dy * t)).toDouble())
    }
}
