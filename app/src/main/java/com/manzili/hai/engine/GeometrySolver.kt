package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.Opening
import com.manzili.hai.model.PlanChange
import com.manzili.hai.model.PlanPoint
import com.manzili.hai.model.PlanProposal
import com.manzili.hai.model.Room
import com.manzili.hai.model.Wall
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Deterministic geometry layer.
 * HAI suggests intent; this engine owns snapping, bounds, overlap checks,
 * candidate generation and direct-manipulation feasibility.
 */
object GeometrySolver {
    data class Check(
        val feasible: Boolean,
        val errors: List<String> = emptyList(),
        val warnings: List<String> = emptyList()
    )

    data class TargetSize(val widthM: Double, val heightM: Double) {
        val areaM2: Double get() = widthM * heightM
    }

    data class Candidate(
        val title: String,
        val reason: String,
        val plan: FloorPlan,
        val score: Int,
        val review: ArchitecturalEngine.ArchitectReview,
        val changes: List<PlanChange> = emptyList(),
        val geometryWarnings: List<String> = emptyList()
    )

    fun brief(plan: FloorPlan, userText: String): String {
        val target = parseTargetSize(userText)
        val locked = plan.rooms.filter { it.locked }.joinToString("، ") { it.name }.ifBlank { "لا يوجد" }
        val graph = SpatialGraphEngine.analyze(plan)
        val flexible = graph.candidates.take(4).joinToString("؛ ") { "${it.roomName}: ${fmt(it.flexibleAreaM2)}م²" }.ifBlank { "غير محسوبة" }
        return buildString {
            append("قيود GeometrySolver: العناصر المقفلة: $locked. ")
            target?.let { append("المقاس المطلوب ${fmt(it.widthM)}×${fmt(it.heightM)}م = ${fmt(it.areaM2)}م². ") }
            append("المساحات المرنة التقريبية: $flexible. ")
            if (plan.widthM == null || plan.heightM == null) append("مقياس المبنى بالمتر غير مكتمل؛ لا يجوز تحويل المتر إلى إحداثيات نسبية بدقة.")
        }
    }

    fun verify(current: FloorPlan, next: FloorPlan): Check {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        next.rooms.forEach { room ->
            if (room.width <= 0f || room.height <= 0f) errors += "أبعاد ${room.name} غير صالحة"
            if (room.x < 0f || room.y < 0f || room.x + room.width > 100.001f || room.y + room.height > 100.001f) {
                errors += "${room.name} خرجت عن حدود المخطط"
            }
        }

        val nextRooms = next.rooms.associateBy { it.id }
        current.rooms.filter { it.locked }.forEach { old ->
            val fresh = nextRooms[old.id]
            if (fresh == null) errors += "الغرفة المقفلة ${old.name} حذفت"
            else if (roomChanged(old, fresh)) errors += "الغرفة المقفلة ${old.name} تغيرت"
        }

        val nextWalls = next.walls.associateBy { it.id }
        current.walls.filter { it.locked }.forEach { old ->
            val fresh = nextWalls[old.id]
            if (fresh == null) errors += "الجدار المقفل ${old.id} حذف"
            else if (wallChanged(old, fresh)) errors += "الجدار المقفل ${old.id} تغير"
        }

        val nextOpenings = next.openings.associateBy { it.id }
        current.openings.filter { it.locked }.forEach { old ->
            val fresh = nextOpenings[old.id]
            if (fresh == null) errors += "الفتحة المقفلة ${old.id} حذفت"
            else if (openingChanged(old, fresh)) errors += "الفتحة المقفلة ${old.id} تغيرت"
        }

        val oldOverlap = overlapPairs(current)
        overlapPairs(next).forEach { (key, value) ->
            val before = oldOverlap[key] ?: 0.0
            if (value > 0.02 && value > before + 0.01) errors += "ظهر تداخل هندسي جديد بين ${key.first} و${key.second}"
        }

        next.openings.forEach { opening ->
            val wall = opening.wallId?.let { nextWalls[it] }
            if (wall == null) {
                warnings += "الفتحة ${opening.id} غير مرتبطة بجدار موثوق"
            } else {
                val distance = pointToSegment(opening.x, opening.y, wall.start.x, wall.start.y, wall.end.x, wall.end.y)
                if (distance > 1.5) errors += "الفتحة ${opening.id} لا تقع على الجدار ${wall.id}"
            }
        }

        return Check(errors.isEmpty(), errors.distinct(), warnings.distinct())
    }

