package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.Room
import kotlin.math.max
import kotlin.math.min

object MultiPageEvidenceFusionEngine {
    fun apply(plan: FloorPlan, pages: List<RemoteFloorplanEvidenceClient.PageResult>): FloorPlan {
        if (pages.isEmpty()) return plan
        if (plan.floors.isEmpty()) {
            val p = pages.first()
            val fusedRooms = fuseRooms(plan.rooms, p.rooms)
            val fused = OpeningEvidenceFusion.merge(plan.openings, p.openings, plan.walls + p.walls)
            return FloorplanParserEngine.refine(
                plan.copy(
                    rooms = fusedRooms,
                    openings = fused.openings,
                    observations = (plan.observations + if (p.rooms.isNotEmpty()) {
                        "قورنت قراءة Vision مع ${p.rooms.size} مساحة من Deep Parser، ولم تُرفع الثقة إلا عند وجود توافق هندسي."
                    } else {
                        "لم يستخرج Deep Parser غرفًا مغلقة مؤكدة؛ بقيت قراءة Vision مرشحة للمراجعة ولم تعتبر دليلًا مستقلًا."
                    }).distinct()
                ),
                p.walls
            ).plan
        }
        val evidence = pages.associateBy { it.pageIndex }
        val floors = plan.floors.map { floor ->
            val p = evidence[floor.index] ?: return@map floor
            val fused = OpeningEvidenceFusion.merge(floor.openings, p.openings, floor.walls + p.walls)
            val local = FloorPlan(
                title = floor.name,
                widthM = plan.widthM,
                heightM = plan.heightM,
                rooms = fuseRooms(floor.rooms, p.rooms),
                walls = floor.walls,
                openings = fused.openings,
                footprint = floor.footprint,
                scaleConfidence = plan.scaleConfidence,
                site = plan.site,
                elements = floor.elements
            )
            val refined = FloorplanParserEngine.refine(local, p.walls).plan
            floor.copy(rooms = refined.rooms, walls = refined.walls, openings = refined.openings, elements = refined.elements)
        }
        val active = floors.firstOrNull { it.id == plan.activeFloorId } ?: floors.firstOrNull()
        return plan.copy(
            floors = floors,
            activeFloorId = active?.id,
            rooms = active?.rooms ?: plan.rooms,
            walls = active?.walls ?: plan.walls,
            openings = active?.openings ?: plan.openings,
            elements = active?.elements ?: plan.elements,
            footprint = active?.footprint?.takeIf { it.isNotEmpty() } ?: plan.footprint,
            observations = (plan.observations + "تم ربط أدلة التحليل العميق بكل صفحة PDF على حدة مع معايرة توافق الغرف.").distinct()
        )
    }

    /**
     * Vision is not allowed to win simply because it returned a high self-confidence value.
     * Rooms that agree spatially with the independent parser become consensus rooms. Unsupported
     * rooms stay reviewable but their confidence is capped, while strong non-overlapping evidence
     * from the independent parser can recover rooms Vision missed.
     */
    private fun fuseRooms(primary: List<Room>, evidence: List<Room>): List<Room> {
        if (primary.isEmpty()) return evidence.map { it.copy(confidence = min(it.confidence, 82)) }
        if (evidence.isEmpty()) return primary.map { it.copy(confidence = min(it.confidence, 72)) }

        val usedEvidence = mutableSetOf<String>()
        val out = mutableListOf<Room>()
        primary.forEach { room ->
            val candidates = evidence.map { it to overlapScore(room, it) }
            val matched = candidates.maxByOrNull { it.second }?.takeIf { it.second >= .34f }
            if (matched == null) {
                val cap = if (evidence.size >= 3) 62 else 70
                out += room.copy(confidence = min(room.confidence, cap))
                return@forEach
            }

            val other = matched.first
            usedEvidence += other.id
            val preferEvidenceGeometry =
                (room.polygon.size < 3 && other.polygon.size >= 3) || other.confidence >= room.confidence + 8
            val geometry = if (preferEvidenceGeometry) other else room
            val name = when {
                !isGenericName(room.name) -> room.name
                !isGenericName(other.name) -> other.name
                else -> room.name
            }
            val type = if (room.type.equals("unknown", true) && !other.type.equals("unknown", true)) other.type else room.type
            out += geometry.copy(
                id = "consensus-${room.id}",
                name = name,
                type = type,
                confidence = (max(room.confidence, other.confidence) + 6).coerceAtMost(96)
            )
        }

        evidence.filterNot { it.id in usedEvidence }
            .filter { it.confidence >= 72 }
            .filter { candidate -> out.none { overlapScore(it, candidate) >= .28f } }
            .take(20)
            .forEach { recovered ->
                out += recovered.copy(
                    id = "remote-recovered-${recovered.id}",
                    confidence = min(recovered.confidence, 74)
                )
            }

        return out.distinctBy { room ->
            val cx = ((room.x + room.width / 2f) * 2f).toInt()
            val cy = ((room.y + room.height / 2f) * 2f).toInt()
            "$cx:$cy:${(room.width * 2f).toInt()}:${(room.height * 2f).toInt()}"
        }
    }

    private fun overlapScore(a: Room, b: Room): Float {
        val ax2 = a.x + a.width
        val ay2 = a.y + a.height
        val bx2 = b.x + b.width
        val by2 = b.y + b.height
        val ix = (min(ax2, bx2) - max(a.x, b.x)).coerceAtLeast(0f)
        val iy = (min(ay2, by2) - max(a.y, b.y)).coerceAtLeast(0f)
        val intersection = ix * iy
        if (intersection <= 0f) return 0f
        val union = (a.width * a.height + b.width * b.height - intersection).coerceAtLeast(.01f)
        return (intersection / union).coerceIn(0f, 1f)
    }

    private fun isGenericName(value: String): Boolean {
        val text = value.trim().lowercase()
        return text.isBlank() || text == "غرفة" || text.startsWith("مساحة") || text.contains("unknown")
    }
}
