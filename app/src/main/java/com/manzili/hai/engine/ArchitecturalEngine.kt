package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.Opening
import com.manzili.hai.model.PlanChange
import com.manzili.hai.model.PlanPoint
import com.manzili.hai.model.PlanProposal
import com.manzili.hai.model.PlanScore
import com.manzili.hai.model.Room
import com.manzili.hai.model.ValidationReport
import com.manzili.hai.model.Wall
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
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

        val architectReview = architecturalReview(current, next)
        warnings += architectReview.objections
        warnings += architectReview.notes

        if (proposal.confidence < 65) warnings += "ثقة HAI في الاقتراح منخفضة (${proposal.confidence}%)"
        if (next.uncertainties.isNotEmpty()) warnings += "لا تزال هناك عناصر غير مؤكدة"
        val before = score(current)
        val after = score(next)
        if (after.overall + 8 < before.overall) warnings += "انخفض تقييم المخطط من ${before.overall} إلى ${after.overall}"
        return ValidationReport(errors.isEmpty(), errors.distinct(), warnings.distinct(), before, after)
    }

    fun toggleLock(plan: FloorPlan, roomId: String) = plan.copy(rooms = plan.rooms.map { if (it.id == roomId) it.copy(locked = !it.locked) else it })

    fun proactiveSuggestions(plan: FloorPlan): List<ArchitectSuggestion> {
        if (plan.rooms.isEmpty()) return emptyList()
        val graph = SpatialGraphEngine.analyze(plan)
        val structure = StructuralGeometryEngine.inspect(plan)
        val out = mutableListOf<ArchitectSuggestion>()

        plan.rooms.filter { !it.locked && it.areaM2 > 0 }.forEach { room ->
            val minArea = room.minAreaM2
            if (minArea != null && room.areaM2 + .05 < minArea) {
                out += ArchitectSuggestion(
                    title = "أقترح تكبير ${room.name}",
                    reason = "مساحتها ${fmt(room.areaM2)}م² أقل من الحد التصميمي المحفوظ ${fmt(minArea)}م².",
                    actionKind = "EXPAND_ROOM",
                    targetIds = listOf(room.id),
                    priority = 96
                )
            }

            val preferred = room.preferredAreaM2
            if (preferred != null && preferred > 0 && room.areaM2 > preferred * 1.22 && !isCoreLiving(room)) {
                val reclaim = (room.areaM2 - preferred).coerceAtMost(room.areaM2 * .18)
                if (reclaim >= 1.0) out += ArchitectSuggestion(
                    title = "يمكن تصغير ${room.name} قليلًا",
                    reason = "هناك قرابة ${fmt(reclaim)}م² فوق المساحة المفضلة ويمكن استثمارها في فراغ أنفع.",
                    actionKind = "SHRINK_ROOM",
                    targetIds = listOf(room.id),
                    priority = 68
                )
            }

            if (circulation(room) && room.areaM2 >= 7.0) {
                val flexible = graph.candidates.firstOrNull { it.roomName == room.name }?.flexibleAreaM2 ?: 0.0
                if (flexible >= 1.2) out += ArchitectSuggestion(
                    title = "أقترح اختصار ${room.name}",
                    reason = "يمكن استرداد نحو ${fmt(flexible)}م² إذا حافظنا على وضوح مسار الحركة.",
                    actionKind = "SHRINK_ROOM",
                    targetIds = listOf(room.id),
                    priority = 82
                )
            }
        }

        if (graph.privacyContacts.isNotEmpty()) {
            val canReasonAboutDoors = structure.doorCount > 0 && structure.confidence >= 65
            val entrance = plan.openings.firstOrNull { isDoor(it) && likelyEntrance(it, plan.walls.associateBy { w -> w.id }) }
            out += ArchitectSuggestion(
                title = if (canReasonAboutDoors) "أقترح إعادة تموضع نقطة دخول/باب" else "أقترح تحسين مسار الضيوف",
                reason = "هناك علاقة ضيوف/خاص تحتاج فصلًا أفضل: ${graph.privacyContacts.take(2).joinToString("، ")}.",
                actionKind = if (canReasonAboutDoors) "MOVE_OPENING" else "REPLAN_CIRCULATION",
                targetIds = entrance?.let { listOf(it.id) } ?: emptyList(),
                priority = 94
            )
        }

        if (graph.roomsWithoutDoor.isNotEmpty() && structure.doorCount > 0) out += ArchitectSuggestion(
            title = "أقترح مراجعة اتصال بعض المساحات",
            reason = "لا يظهر اتصال باب موثوق لـ ${graph.roomsWithoutDoor.take(3).joinToString("، ")}.",
            actionKind = "REVIEW_DOORS",
            priority = 91
        )

        if (structure.confidence in 1..64) out += ArchitectSuggestion(
            title = "ثبّت قراءة البنية أولًا",
            reason = "ثقة الجدران والفتحات ${structure.confidence}% فقط؛ لا أوصي بنقل بنيوي قبل تأكيد العناصر المشكوك فيها.",
            actionKind = "VERIFY_STRUCTURE",
            priority = 98
        )

        return out.distinctBy { it.title }.sortedByDescending { it.priority }.take(5)
    }

    fun architecturalReview(current: FloorPlan, next: FloorPlan): ArchitectReview {
        val objections = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val currentRooms = current.rooms.associateBy { it.id }
        val nextRooms = next.rooms.associateBy { it.id }
        val currentWalls = current.walls.associateBy { it.id }
        val nextWalls = next.walls.associateBy { it.id }
        val nextOpenings = next.openings.associateBy { it.id }

        current.openings.filter { isDoor(it) }.forEach { old ->
            val fresh = nextOpenings[old.id] ?: return@forEach
            val moved = openingMoved(old, fresh)
            val relationChanged = old.connectsRoomIds.toSet() != fresh.connectsRoomIds.toSet()
            if (!moved && !relationChanged) return@forEach

            val beforeRooms = old.connectsRoomIds.mapNotNull { currentRooms[it] }
            val afterRooms = fresh.connectsRoomIds.mapNotNull { nextRooms[it] }

            if (hasGuestPrivatePair(afterRooms) && !hasGuestPrivatePair(beforeRooms)) {
                objections += "اعتراضي على نقل الباب ${old.id}: صار يربط منطقة ضيوف بمنطقة خاصة مباشرة، وهذا يضعف الخصوصية."
            }

            val wasEntrance = likelyEntrance(old, currentWalls)
            val isEntranceNow = likelyEntrance(fresh, nextWalls)
            if (wasEntrance || isEntranceNow) {
                val privateTarget = afterRooms.firstOrNull { isPrivate(it) }
                if (privateTarget != null) {
                    objections += "نقل المدخل بهذا الشكل يجعله يتجه مباشرة إلى «${privateTarget.name}»؛ أفضل إبقاء منطقة انتقال قبل المساحة الخاصة."
                } else if (relationChanged) {
                    notes += "نقل المدخل غيّر أول مساحة وصول؛ راجع مسار الدخول عند المقارنة بين البدائل."
                }
            }

            val oldWall = old.wallId?.let { currentWalls[it] }
            val newWall = fresh.wallId?.let { nextWalls[it] }
            if (oldWall?.kind == "external" && newWall != null && oldWall.id != newWall.id && newWall.kind != "external") {
                objections += "الباب ${old.id} كان على جدار خارجي وانتقل إلى جدار غير خارجي؛ هذا يغيّر وظيفة المدخل وليس موضعه فقط."
            }

            val oldT = oldWall?.let { pointParameter(old.x, old.y, it) }
            val newT = newWall?.let { pointParameter(fresh.x, fresh.y, it) }
            if (oldT != null && newT != null && oldT in .08f..92f && (newT < .06f || newT > .94f)) {
                objections += "موقع الباب ${old.id} أصبح قريبًا جدًا من زاوية الجدار؛ قد يضعف فراغ الحركة حول الباب."
            }
        }

        current.walls.forEach { old ->
            val fresh = nextWalls[old.id] ?: return@forEach
            if (!wallMoved(old, fresh)) return@forEach
            val carriesEntrance = current.openings.any { it.wallId == old.id && isDoor(it) && likelyEntrance(it, currentWalls) }
            if (carriesEntrance) objections += "تحريك الجدار ${old.id} يؤثر على مدخل مرتبط به؛ لازم نراجع مسار الدخول قبل الاعتماد."
        }

        val beforeGraph = SpatialGraphEngine.analyze(current)
        val afterGraph = SpatialGraphEngine.analyze(next)
        val newlyNoDoor = afterGraph.roomsWithoutDoor.toSet() - beforeGraph.roomsWithoutDoor.toSet()
        if (newlyNoDoor.isNotEmpty() && afterGraph.doorConnections.isNotEmpty()) {
            objections += "بعد التعديل فقدت ${newlyNoDoor.take(3).joinToString("، ")} اتصال باب واضحًا؛ النقل يحتاج إعادة ربط الحركة."
        }

        val beforeScore = score(current)
        val afterScore = score(next)
        if (afterScore.privacy + 9 < beforeScore.privacy) {
            objections += "الخصوصية تنخفض بوضوح (${beforeScore.privacy} ← ${afterScore.privacy})؛ أفضّل بديلًا يحافظ على الفصل بين الضيوف والعائلة."
        }
        if (afterScore.efficiency + 12 < beforeScore.efficiency) {
            objections += "الحركة تصبح أقل كفاءة (${beforeScore.efficiency} ← ${afterScore.efficiency})؛ التعديل يضيف دورانًا أو هدرًا أكبر من فائدته."
        }

        current.rooms.forEach { old ->
            val fresh = nextRooms[old.id] ?: return@forEach
            val minArea = old.minAreaM2
            if (minArea != null && old.areaM2 + .05 >= minArea && fresh.areaM2 > 0 && fresh.areaM2 + .05 < minArea) {
                objections += "تصغير «${old.name}» إلى ${fmt(fresh.areaM2)}م² يتجاوز الحد التصميمي المحفوظ ${fmt(minArea)}م²؛ لا أوصي به بدون تنازل صريح منك."
            }
            if (isHabitable(old) && roomAspect(fresh) > 2.9 && roomAspect(old) <= 2.9) {
                objections += "شكل «${old.name}» أصبح ممدودًا أكثر من اللازم؛ المساحة قد تبقى جيدة رقميًا لكن تأثيثها وحركتها يصيران أضعف."
            }
            val beforeNarrow = narrowPhysicalSide(current, old)
            val afterNarrow = narrowPhysicalSide(next, fresh)
            if (isHabitable(old) && beforeNarrow != null && afterNarrow != null && beforeNarrow >= 2.45 && afterNarrow < 2.45) {
                objections += "العرض التقريبي في «${old.name}» ينخفض إلى ${"%.2f".format(afterNarrow)}م؛ لا أوصي بهذا التصغير دون مراجعة الأثاث ومسار الحركة."
            }
        }

        val beforeDoorConflicts = doorConflictPairs(current)
        val afterDoorConflicts = doorConflictPairs(next)
        val newDoorConflicts = afterDoorConflicts - beforeDoorConflicts
        if (newDoorConflicts.isNotEmpty()) {
            objections += "التعديل قرّب بعض الأبواب من بعضها (${newDoorConflicts.take(2).joinToString("، ")})؛ قد تتعارض مناطق فتحها وحركة المستخدمين."
        }

        if (current.openings.any { isWindow(it) }) {
            val beforeWindowed = windowedRoomIds(current)
            val afterWindowed = windowedRoomIds(next)
            current.rooms.filter { isHabitable(it) && it.id in beforeWindowed && it.id !in afterWindowed }.forEach {
                objections += "«${it.name}» فقدت اتصال النافذة المقروء بعد التعديل؛ هذا يضعف الإضاءة والتهوية الطبيعية ويحتاج بديلًا."
            }
        }

        val beforeWet = wetClusterDistance(current)
        val afterWet = wetClusterDistance(next)
        if (beforeWet != null && afterWet != null && afterWet > beforeWet * 1.35 && afterWet - beforeWet > 7.5) {
            notes += "التعديل يباعد مناطق الخدمات الرطبة بشكل ملحوظ؛ راجع مسارات السباكة والخدمة قبل اعتماد البديل."
        }

        return ArchitectReview(objections.distinct(), notes.distinct())
    }

    fun compactBrief(plan: FloorPlan): String {
        val s = score(plan)
        val locked = plan.rooms.filter { it.locked }.joinToString("، ") { it.name }.ifBlank { "لا يوجد" }
        val suggestions = proactiveSuggestions(plan).take(4).joinToString(" | ") { "${it.title}: ${it.reason}" }.ifBlank { "لا توجد ملاحظة استباقية قوية" }
        return "التقييم ${s.overall}/100، الكفاءة ${s.efficiency}، الخصوصية ${s.privacy}، دقة القراءة ${s.readingConfidence}. المقفل: $locked. ${SpatialGraphEngine.compactBrief(plan)} ${StructuralGeometryEngine.compactBrief(plan)} المقترحات المهنية الحالية: $suggestions."
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

    private fun isCoreLiving(r: Room): Boolean {
        val s = (r.type + " " + r.name).lowercase()
        return listOf("majlis", "مجلس", "family", "عائل", "living", "صالة").any { s.contains(it) }
    }

    private fun isDoor(o: Opening) = o.type.lowercase().contains("door") || o.type.contains("باب")
    private fun isWindow(o: Opening) = o.type.lowercase().contains("window") || o.type.contains("ناف")

    private fun likelyEntrance(o: Opening, walls: Map<String, Wall>): Boolean {
        if (!isDoor(o)) return false
        val externalWall = o.wallId?.let { walls[it]?.kind == "external" } == true
        val nearBoundary = o.x <= 5f || o.x >= 95f || o.y <= 5f || o.y >= 95f
        return (externalWall || nearBoundary) && o.connectsRoomIds.size <= 1
    }

    private fun openingMoved(a: Opening, b: Opening): Boolean =
        hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()) > 1.4 || abs(a.rotationDeg - b.rotationDeg) > 4f || a.wallId != b.wallId

    private fun wallMoved(a: Wall, b: Wall): Boolean =
        hypot((a.start.x - b.start.x).toDouble(), (a.start.y - b.start.y).toDouble()) > 1.0 ||
            hypot((a.end.x - b.end.x).toDouble(), (a.end.y - b.end.y).toDouble()) > 1.0

    private fun roomText(r: Room) = (r.type + " " + r.name).lowercase()
    private fun isGuest(r: Room) = listOf("majlis", "guest", "مجلس", "ضيوف").any { roomText(r).contains(it) }
    private fun isPrivate(r: Room) = listOf("bedroom", "master", "family", "نوم", "عائل", "خاص").any { roomText(r).contains(it) }
    private fun isHabitable(r: Room) = listOf("bedroom", "master", "family", "living", "majlis", "نوم", "عائل", "صالة", "مجلس").any { roomText(r).contains(it) }
    private fun isWet(r: Room) = listOf("bath", "toilet", "kitchen", "laundry", "حمام", "دورة", "مطبخ", "غسيل").any { roomText(r).contains(it) }
    private fun hasGuestPrivatePair(rooms: List<Room>) = rooms.any { isGuest(it) } && rooms.any { isPrivate(it) }

    private fun roomAspect(r: Room): Double {
        val a = max(r.width, r.height).toDouble()
        val b = min(r.width, r.height).coerceAtLeast(.1f).toDouble()
        return a / b
    }

    private fun narrowPhysicalSide(plan: FloorPlan, room: Room): Double? {
        val pw = plan.widthM ?: return null
        val ph = plan.heightM ?: return null
        val w = room.width / 100.0 * pw
        val h = room.height / 100.0 * ph
        return min(w, h)
    }

    private fun doorConflictPairs(plan: FloorPlan): Set<String> {
        val doors = plan.openings.filter { isDoor(it) }
        val out = mutableSetOf<String>()
        for (i in doors.indices) for (j in i + 1 until doors.size) {
            val a = doors[i]; val b = doors[j]
            if (a.connectsRoomIds.intersect(b.connectsRoomIds.toSet()).isEmpty()) continue
            val distance = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
            val threshold = max(3.0, ((a.width + b.width) * .55 + 1.0).toDouble())
            if (distance < threshold) out += listOf(a.id, b.id).sorted().joinToString("↔")
        }
        return out
    }

    private fun windowedRoomIds(plan: FloorPlan): Set<String> =
        plan.openings.filter { isWindow(it) }.flatMap { it.connectsRoomIds }.toSet()

    private fun wetClusterDistance(plan: FloorPlan): Double? {
        val wet = plan.rooms.filter { isWet(it) }
        if (wet.size < 2) return null
        return wet.map { a ->
            wet.filter { it.id != a.id }.minOf { b ->
                hypot((a.x + a.width / 2f - b.x - b.width / 2f).toDouble(), (a.y + a.height / 2f - b.y - b.height / 2f).toDouble())
            }
        }.average()
    }

    private fun pointParameter(x: Float, y: Float, wall: Wall): Float {
        val dx = wall.end.x - wall.start.x
        val dy = wall.end.y - wall.start.y
        val den = dx * dx + dy * dy
        if (den < .0001f) return .5f
        return (((x - wall.start.x) * dx + (y - wall.start.y) * dy) / den).coerceIn(0f, 1f)
    }

    private fun fmt(v: Double) = "%.1f".format(v)
}

