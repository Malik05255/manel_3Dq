package com.manzili.hai.engine

import com.manzili.hai.model.*
import kotlin.math.max

object NewBuildSolver {
    data class Program(
        val title: String = "مشروع جديد",
        val city: String,
        val plotWidthM: Double,
        val plotDepthM: Double,
        val floorCount: Int,
        val bedrooms: Int,
        val guestEntranceIndependent: Boolean,
        val privacyPriority: Int,
        val circulationPriority: Int,
        val daylightPriority: Int,
        val notes: String = ""
    )

    data class Candidate(
        val id: String,
        val title: String,
        val rationale: String,
        val plan: FloorPlan,
        val overall: Int,
        val metrics: List<String>
    )

    fun generate(program: Program): List<Candidate> {
        require(program.plotWidthM in 6.0..100.0 && program.plotDepthM in 6.0..150.0)
        require(program.floorCount in 1..3)
        require(program.bedrooms in 1..10)
        return listOf(
            make(program, 0, "خصوصية أولًا", "يفصل الضيوف في واجهة مستقلة ويؤخر غرف النوم عن المدخل."),
            make(program, 1, "قلب عائلي", "يجعل الصالة العائلية مركز الحركة وتلتف حولها الخدمات والغرف."),
            make(program, 2, "جناحان واضحان", "يقسم المخطط إلى جناح ضيوف وجناح عائلة مع محور خدمة مستقل نسبيًا.")
        ).sortedByDescending { it.overall }
    }

    private fun make(program: Program, variant: Int, title: String, rationale: String): Candidate {
        val plot = fullBoundary()
        val floors = (0 until program.floorCount).map { index ->
            if (index == 0) groundFloor(program, variant) else upperFloor(program, variant, index)
        }
        val first = floors.first()
        val constraints = buildList {
            if (program.guestEntranceIndependent) add(ProjectConstraint("guest-entrance-independent", ProjectMemoryEngine.GUEST_ENTRANCE_INDEPENDENT, "مدخل الضيوف مستقل", hard = true, priority = 100))
            if (program.privacyPriority >= 90) add(ProjectConstraint("priority-privacy", ProjectMemoryEngine.PRIORITY_PRIVACY, "الخصوصية أولوية", value = program.privacyPriority.toDouble(), hard = false, priority = 98))
            if (program.circulationPriority >= 90) add(ProjectConstraint("priority-circulation", ProjectMemoryEngine.PRIORITY_CIRCULATION, "تقليل الممرات وسهولة الحركة أولوية", value = program.circulationPriority.toDouble(), hard = false, priority = 92))
            if (program.daylightPriority >= 90) add(ProjectConstraint("priority-daylight", ProjectMemoryEngine.PRIORITY_DAYLIGHT, "الإضاءة الطبيعية أولوية", value = program.daylightPriority.toDouble(), hard = false, priority = 92))
        }
        val base = FloorPlan(
            title = program.title,
            widthM = program.plotWidthM,
            heightM = program.plotDepthM,
            rooms = first.rooms,
            walls = first.walls,
            openings = first.openings,
            observations = listOf("حل أولي مولد هندسيًا؛ الارتدادات والاشتراطات النظامية لم تُفترض تلقائيًا."),
            uncertainties = emptyList(),
            sourceSummary = "HAI ولّد ثلاثة اتجاهات مختلفة من نفس برنامج المشروع. هذا البديل: $title.",
            preferences = PlanPreferences(program.privacyPriority, program.circulationPriority, program.daylightPriority, 65, listOf(program.notes).filter { it.isNotBlank() }),
            constraints = constraints,
            footprint = first.footprint,
            dimensions = listOf(
                PlanDimension("plot-width", "عرض الأرض", program.plotWidthM, "horizontal", confidence = 100, sourceText = "إدخال المستخدم"),
                PlanDimension("plot-depth", "طول الأرض", program.plotDepthM, "vertical", confidence = 100, sourceText = "إدخال المستخدم")
            ),
            scaleConfidence = 100,
            site = SiteContext(countryCode = "SA", city = program.city, plotBoundary = plot, northDeg = 0f),
            floors = floors,
            activeFloorId = first.id,
            northDeg = 0f
        )
        val plan = MultiFloorGeometryEngine.normalize(ProjectMemoryEngine.reconcile(base))
        val score = ArchitecturalEngine.score(plan)
        val guestRoom = plan.rooms.firstOrNull { it.type == "majlis" }
        val familyRoom = plan.rooms.firstOrNull { it.type == "living" }
        val separation = if (guestRoom != null && familyRoom != null) kotlin.math.abs(guestRoom.x - familyRoom.x) + kotlin.math.abs(guestRoom.y - familyRoom.y) else 0f
        val adjusted = (score.overall + when (variant) { 0 -> program.privacyPriority / 18; 1 -> program.circulationPriority / 20; else -> (program.privacyPriority + program.circulationPriority) / 40 } + (separation / 20f).toInt()).coerceIn(0, 100)
        return Candidate(
            id = "candidate-$variant",
            title = title,
            rationale = rationale,
            plan = plan,
            overall = adjusted,
            metrics = listOf("${program.floorCount} دور", "${program.bedrooms} غرف نوم", "خصوصية ${program.privacyPriority}/100", "حركة ${program.circulationPriority}/100")
        )
    }

