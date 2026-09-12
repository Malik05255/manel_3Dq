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
 * Deterministic geometry layer. HAI may suggest intent, but this engine owns snapping,
 * bounds, overlap checks and direct manipulation feasibility.
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

        val oldOverlap = overlapPairs(current)
        overlapPairs(next).forEach { (key, value) ->
            val before = oldOverlap[key] ?: 0.0
            if (value > 0.02 && value > before + 0.01) errors += "ظهر تداخل هندسي جديد بين ${key.first} و${key.second}"
        }

        val wallsById = next.walls.associateBy { it.id }
        next.openings.forEach { opening ->
            val wall = opening.wallId?.let { wallsById[it] }
            if (wall == null) {
                warnings += "الفتحة ${opening.id} غير مرتبطة بجدار موثوق"
            } else {
                val distance = pointToSegment(opening.x, opening.y, wall.start.x, wall.start.y, wall.end.x, wall.end.y)
                if (distance > 1.5) errors += "الفتحة ${opening.id} لا تقع على الجدار ${wall.id}"
            }
        }

        return Check(errors.isEmpty(), errors.distinct(), warnings.distinct())
    }

    fun moveRoom(plan: FloorPlan, roomId: String, deltaXPct: Float, deltaYPct: Float): PlanProposal {
        val room = plan.rooms.firstOrNull { it.id == roomId }
            ?: return rejected("لم أجد الغرفة المطلوبة")
        if (room.locked) return rejected("الغرفة «${room.name}» مقفلة؛ لن أحركها")

        val moved = room.copy(
            x = snap((room.x + deltaXPct).coerceIn(0f, 100f - room.width)),
            y = snap((room.y + deltaYPct).coerceIn(0f, 100f - room.height))
        )
        val next = plan.copy(rooms = plan.rooms.map { if (it.id == roomId) moved else it })
        return proposalFromDirectEdit(plan, next, room, moved, "MOVE", "نقل مباشر مع محاذاة هندسية")
    }

    fun resizeRoom(plan: FloorPlan, roomId: String, deltaWidthPct: Float, deltaHeightPct: Float): PlanProposal {
        val room = plan.rooms.firstOrNull { it.id == roomId }
            ?: return rejected("لم أجد الغرفة المطلوبة")
        if (room.locked) return rejected("الغرفة «${room.name}» مقفلة؛ لن أغير حجمها")

        val resized = room.copy(
            width = snap((room.width + deltaWidthPct).coerceIn(2f, 100f - room.x)),
            height = snap((room.height + deltaHeightPct).coerceIn(2f, 100f - room.y))
        )
        val nextArea = scaledArea(plan, resized)
        val updated = resized.copy(areaM2 = nextArea ?: room.areaM2)
        val next = plan.copy(rooms = plan.rooms.map { if (it.id == roomId) updated else it })
        return proposalFromDirectEdit(plan, next, room, updated, "RESIZE", "تغيير حجم مباشر مع فحص الأثر")
    }

    fun moveOpening(plan: FloorPlan, openingId: String, targetX: Float, targetY: Float): PlanProposal {
        val opening = plan.openings.firstOrNull { it.id == openingId }
            ?: return rejected("لم أجد الباب أو النافذة المطلوبة")
        if (opening.locked) return rejected("العنصر ${opening.id} مقفل؛ لن أنقله")
        if (plan.walls.isEmpty()) return rejected("لا أستطيع نقل الفتحة بدقة قبل قراءة الجدران")

        val wall = plan.walls.minByOrNull { pointToSegment(targetX, targetY, it.start.x, it.start.y, it.end.x, it.end.y) }
            ?: return rejected("لم أجد جدارًا صالحًا للنقل")
        if (wall.locked) return rejected("الجدار ${wall.id} مقفل؛ لن أنقل فتحة إليه")
        val snapped = projectToSegment(targetX, targetY, wall)
        val moved = opening.copy(x = snap(snapped.x), y = snap(snapped.y), wallId = wall.id)
        val next = plan.copy(openings = plan.openings.map { if (it.id == openingId) moved else it })

        val geometry = verify(plan, next)
        if (!geometry.feasible) return rejected(geometry.errors.joinToString(". "))
        val architect = ArchitecturalEngine.architecturalReview(plan, next)
        if (architect.hasMaterialObjection) {
            return PlanProposal(
                message = architect.objections.joinToString(" "),
                updatedPlan = next,
                changes = listOf(PlanChange(roomName = opening.id, action = "MOVE_OPENING", note = "المحرك لديه اعتراض معماري قبل الاعتماد")),
                requiresConfirmation = true,
                confidence = 82
            )
        }
        return PlanProposal(
            message = "الموقع الجديد مقبول هندسيًا ولم أجد اعتراضًا معماريًا جوهريًا.",
            updatedPlan = next,
            changes = listOf(PlanChange(roomName = opening.id, action = "MOVE_OPENING", note = "نقل الفتحة إلى ${wall.id}")),
            requiresConfirmation = true,
            confidence = 90
        )
    }

    private fun proposalFromDirectEdit(
        current: FloorPlan,
        next: FloorPlan,
        before: Room,
        after: Room,
        action: String,
        note: String
    ): PlanProposal {
        val geometry = verify(current, next)
        if (!geometry.feasible) return rejected(geometry.errors.joinToString(". "))
        val review = ArchitecturalEngine.architecturalReview(current, next)
        val message = if (review.hasMaterialObjection) review.objections.joinToString(" ")
        else "التعديل ممكن هندسيًا ولم أجد اعتراضًا معماريًا جوهريًا."
        return PlanProposal(
            message = message,
            updatedPlan = next,
            changes = listOf(
                PlanChange(
                    roomId = before.id,
                    roomName = before.name,
                    action = action,
                    beforeAreaM2 = before.areaM2.takeIf { it > 0 },
                    afterAreaM2 = after.areaM2.takeIf { it > 0 },
                    note = note
                )
            ),
            requiresConfirmation = true,
            confidence = if (review.hasMaterialObjection) 80 else 93
        )
    }

    private fun rejected(message: String) = PlanProposal(
        message = message,
        updatedPlan = null,
        requiresConfirmation = true,
        confidence = 100
    )

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

    private fun snap(v: Float): Float = (round(v * 2f) / 2f).coerceIn(0f, 100f)

    private fun projectToSegment(px: Float, py: Float, wall: Wall): PlanPoint {
        val ax = wall.start.x; val ay = wall.start.y
        val bx = wall.end.x; val by = wall.end.y
        val dx = bx - ax; val dy = by - ay
        if (abs(dx) < 0.0001f && abs(dy) < 0.0001f) return wall.start
        val t = (((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
        return PlanPoint(ax + t * dx, ay + t * dy)
    }

    private fun pointToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Double {
        val dx = bx - ax; val dy = by - ay
        if (abs(dx) < 0.0001f && abs(dy) < 0.0001f) return hypot((px - ax).toDouble(), (py - ay).toDouble())
        val t = (((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
        val x = ax + t * dx; val y = ay + t * dy
        return hypot((px - x).toDouble(), (py - y).toDouble())
    }

    private fun fmt(v: Double) = "%.1f".format(v)
}