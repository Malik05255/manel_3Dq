package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.Opening
import com.manzili.hai.model.ProjectConstraint
import com.manzili.hai.model.Room
import kotlin.math.abs
import kotlin.math.max

/**
 * Persistent project rules extracted from the owner's language.
 * Rules are deterministic state, not conversational context.
 */
object ProjectMemoryEngine {
    const val LOCK_ELEMENT = "LOCK_ELEMENT"
    const val MIN_ROOM_AREA = "MIN_ROOM_AREA"
    const val GUEST_ENTRANCE_INDEPENDENT = "GUEST_ENTRANCE_INDEPENDENT"
    const val PRIORITY_PRIVACY = "PRIORITY_PRIVACY"
    const val PRIORITY_CIRCULATION = "PRIORITY_CIRCULATION"
    const val PRIORITY_DAYLIGHT = "PRIORITY_DAYLIGHT"

    data class Capture(
        val plan: FloorPlan,
        val added: List<ProjectConstraint>,
        val message: String,
        val memoryOnly: Boolean
    )

    data class Review(
        val objections: List<String> = emptyList(),
        val notes: List<String> = emptyList()
    ) {
        val hasObjection: Boolean get() = objections.isNotEmpty()
    }

    fun capture(
        plan: FloorPlan,
        userText: String,
        selectedKind: String? = null,
        selectedId: String? = null
    ): Capture {
        val text = normalize(userText)
        if (text.isBlank()) return Capture(plan, emptyList(), "", false)
        val added = mutableListOf<ProjectConstraint>()
        val mentionedRooms = mentionedRooms(plan, text)
        val selectedTargets = if (selectedKind != null && selectedId != null && refersToSelected(text)) listOf(selectedId) else emptyList()

        if (containsAny(text, "لا تغير", "لا تعدل", "لا تلمس", "ممنوع تغي", "ثبته", "ثبّته", "خله ثابت", "خليه ثابت", "يبقى ثابت", "يكون ثابت")) {
            val ids = (mentionedRooms.map { it.id } + selectedTargets).distinct()
            if (ids.isNotEmpty()) {
                added += ProjectConstraint(
                    id = stableId(LOCK_ELEMENT, ids),
                    kind = LOCK_ELEMENT,
                    text = userText.trim(),
                    targetIds = ids,
                    hard = true,
                    priority = 100
                )
            }
        }

        if (containsAny(text, "لا يصغر", "لا تصغر", "ممنوع يصغر", "ما يصغر", "لا تقلل", "لا تنقص", "لا تقل مساح")) {
            val targets = if (mentionedRooms.isNotEmpty()) mentionedRooms else selectedRoom(plan, selectedKind, selectedId)?.let(::listOf).orEmpty()
            targets.forEach { room ->
                if (room.areaM2 > 0.0) {
                    added += ProjectConstraint(
                        id = stableId(MIN_ROOM_AREA, listOf(room.id)),
                        kind = MIN_ROOM_AREA,
                        text = userText.trim(),
                        targetIds = listOf(room.id),
                        value = room.areaM2,
                        hard = true,
                        priority = 100
                    )
                }
            }
        }

        if (text.contains("مدخل") && containsAny(text, "ضيف", "ضيوف", "مجلس") && containsAny(text, "مستقل", "منفصل", "خاص")) {
            added += ProjectConstraint(
                id = "guest-entrance-independent",
                kind = GUEST_ENTRANCE_INDEPENDENT,
                text = userText.trim(),
                hard = true,
                priority = 100
            )
        }

        if (text.contains("الخصوصي") && containsAny(text, "اهم", "أهم", "اولوي", "أولوي", "قبل", "مهم")) {
            added += ProjectConstraint(
                id = "priority-privacy",
                kind = PRIORITY_PRIVACY,
                text = userText.trim(),
                value = 100.0,
                hard = false,
                priority = 98
            )
        }
        if (containsAny(text, "الممرات أهم", "الحركة أهم", "سهولة الحركة", "تقليل الممرات") && !text.contains("ليست")) {
            added += ProjectConstraint(
                id = "priority-circulation",
                kind = PRIORITY_CIRCULATION,
                text = userText.trim(),
                value = 95.0,
                hard = false,
                priority = 92
            )
        }
        if (containsAny(text, "الإضاءة أهم", "الاضاءة اهم", "الإضاءة الطبيعية أولوية", "الاضاءة الطبيعية اولوي")) {
            added += ProjectConstraint(
                id = "priority-daylight",
                kind = PRIORITY_DAYLIGHT,
                text = userText.trim(),
                value = 95.0,
                hard = false,
                priority = 92
            )
        }

        if (added.isEmpty()) return Capture(plan, emptyList(), "", false)
        val merged = mergeConstraints(plan.constraints, added)
        val updated = reconcile(plan.copy(constraints = merged))
        val labels = added.distinctBy { it.id }.joinToString("، ") { humanLabel(updated, it) }
        val memoryOnly = !containsPositiveEditIntent(text)
        return Capture(
            plan = updated,
            added = added.distinctBy { it.id },
            message = "ثبتّها كقاعدة للمشروع: $labels. سأحترمها في الاقتراحات والسحب والبدائل القادمة.",
            memoryOnly = memoryOnly
        )
    }