object GeometrySolver {
    data class Candidate(
        val title: String,
        val plan: FloorPlan,
        val reason: String,
        val score: Int,
        val review: ArchitecturalEngine.ArchitectReview
    )

    private enum class Edge { LEFT, RIGHT, TOP, BOTTOM }

    fun drag(plan: FloorPlan, kind: String, id: String, dx: Float, dy: Float): Candidate? {
        if (abs(dx) + abs(dy) < .12f) return null
        val next = when (kind) {
            "room" -> translateRoom(plan, id, dx, dy)
            "opening" -> moveOpeningToward(plan, id, dx, dy)
            "wall" -> moveWallByDrag(plan, id, dx, dy)
            else -> null
        } ?: return null
        return candidate(plan, next, "معاينة السحب", "المحرك حرّك العناصر المرتبطة مع الحفاظ على القيود المقروءة.")
    }

    fun actionCandidates(plan: FloorPlan, kind: String, id: String, action: String): List<Candidate> {
        val raw = when (kind) {
            "room" -> roomCandidates(plan, id, action)
            "opening" -> openingCandidates(plan, id, action)
            "wall" -> wallCandidates(plan, id, action)
            else -> emptyList()
        }
        return raw.mapNotNull { (title, next, reason) ->
            if (!basicValid(next)) null else candidate(plan, next, title, reason)
        }.distinctBy { signature(it.plan) }.sortedByDescending { it.score }.take(4)
    }