    /** Generate several deterministic choices instead of one arbitrary edit. */
    fun actionCandidates(plan: FloorPlan, kind: String, id: String, action: String): List<Candidate> {
        val raw = when (kind) {
            "room" -> roomCandidates(plan, id, action)
            "opening" -> openingCandidates(plan, id, action)
            "wall" -> wallCandidates(plan, id, action)
            else -> emptyList()
        }
        return raw.distinctBy { fingerprint(it.plan) }
            .sortedByDescending { it.score }
            .take(4)
    }

    /** Candidate used while the user's finger is moving. Nothing is committed here. */
    fun drag(plan: FloorPlan, kind: String, id: String, deltaXPct: Float, deltaYPct: Float): Candidate? {
        return when (kind) {
            "room" -> moveRoomCandidate(plan, id, deltaXPct, deltaYPct, "معاينة السحب")
            "opening" -> {
                val opening = plan.openings.firstOrNull { it.id == id } ?: return null
                moveOpeningCandidate(plan, id, opening.x + deltaXPct, opening.y + deltaYPct, "معاينة نقل الفتحة")
            }
            // A wall changes room topology; until room polygons are linked to wall faces,
            // refusing direct wall drag is safer than visually moving a line only.
            "wall" -> null
            else -> null
        }
    }

    fun toProposal(current: FloorPlan, candidate: Candidate): PlanProposal {
        val geometry = verify(current, candidate.plan)
        if (!geometry.feasible) return rejected(geometry.errors.joinToString(". "))
        val objections = candidate.review.objections
        val message = when {
            objections.isNotEmpty() -> objections.joinToString(" ")
            geometry.warnings.isNotEmpty() -> "التعديل ممكن، لكن توجد ملاحظة قراءة: ${geometry.warnings.first()}"
            else -> "التعديل سليم هندسيًا ولم أجد اعتراضًا معماريًا جوهريًا."
        }
        return PlanProposal(
            message = message,
            updatedPlan = candidate.plan,
            changes = candidate.changes,
            requiresConfirmation = true,
            confidence = if (objections.isNotEmpty()) 82 else 94
        )
    }

    fun moveRoom(plan: FloorPlan, roomId: String, deltaXPct: Float, deltaYPct: Float): PlanProposal {
        val c = moveRoomCandidate(plan, roomId, deltaXPct, deltaYPct, "نقل مباشر مع محاذاة هندسية")
            ?: return rejected("لا يمكن نقل الغرفة إلى هذا الموقع دون كسر القيود")
        return toProposal(plan, c)
    }

    fun resizeRoom(plan: FloorPlan, roomId: String, deltaWidthPct: Float, deltaHeightPct: Float): PlanProposal {
        val c = resizeRoomCandidate(plan, roomId, deltaWidthPct, deltaHeightPct, "تغيير حجم مباشر مع فحص الأثر")
            ?: return rejected("لا يمكن تغيير حجم الغرفة بهذه القيمة دون كسر القيود")
        return toProposal(plan, c)
    }

    fun moveOpening(plan: FloorPlan, openingId: String, targetX: Float, targetY: Float): PlanProposal {
        val c = moveOpeningCandidate(plan, openingId, targetX, targetY, "نقل الفتحة إلى أقرب موضع صالح")
            ?: return rejected("لا يمكن نقل الباب أو النافذة إلى هذا الموضع بأمان")
        return toProposal(plan, c)
    }

