package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.Opening
import com.manzili.hai.model.Room
import com.manzili.hai.model.Wall
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Deterministic architectural impact analysis.
 * These checks are design heuristics, not statutory-code certification.
 */
object ArchitecturalImpactEngine {
    enum class Severity { OBJECTION, NOTE }

    data class Finding(
        val key: String,
        val category: String,
        val severity: Severity,
        val message: String,
        val targetIds: List<String> = emptyList()
    )

    data class Report(val findings: List<Finding>) {
        val objections: List<Finding> get() = findings.filter { it.severity == Severity.OBJECTION }
        val notes: List<Finding> get() = findings.filter { it.severity == Severity.NOTE }
    }

    fun inspect(plan: FloorPlan): Report = Report(snapshot(plan).values.sortedByDescending { priority(it) })

    fun compare(before: FloorPlan, after: FloorPlan): Report {
        val old = snapshot(before)
        val fresh = snapshot(after)
        val out = fresh.values.mapNotNull { finding ->
            val previous = old[finding.key]
            when {
                previous == null -> finding
                previous.severity == Severity.NOTE && finding.severity == Severity.OBJECTION -> finding
                else -> null
            }
        }
        return Report(out.sortedByDescending { priority(it) })
    }

    fun compactBrief(plan: FloorPlan): String {
        val report = inspect(plan)
        if (report.findings.isEmpty()) return "فحص الأثر الوظيفي لم يجد مشكلة قوية من البيانات المتاحة."
        val urgent = report.objections.take(3).joinToString("؛ ") { it.message }
        val notes = report.notes.take(2).joinToString("؛ ") { it.message }
        return buildString {
            if (urgent.isNotBlank()) append("اعتراضات أثر وظيفي: $urgent. ")
            if (notes.isNotBlank()) append("ملاحظات أثر: $notes.")
        }.trim()
    }

    private fun snapshot(plan: FloorPlan): Map<String, Finding> {
        val out = linkedMapOf<String, Finding>()
        val rooms = plan.rooms.associateBy { it.id }
        val walls = plan.walls.associateBy { it.id }
        val doors = plan.openings.filter { isDoor(it) }
        val windows = plan.openings.filter { isWindow(it) }

        entranceExposure(plan, rooms, walls)?.let { out[it.key] = it }
        guestPathIntrusions(plan, rooms, walls).forEach { out[it.key] = it }
        circulationBottlenecks(plan).forEach { out[it.key] = it }
        furnishingConstraints(plan).forEach { out[it.key] = it }
        daylightLossRisks(plan, rooms, walls, windows).forEach { out[it.key] = it }
        doorClearanceRisks(plan, doors).forEach { out[it.key] = it }
        kitchenFamilyDistance(plan)?.let { out[it.key] = it }
        wetZoneSpread(plan)?.let { out[it.key] = it }
        return out
    }

    private fun entranceExposure(plan: FloorPlan, rooms: Map<String, Room>, walls: Map<String, Wall>): Finding? {
        val entrance = plan.openings.firstOrNull { isDoor(it) && likelyEntrance(it, walls) } ?: return null
        val target = entrance.connectsRoomIds.mapNotNull { rooms[it] }.firstOrNull { isPrivate(it) } ?: return null
        return Finding(
            key = "entrance-private:${entrance.id}:${target.id}",
            category = "privacy",
            severity = Severity.OBJECTION,
            message = "المدخل يفتح مباشرة على «${target.name}»؛ هذا يكشف منطقة خاصة من نقطة الوصول.",
            targetIds = listOf(entrance.id, target.id)
        )
    }