    fun toProposal(current: FloorPlan, candidate: Candidate): PlanProposal {
        val changes = diff(current, candidate.plan)
        val objection = candidate.review.objections.firstOrNull()
        val message = objection ?: if (candidate.review.notes.isNotEmpty()) candidate.review.notes.first() else ""
        val confidence = (95 - candidate.review.objections.size * 8 - candidate.review.notes.size * 2).coerceIn(55, 96)
        return PlanProposal(
            message = message,
            updatedPlan = candidate.plan,
            changes = changes,
            requiresConfirmation = true,
            confidence = confidence
        )
    }

    private fun roomCandidates(plan: FloorPlan, roomId: String, action: String): List<Triple<String, FloorPlan, String>> {
        val room = plan.rooms.firstOrNull { it.id == roomId } ?: return emptyList()
        if (room.locked) return emptyList()
        return when (action) {
            "EXPAND" -> resizeCandidates(plan, room, expand = true)
            "SHRINK" -> resizeCandidates(plan, room, expand = false)
            "MOVE" -> listOf(
                Triple("يمين", translateRoom(plan, roomId, 5f, 0f), "نقل الغرفة إلى اليمين مع إعادة توزيع الحدود المشتركة."),
                Triple("يسار", translateRoom(plan, roomId, -5f, 0f), "نقل الغرفة إلى اليسار مع إعادة توزيع الحدود المشتركة."),
                Triple("أسفل", translateRoom(plan, roomId, 0f, 5f), "نقل الغرفة إلى الأسفل مع إعادة توزيع الحدود المشتركة."),
                Triple("أعلى", translateRoom(plan, roomId, 0f, -5f), "نقل الغرفة إلى الأعلى مع إعادة توزيع الحدود المشتركة.")
            ).mapNotNull { (t, p, r) -> p?.let { Triple(t, it, r) } }
            else -> emptyList()
        }
    }