    private fun roomCandidates(plan: FloorPlan, roomId: String, action: String): List<Candidate> = when (action.uppercase()) {
        "MOVE" -> listOf(
            Triple(2f, 0f, "يمين"), Triple(-2f, 0f, "يسار"),
            Triple(0f, 2f, "أسفل"), Triple(0f, -2f, "أعلى"),
            Triple(3.5f, 0f, "يمين أبعد"), Triple(-3.5f, 0f, "يسار أبعد")
        ).mapNotNull { (dx, dy, label) -> moveRoomCandidate(plan, roomId, dx, dy, "نقل $label") }

        "EXPAND" -> listOf(
            Triple(1.5f, 1.5f, "متوازن"), Triple(2.5f, 1f, "عرضي"), Triple(1f, 2.5f, "طولي")
        ).mapNotNull { (dw, dh, label) -> resizeRoomCandidate(plan, roomId, dw, dh, "تكبير $label") }

        "SHRINK" -> listOf(
            Triple(-1.5f, -1.5f, "متوازن"), Triple(-2.5f, -1f, "عرضي"), Triple(-1f, -2.5f, "طولي")
        ).mapNotNull { (dw, dh, label) -> resizeRoomCandidate(plan, roomId, dw, dh, "تصغير $label") }

        else -> emptyList()
    }

    private fun openingCandidates(plan: FloorPlan, openingId: String, action: String): List<Candidate> {
        if (action.uppercase() != "MOVE") return emptyList()
        val opening = plan.openings.firstOrNull { it.id == openingId } ?: return emptyList()
        if (opening.locked) return emptyList()
        val wall = opening.wallId?.let { id -> plan.walls.firstOrNull { it.id == id } } ?: return emptyList()
        if (wall.locked) return emptyList()
        val currentT = segmentT(opening.x, opening.y, wall)
        return listOf(-0.18f, -0.10f, 0.10f, 0.18f).mapNotNull { dt ->
            val t = (currentT + dt).coerceIn(0.08f, 0.92f)
            val point = PlanPoint(
                wall.start.x + (wall.end.x - wall.start.x) * t,
                wall.start.y + (wall.end.y - wall.start.y) * t
            )
            moveOpeningCandidate(plan, openingId, point.x, point.y, "تحريك على نفس الجدار")
        }
    }

    private fun wallCandidates(plan: FloorPlan, wallId: String, action: String): List<Candidate> {
        if (action.uppercase() != "MOVE") return emptyList()
        val wall = plan.walls.firstOrNull { it.id == wallId } ?: return emptyList()
        if (wall.locked || wall.kind == "external") return emptyList()
        // The current room model stores rectangular room bounds independently of wall faces.
        // Moving a wall line alone would create a false plan, so no candidate is emitted yet.
        return emptyList()
    }

    private fun moveRoomCandidate(
        plan: FloorPlan,
        roomId: String,
        deltaXPct: Float,
        deltaYPct: Float,
        label: String
    ): Candidate? {
        val room = plan.rooms.firstOrNull { it.id == roomId } ?: return null
        if (room.locked) return null
        val moved = room.copy(
            x = snap((room.x + deltaXPct).coerceIn(0f, 100f - room.width)),
            y = snap((room.y + deltaYPct).coerceIn(0f, 100f - room.height))
        )
        if (!roomChanged(room, moved)) return null
        val next = plan.copy(rooms = plan.rooms.map { if (it.id == roomId) moved else it })
        return buildCandidate(
            plan,
            next,
            title = label,
            reason = "نقل ${room.name} مع Snap وفحص التداخل والخصوصية",
            changes = listOf(PlanChange(room.id, room.name, "MOVE", room.areaM2.takeIf { it > 0 }, moved.areaM2.takeIf { it > 0 }, label))
        )
    }

