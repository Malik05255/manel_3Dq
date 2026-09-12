package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import kotlin.math.abs

object NewBuildOptimizer {
    private data class Scored(
        val plan: FloorPlan,
        val score: Int,
        val sourceTitle: String,
        val moves: Int
    )

    fun generate(program: NewBuildSolver.Program): List<NewBuildSolver.Candidate> {
        val seeds = NewBuildSolver.generate(program)
        val pool = mutableListOf<Scored>()

        seeds.forEach { seed ->
            val base = MultiFloorGeometryEngine.normalize(seed.plan)
            add(pool, base, program, seed.title, 0)

            val firstWave = mutableListOf<FloorPlan>()
            base.rooms.filterNot { it.locked }.take(7).forEach { room ->
                listOf("MOVE", "EXPAND", "SHRINK").forEach { action ->
                    GeometrySolver.actionCandidates(base, "room", room.id, action).take(3).forEach { candidate ->
                        if (candidate.review.objections.isEmpty()) {
                            val next = MultiFloorGeometryEngine.persistActive(candidate.plan)
                            add(pool, next, program, seed.title, 1)
                            firstWave += next
                        }
                    }
                }
            }

            firstWave.distinctBy(::signature).take(12).forEachIndexed { index, current ->
                val rooms = current.rooms.filterNot { it.locked }
                if (rooms.isEmpty()) return@forEachIndexed
                val room = rooms[index % rooms.size]
                val action = if (index % 2 == 0) "MOVE" else "EXPAND"
                GeometrySolver.actionCandidates(current, "room", room.id, action).take(2).forEach { candidate ->
                    if (candidate.review.objections.isEmpty()) {
                        add(pool, MultiFloorGeometryEngine.persistActive(candidate.plan), program, seed.title, 2)
                    }
                }
            }
        }

        val unique = pool.distinctBy { signature(it.plan) }.sortedByDescending { it.score }
        val selected = mutableListOf<Scored>()
        unique.forEach { item ->
            if (selected.size < 3 && selected.all { layoutDistance(it.plan, item.plan) >= 5.5f }) selected += item
        }
        if (selected.size < 3) unique.forEach { if (selected.size < 3 && it !in selected) selected += it }

        return selected.take(3).mapIndexed { index, item ->
            NewBuildSolver.Candidate(
                id = "optimized-${index + 1}",
                title = when (index) {
                    0 -> "الحل الأمثل"
                    1 -> "بديل متوازن"
                    else -> "بديل مختلف"
                },
                rationale = "اختير بعد بحث هندسي بين ${pool.size} حالة مولدة ومفلترة. نقطة البداية: ${item.sourceTitle}، ثم ${item.moves} خطوة تحسين.",
                plan = item.plan,
                overall = item.score,
                metrics = listOf(
                    "بحث ${pool.size} حالة",
                    "${program.floorCount} دور",
                    "${program.bedrooms} غرف نوم",
                    "خصوصية ${program.privacyPriority}/100"
                )
            )
        }
    }

    private fun add(
        pool: MutableList<Scored>,
        plan: FloorPlan,
        program: NewBuildSolver.Program,
        source: String,
        moves: Int
    ) {
        val geometry = GeometryV3Engine.inspect(plan)
        if (!geometry.valid) return
        val score = ArchitecturalEngine.score(geometry.plan)
        val privacyBonus = ((score.privacy - 50) * program.privacyPriority / 500).coerceIn(-10, 10)
        val efficiencyBonus = ((score.efficiency - 50) * program.circulationPriority / 550).coerceIn(-9, 9)
        val warningPenalty = geometry.warnings.size.coerceAtMost(5)
        pool += Scored(
            geometry.plan,
            (score.overall + privacyBonus + efficiencyBonus - warningPenalty).coerceIn(0, 100),
            source,
            moves
        )
    }

    private fun signature(plan: FloorPlan): String = plan.rooms
        .sortedBy { it.id }
        .joinToString("|") { room ->
            "${room.id}:${(room.x / 2f).toInt()}:${(room.y / 2f).toInt()}:${(room.width / 2f).toInt()}:${(room.height / 2f).toInt()}"
        }

    private fun layoutDistance(a: FloorPlan, b: FloorPlan): Float {
        val other = b.rooms.associateBy { it.id }
        val values = a.rooms.mapNotNull { room ->
            val target = other[room.id] ?: return@mapNotNull null
            abs(room.x - target.x) + abs(room.y - target.y) +
                abs(room.width - target.width) * 0.5f + abs(room.height - target.height) * 0.5f
        }
        return if (values.isEmpty()) 100f else values.average().toFloat()
    }
}