    private fun resizeCandidates(plan: FloorPlan, room: Room, expand: Boolean): List<Triple<String, FloorPlan, String>> {
        val step = 3.2f
        val directions = listOf(Edge.LEFT, Edge.RIGHT, Edge.TOP, Edge.BOTTOM)
        return directions.mapNotNull { edge ->
            val delta = when (edge) {
                Edge.LEFT -> if (expand) -step else step
                Edge.RIGHT -> if (expand) step else -step
                Edge.TOP -> if (expand) -step else step
                Edge.BOTTOM -> if (expand) step else -step
            }
            val wall = boundaryWall(plan, room, edge)
            val next = if (wall != null) {
                when (edge) {
                    Edge.LEFT, Edge.RIGHT -> shiftWall(plan, wall.id, delta, 0f)
                    Edge.TOP, Edge.BOTTOM -> shiftWall(plan, wall.id, 0f, delta)
                }
            } else if (plan.walls.isEmpty()) {
                resizeRoomRect(plan, room.id, edge, delta)
            } else null
            next?.takeIf { basicValid(it) && newOverlapCount(plan, it) == 0 }?.let {
                val label = when (edge) { Edge.LEFT -> "من اليسار"; Edge.RIGHT -> "من اليمين"; Edge.TOP -> "من الأعلى"; Edge.BOTTOM -> "من الأسفل" }
                Triple(label, it, if (expand) "تكبير ${room.name} $label مع تعديل الحد المشترك." else "تصغير ${room.name} $label واستثمار المساحة المحررة.")
            }
        }
    }

