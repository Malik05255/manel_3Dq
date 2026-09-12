package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.Opening
import com.manzili.hai.model.PlanProposal
import com.manzili.hai.model.PlanScore
import com.manzili.hai.model.Room
import com.manzili.hai.model.ValidationReport
import com.manzili.hai.model.Wall
import kotlin.math.abs
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