    private fun resizeRoomCandidate(
        plan: FloorPlan,
        roomId: String,
        deltaWidthPct: Float,
        deltaHeightPct: Float,
        label: String
    ): Candidate? {
        val room = plan.rooms.firstOrNull { it.id == roomId } ?: return null
        if (room.locked) return null
        val resized = room.copy(
            width = snap((room.width + deltaWidthPct).coerceIn(2f, 100f - room.x)),
            height = snap((room.height + deltaHeightPct).coerceIn(2f, 100f - room.y))
        )
        if (!roomChanged(room, resized)) return null
        val nextArea = scaledArea(plan, resized)
        val updated = resized.copy(areaM2 = nextArea ?: room.areaM2)
        val next = plan.copy(rooms = plan.rooms.map { if (it.id == roomId) updated else it })
        return buildCandidate(
            plan,
            next,
            title = label,
            reason = "تعديل مساحة ${room.name} مع فحص الحدود والتداخل والأثر الوظيفي",
            changes = listOf(PlanChange(room.id, room.name, "RESIZE", room.areaM2.takeIf { it > 0 }, updated.areaM2.takeIf { it > 0 }, label))
        )
    }

    private fun moveOpeningCandidate(
        plan: FloorPlan,
        openingId: String,
        targetX: Float,
        targetY: Float,
        label: String
    ): Candidate? {
        val opening = plan.openings.firstOrNull { it.id == openingId } ?: return null
        if (opening.locked || plan.walls.isEmpty()) return null
        val wall = plan.walls
            .filterNot { it.locked }
            .minByOrNull { pointToSegment(targetX, targetY, it.start.x, it.start.y, it.end.x, it.end.y) }
            ?: return null
        val snapped = projectToSegment(targetX, targetY, wall)
        val moved = opening.copy(x = snap(snapped.x), y = snap(snapped.y), wallId = wall.id)
        if (!openingChanged(opening, moved)) return null
        val next = plan.copy(openings = plan.openings.map { if (it.id == openingId) moved else it })
        return buildCandidate(
            plan,
            next,
            title = label,
            reason = "الموضع مسقّط على الجدار ${wall.id} ثم مراجع معماريًا قبل الاعتماد",
            changes = listOf(PlanChange(roomName = opening.id, action = "MOVE_OPENING", note = label))
        )
    }

    private fun buildCandidate(
        current: FloorPlan,
        next: FloorPlan,
        title: String,
        reason: String,
        changes: List<PlanChange>
    ): Candidate? {
        val geometry = verify(current, next)
        if (!geometry.feasible) return null
        val review = ArchitecturalEngine.architecturalReview(current, next)
        val before = ArchitecturalEngine.score(current)
        val after = ArchitecturalEngine.score(next)
        val delta = (after.overall - before.overall).coerceIn(-20, 20)
        val objectionPenalty = review.objections.size * 16
        val warningPenalty = geometry.warnings.size.coerceAtMost(3) * 3
        val score = (86 + delta - objectionPenalty - warningPenalty).coerceIn(20, 99)
        return Candidate(title, reason, next, score, review, changes, geometry.warnings)
    }

    private fun parseTargetSize(text: String): TargetSize? {
        val normalized = normalizeDigits(text).replace('×', 'x').replace('X', 'x')
        val match = Regex("(\\d+(?:\\.\\d+)?)\\s*x\\s*(\\d+(?:\\.\\d+)?)").find(normalized) ?: return null
        val a = match.groupValues[1].toDoubleOrNull() ?: return null
        val b = match.groupValues[2].toDoubleOrNull() ?: return null
        if (a <= 0 || b <= 0 || a > 100 || b > 100) return null
        return TargetSize(a, b)
    }

    private fun normalizeDigits(text: String): String = buildString {
        text.forEach { c ->
            append(
                when (c) {
                    '٠', '۰' -> '0'; '١', '۱' -> '1'; '٢', '۲' -> '2'; '٣', '۳' -> '3'; '٤', '۴' -> '4'
                    '٥', '۵' -> '5'; '٦', '۶' -> '6'; '٧', '۷' -> '7'; '٨', '۸' -> '8'; '٩', '۹' -> '9'
                    else -> c
                }
            )
        }
    }

