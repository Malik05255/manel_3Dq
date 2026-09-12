package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.PlanProposal
import com.manzili.hai.model.PlanScore
import com.manzili.hai.model.Room
import com.manzili.hai.model.ValidationReport
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

object ArchitecturalEngine {

    fun score(plan: FloorPlan): PlanScore {
        if (plan.rooms.isEmpty()) {
            return PlanScore(0, 0, 0, 0, 0, listOf("لا توجد غرف كافية للتقييم"))
        }

        val knownArea = plan.rooms.filter { it.areaM2 > 0.0 }.sumOf { it.areaM2 }
        val circulationArea = plan.rooms.filter { isCirculation(it) && it.areaM2 > 0.0 }.sumOf { it.areaM2 }
        val circulationRatio = if (knownArea > 0) circulationArea / knownArea else 0.12
        val efficiency = (100.0 - circulationRatio * 190.0).toInt().coerceIn(35, 100)

        val confidence = plan.rooms.map { it.confidence }.average().toInt().coerceIn(0, 100)
        val privacy = privacyScore(plan)
        val geometry = geometryScore(plan)
        val overall = (
            efficiency * 0.30 +
                privacy * 0.30 +
                confidence * 0.15 +
                geometry * 0.25
            ).toInt().coerceIn(0, 100)

        val notes = buildList {
            if (circulationRatio > 0.16) add("نسبة الممرات مرتفعة ويمكن محاولة استرداد مساحة منها")
            if (privacy < 70) add("الفصل بين مسار الضيوف والمنطقة العائلية يحتاج مراجعة")
            if (confidence < 80) add("بعض عناصر المخطط تحتاج تأكيدًا قبل تعديلها")
            if (geometry < 85) add("هناك تداخلات هندسية في تمثيل المخطط تحتاج مراجعة")
        }
        return PlanScore(overall, efficiency, privacy, confidence, geometry, notes)
    }

    fun validate(current: FloorPlan, proposal: PlanProposal): ValidationReport {
        val next = proposal.updatedPlan
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        if (next == null) {
            return ValidationReport(false, listOf("الاقتراح لا يحتوي مخططًا هندسيًا قابلًا للتطبيق"), emptyList(), score(current), null)
        }

        next.rooms.forEach { room ->
            if (room.width <= 0f || room.height <= 0f) errors += "أبعاد ${room.name} غير صالحة"
            if (room.x < 0f || room.y < 0f || room.x + room.width > 100.2f || room.y + room.height > 100.2f) {
                errors += "${room.name} خرجت خارج حدود المبنى"
            }
            if (room.areaM2 < 0) errors += "مساحة ${room.name} سالبة"
            room.minAreaM2?.let { minimum ->
                if (room.areaM2 > 0 && room.areaM2 + 0.05 < minimum) warnings += "${room.name} أصبحت أقل من الحد المفضّل المحفوظ (${format(minimum)}م²)"
            }
        }

        val nextById = next.rooms.associateBy { it.id }
        current.rooms.filter { it.locked }.forEach { old ->
            val fresh = nextById[old.id]
            if (fresh == null) {
                errors += "الغرفة المقفلة «${old.name}» اختفت من الاقتراح"
            } else if (!sameGeometry(old, fresh) || areaChanged(old, fresh)) {
                errors += "تم تغيير «${old.name}» رغم أنها مقفلة"
            }
        }

        current.rooms.filterNot { it.locked }.forEach { old ->
            if (nextById[old.id] == null) warnings += "الاقتراح حذف «${old.name}»؛ يحتاج موافقة صريحة"
        }

        val currentOverlap = overlapMap(current)
        val nextOverlap = overlapMap(next)
        nextOverlap.forEach { (pair, ratio) ->
            val before = currentOverlap[pair] ?: 0.0
            if (ratio > 0.02 && ratio > before + 0.015) {
                errors += "ظهر تداخل جديد بين ${pair.first} و${pair.second}"
            }
        }

        val footprint = if (next.widthM != null && next.heightM != null) next.widthM * next.heightM else null
        val knownAreas = next.rooms.filter { it.areaM2 > 0 }.sumOf { it.areaM2 }
        if (footprint != null && knownAreas > footprint * 1.08) {
            warnings += "مجموع المساحات المقروءة أكبر من بصمة المبنى؛ راجع الأبعاد قبل الاعتماد"
        }

        if (proposal.confidence < 65) warnings += "ثقة HAI في هذا التعديل منخفضة (${proposal.confidence}%)"
        if (next.uncertainties.isNotEmpty()) warnings += "لا تزال هناك عناصر غير مؤكدة في المخطط"

        val beforeScore = score(current)
        val afterScore = score(next)
        if (afterScore.overall + 8 < beforeScore.overall) {
            warnings += "التقييم المعماري الإجمالي انخفض من ${beforeScore.overall} إلى ${afterScore.overall}"
        }
        return ValidationReport(errors.isEmpty(), errors.distinct(), warnings.distinct(), beforeScore, afterScore)
    }