    private fun openingCandidates(plan: FloorPlan, openingId: String, action: String): List<Triple<String, FloorPlan, String>> {
        val opening = plan.openings.firstOrNull { it.id == openingId } ?: return emptyList()
        if (opening.locked || action != "MOVE") return emptyList()
        val wall = opening.wallId?.let { id -> plan.walls.firstOrNull { it.id == id } } ?: return emptyList()
        val t = parameter(opening.x, opening.y, wall)
        return listOf(-.22f, -.12f, .12f, .22f).mapNotNull { dt ->
            val next = moveOpeningToParameter(plan, openingId, (t + dt).coerceIn(.08f, .92f)) ?: return@mapNotNull null
            val direction = if (dt < 0) "جهة بداية الجدار" else "جهة نهاية الجدار"
            Triple(direction, next, "نقل الفتحة على نفس الجدار مع Snap وإعادة قراءة الغرف المرتبطة بها.")
        }
    }

    private fun wallCandidates(plan: FloorPlan, wallId: String, action: String): List<Triple<String, FloorPlan, String>> {
        val wall = plan.walls.firstOrNull { it.id == wallId } ?: return emptyList()
        if (wall.locked || action != "MOVE" || wall.kind == "external" || wall.confidence < 65) return emptyList()
        val vertical = abs(wall.start.x - wall.end.x) <= 1.1f
        val horizontal = abs(wall.start.y - wall.end.y) <= 1.1f
        if (!vertical && !horizontal) return emptyList()
        val step = 2.8f
        val a = if (vertical) shiftWall(plan, wallId, -step, 0f) else shiftWall(plan, wallId, 0f, -step)
        val b = if (vertical) shiftWall(plan, wallId, step, 0f) else shiftWall(plan, wallId, 0f, step)
        return listOfNotNull(
            a?.let { Triple("بديل A", it, "تحريك الجدار في الاتجاه الأول مع تحديث الغرف والفتحات المرتبطة.") },
            b?.let { Triple("بديل B", it, "تحريك الجدار في الاتجاه المقابل مع تحديث الغرف والفتحات المرتبطة.") }
        ).filter { basicValid(it.second) && newOverlapCount(plan, it.second) == 0 }
    }