    private fun guestPathIntrusions(plan: FloorPlan, rooms: Map<String, Room>, walls: Map<String, Wall>): List<Finding> {
        if (rooms.isEmpty()) return emptyList()
        val graph = roomDoorGraph(plan)
        if (graph.isEmpty()) return emptyList()
        val entrances = plan.openings.filter { isDoor(it) && likelyEntrance(it, walls) }
            .flatMap { it.connectsRoomIds }.distinct().filter { it in rooms }
        val guests = rooms.values.filter { isGuest(it) }
        val out = mutableListOf<Finding>()
        entrances.forEach { start ->
            guests.forEach { guest ->
                val path = shortestPath(graph, start, guest.id) ?: return@forEach
                val intruders = path.drop(1).dropLast(1).mapNotNull { rooms[it] }.filter { isPrivate(it) }
                if (intruders.isNotEmpty()) {
                    val names = intruders.joinToString("، ") { it.name }
                    out += Finding(
                        key = "guest-path:$start:${guest.id}:${intruders.joinToString(",") { it.id }}",
                        category = "privacy",
                        severity = Severity.OBJECTION,
                        message = "مسار الضيف إلى «${guest.name}» يمر عبر منطقة خاصة ($names)؛ أفضّل فصل حركة الضيوف عن العائلة.",
                        targetIds = listOf(start, guest.id) + intruders.map { it.id }
                    )
                }
            }
        }
        return out.distinctBy { it.key }
    }

    private fun circulationBottlenecks(plan: FloorPlan): List<Finding> {
        if (plan.widthM == null || plan.heightM == null) return emptyList()
        return plan.rooms.filter { isCirculation(it) }.mapNotNull { room ->
            val dims = physicalDimensions(plan, room) ?: return@mapNotNull null
            val narrow = min(dims.first, dims.second)
            when {
                narrow < .85 -> Finding(
                    key = "circulation-tight:${room.id}",
                    category = "circulation",
                    severity = Severity.OBJECTION,
                    message = "«${room.name}» أصبح ضيقًا جدًا للحركة (${fmt2(narrow)}م تقريبًا). هذا مؤشر تصميمي داخلي وليس حكم كود.",
                    targetIds = listOf(room.id)
                )
                narrow < 1.00 -> Finding(
                    key = "circulation-tight:${room.id}",
                    category = "circulation",
                    severity = Severity.NOTE,
                    message = "عرض «${room.name}» التقريبي ${fmt2(narrow)}م؛ راجع سهولة المرور والأثاث قبل الاعتماد.",
                    targetIds = listOf(room.id)
                )
                else -> null
            }
        }
    }

    private fun furnishingConstraints(plan: FloorPlan): List<Finding> {
        if (plan.widthM == null || plan.heightM == null) return emptyList()
        return plan.rooms.mapNotNull { room ->
            val dims = physicalDimensions(plan, room) ?: return@mapNotNull null
            val narrow = min(dims.first, dims.second)
            val threshold = when {
                isBedroom(room) -> 2.40
                isMajlisOrLiving(room) -> 2.70
                isKitchen(room) -> 2.10
                else -> return@mapNotNull null
            }
            if (narrow >= threshold) return@mapNotNull null
            Finding(
                key = "furnishing:${room.id}",
                category = "furniture",
                severity = if (narrow < threshold - .25) Severity.OBJECTION else Severity.NOTE,
                message = "العرض الصافي التقريبي في «${room.name}» ${fmt2(narrow)}م؛ قد تبقى المساحة جيدة رقميًا لكن التأثيث والحركة يصيران أضعف.",
                targetIds = listOf(room.id)
            )
        }
    }

    private fun daylightLossRisks(
        plan: FloorPlan,
        rooms: Map<String, Room>,
        walls: Map<String, Wall>,
        windows: List<Opening>
    ): List<Finding> {
        val externalWindowRooms = windows.filter { w -> w.wallId?.let { walls[it]?.kind == "external" } == true }
            .flatMap { it.connectsRoomIds }.toSet()
        return rooms.values.filter { isHabitable(it) && it.id !in externalWindowRooms }.mapNotNull { room ->
            val anyWindow = windows.any { room.id in it.connectsRoomIds }
            if (!anyWindow) return@mapNotNull null
            Finding(
                key = "internal-window:${room.id}",
                category = "daylight",
                severity = Severity.NOTE,
                message = "«${room.name}» لها نافذة مقروءة لكن ليست على جدار خارجي موثوق؛ لا أعدّها دليلًا كافيًا على إضاءة/تهوية طبيعية.",
                targetIds = listOf(room.id)
            )
        }
    }

