package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.PlanProposal
import com.manzili.hai.model.PlanScore
import com.manzili.hai.model.Room
import com.manzili.hai.model.ValidationReport
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object ArchitecturalEngine {
    data class ArchitectSuggestion(
        val title: String,
        val reason: String,
        val actionKind: String,
        val targetIds: List<String> = emptyList(),
        val priority: Int = 50
    )

    data class ArchitectReview(
        val objections: List<String> = emptyList(),
        val notes: List<String> = emptyList()
    ) {
        val hasMaterialObjection: Boolean get() = objections.isNotEmpty()
    }

    fun score(plan: FloorPlan): PlanScore {
        if (plan.rooms.isEmpty()) return PlanScore(0, 0, 0, 0, 0, listOf("لا توجد غرف كافية للتقييم"))
        val graph = SpatialGraphEngine.analyze(plan)
        val structure = StructuralGeometryEngine.inspect(plan)
        val impact = ArchitecturalImpactEngine.inspect(plan)

        val knownArea = plan.rooms.filter { it.areaM2 > 0 }.sumOf { it.areaM2 }
        val circulationArea = plan.rooms.filter { isCirculation(it) && it.areaM2 > 0 }.sumOf { it.areaM2 }
        val circulationRatio = if (knownArea > 0) circulationArea / knownArea else 0.12
        val circulationImpact = impact.findings.count { it.category == "circulation" }
        val efficiency = (100 - circulationRatio * 185 - circulationImpact * 7).toInt().coerceIn(25, 100)

        val roomConfidence = plan.rooms.map { it.confidence }.average().toInt().coerceIn(0, 100)
        val confidence = if (structure.confidence > 0) {
            (roomConfidence * .70 + structure.confidence * .30).toInt().coerceIn(0, 100)
        } else roomConfidence

        val privacyImpact = impact.findings.count { it.category == "privacy" && it.severity == ArchitecturalImpactEngine.Severity.OBJECTION }
        val privacy = (88 - graph.privacyContacts.size * 8 - privacyImpact * 12).coerceIn(20, 100)

        val structuralPenalty = structure.errors.size * 16 + structure.warnings.size.coerceAtMost(5) * 3
        val impactPenalty = impact.objections.size.coerceAtMost(5) * 5 + impact.notes.size.coerceAtMost(5) * 2
        val geometry = (100 - overlapPenalty(plan) - graph.isolated.size.coerceAtMost(4) * 4 - structuralPenalty - impactPenalty).coerceIn(15, 100)
        val overall = (efficiency * .27 + privacy * .31 + confidence * .16 + geometry * .26).toInt().coerceIn(0, 100)

        val notes = buildList {
            if (circulationRatio > .16) add("نسبة الممرات مرتفعة ويمكن استرداد جزء منها")
            if (graph.privacyContacts.isNotEmpty()) add("هناك علاقة مكانية ضيوف/خاص تحتاج مراجعة")
            if (graph.isolated.isNotEmpty()) add("بعض المساحات تبدو معزولة في القراءة الحالية")
            impact.findings.take(3).forEach { add(it.message) }
            if (confidence < 80) add("بعض العناصر تحتاج تأكيدًا قبل تعديل حساس")
            if (plan.walls.isEmpty()) add("الجدران لم تتحول بعد إلى هندسة خطية موثوقة")
            if (structure.errors.isNotEmpty()) add("بيانات الجدران أو الفتحات تحتوي خطأ هندسيًا")
        }
        return PlanScore(overall, efficiency, privacy, confidence, geometry, notes.distinct())
    }

    fun validate(current: FloorPlan, proposal: PlanProposal): ValidationReport {
        val next = proposal.updatedPlan
            ?: return ValidationReport(false, listOf("لا يوجد مخطط قابل للتطبيق في الاقتراح"), emptyList(), score(current), null)

        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val geometry = GeometrySolver.verify(current, next)
        errors += geometry.errors
        warnings += geometry.warnings

        next.rooms.forEach { room ->
            room.minAreaM2?.let { minArea ->
                if (room.areaM2 > 0 && room.areaM2 + .05 < minArea) {
                    warnings += "${room.name} أقل من الحد التصميمي المحفوظ (${fmt(minArea)}م²)"
                }
            }
        }

        val nextRooms = next.rooms.associateBy { it.id }
        current.rooms.filterNot { it.locked }.forEach { old ->
            if (nextRooms[old.id] == null) warnings += "تم حذف «${old.name}» ويحتاج ذلك موافقة صريحة"
        }

        val structural = StructuralGeometryEngine.compare(current, next)
        errors += structural.errors
        warnings += structural.warnings

        val review = architecturalReview(current, next)
        warnings += review.objections
        warnings += review.notes

        if (proposal.confidence < 65) warnings += "ثقة HAI في الاقتراح منخفضة (${proposal.confidence}%)"
        if (next.uncertainties.isNotEmpty()) warnings += "لا تزال هناك عناصر غير مؤكدة في المخطط"

        val before = score(current)
        val after = score(next)
        if (after.overall + 8 < before.overall) {
            warnings += "انخفض تقييم المخطط من ${before.overall} إلى ${after.overall}"
        }
        return ValidationReport(errors.isEmpty(), errors.distinct(), warnings.distinct(), before, after)
    }

    fun toggleLock(plan: FloorPlan, roomId: String): FloorPlan =
        plan.copy(rooms = plan.rooms.map { if (it.id == roomId) it.copy(locked = !it.locked) else it })

    fun proactiveSuggestions(plan: FloorPlan): List<ArchitectSuggestion> {
        if (plan.rooms.isEmpty()) return emptyList()
        val graph = SpatialGraphEngine.analyze(plan)
        val structure = StructuralGeometryEngine.inspect(plan)
        val impact = ArchitecturalImpactEngine.inspect(plan)
        val out = mutableListOf<ArchitectSuggestion>()

        plan.rooms.filter { !it.locked && it.areaM2 > 0 }.forEach { room ->
            val minArea = room.minAreaM2
            if (minArea != null && room.areaM2 + .05 < minArea) {
                out += ArchitectSuggestion(
                    title = "أقترح تكبير ${room.name}",
                    reason = "مساحتها ${fmt(room.areaM2)}م² أقل من الحد التصميمي المحفوظ ${fmt(minArea)}م².",
                    actionKind = "EXPAND_ROOM",
                    targetIds = listOf(room.id),
                    priority = 97
                )
            }

            val preferred = room.preferredAreaM2
            if (preferred != null && preferred > 0 && room.areaM2 > preferred * 1.22 && !isCoreLiving(room)) {
                val reclaim = (room.areaM2 - preferred).coerceAtMost(room.areaM2 * .18)
                if (reclaim >= 1.0) {
                    out += ArchitectSuggestion(
                        title = "يمكن تصغير ${room.name} قليلًا",
                        reason = "هناك قرابة ${fmt(reclaim)}م² فوق المساحة المفضلة ويمكن استثمارها في فراغ أنفع.",
                        actionKind = "SHRINK_ROOM",
                        targetIds = listOf(room.id),
                        priority = 66
                    )
                }
            }

            if (isCirculation(room) && room.areaM2 >= 7.0) {
                val flexible = graph.candidates.firstOrNull { it.roomName == room.name }?.flexibleAreaM2 ?: 0.0
                if (flexible >= 1.2) {
                    out += ArchitectSuggestion(
                        title = "أقترح اختصار ${room.name}",
                        reason = "يمكن استرداد نحو ${fmt(flexible)}م² إذا حافظنا على وضوح مسار الحركة.",
                        actionKind = "SHRINK_ROOM",
                        targetIds = listOf(room.id),
                        priority = 82
                    )
                }
            }
        }

        impact.findings.take(5).forEach { finding ->
            val firstRoom = finding.targetIds.firstOrNull { id -> plan.rooms.any { it.id == id } }
            val firstOpening = finding.targetIds.firstOrNull { id -> plan.openings.any { it.id == id } }
            val action = when (finding.category) {
                "circulation", "furniture" -> if (firstRoom != null) "EXPAND_ROOM" else "REPLAN"
                "doors", "privacy" -> if (firstOpening != null) "MOVE_OPENING" else "REPLAN_CIRCULATION"
                "daylight" -> "REVIEW_WINDOWS"
                "service" -> "REPLAN_SERVICE"
                else -> "REVIEW"
            }
            out += ArchitectSuggestion(
                title = suggestionTitle(finding),
                reason = finding.message,
                actionKind = action,
                targetIds = listOfNotNull(firstOpening ?: firstRoom),
                priority = if (finding.severity == ArchitecturalImpactEngine.Severity.OBJECTION) 95 else 72
            )
        }

        if (graph.roomsWithoutDoor.isNotEmpty() && structure.doorCount > 0) {
            out += ArchitectSuggestion(
                title = "أقترح مراجعة اتصال بعض المساحات",
                reason = "لا يظهر اتصال باب موثوق لـ ${graph.roomsWithoutDoor.take(3).joinToString("، ")}.",
                actionKind = "REVIEW_DOORS",
                priority = 91
            )
        }

        if (structure.confidence in 1..64) {
            out += ArchitectSuggestion(
                title = "ثبّت قراءة البنية أولًا",
                reason = "ثقة الجدران والفتحات ${structure.confidence}% فقط؛ لا أوصي بنقل حساس قبل تأكيد العناصر المشكوك فيها.",
                actionKind = "VERIFY_STRUCTURE",
                priority = 99
            )
        }

        return out.distinctBy { it.title + it.targetIds.joinToString() }
            .sortedByDescending { it.priority }
            .take(6)
    }

    fun architecturalReview(current: FloorPlan, next: FloorPlan): ArchitectReview {
        val objections = mutableListOf<String>()
        val notes = mutableListOf<String>()

        val impact = ArchitecturalImpactEngine.compare(current, next)
        objections += impact.objections.map { it.message }
        notes += impact.notes.map { it.message }

        val currentRooms = current.rooms.associateBy { it.id }
        val nextRooms = next.rooms.associateBy { it.id }
        currentRooms.values.forEach { old ->
            val fresh = nextRooms[old.id] ?: return@forEach
            val minArea = old.minAreaM2
            if (minArea != null && old.areaM2 + .05 >= minArea && fresh.areaM2 > 0 && fresh.areaM2 + .05 < minArea) {
                objections += "تصغير «${old.name}» إلى ${fmt(fresh.areaM2)}م² يتجاوز الحد التصميمي المحفوظ ${fmt(minArea)}م²؛ لا أوصي به بدون تنازل صريح."
            }
            if (isHabitable(old) && roomAspect(old) <= 2.9 && roomAspect(fresh) > 2.9) {
                objections += "شكل «${old.name}» أصبح ممدودًا أكثر من اللازم؛ المساحة وحدها لا تكفي إذا ضعف التأثيث والحركة."
            }
        }

        val beforeGraph = SpatialGraphEngine.analyze(current)
        val afterGraph = SpatialGraphEngine.analyze(next)
        val newlyNoDoor = afterGraph.roomsWithoutDoor.toSet() - beforeGraph.roomsWithoutDoor.toSet()
        if (newlyNoDoor.isNotEmpty() && afterGraph.doorConnections.isNotEmpty()) {
            objections += "بعد التعديل فقدت ${newlyNoDoor.take(3).joinToString("، ")} اتصال باب واضحًا؛ التوزيع يحتاج إعادة ربط الحركة."
        }

        val before = score(current)
        val after = score(next)
        if (after.privacy + 9 < before.privacy) {
            objections += "الخصوصية تنخفض بوضوح (${before.privacy} ← ${after.privacy})؛ أفضّل بديلًا يحافظ على الفصل بين الضيوف والعائلة."
        }
        if (after.efficiency + 12 < before.efficiency) {
            objections += "الحركة تصبح أقل كفاءة (${before.efficiency} ← ${after.efficiency})؛ التعديل يضيف هدرًا أو دورانًا أكبر من فائدته."
        }

        return ArchitectReview(objections.distinct(), notes.distinct())
    }

    fun compactBrief(plan: FloorPlan): String {
        val s = score(plan)
        val locked = plan.rooms.filter { it.locked }.joinToString("، ") { it.name }.ifBlank { "لا يوجد" }
        val suggestions = proactiveSuggestions(plan).take(4)
            .joinToString(" | ") { "${it.title}: ${it.reason}" }
            .ifBlank { "لا توجد ملاحظة استباقية قوية" }
        return "التقييم ${s.overall}/100، الكفاءة ${s.efficiency}، الخصوصية ${s.privacy}، دقة القراءة ${s.readingConfidence}. المقفل: $locked. ${SpatialGraphEngine.compactBrief(plan)} ${StructuralGeometryEngine.compactBrief(plan)} ${ArchitecturalImpactEngine.compactBrief(plan)} المقترحات المهنية الحالية: $suggestions."
    }

    fun initialArchitectMessage(plan: FloorPlan): String {
        val suggestions = proactiveSuggestions(plan).take(3)
        if (suggestions.isEmpty()) return plan.sourceSummary
        val base = plan.sourceSummary.trim()
        val advice = suggestions.joinToString("\n") { "• ${it.title} — ${it.reason}" }
        return buildString {
            if (base.isNotBlank()) append(base).append("\n\n")
            append("ملاحظاتي الأولى كمراجعة معمارية:\n").append(advice)
        }
    }

    private fun suggestionTitle(f: ArchitecturalImpactEngine.Finding): String = when (f.category) {
        "privacy" -> "أقترح تحسين فصل الحركة والخصوصية"
        "circulation" -> "أقترح معالجة اختناق الحركة"
        "furniture" -> "أقترح تحسين قابلية التأثيث"
        "daylight" -> "أقترح مراجعة النافذة والواجهة"
        "doors" -> "أقترح إعادة تموضع الباب"
        "service" -> "أقترح تحسين علاقة الخدمات"
        else -> "لدي تحسين معماري"
    }

    private fun overlapPenalty(plan: FloorPlan): Int {
        var penalty = 0
        for (i in plan.rooms.indices) for (j in i + 1 until plan.rooms.size) {
            val overlap = overlapRatio(plan.rooms[i], plan.rooms[j])
            if (overlap > .08) penalty += min(15, (overlap * 45).toInt())
        }
        return penalty
    }

    private fun overlapRatio(a: Room, b: Room): Double {
        val left = max(a.x, b.x)
        val top = max(a.y, b.y)
        val right = min(a.x + a.width, b.x + b.width)
        val bottom = min(a.y + a.height, b.y + b.height)
        if (right <= left || bottom <= top) return 0.0
        val base = min(a.width * a.height, b.width * b.height).coerceAtLeast(.01f)
        return (((right - left) * (bottom - top)) / base).toDouble()
    }

    private fun roomAspect(room: Room): Double {
        val short = min(room.width, room.height).coerceAtLeast(.01f)
        val long = max(room.width, room.height)
        return (long / short).toDouble()
    }

    private fun roomText(room: Room) = (room.type + " " + room.name).lowercase()
    private fun isCirculation(room: Room) = listOf("corridor", "hallway", "passage", "ممر", "مدخل", "لوبي").any { roomText(room).contains(it) }
    private fun isCoreLiving(room: Room) = listOf("majlis", "مجلس", "family", "عائل", "living", "صالة").any { roomText(room).contains(it) }
    private fun isHabitable(room: Room) = listOf("bedroom", "master", "majlis", "guest", "living", "family", "نوم", "مجلس", "صالة", "عائل").any { roomText(room).contains(it) }
    private fun fmt(v: Double) = "%.1f".format(v)
}