    fun carryForward(current: FloorPlan, next: FloorPlan): FloorPlan {
        val constraints = mergeConstraints(next.constraints, current.constraints)
        val prefs = next.preferences.copy(
            privacyPriority = max(next.preferences.privacyPriority, priorityValue(constraints, PRIORITY_PRIVACY, current.preferences.privacyPriority)),
            circulationPriority = max(next.preferences.circulationPriority, priorityValue(constraints, PRIORITY_CIRCULATION, current.preferences.circulationPriority)),
            daylightPriority = max(next.preferences.daylightPriority, priorityValue(constraints, PRIORITY_DAYLIGHT, current.preferences.daylightPriority)),
            notes = (next.preferences.notes + current.preferences.notes + constraints.map { it.text }).filter { it.isNotBlank() }.distinct()
        )
        return reconcile(next.copy(constraints = constraints, preferences = prefs))
    }

    fun reconcile(plan: FloorPlan): FloorPlan {
        val active = plan.constraints.filter { it.active }
        val lockIds = active.filter { it.kind == LOCK_ELEMENT }.flatMap { it.targetIds }.toSet()
        val mins = active.filter { it.kind == MIN_ROOM_AREA && it.value != null }
            .flatMap { c -> c.targetIds.map { it to c.value!! } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, values) -> values.maxOrNull() ?: 0.0 }

