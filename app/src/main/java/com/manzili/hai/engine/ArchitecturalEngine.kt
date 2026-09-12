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
    fun score(plan: FloorPlan): PlanScore {
        if (plan.rooms.isEmpty()) return PlanScore(0, 0, 0, 0, 0, listOf("لا توجد غرف كافية للتقييم"))
        val graph = SpatialGraphEngine.analyze(plan)
        val structure = StructuralGeometryEngine.inspect(plan)
        val known = plan.rooms.filter { it.areaM2 > 0 }.sumOf { it.areaM2 }
        val circulation = plan.rooms.filter { circulation(it) && it.areaM2 > 0 }.sumOf { it.areaM2 }
        val ratio = if (known > 0) circulation / known else 0.12
        val efficiency = (100 - ratio * 190).toInt().coerceIn(35, 100)
        val roomConfidence = plan.rooms.map { it.confidence }.average().toInt().coerceIn(0, 100)
        val confidence = if (structure.confidence > 0) ((roomConfidence * .72) + (structure.confidence * .28)).toInt() else roomConfidence
        val privacy = (82 - graph.privacyContacts.size * 10).coerceIn(35, 100)
        val structuralPenalty = structure.errors.size * 15 + structure.warnings.size.coerceAtMost(4) * 3
        val geometry = (100 - overlapPenalty(plan) - graph.isolated.size.coerceAtMost(4) * 4 - structuralPenalty).coerceIn(20, 100)
        val overall = (efficiency * .30 + privacy * .30 + confidence * .15 + geometry * .25).toInt().coerceIn(0, 100)
        val notes = buildList {
            if (ratio > .16) add("نسبة الممرات مرتفعة ويمكن استرداد جزء منها")
            if (graph.privacyContacts.isNotEmpty()) add("هناك تلامس مكاني بين منطقة ضيوف ومنطقة خاصة")
            if (graph.isolated.isNotEmpty()) add("بعض المساحات تبدو معزولة في القراءة الحالية")
            if (confidence < 80) add("بعض العناصر تحتاج تأكيدًا قبل التعديل")
            if (plan.walls.isEmpty()) add("الجدران لم تتحول بعد إلى هندسة خطية موثوقة")
            if (structure.errors.isNotEmpty()) add("بيانات الجدران أو الفتحات تحتوي خطأ هندسيًا")
            if (geometry < 85) add("التمثيل الهندسي يحتاج مراجعة قبل الاعتماد")
        }
        return PlanScore(overall, efficiency, privacy, confidence, geometry, notes)
    }

    fun validate(current: FloorPlan, proposal: PlanProposal): ValidationReport {
        val next = proposal.updatedPlan ?: return ValidationReport(false, listOf("لا يوجد مخطط قابل للتطبيق في الاقتراح"), emptyList(), score(current), null)
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        next.rooms.forEach { room ->
            if (room.width <= 0 || room.height <= 0) errors += "أبعاد ${room.name} غير صالحة"
            if (room.x < 0 || room.y < 0 || room.x + room.width > 100.2 || room.y + room.height > 100.2) errors += "${room.name} خارج حدود المخطط"
            room.minAreaM2?.let { if (room.areaM2 > 0 && room.areaM2 + .05 < it) warnings += "${room.name} أقل من الحد المحفوظ (${fmt(it)}م²)" }
        }
        val byId = next.rooms.associateBy { it.id }
        current.rooms.filter { it.locked }.forEach { old ->
            val fresh = byId[old.id]
            if (fresh == null) errors += "الغرفة المقفلة «${old.name}» حذفت"
            else if (changed(old, fresh)) errors += "الغرفة المقفلة «${old.name}» تغيرت"
        }
        current.rooms.filterNot { it.locked }.forEach { old -> if (byId[old.id] == null) warnings += "تم حذف «${old.name}» ويحتاج ذلك موافقة صريحة" }
        val beforeOverlap = overlapMap(current)
        overlapMap(next).forEach { (pair, value) ->
            val old = beforeOverlap[pair] ?: 0.0
            if (value > .02 && value > old + .015) errors += "ظهر تداخل جديد بين ${pair.first} و${pair.second}"
        }
        val beforeGraph = SpatialGraphEngine.analyze(current)
        val afterGraph = SpatialGraphEngine.analyze(next)
        val newPrivacy = afterGraph.privacyContacts.filterNot { it in beforeGraph.privacyContacts }
        if (newPrivacy.isNotEmpty()) warnings += "ظهر تلامس جديد بين الضيوف والمنطقة الخاصة: ${newPrivacy.joinToString("، ")}"
        if (afterGraph.isolated.size > beforeGraph.isolated.size) warnings += "زاد عدد المساحات المعزولة في التعديل"

        val structure = StructuralGeometryEngine.compare(current, next)
        errors += structure.errors
        warnings += structure.warnings

        if (proposal.confidence < 65) warnings += "ثقة HAI في الاقتراح منخفضة (${proposal.confidence}%)"
        if (next.uncertainties.isNotEmpty()) warnings += "لا تزال هناك عناصر غير مؤكدة"
        val before = score(current)
        val after = score(next)
        if (after.overall + 8 < before.overall) warnings += "انخفض تقييم المخطط من ${before.overall} إلى ${after.overall}"
        return ValidationReport(errors.isEmpty(), errors.distinct(), warnings.distinct(), before, after)
    }

    fun toggleLock(plan: FloorPlan, roomId: String) = plan.copy(rooms = plan.rooms.map { if (it.id == roomId) it.copy(locked = !it.locked) else it })

    fun compactBrief(plan: FloorPlan): String {
        val s = score(plan)
        val locked = plan.rooms.filter { it.locked }.joinToString("، ") { it.name }.ifBlank { "لا يوجد" }
        return "التقييم ${s.overall}/100، الكفاءة ${s.efficiency}، الخصوصية ${s.privacy}، دقة القراءة ${s.readingConfidence}. المقفل: $locked. ${SpatialGraphEngine.compactBrief(plan)} ${StructuralGeometryEngine.compactBrief(plan)}"
    }

    private fun changed(a: Room, b: Room): Boolean =
        abs(a.x - b.x) > .15f || abs(a.y - b.y) > .15f || abs(a.width - b.width) > .15f || abs(a.height - b.height) > .15f ||
            (a.areaM2 > 0 && b.areaM2 > 0 && abs(a.areaM2 - b.areaM2) > .08)

    private fun overlapPenalty(plan: FloorPlan): Int {
        var penalty = 0
        for (i in plan.rooms.indices) for (j in i + 1 until plan.rooms.size) {
            val r = overlap(plan.rooms[i], plan.rooms[j])
            if (r > .08) penalty += min(15, (r * 45).toInt())
        }
        return penalty
    }

    private fun overlapMap(plan: FloorPlan): Map<Pair<String, String>, Double> {
        val out = mutableMapOf<Pair<String, String>, Double>()
        for (i in plan.rooms.indices) for (j in i + 1 until plan.rooms.size) {
            val a = plan.rooms[i]; val b = plan.rooms[j]; val r = overlap(a, b)
            if (r > 0) { val n = listOf(a.name, b.name).sorted(); out[n[0] to n[1]] = r }
        }
        return out
    }

    private fun overlap(a: Room, b: Room): Double {
        val left = max(a.x, b.x); val top = max(a.y, b.y)
        val right = min(a.x + a.width, b.x + b.width); val bottom = min(a.y + a.height, b.y + b.height)
        if (right <= left || bottom <= top) return 0.0
        return (((right - left) * (bottom - top)) / min(a.width * a.height, b.width * b.height).coerceAtLeast(.01f)).toDouble()
    }

    private fun circulation(r: Room): Boolean {
        val s = (r.type + " " + r.name).lowercase()
        return listOf("corridor", "hallway", "passage", "ممر", "مدخل", "لوبي").any { s.contains(it) }
    }
    private fun fmt(v: Double) = "%.1f".format(v)
}