    private fun scaledArea(plan: FloorPlan, room: Room): Double? {
        val w = plan.widthM ?: return null
        val h = plan.heightM ?: return null
        return (room.width / 100.0 * w) * (room.height / 100.0 * h)
    }

    private fun overlapPairs(plan: FloorPlan): Map<Pair<String, String>, Double> {
        val result = mutableMapOf<Pair<String, String>, Double>()
        for (i in plan.rooms.indices) for (j in i + 1 until plan.rooms.size) {
            val a = plan.rooms[i]
            val b = plan.rooms[j]
            val overlap = overlapRatio(a, b)
            if (overlap > 0) {
                val names = listOf(a.name, b.name).sorted()
                result[names[0] to names[1]] = overlap
            }
        }
        return result
    }

    private fun overlapRatio(a: Room, b: Room): Double {
        val left = max(a.x, b.x)
        val top = max(a.y, b.y)
        val right = min(a.x + a.width, b.x + b.width)
        val bottom = min(a.y + a.height, b.y + b.height)
        if (right <= left || bottom <= top) return 0.0
        val intersection = (right - left) * (bottom - top)
        val base = min(a.width * a.height, b.width * b.height).coerceAtLeast(0.01f)
        return (intersection / base).toDouble()
    }

    private fun roomChanged(a: Room, b: Room): Boolean =
        abs(a.x - b.x) > 0.1f || abs(a.y - b.y) > 0.1f || abs(a.width - b.width) > 0.1f || abs(a.height - b.height) > 0.1f

    private fun wallChanged(a: Wall, b: Wall): Boolean =
        abs(a.start.x - b.start.x) > 0.1f || abs(a.start.y - b.start.y) > 0.1f ||
            abs(a.end.x - b.end.x) > 0.1f || abs(a.end.y - b.end.y) > 0.1f

    private fun openingChanged(a: Opening, b: Opening): Boolean =
        abs(a.x - b.x) > 0.1f || abs(a.y - b.y) > 0.1f || a.wallId != b.wallId || abs(a.width - b.width) > 0.1f

    private fun snap(v: Float): Float = (round(v * 2f) / 2f).coerceIn(0f, 100f)

    private fun projectToSegment(px: Float, py: Float, wall: Wall): PlanPoint {
        val ax = wall.start.x; val ay = wall.start.y
        val bx = wall.end.x; val by = wall.end.y
        val dx = bx - ax; val dy = by - ay
        if (abs(dx) < 0.0001f && abs(dy) < 0.0001f) return wall.start
        val t = (((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
        return PlanPoint(ax + t * dx, ay + t * dy)
    }

    private fun segmentT(px: Float, py: Float, wall: Wall): Float {
        val dx = wall.end.x - wall.start.x
        val dy = wall.end.y - wall.start.y
        if (abs(dx) < 0.0001f && abs(dy) < 0.0001f) return 0f
        return (((px - wall.start.x) * dx + (py - wall.start.y) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
    }

    private fun pointToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Double {
        val dx = bx - ax; val dy = by - ay
        if (abs(dx) < 0.0001f && abs(dy) < 0.0001f) return hypot((px - ax).toDouble(), (py - ay).toDouble())
        val t = (((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
        val x = ax + t * dx; val y = ay + t * dy
        return hypot((px - x).toDouble(), (py - y).toDouble())
    }

    private fun fingerprint(plan: FloorPlan): String = buildString {
        plan.rooms.forEach { append("r${it.id}:${it.x},${it.y},${it.width},${it.height};") }
        plan.openings.forEach { append("o${it.id}:${it.x},${it.y},${it.wallId};") }
    }

    private fun rejected(message: String) = PlanProposal(
        message = message,
        updatedPlan = null,
        requiresConfirmation = true,
        confidence = 100
    )

    private fun fmt(v: Double) = "%.1f".format(v)
}