        val rooms = plan.rooms.map { room ->
            val minFromMemory = mins[room.id]
            room.copy(
                locked = room.locked || room.id in lockIds,
                minAreaM2 = when {
                    minFromMemory == null -> room.minAreaM2
                    room.minAreaM2 == null -> minFromMemory
                    else -> max(room.minAreaM2, minFromMemory)
                }
            )
        }
        val walls = plan.walls.map { if (it.id in lockIds) it.copy(locked = true) else it }
        val openings = plan.openings.map { if (it.id in lockIds) it.copy(locked = true) else it }
        val prefs = plan.preferences.copy(
            privacyPriority = max(plan.preferences.privacyPriority, priorityValue(active, PRIORITY_PRIVACY, plan.preferences.privacyPriority)),
            circulationPriority = max(plan.preferences.circulationPriority, priorityValue(active, PRIORITY_CIRCULATION, plan.preferences.circulationPriority)),
            daylightPriority = max(plan.preferences.daylightPriority, priorityValue(active, PRIORITY_DAYLIGHT, plan.preferences.daylightPriority)),
            notes = (plan.preferences.notes + active.map { it.text }).filter { it.isNotBlank() }.distinct()
        )
        return plan.copy(rooms = rooms, walls = walls, openings = openings, preferences = prefs)
    }

    fun review(base: FloorPlan, proposedRaw: FloorPlan): Review {
        val proposed = carryForward(base, proposedRaw)
        val objections = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val active = base.constraints.filter { it.active }
        val baseRooms = base.rooms.associateBy { it.id }
        val nextRooms = proposed.rooms.associateBy { it.id }
        val baseWalls = base.walls.associateBy { it.id }
        val nextWalls = proposed.walls.associateBy { it.id }
        val baseOpenings = base.openings.associateBy { it.id }
        val nextOpenings = proposed.openings.associateBy { it.id }

        active.forEach { c ->
            when (c.kind) {
                LOCK_ELEMENT -> c.targetIds.forEach { id ->
                    when {
                        id in baseRooms -> {
                            val a = baseRooms[id]
                            val b = nextRooms[id]
                            if (a == null || b == null || roomChanged(a, b)) objections += "قاعدة المشروع تمنع تغيير «${a?.name ?: id}»."
                        }
                        id in baseWalls -> {
                            val a = baseWalls[id]
                            val b = nextWalls[id]
                            if (a == null || b == null || a.start != b.start || a.end != b.end) objections += "قاعدة المشروع تمنع تغيير الجدار $id."
                        }
                        id in baseOpenings -> {
                            val a = baseOpenings[id]
                            val b = nextOpenings[id]
                            if (a == null || b == null || openingChanged(a, b)) objections += "قاعدة المشروع تمنع تغيير ${openingLabel(a, id)}."
                        }
                    }
                }
                MIN_ROOM_AREA -> {
                    val minimum = c.value ?: return@forEach
                    c.targetIds.forEach { id ->
                        val room = nextRooms[id]
                        if (room == null) objections += "قاعدة المشروع تمنع حذف الغرفة المرتبطة بالحد الأدنى للمساحة."
                        else if (room.areaM2 > 0.0 && room.areaM2 + .05 < minimum) {
                            objections += "قاعدة المشروع: «${room.name}» ممنوع تصغر عن ${fmt(minimum)}م²، والمقترح ${fmt(room.areaM2)}م²."
                        }
                    }
                }
                GUEST_ENTRANCE_INDEPENDENT -> {
                    if (!hasIndependentGuestEntrance(proposed)) {
                        objections += "قاعدة المشروع تشترط مدخل ضيوف/مجلس مستقلًا على جدار خارجي؛ التعديل الحالي لا يحافظ على ذلك بوضوح."
                    }
                }
            }
        }
        if (active.any { it.kind == PRIORITY_PRIVACY } && proposed.preferences.privacyPriority < 95) {
            notes += "الخصوصية مسجلة كأولوية عليا للمشروع."
        }
        return Review(objections.distinct(), notes.distinct())
    }

    fun enforceProposal(base: FloorPlan, proposal: com.manzili.hai.model.PlanProposal): com.manzili.hai.model.PlanProposal {
        val nextRaw = proposal.updatedPlan ?: return proposal
        val next = carryForward(base, nextRaw)
        val review = review(base, next)
        return if (review.hasObjection) {
            proposal.copy(
                message = buildString {
                    if (proposal.message.isNotBlank()) append(proposal.message).append("\n\n")
                    append("اعتراض ذاكرة المشروع: ").append(review.objections.joinToString(" "))
                    append(" لن أعرض تعديلًا يكسر قاعدة سبق أن ثبتّها.")
                },
                updatedPlan = null,
                confidence = 100
            )
        } else proposal.copy(updatedPlan = next)
    }

    fun compactBrief(plan: FloorPlan): String {
        val active = plan.constraints.filter { it.active }.sortedByDescending { it.priority }
        if (active.isEmpty()) return "لا توجد قواعد مشروع دائمة محفوظة."
        return "قواعد المشروع الدائمة (${active.size}): " + active.take(8).joinToString(" | ") { humanLabel(plan, it) }
    }

    private fun mergeConstraints(a: List<ProjectConstraint>, b: List<ProjectConstraint>): List<ProjectConstraint> {
        val map = linkedMapOf<String, ProjectConstraint>()
        (a + b).forEach { c -> map[c.id] = c }
        return map.values.toList()
    }

    private fun priorityValue(constraints: List<ProjectConstraint>, kind: String, fallback: Int): Int =
        constraints.filter { it.active && it.kind == kind }.mapNotNull { it.value?.toInt() }.maxOrNull() ?: fallback

    private fun hasIndependentGuestEntrance(plan: FloorPlan): Boolean {
        val guestIds = plan.rooms.filter(::isGuestRoom).map { it.id }.toSet()
        if (guestIds.isEmpty()) return false
        val walls = plan.walls.associateBy { it.id }
        return plan.openings.any { o ->
            isDoor(o) && o.connectsRoomIds.any { it in guestIds } && o.wallId?.let { walls[it]?.kind?.lowercase() == "external" } == true
        }
    }

    private fun mentionedRooms(plan: FloorPlan, text: String): List<Room> = plan.rooms.filter { room ->
        val name = normalize(room.name)
        name.length >= 3 && text.contains(name) || semanticAliases(room).any { text.contains(it) }
    }

    private fun selectedRoom(plan: FloorPlan, kind: String?, id: String?): Room? =
        if (kind == "room" && id != null) plan.rooms.firstOrNull { it.id == id } else null

    private fun semanticAliases(room: Room): List<String> {
        val n = normalize(room.name + " " + room.type)
        return buildList {
            if (containsAny(n, "مجلس", "majlis", "guest")) add("المجلس")
            if (containsAny(n, "صاله", "صالة", "living")) { add("الصاله"); add("الصالة") }
            if (containsAny(n, "مطبخ", "kitchen")) add("المطبخ")
            if (containsAny(n, "درج", "سلم", "stair")) { add("الدرج"); add("السلم") }
            if (containsAny(n, "غرفه نوم", "غرفة نوم", "bedroom")) { add("غرفة النوم"); add("غرفه النوم") }
        }
    }

    private fun refersToSelected(text: String): Boolean = containsAny(text, "هذا", "هذي", "هذه", "العنصر", "الجدار", "الباب", "النافذه", "النافذة", "الغرفه", "الغرفة")

    private fun containsPositiveEditIntent(text: String): Boolean = containsAny(
        text,
        "كبر ", "كبّر ", "صغر ", "صغّر ", "انقل ", "حرك ", "حرّك ", "اضف ", "أضف ", "احذف ", "وسع ", "وسّع ", "قلص ", "قلّص ", "غير مكان", "غيّر مكان", "بدل مكان", "بدّل مكان"
    )

    private fun roomChanged(a: Room, b: Room): Boolean =
        abs(a.x - b.x) > .05f || abs(a.y - b.y) > .05f || abs(a.width - b.width) > .05f || abs(a.height - b.height) > .05f || abs(a.areaM2 - b.areaM2) > .05

    private fun openingChanged(a: Opening, b: Opening): Boolean =
        abs(a.x - b.x) > .05f || abs(a.y - b.y) > .05f || abs(a.width - b.width) > .05f || a.wallId != b.wallId || a.connectsRoomIds != b.connectsRoomIds

    private fun openingLabel(o: Opening?, fallback: String): String {
        if (o == null) return fallback
        val window = o.type.lowercase().contains("window") || o.type.contains("ناف")
        return if (window) "النافذة ${o.id}" else "الباب ${o.id}"
    }

    private fun humanLabel(plan: FloorPlan, c: ProjectConstraint): String = when (c.kind) {
        LOCK_ELEMENT -> {
            val names = c.targetIds.map { id -> plan.rooms.firstOrNull { it.id == id }?.name ?: id }.joinToString("، ")
            "عدم تغيير $names"
        }
        MIN_ROOM_AREA -> {
            val room = c.targetIds.firstOrNull()?.let { id -> plan.rooms.firstOrNull { it.id == id } }
            "${room?.name ?: "الغرفة"} لا تصغر عن ${c.value?.let(::fmt) ?: "المساحة الحالية"}م²"
        }
        GUEST_ENTRANCE_INDEPENDENT -> "مدخل الضيوف/المجلس مستقل"
        PRIORITY_PRIVACY -> "الخصوصية أولوية عليا"
        PRIORITY_CIRCULATION -> "سهولة الحركة وتقليل الممرات أولوية"
        PRIORITY_DAYLIGHT -> "الإضاءة الطبيعية أولوية"
        else -> c.text
    }

    private fun isGuestRoom(room: Room): Boolean {
        val n = normalize(room.name + " " + room.type)
        return containsAny(n, "مجلس", "ضيوف", "ضيف", "majlis", "guest")
    }

    private fun isDoor(o: Opening): Boolean {
        val n = normalize(o.type)
        return !n.contains("window") && !n.contains("ناف")
    }

    private fun stableId(kind: String, targets: List<String>): String = kind.lowercase() + ":" + targets.sorted().joinToString(":")

    private fun normalize(raw: String): String = raw.lowercase()
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
        .replace('ة', 'ه').replace('ى', 'ي')
        .replace("ـ", "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun containsAny(text: String, vararg terms: String): Boolean = terms.any { text.contains(normalize(it)) }
    private fun fmt(v: Double): String = if (abs(v - v.toInt()) < .05) v.toInt().toString() else "%.1f".format(v)
}