    private fun doorClearanceRisks(plan: FloorPlan, doors: List<Opening>): List<Finding> {
        val out = mutableListOf<Finding>()
        for (i in doors.indices) for (j in i + 1 until doors.size) {
            val a = doors[i]
            val b = doors[j]
            if (a.wallId == null || a.wallId != b.wallId) continue
            val d = physicalDistance(plan, a.x, a.y, b.x, b.y)
            val widthA = openingPhysicalWidth(plan, a)
            val widthB = openingPhysicalWidth(plan, b)
            val threshold = when {
                d != null && widthA != null && widthB != null -> max(.75, (widthA + widthB) * .58)
                else -> 4.0
            }
            val actual = d ?: hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
            if (actual < threshold) {
                out += Finding(
                    key = "door-clearance:${listOf(a.id, b.id).sorted().joinToString(":")}",
                    category = "doors",
                    severity = Severity.OBJECTION,
                    message = "البابان ${a.id} و${b.id} متقاربان؛ مناطق الاستخدام والفتح قد تتعارضان. اتجاه مفصلة الباب غير مؤكد، لذلك أتعامل معها كمشكلة خلوص لا كحكم نهائي على قوس الفتح.",
                    targetIds = listOf(a.id, b.id)
                )
            }
        }
        return out
    }

    private fun kitchenFamilyDistance(plan: FloorPlan): Finding? {
        val kitchen = plan.rooms.firstOrNull { isKitchen(it) } ?: return null
        val family = plan.rooms.firstOrNull { isFamilyLiving(it) } ?: return null
        val d = centroidDistanceMeters(plan, kitchen, family) ?: return null
        if (d <= 7.0) return null
        return Finding(
            key = "kitchen-family:${kitchen.id}:${family.id}",
            category = "service",
            severity = Severity.NOTE,
            message = "المسافة التقريبية بين «${kitchen.name}» و«${family.name}» ${fmt1(d)}م؛ راجع مسار الخدمة اليومي قبل تثبيت التوزيع.",
            targetIds = listOf(kitchen.id, family.id)
        )
    }

    private fun wetZoneSpread(plan: FloorPlan): Finding? {
        val wet = plan.rooms.filter { isWet(it) }
        if (wet.size < 2) return null
        val pairs = mutableListOf<Double>()
        for (i in wet.indices) for (j in i + 1 until wet.size) {
            centroidDistanceMeters(plan, wet[i], wet[j])?.let { pairs += it }
        }
        if (pairs.isEmpty()) return null
        val avg = pairs.average()
        if (avg <= 8.0) return null
        return Finding(
            key = "wet-spread:${wet.map { it.id }.sorted().joinToString(":")}",
            category = "service",
            severity = Severity.NOTE,
            message = "متوسط تباعد مناطق الخدمات الرطبة نحو ${fmt1(avg)}م؛ تجميعها أكثر قد يبسط مسارات السباكة والخدمة.",
            targetIds = wet.map { it.id }
        )
    }

    private fun roomDoorGraph(plan: FloorPlan): Map<String, Set<String>> {
        val graph = mutableMapOf<String, MutableSet<String>>()
        plan.openings.filter { isDoor(it) && it.confidence >= 55 }.forEach { opening ->
            val ids = opening.connectsRoomIds.distinct().take(2)
            if (ids.size == 2) {
                graph.getOrPut(ids[0]) { mutableSetOf() } += ids[1]
                graph.getOrPut(ids[1]) { mutableSetOf() } += ids[0]
            }
        }
        return graph.mapValues { it.value.toSet() }
    }

