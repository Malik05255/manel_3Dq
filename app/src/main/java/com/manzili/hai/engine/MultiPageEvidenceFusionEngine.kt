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
            val fusedRooms = fuseRooms(plan.rooms, p.rooms, p)
            val fused = OpeningEvidenceFusion.merge(plan.openings, p.openings, plan.walls + p.walls)
            return FloorplanParserEngine.refine(
                plan.copy(
                    rooms = fusedRooms,
                    openings = fused.openings,
                    scaleConfidence = calibratedScale(plan.scaleConfidence, p),
                    observations = (plan.observations + pageObservation(p)).distinct()
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
                rooms = fuseRooms(floor.rooms, p.rooms, p),
                walls = floor.walls,
                openings = fused.openings,
                footprint = floor.footprint,
                scaleConfidence = calibratedScale(plan.scaleConfidence, p),
                site = plan.site,
                elements = floor.elements
            )
            val refined = FloorplanParserEngine.refine(local, p.walls).plan
            floor.copy(rooms = refined.rooms, walls = refined.walls, openings = refined.openings, elements = refined.elements)
        }
        val active = floors.firstOrNull { it.id == plan.activeFloorId } ?: floors.firstOrNull()
        val pageScale = pages.map { calibratedScale(plan.scaleConfidence, it) }.maxOrNull() ?: plan.scaleConfidence
        return plan.copy(
            floors = floors,
            activeFloorId = active?.id,
            rooms = active?.rooms ?: plan.rooms,
            walls = active?.walls ?: plan.walls,
            openings = active?.openings ?: plan.openings,
            elements = active?.elements ?: plan.elements,
            footprint = active?.footprint?.takeIf { it.isNotEmpty() } ?: plan.footprint,
            scaleConfidence = max(plan.scaleConfidence, pageScale),
            observations = (plan.observations + pages.map(::pageObservation) + "تم ربط أدلة التحليل العميق بكل صفحة PDF على حدة مع معايرة هندسة الجدران والطوبولوجيا والأبعاد.").distinct()
        )
    }

    private fun pageObservation(page: RemoteFloorplanEvidenceClient.PageResult): String {
        val source = if (page.modelUsed.contains("roboflow-universe", ignoreCase = true)) "Roboflow" else "Deep Parser"
        return if (page.rooms.isNotEmpty()) {
            "$source صفحة ${page.pageIndex + 1}: ${page.rooms.size} مساحة، هندسة ${page.geometryConfidence}%، ترابط جدران ${page.wallTopology}%، أبعاد مؤكدة ${page.dimensionEvidenceCount}."
        } else {
            "$source صفحة ${page.pageIndex + 1}: لم يستخرج غرفًا مغلقة مؤكدة؛ بقيت قراءة Vision للمراجعة ولم تعتبر دليلًا مستقلًا."
        }
    }

    private fun calibratedScale(current: Int, page: RemoteFloorplanEvidenceClient.PageResult): Int {
        if (page.scaleConfidence <= 0) return current
        return if (page.dimensionEvidenceCount >= 2) {
            max(current, page.scaleConfidence)
        } else {
            min(current, page.scaleConfidence.coerceAtMost(76))
        }
    }

    private fun fuseRooms(
        primary: List<Room>,
        evidence: List<Room>,
        page: RemoteFloorplanEvidenceClient.PageResult
    ): List<Room> {
        val qualityValues = listOf(page.confidence, page.geometryConfidence, page.wallTopology).filter { it > 0 }
        val pageTrust = if (qualityValues.isEmpty()) 70 else qualityValues.average().toInt()
        val evidenceCap = (pageTrust + 8).coerceIn(58, 92)
        val consensusCap = (pageTrust + 12).coerceIn(66, 96)
        val roboflowPrimary = page.modelUsed.contains("roboflow-universe", ignoreCase = true)

        if (roboflowPrimary && evidence.size >= 3) {
            return fuseRoboflowPrimary(primary, evidence, pageTrust)
        }

        if (primary.isEmpty()) return evidence.map { it.copy(confidence = min(it.confidence, evidenceCap)) }
        if (evidence.isEmpty()) return primary.map { it.copy(confidence = min(it.confidence, min(72, pageTrust + 4))) }

        val usedEvidence = mutableSetOf<String>()
        val out = mutableListOf<Room>()
        primary.forEach { room ->
            val matched = evidence.map { it to overlapScore(room, it) }.maxByOrNull { it.second }?.takeIf { it.second >= .34f }
            if (matched == null) {
                val cap = if (evidence.size >= 3) min(62, evidenceCap) else min(70, evidenceCap)
                out += room.copy(confidence = min(room.confidence, cap))
                return@forEach
            }

            val other = matched.first
            usedEvidence += other.id
            val preferEvidenceGeometry = (room.polygon.size < 3 && other.polygon.size >= 3) || other.confidence >= room.confidence + 8
            val geometry = if (preferEvidenceGeometry) other else room
            val name = when {
                !isGenericName(room.name) -> room.name
                !isGenericName(other.name) -> other.name
                else -> room.name
            }
            val type = if (room.type.equals("unknown", true) && !other.type.equals("unknown", true)) other.type else room.type
            val localAgreement = (max(room.confidence, other.confidence) + 6).coerceAtMost(consensusCap)
            out += geometry.copy(id = room.id, name = name, type = type, confidence = localAgreement)
        }

        evidence.filterNot { it.id in usedEvidence }
            .filter { it.confidence >= 72 }
            .filter { candidate -> out.none { overlapScore(it, candidate) >= .28f } }
            .take(20)
            .forEach { recovered ->
                out += recovered.copy(id = "remote-recovered-${recovered.id}", confidence = min(recovered.confidence, min(74, evidenceCap)))
            }

        return dedupeRooms(out)
    }

    private fun fuseRoboflowPrimary(localRooms: List<Room>, roboflowRooms: List<Room>, pageTrust: Int): List<Room> {
        val cap = (pageTrust + 16).coerceIn(72, 94)
        val out = roboflowRooms.sortedByDescending { it.confidence }.take(40).map { remote ->
            val local = localRooms.map { it to overlapScore(it, remote) }.maxByOrNull { it.second }?.takeIf { it.second >= .24f }?.first
            val name = when {
                local != null && !isGenericName(local.name) -> local.name
                !isGenericName(remote.name) -> remote.name
                else -> remote.name
            }
            val type = if (local != null && !local.type.equals("unknown", true)) local.type else remote.type
            val confidence = if (local != null) min(cap, max(remote.confidence, local.confidence) + 4) else min(cap, remote.confidence)
            remote.copy(name = name, type = type, confidence = confidence)
        }.toMutableList()

        localRooms.filter { it.confidence >= 78 }
            .filter { candidate -> out.none { overlapScore(it, candidate) >= .24f } }
            .take(8)
            .forEach { local -> out += local.copy(id = "vision-extra-${local.id}", confidence = min(local.confidence, 66)) }

        return dedupeRooms(out)
    }

    private fun dedupeRooms(rooms: List<Room>): List<Room> = rooms.distinctBy { room ->
        val cx = ((room.x + room.width / 2f) * 2f).toInt()
        val cy = ((room.y + room.height / 2f) * 2f).toInt()
        "$cx:$cy:${(room.width * 2f).toInt()}:${(room.height * 2f).toInt()}"
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