    fun toggleLock(plan: FloorPlan, roomId: String): FloorPlan {
        return plan.copy(rooms = plan.rooms.map { if (it.id == roomId) it.copy(locked = !it.locked) else it })
    }

    fun compactBrief(plan: FloorPlan): String {
        val s = score(plan)
        val locked = plan.rooms.filter { it.locked }.joinToString("، ") { it.name }.ifBlank { "لا يوجد" }
        return "التقييم ${s.overall}/100، الكفاءة ${s.efficiency}، الخصوصية ${s.privacy}، دقة القراءة ${s.readingConfidence}. العناصر المقفلة: $locked."
    }

    private fun privacyScore(plan: FloorPlan): Int {
        val guests = plan.rooms.filter { isGuest(it) }
        val privateRooms = plan.rooms.filter { isPrivate(it) }
        if (guests.isEmpty() || privateRooms.isEmpty()) return 78

        val distances = guests.flatMap { g ->
            privateRooms.map { p ->
                val gx = g.x + g.width / 2f
                val gy = g.y + g.height / 2f
                val px = p.x + p.width / 2f
                val py = p.y + p.height / 2f
                hypot((gx - px).toDouble(), (gy - py).toDouble())
            }
        }
        val averageDistance = distances.average()
        val separation = (averageDistance / 55.0).coerceIn(0.0, 1.0)
        return (55 + separation * 45).toInt().coerceIn(45, 100)
    }

    private fun geometryScore(plan: FloorPlan): Int {
        var penalty = 0
        for (i in plan.rooms.indices) {
            val a = plan.rooms[i]
            if (a.x < 0 || a.y < 0 || a.x + a.width > 100.2 || a.y + a.height > 100.2) penalty += 15
            for (j in i + 1 until plan.rooms.size) {
                val b = plan.rooms[j]
                val ratio = overlapRatio(a, b)
                if (ratio > 0.08) penalty += min(15, (ratio * 45).toInt())
            }
        }
        return (100 - penalty).coerceIn(20, 100)
    }

    private fun overlapMap(plan: FloorPlan): Map<Pair<String, String>, Double> {
        val out = mutableMapOf<Pair<String, String>, Double>()
        for (i in plan.rooms.indices) for (j in i + 1 until plan.rooms.size) {
            val a = plan.rooms[i]
            val b = plan.rooms[j]
            val ratio = overlapRatio(a, b)
            if (ratio > 0.0) {
                val names = listOf(a.name, b.name).sorted()
                out[names[0] to names[1]] = ratio
            }
        }
        return out
    }

    private fun overlapRatio(a: Room, b: Room): Double {
        val left = max(a.x, b.x)
        val top = max(a.y, b.y)
        val right = min(a.x + a.width, b.x + b.width)
        val bottom = min(a.y + a.height, b.y + b.height)
        if (right <= left || bottom <= top) return 0.0
        val overlap = (right - left) * (bottom - top)
        val minArea = min(a.width * a.height, b.width * b.height).coerceAtLeast(0.01f)
        return (overlap / minArea).toDouble()
    }

    private fun sameGeometry(a: Room, b: Room): Boolean {
        return abs(a.x - b.x) < 0.15f && abs(a.y - b.y) < 0.15f && abs(a.width - b.width) < 0.15f && abs(a.height - b.height) < 0.15f
    }

    private fun areaChanged(a: Room, b: Room): Boolean {
        if (a.areaM2 <= 0 || b.areaM2 <= 0) return false
        return abs(a.areaM2 - b.areaM2) > 0.08
    }

    private fun isCirculation(room: Room): Boolean {
        val s = (room.type + " " + room.name).lowercase()
        return listOf("corridor", "hallway", "passage", "ممر", "مدخل", "لوبي").any { s.contains(it) }
    }

    private fun isGuest(room: Room): Boolean {
        val s = (room.type + " " + room.name).lowercase()
        return listOf("majlis", "guest", "reception", "مجلس", "ضيوف", "استقبال").any { s.contains(it) }
    }

    private fun isPrivate(room: Room): Boolean {
        val s = (room.type + " " + room.name).lowercase()
        return listOf("bedroom", "master", "family", "نوم", "عائل", "ماستر").any { s.contains(it) }
    }

    private fun format(value: Double) = "%.1f".format(value)
}