    private fun translateRoom(plan: FloorPlan, roomId: String, dx: Float, dy: Float): FloorPlan? {
        val room = plan.rooms.firstOrNull { it.id == roomId } ?: return null
        if (room.locked) return null
        if (plan.walls.isEmpty()) {
            val nx = (room.x + dx).coerceIn(0f, 100f - room.width)
            val ny = (room.y + dy).coerceIn(0f, 100f - room.height)
            return plan.copy(rooms = plan.rooms.map { if (it.id == roomId) it.copy(x = nx, y = ny) else it })
        }
        val left = boundaryWall(plan, room, Edge.LEFT) ?: return null
        val right = boundaryWall(plan, room, Edge.RIGHT) ?: return null
        val top = boundaryWall(plan, room, Edge.TOP) ?: return null
        val bottom = boundaryWall(plan, room, Edge.BOTTOM) ?: return null
        if (listOf(left, right, top, bottom).any { it.kind == "external" || it.locked || it.confidence < 65 }) return null
        var next: FloorPlan = plan
        if (abs(dx) > .05f) {
            next = shiftWall(next, left.id, dx, 0f) ?: return null
            next = shiftWall(next, right.id, dx, 0f) ?: return null
        }
        if (abs(dy) > .05f) {
            next = shiftWall(next, top.id, 0f, dy) ?: return null
            next = shiftWall(next, bottom.id, 0f, dy) ?: return null
        }
        return next.takeIf { basicValid(it) }
    }

    private fun moveOpeningToward(plan: FloorPlan, openingId: String, dx: Float, dy: Float): FloorPlan? {
        val opening = plan.openings.firstOrNull { it.id == openingId } ?: return null
        if (opening.locked) return null
        val wall = opening.wallId?.let { id -> plan.walls.firstOrNull { it.id == id } } ?: return null
        val desiredX = opening.x + dx
        val desiredY = opening.y + dy
        val t = parameter(desiredX, desiredY, wall).coerceIn(.08f, .92f)
        return moveOpeningToParameter(plan, openingId, t)
    }

    private fun moveOpeningToParameter(plan: FloorPlan, openingId: String, tWanted: Float): FloorPlan? {
        val opening = plan.openings.firstOrNull { it.id == openingId } ?: return null
        if (opening.locked) return null
        val wall = opening.wallId?.let { id -> plan.walls.firstOrNull { it.id == id } } ?: return null
        val trials = listOf(0f, -.04f, .04f, -.08f, .08f, -.12f, .12f)
        val chosen = trials.map { (tWanted + it).coerceIn(.08f, .92f) }.firstOrNull { t ->
            val p = pointOn(wall, t)
            plan.openings.filter { it.id != opening.id && it.wallId == wall.id }.none { other ->
                hypot((other.x - p.x).toDouble(), (other.y - p.y).toDouble()) < max(2.4, ((opening.width + other.width) * .48).toDouble())
            }
        } ?: return null
        val p = pointOn(wall, chosen)
        val rotation = Math.toDegrees(atan2((wall.end.y - wall.start.y).toDouble(), (wall.end.x - wall.start.x).toDouble())).toFloat()
        val linked = nearRoomIds(plan, p.x, p.y)
        return plan.copy(openings = plan.openings.map {
            if (it.id == opening.id) it.copy(x = p.x, y = p.y, rotationDeg = rotation, connectsRoomIds = linked) else it
        })
    }

    private fun moveWallByDrag(plan: FloorPlan, wallId: String, dx: Float, dy: Float): FloorPlan? {
        val wall = plan.walls.firstOrNull { it.id == wallId } ?: return null
        if (wall.locked || wall.kind == "external" || wall.confidence < 65) return null
        val vertical = abs(wall.start.x - wall.end.x) <= 1.1f
        val horizontal = abs(wall.start.y - wall.end.y) <= 1.1f
        return when {
            vertical -> shiftWall(plan, wallId, dx, 0f)
            horizontal -> shiftWall(plan, wallId, 0f, dy)
            else -> null
        }
    }

    private fun shiftWall(plan: FloorPlan, wallId: String, dx: Float, dy: Float): FloorPlan? {
        val wall = plan.walls.firstOrNull { it.id == wallId } ?: return null
        if (wall.locked || wall.kind == "external" || wall.confidence < 65) return null
        val vertical = abs(wall.start.x - wall.end.x) <= 1.1f
        val horizontal = abs(wall.start.y - wall.end.y) <= 1.1f
        if (!vertical && !horizontal) return null
        if (vertical && abs(dy) > .05f) return null
        if (horizontal && abs(dx) > .05f) return null

        val moved = wall.copy(
            start = PlanPoint(wall.start.x + dx, wall.start.y + dy),
            end = PlanPoint(wall.end.x + dx, wall.end.y + dy)
        )
        if (listOf(moved.start.x, moved.start.y, moved.end.x, moved.end.y).any { it !in 0f..100f }) return null

        val changedRooms = mutableMapOf<String, Room>()
        plan.rooms.forEach { room ->
            var fresh = room
            if (vertical && spansOverlap(room.y, room.y + room.height, wall.start.y, wall.end.y) >= min(3f, room.height * .35f)) {
                val x = wall.start.x
                when {
                    abs(room.x + room.width - x) <= 1.8f -> fresh = resizeRight(room, dx)
                    abs(room.x - x) <= 1.8f -> fresh = resizeLeft(room, dx)
                }
            } else if (horizontal && spansOverlap(room.x, room.x + room.width, wall.start.x, wall.end.x) >= min(3f, room.width * .35f)) {
                val y = wall.start.y
                when {
                    abs(room.y + room.height - y) <= 1.8f -> fresh = resizeBottom(room, dy)
                    abs(room.y - y) <= 1.8f -> fresh = resizeTop(room, dy)
                }
            }
            if (fresh != room) {
                if (room.locked || fresh.width < 1f || fresh.height < 1f || fresh.x < 0f || fresh.y < 0f || fresh.x + fresh.width > 100f || fresh.y + fresh.height > 100f) return null
                changedRooms[room.id] = fresh
            }
        }
        if (changedRooms.isEmpty()) return null

        var next = plan.copy(
            rooms = plan.rooms.map { changedRooms[it.id] ?: it },
            walls = plan.walls.map { if (it.id == wall.id) moved else it },
            openings = plan.openings.map { o -> if (o.wallId == wall.id) o.copy(x = o.x + dx, y = o.y + dy) else o }
        )
        next = next.copy(openings = next.openings.map { o ->
            if (o.wallId == wall.id) o.copy(connectsRoomIds = nearRoomIds(next, o.x, o.y)) else o
        })
        return next.takeIf { basicValid(it) }
    }