    private fun groundFloor(program: Program, variant: Int): FloorLevel {
        val rooms = mutableListOf<Room>()
        when (variant) {
            0 -> {
                rooms += room(program, "g-majlis", "مجلس رجال", "majlis", 0f, 0f, 38f, 34f)
                rooms += room(program, "g-guestbath", "دورة مياه ضيوف", "bath", 0f, 34f, 18f, 18f)
                rooms += room(program, "g-living", "صالة عائلية", "living", 38f, 0f, 62f, 52f)
                rooms += room(program, "g-kitchen", "مطبخ", "kitchen", 48f, 52f, 52f, 30f)
                rooms += room(program, "g-laundry", "غسيل/خدمة", "laundry", 70f, 82f, 30f, 18f)
                if (program.floorCount == 1 || program.bedrooms >= 5) rooms += room(program, "g-bed", "غرفة نوم أرضية", "bedroom", 0f, 52f, 48f, 48f)
                else rooms += room(program, "g-flex", "غرفة مرنة", "flex", 0f, 52f, 48f, 48f)
            }
            1 -> {
                rooms += room(program, "g-majlis", "مجلس رجال", "majlis", 0f, 0f, 35f, 38f)
                rooms += room(program, "g-living", "صالة عائلية", "living", 35f, 0f, 65f, 58f)
                rooms += room(program, "g-guestbath", "دورة مياه ضيوف", "bath", 0f, 38f, 20f, 18f)
                rooms += room(program, "g-kitchen", "مطبخ", "kitchen", 55f, 58f, 45f, 28f)
                rooms += room(program, "g-laundry", "غسيل/خدمة", "laundry", 75f, 86f, 25f, 14f)
                rooms += room(program, "g-flex", if (program.floorCount == 1) "غرفة نوم" else "غرفة مرنة", if (program.floorCount == 1) "bedroom" else "flex", 0f, 56f, 55f, 44f)
            }
            else -> {
                rooms += room(program, "g-majlis", "مجلس رجال", "majlis", 0f, 0f, 42f, 46f)
                rooms += room(program, "g-guestbath", "دورة مياه ضيوف", "bath", 0f, 46f, 18f, 16f)
                rooms += room(program, "g-living", "صالة عائلية", "living", 42f, 0f, 58f, 48f)
                rooms += room(program, "g-kitchen", "مطبخ", "kitchen", 58f, 48f, 42f, 30f)
                rooms += room(program, "g-laundry", "غسيل/خدمة", "laundry", 78f, 78f, 22f, 22f)
                rooms += room(program, "g-flex", if (program.floorCount == 1) "غرفة نوم" else "غرفة مرنة", if (program.floorCount == 1) "bedroom" else "flex", 0f, 62f, 58f, 38f)
            }
        }
        return level(0, "الدور الأرضي", rooms)
    }

    private fun upperFloor(program: Program, variant: Int, index: Int): FloorLevel {
        val totalUpper = max(1, program.floorCount - 1)
        val remainingBedrooms = max(1, program.bedrooms - if (program.floorCount == 1) 1 else 0)
        val count = max(1, (remainingBedrooms + totalUpper - 1) / totalUpper)
        val slots = when (variant) {
            0 -> listOf(floatArrayOf(0f,0f,45f,38f), floatArrayOf(55f,0f,45f,38f), floatArrayOf(0f,58f,45f,42f), floatArrayOf(55f,58f,45f,42f))
            1 -> listOf(floatArrayOf(0f,0f,40f,42f), floatArrayOf(60f,0f,40f,42f), floatArrayOf(0f,58f,40f,42f), floatArrayOf(60f,58f,40f,42f))
            else -> listOf(floatArrayOf(0f,0f,48f,40f), floatArrayOf(52f,0f,48f,40f), floatArrayOf(0f,60f,48f,40f), floatArrayOf(52f,60f,48f,40f))
        }
        val rooms = mutableListOf<Room>()
        val floorOffset = (index - 1) * count
        repeat(minOf(count, 4)) { i ->
            val s = slots[i]
            val n = floorOffset + i + 1
            rooms += room(program, "f${index}-bed$n", if (n == 1) "غرفة نوم رئيسية" else "غرفة نوم $n", "bedroom", s[0], s[1], s[2], s[3])
        }
        rooms += room(program, "f${index}-family", "صالة عائلية", "living", 25f, 40f, 50f, 20f)
        return level(index, if (index == 1) "الدور الأول" else "الدور $index", rooms)
    }

    private fun room(program: Program, id: String, name: String, type: String, x: Float, y: Float, w: Float, h: Float): Room {
        val area = program.plotWidthM * program.plotDepthM * (w / 100.0) * (h / 100.0)
        val polygon = listOf(PlanPoint(x,y), PlanPoint(x+w,y), PlanPoint(x+w,y+h), PlanPoint(x,y+h))
        return Room(id, name, type, x, y, w, h, area, confidence = 100, polygon = polygon)
    }

    private fun level(index: Int, name: String, rooms: List<Room>): FloorLevel {
        val walls = mutableListOf<Wall>()
        val openings = mutableListOf<Opening>()
        rooms.forEach { r ->
            val p = r.polygon
            if (p.size >= 4) {
                val ids = (0..3).map { "${r.id}-w$it" }
                walls += Wall(ids[0], p[0], p[1], kind = "unknown", confidence = 100)
                walls += Wall(ids[1], p[1], p[2], kind = "unknown", confidence = 100)
                walls += Wall(ids[2], p[2], p[3], kind = "unknown", confidence = 100)
                walls += Wall(ids[3], p[3], p[0], kind = "unknown", confidence = 100)
                openings += Opening("${r.id}-door", "door", (p[0].x + p[1].x) / 2f, p[0].y, 3f, wallId = ids[0], connectsRoomIds = listOf(r.id), confidence = 100)
            }
        }
        return FloorLevel("floor-$index", name, index, elevationM = index * 3.2, footprint = fullBoundary(), rooms = rooms, walls = walls, openings = openings)
    }

    private fun fullBoundary() = listOf(PlanPoint(0f,0f), PlanPoint(100f,0f), PlanPoint(100f,100f), PlanPoint(0f,100f))
}