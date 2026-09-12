package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import kotlin.math.abs

/**
 * Broader deterministic search than NewBuildOptimizer.
 * It explores many validated room moves/resizes across several generations, keeps a bounded beam,
 * and returns three sufficiently different high-scoring plans. It does not claim mathematical proof
 * of global optimality; every returned plan still passes GeometryV3Engine.
 */
object GlobalLayoutOptimizer {
    private data class Node(val plan: FloorPlan, val score: Int, val depth: Int, val lineage: String)

    fun generate(program: NewBuildSolver.Program): List<NewBuildSolver.Candidate> {
        val seeds = NewBuildSolver.generate(program).map { seed ->
            val normalized = MultiFloorGeometryEngine.persistActive(MultiFloorGeometryEngine.normalize(seed.plan))
            Node(normalized, score(normalized, program), 0, seed.title)
        }
        if (seeds.isEmpty()) return emptyList()

        val seen = linkedSetOf<String>()
        var beam = seeds.filter { GeometryV3Engine.inspect(it.plan).valid }
            .distinctBy { signature(it.plan) }
            .sortedByDescending { it.score }
            .take(24)
        beam.forEach { seen += signature(it.plan) }
        val archive = beam.toMutableList()

        repeat(7) { generation ->
            val expanded = mutableListOf<Node>()
            beam.forEachIndexed { nodeIndex, node ->
                val rooms = node.plan.rooms.filterNot { it.locked }
                    .sortedByDescending { roomPriority(it.type, program) }
                    .take(10)
                rooms.forEachIndexed { roomIndex, room ->
                    val actions = if ((generation + roomIndex + nodeIndex) % 2 == 0)
                        listOf("MOVE", "EXPAND", "SHRINK") else listOf("EXPAND", "MOVE", "SHRINK")
                    actions.forEach { action ->
                        GeometrySolver.actionCandidates(node.plan, "room", room.id, action).take(4).forEach { candidate ->
                            if (candidate.review.objections.isNotEmpty()) return@forEach
                            val next = MultiFloorGeometryEngine.persistActive(candidate.plan)
                            val report = GeometryV3Engine.inspect(next)
                            if (!report.valid) return@forEach
                            val sig = signature(report.plan)
                            if (!seen.add(sig)) return@forEach
                            expanded += Node(
                                plan = report.plan,
                                score = score(report.plan, program) - report.warnings.size.coerceAtMost(5),
                                depth = node.depth + 1,
                                lineage = node.lineage
                            )
                        }
                    }
                }
            }

            if (expanded.isEmpty()) return@repeat
            archive += expanded
            beam = expanded
                .sortedWith(compareByDescending<Node> { it.score }.thenBy { it.depth })
                .let { diversityPrune(it, 28) }
        }

        val ranked = archive.distinctBy { signature(it.plan) }.sortedByDescending { it.score }
        val selected = mutableListOf<Node>()
        ranked.forEach { node ->
            if (selected.size < 3 && selected.all { layoutDistance(it.plan, node.plan) >= 7.0f }) selected += node
        }
        if (selected.size < 3) ranked.forEach { if (selected.size < 3 && it !in selected) selected += it }

        return selected.take(3).mapIndexed { index, node ->
            NewBuildSolver.Candidate(
                id = "global-${index + 1}",
                title = when (index) {
                    0 -> "الأفضل بعد البحث الشامل"
                    1 -> "بديل عالمي متوازن"
                    else -> "بديل عالمي مختلف"
                },
                rationale = "اختير بعد استكشاف ${archive.size} حالة هندسية صحيحة عبر ${node.depth} أجيال تحسين؛ نقطة البداية ${node.lineage}. لا توجد ادعاءات باعتماد بلدي أو إنشائي.",
                plan = node.plan,
                overall = node.score.coerceIn(0, 100),
                metrics = listOf(
                    "${archive.size} حالة مفحوصة",
                    "Beam 28",
                    "خصوصية ${program.privacyPriority}/100",
                    "حركة ${program.circulationPriority}/100"
                )
            )
        }
    }

    private fun diversityPrune(nodes: List<Node>, limit: Int): List<Node> {
        val out = mutableListOf<Node>()
        nodes.forEach { node ->
            if (out.size < limit && out.all { layoutDistance(it.plan, node.plan) >= 2.4f }) out += node
        }
        if (out.size < limit) nodes.forEach { if (out.size < limit && it !in out) out += it }
        return out
    }

    private fun score(plan: FloorPlan, program: NewBuildSolver.Program): Int {
        val s = ArchitecturalEngine.score(plan)
        val privacy = ((s.privacy - 50) * program.privacyPriority / 450).coerceIn(-12, 12)
        val circulation = ((s.efficiency - 50) * program.circulationPriority / 500).coerceIn(-10, 10)
        val daylight = ((plan.preferences.daylightPriority - 50) * program.daylightPriority / 1000).coerceIn(0, 5)
        val overlapPenalty = overlapPenalty(plan)
        return (s.overall + privacy + circulation + daylight - overlapPenalty).coerceIn(0, 100)
    }

    private fun overlapPenalty(plan: FloorPlan): Int {
        var pairs = 0
        for (i in plan.rooms.indices) for (j in i + 1 until plan.rooms.size) {
            val a = plan.rooms[i]; val b = plan.rooms[j]
            val x = minOf(a.x + a.width, b.x + b.width) - maxOf(a.x, b.x)
            val y = minOf(a.y + a.height, b.y + b.height) - maxOf(a.y, b.y)
            if (x > .3f && y > .3f) pairs++
        }
        return (pairs * 6).coerceAtMost(24)
    }

    private fun roomPriority(type: String, program: NewBuildSolver.Program): Int = when (type.lowercase()) {
        "majlis", "guest", "مجلس" -> 100 + program.privacyPriority
        "living", "family", "صالة" -> 90 + program.circulationPriority
        "kitchen", "service", "مطبخ" -> 80
        "bedroom", "master", "نوم" -> 75
        else -> 50
    }

    private fun signature(plan: FloorPlan): String = plan.rooms.sortedBy { it.id }.joinToString("|") { r ->
        "${r.id}:${(r.x * 2).toInt()}:${(r.y * 2).toInt()}:${(r.width * 2).toInt()}:${(r.height * 2).toInt()}"
    }

    private fun layoutDistance(a: FloorPlan, b: FloorPlan): Float {
        val other = b.rooms.associateBy { it.id }
        val values = a.rooms.mapNotNull { room ->
            val target = other[room.id] ?: return@mapNotNull null
            abs(room.x - target.x) + abs(room.y - target.y) +
                .65f * abs(room.width - target.width) + .65f * abs(room.height - target.height)
        }
        return if (values.isEmpty()) 100f else values.average().toFloat()
    }
}
