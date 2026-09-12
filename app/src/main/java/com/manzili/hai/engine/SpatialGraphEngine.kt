package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.Room
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object SpatialGraphEngine {
    data class Edge(val aId: String, val bId: String, val aName: String, val bName: String, val span: Float)
    data class Candidate(val roomName: String, val flexibleAreaM2: Double, val score: Int)
    data class DoorConnection(val openingId: String, val roomIds: List<String>, val roomNames: List<String>, val confidence: Int)
    data class Report(
        val edges: List<Edge>,
        val isolated: List<String>,
        val privacyContacts: List<String>,
        val candidates: List<Candidate>,
        val doorConnections: List<DoorConnection> = emptyList(),
        val roomsWithoutDoor: List<String> = emptyList()
    )

    fun analyze(plan: FloorPlan): Report {
        val edges = mutableListOf<Edge>()
        for (i in plan.rooms.indices) for (j in i + 1 until plan.rooms.size) {
            val a = plan.rooms[i]
            val b = plan.rooms[j]
            val span = sharedSpan(a, b)
            if (span >= 2.2f) edges += Edge(a.id, b.id, a.name, b.name, span)
        }
        val linked = edges.flatMap { listOf(it.aId, it.bId) }.toSet()
        val isolated = plan.rooms.filter { it.id !in linked && it.width > 2f && it.height > 2f }.map { it.name }
        val byId = plan.rooms.associateBy { it.id }
        val privacy = edges.mapNotNull { e ->
            val a = byId[e.aId] ?: return@mapNotNull null
            val b = byId[e.bId] ?: return@mapNotNull null
            if ((isGuest(a) && isPrivate(b)) || (isGuest(b) && isPrivate(a))) "${a.name} ↔ ${b.name}" else null
        }.distinct()

        val doors = plan.openings.filter { isDoor(it.type) }.mapNotNull { opening ->
            val ids = opening.connectsRoomIds.filter { it in byId.keys }.distinct()
            if (ids.isEmpty()) null else DoorConnection(
                openingId = opening.id,
                roomIds = ids,
                roomNames = ids.mapNotNull { byId[it]?.name },
                confidence = opening.confidence
            )
        }
        val roomsWithDoor = doors.flatMap { it.roomIds }.toSet()
        val roomsWithoutDoor = if (doors.isEmpty()) emptyList() else plan.rooms
            .filter { it.id !in roomsWithDoor && !isVoidLike(it) }
            .map { it.name }

        val candidates = plan.rooms.filter { !it.locked && it.areaM2 > 0 }.mapNotNull { room ->
            val keep = room.minAreaM2 ?: room.areaM2 * keepRatio(room)
            val flexible = (room.areaM2 - keep).coerceAtLeast(0.0)
            if (flexible < 0.8) null else Candidate(room.name, flexible, candidateScore(room, flexible))
        }.sortedByDescending { it.score }.take(5)

        return Report(edges, isolated, privacy, candidates, doors, roomsWithoutDoor)
    }

    fun compactBrief(plan: FloorPlan): String {
        val report = analyze(plan)
        val flex = report.candidates.take(3).joinToString("؛ ") { "${it.roomName} ${"%.1f".format(it.flexibleAreaM2)}م²" }
        return buildString {
            append("العلاقات المكانية المقروءة: ${report.edges.size}. ")
            if (report.doorConnections.isNotEmpty()) append("روابط الأبواب المؤكدة: ${report.doorConnections.size}. ")
            if (report.roomsWithoutDoor.isNotEmpty()) append("غرف بلا اتصال باب مقروء: ${report.roomsWithoutDoor.take(4).joinToString("، ")}. ")
            if (report.isolated.isNotEmpty()) append("عناصر معزولة ظاهريًا: ${report.isolated.joinToString("، ")}. ")
            if (report.privacyContacts.isNotEmpty()) append("تلامس ضيوف/خاص: ${report.privacyContacts.joinToString("، ")}. ")
            if (flex.isNotBlank()) append("مساحات مرنة مبدئيًا: $flex.")
        }.trim()
    }

    private fun sharedSpan(a: Room, b: Room): Float {
        val tolerance = 1.35f
        if (min(abs(a.x + a.width - b.x), abs(b.x + b.width - a.x)) <= tolerance) {
            return overlap(a.y, a.y + a.height, b.y, b.y + b.height)
        }
        if (min(abs(a.y + a.height - b.y), abs(b.y + b.height - a.y)) <= tolerance) {
            return overlap(a.x, a.x + a.width, b.x, b.x + b.width)
        }
        return 0f
    }

    private fun overlap(a0: Float, a1: Float, b0: Float, b1: Float) = (min(a1, b1) - max(a0, b0)).coerceAtLeast(0f)

    private fun candidateScore(room: Room, flexible: Double): Int {
        val penalty = when { isService(room) -> 45; isPrivate(room) -> 25; isGuest(room) -> 18; isCirculation(room) -> -8; else -> 0 }
        return (78 - penalty + min(18.0, flexible * 2).toInt()).coerceIn(0, 100)
    }

    private fun keepRatio(room: Room) = when { isCirculation(room) -> 0.58; isGuest(room) -> 0.78; isPrivate(room) -> 0.82; isService(room) -> 0.90; else -> 0.72 }
    private fun t(room: Room) = (room.type + " " + room.name).lowercase()
    private fun isDoor(type: String) = type.lowercase().contains("door") || type.contains("باب")
    private fun isCirculation(r: Room) = listOf("corridor", "hallway", "ممر", "مدخل").any { t(r).contains(it) }
    private fun isGuest(r: Room) = listOf("majlis", "guest", "مجلس", "ضيوف").any { t(r).contains(it) }
    private fun isPrivate(r: Room) = listOf("bedroom", "master", "family", "نوم", "عائل").any { t(r).contains(it) }
    private fun isService(r: Room) = listOf("stair", "elevator", "bath", "kitchen", "درج", "مصعد", "حمام", "مطبخ").any { t(r).contains(it) }
    private fun isVoidLike(r: Room) = listOf("void", "shaft", "منور", "فراغ").any { t(r).contains(it) }
}