    private fun resizeRoomRect(plan: FloorPlan, roomId: String, edge: Edge, delta: Float): FloorPlan? {
        val room = plan.rooms.firstOrNull { it.id == roomId } ?: return null
        if (room.locked) return null
        val fresh = when (edge) {
            Edge.LEFT -> resizeLeft(room, delta)
            Edge.RIGHT -> resizeRight(room, delta)
            Edge.TOP -> resizeTop(room, delta)
            Edge.BOTTOM -> resizeBottom(room, delta)
        }
        if (fresh.width < 1f || fresh.height < 1f || fresh.x < 0f || fresh.y < 0f || fresh.x + fresh.width > 100f || fresh.y + fresh.height > 100f) return null
        return plan.copy(rooms = plan.rooms.map { if (it.id == roomId) fresh else it })
    }

    private fun resizeLeft(room: Room, delta: Float): Room {
        val newWidth = room.width - delta
        return room.copy(x = room.x + delta, width = newWidth, areaM2 = scaledArea(room, newWidth, room.height))
    }

    private fun resizeRight(room: Room, delta: Float): Room {
        val newWidth = room.width + delta
        return room.copy(width = newWidth, areaM2 = scaledArea(room, newWidth, room.height))
    }

    private fun resizeTop(room: Room, delta: Float): Room {
        val newHeight = room.height - delta
        return room.copy(y = room.y + delta, height = newHeight, areaM2 = scaledArea(room, room.width, newHeight))
    }

    private fun resizeBottom(room: Room, delta: Float): Room {
        val newHeight = room.height + delta
        return room.copy(height = newHeight, areaM2 = scaledArea(room, room.width, newHeight))
    }

    private fun scaledArea(room: Room, width: Float, height: Float): Double {
        if (room.areaM2 <= 0 || room.width <= .01f || room.height <= .01f) return room.areaM2
        val ratio = (width * height / (room.width * room.height)).toDouble()
        return (room.areaM2 * ratio).coerceAtLeast(0.0)
    }

    private fun boundaryWall(plan: FloorPlan, room: Room, edge: Edge): Wall? {
        val target = when (edge) {
            Edge.LEFT -> room.x
            Edge.RIGHT -> room.x + room.width
            Edge.TOP -> room.y
            Edge.BOTTOM -> room.y + room.height
        }
        val verticalEdge = edge == Edge.LEFT || edge == Edge.RIGHT
        return plan.walls.mapNotNull { wall ->
            val vertical = abs(wall.start.x - wall.end.x) <= 1.1f
            val horizontal = abs(wall.start.y - wall.end.y) <= 1.1f
            if (verticalEdge && !vertical || !verticalEdge && !horizontal) return@mapNotNull null
            val distance = if (verticalEdge) abs(wall.start.x - target) else abs(wall.start.y - target)
            if (distance > 2.0f) return@mapNotNull null
            val overlap = if (verticalEdge) spansOverlap(room.y, room.y + room.height, wall.start.y, wall.end.y)
            else spansOverlap(room.x, room.x + room.width, wall.start.x, wall.end.x)
            val needed = if (verticalEdge) min(3f, room.height * .35f) else min(3f, room.width * .35f)
            if (overlap < needed) null else wall to (distance - overlap * .02f)
        }.minByOrNull { it.second }?.first
    }

    private fun nearRoomIds(plan: FloorPlan, x: Float, y: Float): List<String> = plan.rooms
        .map { it to pointRectDistance(x, y, it) }
        .filter { it.second <= 1.8f }
        .sortedBy { it.second }
        .take(2)
        .map { it.first.id }

    private fun pointRectDistance(px: Float, py: Float, r: Room): Float {
        val dx = max(max(r.x - px, 0f), px - (r.x + r.width))
        val dy = max(max(r.y - py, 0f), py - (r.y + r.height))
        return hypot(dx.toDouble(), dy.toDouble()).toFloat()
    }