    private fun shortestPath(graph: Map<String, Set<String>>, start: String, goal: String): List<String>? {
        if (start == goal) return listOf(start)
        val queue = ArrayDeque<List<String>>()
        val seen = mutableSetOf(start)
        queue.add(listOf(start))
        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            val last = path.last()
            graph[last].orEmpty().forEach { next ->
                if (!seen.add(next)) return@forEach
                val fresh = path + next
                if (next == goal) return fresh
                queue.add(fresh)
            }
        }
        return null
    }

    private fun physicalDimensions(plan: FloorPlan, room: Room): Pair<Double, Double>? {
        val w = plan.widthM ?: return null
        val h = plan.heightM ?: return null
        return room.width / 100.0 * w to room.height / 100.0 * h
    }

    private fun centroidDistanceMeters(plan: FloorPlan, a: Room, b: Room): Double? {
        val w = plan.widthM ?: return null
        val h = plan.heightM ?: return null
        val ax = (a.x + a.width / 2f) / 100.0 * w
        val ay = (a.y + a.height / 2f) / 100.0 * h
        val bx = (b.x + b.width / 2f) / 100.0 * w
        val by = (b.y + b.height / 2f) / 100.0 * h
        return hypot(ax - bx, ay - by)
    }

    private fun physicalDistance(plan: FloorPlan, ax: Float, ay: Float, bx: Float, by: Float): Double? {
        val w = plan.widthM ?: return null
        val h = plan.heightM ?: return null
        return hypot((ax - bx) / 100.0 * w, (ay - by) / 100.0 * h)
    }

    private fun openingPhysicalWidth(plan: FloorPlan, o: Opening): Double? {
        val wall = o.wallId?.let { id -> plan.walls.firstOrNull { it.id == id } } ?: return null
        val w = plan.widthM ?: return null
        val h = plan.heightM ?: return null
        val dx = abs(wall.end.x - wall.start.x)
        val dy = abs(wall.end.y - wall.start.y)
        val scale = if (dx >= dy) w / 100.0 else h / 100.0
        return o.width * scale
    }

    private fun likelyEntrance(o: Opening, walls: Map<String, Wall>): Boolean {
        if (!isDoor(o)) return false
        val external = o.wallId?.let { walls[it]?.kind == "external" } == true
        val boundary = o.x <= 5f || o.x >= 95f || o.y <= 5f || o.y >= 95f
        return (external || boundary) && o.connectsRoomIds.size <= 1
    }

    private fun text(r: Room) = (r.type + " " + r.name).lowercase()
    private fun isDoor(o: Opening) = o.type.lowercase().contains("door") || o.type.contains("باب")
    private fun isWindow(o: Opening) = o.type.lowercase().contains("window") || o.type.contains("ناف")
    private fun isGuest(r: Room) = listOf("majlis", "guest", "مجلس", "ضيوف").any { text(r).contains(it) }
    private fun isPrivate(r: Room) = listOf("bedroom", "master", "family", "نوم", "عائل", "خاص").any { text(r).contains(it) }
    private fun isBedroom(r: Room) = listOf("bedroom", "master", "نوم", "غرفة نوم").any { text(r).contains(it) }
    private fun isFamilyLiving(r: Room) = listOf("family", "living", "عائل", "صالة").any { text(r).contains(it) }
    private fun isMajlisOrLiving(r: Room) = isGuest(r) || isFamilyLiving(r)
    private fun isKitchen(r: Room) = listOf("kitchen", "مطبخ").any { text(r).contains(it) }
    private fun isWet(r: Room) = listOf("bath", "wc", "toilet", "kitchen", "laundry", "حمام", "دورة", "مطبخ", "غسيل").any { text(r).contains(it) }
    private fun isCirculation(r: Room) = listOf("corridor", "hallway", "passage", "ممر", "مدخل", "لوبي").any { text(r).contains(it) }
    private fun isHabitable(r: Room) = isBedroom(r) || isMajlisOrLiving(r)

    private fun priority(f: Finding): Int = when (f.severity) {
        Severity.OBJECTION -> 100
        Severity.NOTE -> 60
    }

    private fun fmt1(v: Double) = "%.1f".format(v)
    private fun fmt2(v: Double) = "%.2f".format(v)
}