    private fun parameter(x: Float, y: Float, wall: Wall): Float {
        val dx = wall.end.x - wall.start.x
        val dy = wall.end.y - wall.start.y
        val den = dx * dx + dy * dy
        if (den < .0001f) return .5f
        return (((x - wall.start.x) * dx + (y - wall.start.y) * dy) / den).coerceIn(0f, 1f)
    }

    private fun pointOn(wall: Wall, t: Float) = PlanPoint(
        wall.start.x + (wall.end.x - wall.start.x) * t,
        wall.start.y + (wall.end.y - wall.start.y) * t
    )

    private fun spansOverlap(a0: Float, a1: Float, b0: Float, b1: Float): Float {
        val lowA = min(a0, a1); val highA = max(a0, a1)
        val lowB = min(b0, b1); val highB = max(b0, b1)
        return (min(highA, highB) - max(lowA, lowB)).coerceAtLeast(0f)
    }

    private fun basicValid(plan: FloorPlan): Boolean {
        if (StructuralGeometryEngine.inspect(plan).errors.isNotEmpty()) return false
        return plan.rooms.all { it.width >= 1f && it.height >= 1f && it.x >= 0f && it.y >= 0f && it.x + it.width <= 100.1f && it.y + it.height <= 100.1f }
    }

    private fun newOverlapCount(before: FloorPlan, after: FloorPlan): Int {
        val beforePairs = overlapPairs(before)
        return (overlapPairs(after) - beforePairs).size
    }

    private fun overlapPairs(plan: FloorPlan): Set<String> {
        val out = mutableSetOf<String>()
        for (i in plan.rooms.indices) for (j in i + 1 until plan.rooms.size) {
            val a = plan.rooms[i]; val b = plan.rooms[j]
            val w = min(a.x + a.width, b.x + b.width) - max(a.x, b.x)
            val h = min(a.y + a.height, b.y + b.height) - max(a.y, b.y)
            if (w > .5f && h > .5f) out += listOf(a.id, b.id).sorted().joinToString("|")
        }
        return out
    }

    private fun candidate(current: FloorPlan, next: FloorPlan, title: String, reason: String): Candidate {
        val review = ArchitecturalEngine.architecturalReview(current, next)
        val score = ArchitecturalEngine.score(next)
        val overlaps = newOverlapCount(current, next)
        val quality = (score.overall - review.objections.size * 10 - review.notes.size * 2 - overlaps * 18).coerceIn(0, 100)
        return Candidate(title, next, reason, quality, review)
    }

    private fun diff(current: FloorPlan, next: FloorPlan): List<PlanChange> {
        val changes = mutableListOf<PlanChange>()
        val nextRooms = next.rooms.associateBy { it.id }
        current.rooms.forEach { old ->
            val fresh = nextRooms[old.id] ?: return@forEach
            if (abs(old.x - fresh.x) > .1f || abs(old.y - fresh.y) > .1f || abs(old.width - fresh.width) > .1f || abs(old.height - fresh.height) > .1f) {
                changes += PlanChange(old.id, old.name, "MOVE_RESIZE", old.areaM2.takeIf { it > 0 }, fresh.areaM2.takeIf { it > 0 }, "تم تحديث حدود الغرفة والعناصر المشتركة هندسيًا.")
            }
        }
        val nextOpenings = next.openings.associateBy { it.id }
        current.openings.forEach { old ->
            val fresh = nextOpenings[old.id] ?: return@forEach
            if (hypot((old.x - fresh.x).toDouble(), (old.y - fresh.y).toDouble()) > .1 || old.wallId != fresh.wallId) {
                val label = if (old.type.lowercase().contains("window") || old.type.contains("ناف")) "نافذة ${old.id}" else "باب ${old.id}"
                changes += PlanChange(null, label, "MOVE_OPENING", null, null, "تم نقل الفتحة مع Snap على الجدار وإعادة فحص اتصالها بالفراغات.")
            }
        }
        val nextWalls = next.walls.associateBy { it.id }
        current.walls.forEach { old ->
            val fresh = nextWalls[old.id] ?: return@forEach
            val moved = hypot((old.start.x - fresh.start.x).toDouble(), (old.start.y - fresh.start.y).toDouble()) > .1 || hypot((old.end.x - fresh.end.x).toDouble(), (old.end.y - fresh.end.y).toDouble()) > .1
            if (moved) changes += PlanChange(null, "جدار ${old.id}", "MOVE_WALL", null, null, "تحرك الجدار وتحدثت الغرف والفتحات المرتبطة به.")
        }
        return changes.distinctBy { "${it.action}:${it.roomId}:${it.roomName}" }
    }

    private fun signature(plan: FloorPlan): String = buildString {
        plan.rooms.forEach { append(it.id).append(':').append("%.1f".format(it.x)).append(',').append("%.1f".format(it.y)).append(',').append("%.1f".format(it.width)).append(',').append("%.1f".format(it.height)).append(';') }
        plan.openings.forEach { append(it.id).append('@').append("%.1f".format(it.x)).append(',').append("%.1f".format(it.y)).append(';') }
    }
}
