package com.manzili.hai.engine

import com.manzili.hai.model.*
import kotlin.math.abs
import kotlin.math.roundToInt

/** Type-aware local fallback seeds so non-villa projects never fall back to a villa-only topology. */
object SaudiProjectTypeSeedEngine {
    fun generate(program: NewBuildSolver.Program, type: SaudiProjectTypeEngine.Type): List<NewBuildSolver.Candidate> {
        if (type == SaudiProjectTypeEngine.Type.VILLA_ONE || type == SaudiProjectTypeEngine.Type.VILLA_TWO) {
            return NewBuildSolver.generate(program).map { it.copy(plan = SaudiProjectTypeEngine.apply(it.plan, type), metrics = it.metrics + type.label) }
        }
        return (0..2).map { variant -> make(program, type, variant) }.sortedByDescending { it.overall }
    }

    private fun make(program: NewBuildSolver.Program, type: SaudiProjectTypeEngine.Type, variant: Int): NewBuildSolver.Candidate {
        val floors = when (type) {
            SaudiProjectTypeEngine.Type.BUILDING_ONE, SaudiProjectTypeEngine.Type.TRADITIONAL, SaudiProjectTypeEngine.Type.REST_HOUSE -> 1
            else -> maxOf(2, program.floorCount)
        }
        val floorLevels = (0 until floors).map { floorIndex -> levelFor(program, type, variant, floorIndex) }
        val first = floorLevels.first()
        var plan = FloorPlan(
            title = type.label,
            widthM = program.plotWidthM,
            heightM = program.plotDepthM,
            rooms = first.rooms,
            walls = first.walls,
            openings = first.openings,
            footprint = first.footprint,
            scaleConfidence = 100,
            preferences = PlanPreferences(program.privacyPriority, program.circulationPriority, program.daylightPriority, 85, listOf(program.notes)),
            site = SiteContext(countryCode = "SA", city = program.city, plotBoundary = boundary()),
            floors = floorLevels,
            activeFloorId = first.id,
            observations = listOf("Seed محلي خاص بنوع ${type.label}؛ سيخضع للمحسن الهندسي والمراجعة السعودية.")
        )
        plan = SaudiProjectTypeEngine.apply(MultiFloorGeometryEngine.normalize(plan), type)
        val geom = GeometryV3Engine.inspect(plan)
        val score = if (geom.valid) ArchitecturalEngine.score(geom.plan).overall else 30
        return NewBuildSolver.Candidate(
            id = "type-${type.name.lowercase()}-$variant",
            title = when (variant) { 0 -> "${type.label} • خصوصية"; 1 -> "${type.label} • حركة"; else -> "${type.label} • مرونة" },
            rationale = "تكوين محلي مخصص لنوع ${type.label} بدل استخدام قالب فيلا عام.",
            plan = geom.plan,
            overall = score,
            metrics = listOf(type.label, "${floorLevels.size} دور", "Type-aware fallback")
        )
    }

    private fun levelFor(program: NewBuildSolver.Program, type: SaudiProjectTypeEngine.Type, variant: Int, floor: Int): FloorLevel {
        val rooms = when (type) {
            SaudiProjectTypeEngine.Type.BUILDING_ONE, SaudiProjectTypeEngine.Type.BUILDING_TWO -> apartmentRooms(program, variant, floor)
            SaudiProjectTypeEngine.Type.TOWNHOUSE -> townhouseRooms(program, variant, floor)
            SaudiProjectTypeEngine.Type.DUPLEX -> duplexRooms(program, variant, floor)
            SaudiProjectTypeEngine.Type.TRADITIONAL -> traditionalRooms(program, variant)
            SaudiProjectTypeEngine.Type.REST_HOUSE -> restHouseRooms(program, variant)
            else -> emptyList()
        }
        val (walls, openings) = topology(rooms)
        return FloorLevel(
            id = "floor-$floor",
            name = if (floor == 0) "الدور الأرضي" else "الدور $floor",
            index = floor,
            elevationM = floor * 3.2,
            clearHeightM = 2.9,
            footprint = boundary(),
            rooms = rooms,
            walls = walls,
            openings = openings
        )
    }

    private fun apartmentRooms(program: NewBuildSolver.Program, variant: Int, floor: Int): List<Room> {
        val units = unitsPerFloor(program.notes).coerceIn(1, 3)
        val gap = 3f
        val totalGap = gap * (units - 1)
        val unitW = (100f - totalGap) / units
        val rooms = mutableListOf<Room>()
        repeat(units) { u ->
            val x = u * (unitW + gap)
            val prefix = "f${floor}u${u + 1}"
            val livingH = if (variant == 0) 34f else 38f
            rooms += room("$prefix-living", "شقة ${u + 1} - صالة", "living", x, 0f, unitW, livingH)
            rooms += room("$prefix-kitchen", "شقة ${u + 1} - مطبخ", "kitchen", x, livingH, unitW * .42f, 25f)
            rooms += room("$prefix-bed1", "شقة ${u + 1} - نوم 1", "bedroom", x + unitW * .42f, livingH, unitW * .58f, 25f)
            rooms += room("$prefix-bed2", "شقة ${u + 1} - نوم 2", "bedroom", x, livingH + 25f, unitW * .58f, 100f - livingH - 25f)
            rooms += room("$prefix-bath", "شقة ${u + 1} - حمام", "bath", x + unitW * .58f, livingH + 25f, unitW * .42f, 100f - livingH - 25f)
        }
        return rooms
    }

    private fun townhouseRooms(program: NewBuildSolver.Program, variant: Int, floor: Int): List<Room> = if (floor == 0) {
        listOf(
            room("g-majlis", "مجلس", "majlis", 0f, 0f, 100f, if (variant == 0) 28f else 24f),
            room("g-living", "صالة عائلية", "living", 0f, 28f, 100f, 30f),
            room("g-kitchen", "مطبخ", "kitchen", 0f, 58f, 62f, 25f),
            room("g-service", "خدمة/غسيل", "service", 62f, 58f, 38f, 25f),
            room("g-yard", "فناء خلفي", "courtyard", 0f, 83f, 100f, 17f)
        )
    } else {
        listOf(
            room("f${floor}-master", "غرفة رئيسية", "bedroom", 0f, 0f, 100f, 38f),
            room("f${floor}-bed2", "غرفة نوم 2", "bedroom", 0f, 38f, 50f, 36f),
            room("f${floor}-bed3", "غرفة نوم 3", "bedroom", 50f, 38f, 50f, 36f),
            room("f${floor}-family", "صالة علوية", "living", 0f, 74f, 100f, 26f)
        )
    }

    private fun duplexRooms(program: NewBuildSolver.Program, variant: Int, floor: Int): List<Room> {
        val rooms = mutableListOf<Room>()
        repeat(2) { unit ->
            val x = unit * 50f
            val prefix = "f${floor}d${unit + 1}"
            if (floor == 0) {
                rooms += room("$prefix-majlis", "وحدة ${unit + 1} - مجلس", "majlis", x, 0f, 50f, 30f)
                rooms += room("$prefix-living", "وحدة ${unit + 1} - صالة", "living", x, 30f, 50f, 36f)
                rooms += room("$prefix-kitchen", "وحدة ${unit + 1} - مطبخ", "kitchen", x, 66f, 50f, 34f)
            } else {
                rooms += room("$prefix-master", "وحدة ${unit + 1} - رئيسية", "bedroom", x, 0f, 50f, 45f)
                rooms += room("$prefix-bed", "وحدة ${unit + 1} - نوم", "bedroom", x, 45f, 50f, 35f)
                rooms += room("$prefix-family", "وحدة ${unit + 1} - صالة", "living", x, 80f, 50f, 20f)
            }
        }
        return rooms
    }

    private fun traditionalRooms(program: NewBuildSolver.Program, variant: Int): List<Room> = listOf(
        room("t-majlis", "مجلس", "majlis", 0f, 0f, 42f, 35f),
        room("t-living", "صالة عائلية", "living", 42f, 0f, 58f, 35f),
        room("t-bed1", "غرفة نوم 1", "bedroom", 0f, 35f, 34f, 35f),
        room("t-bed2", "غرفة نوم 2", "bedroom", 34f, 35f, 33f, 35f),
        room("t-kitchen", "مطبخ", "kitchen", 67f, 35f, 33f, 35f),
        room("t-yard", "حوش", "courtyard", 0f, 70f, 100f, 30f)
    )

    private fun restHouseRooms(program: NewBuildSolver.Program, variant: Int): List<Room> = listOf(
        room("r-majlis", "مجلس كبير", "majlis", 0f, 0f, 48f, 38f),
        room("r-family", "جلسة عائلية", "living", 52f, 0f, 48f, 38f),
        room("r-kitchen", "مطبخ خدمة", "kitchen", 0f, 38f, 32f, 22f),
        room("r-bath", "دورات مياه", "bath", 32f, 38f, 22f, 22f),
        room("r-bed", "غرفة مبيت", "bedroom", 54f, 38f, 46f, 22f),
        room("r-yard", "حوش وجلسات خارجية", "courtyard", 0f, 60f, 100f, 40f)
    )

    private fun room(id:String,name:String,type:String,x:Float,y:Float,w:Float,h:Float)=Room(
        id=id,name=name,type=type,x=x,y=y,width=w,height=h,areaM2=0.0,confidence=100,
        polygon=listOf(PlanPoint(x,y),PlanPoint(x+w,y),PlanPoint(x+w,y+h),PlanPoint(x,y+h))
    )

    /**
     * Produces canonical wall segments. Long room edges are split at every T/cross junction before
     * deduplication, so Geometry V3 never sees one wall segment touching three rooms at once.
     */
    private fun topology(rooms: List<Room>): Pair<List<Wall>, List<Opening>> {
        val solidRooms = rooms.filterNot { it.type == "courtyard" }
        val allCorners = solidRooms.flatMap { it.polygon }
        val wallsByKey = linkedMapOf<String, Wall>()
        val roomWallIds = linkedMapOf<String, MutableList<String>>()

        solidRooms.forEach { room ->
            val polygon = room.polygon
            if (polygon.size < 3) return@forEach
            polygon.indices.forEach { index ->
                val a = polygon[index]
                val b = polygon[(index + 1) % polygon.size]
                splitEdge(a, b, allCorners).forEach { (start, end) ->
                    if (abs(start.x - end.x) < .001f && abs(start.y - end.y) < .001f) return@forEach
                    val key = segmentKey(start, end)
                    val wall = wallsByKey[key] ?: Wall(
                        id = "seed-wall-${wallsByKey.size + 1}",
                        start = start,
                        end = end,
                        confidence = 100
                    ).also { wallsByKey[key] = it }
                    roomWallIds.getOrPut(room.id) { mutableListOf() }.add(wall.id)
                }
            }
        }

        val openings = solidRooms.mapNotNull { room ->
            val wallId = roomWallIds[room.id].orEmpty().firstOrNull() ?: return@mapNotNull null
            val wall = wallsByKey.values.firstOrNull { it.id == wallId } ?: return@mapNotNull null
            Opening(
                id = "${room.id}-door",
                type = "door",
                x = (wall.start.x + wall.end.x) / 2f,
                y = (wall.start.y + wall.end.y) / 2f,
                width = 2.8f,
                wallId = wall.id,
                connectsRoomIds = listOf(room.id),
                confidence = 100
            )
        }
        return wallsByKey.values.toList() to openings
    }

    private fun splitEdge(a: PlanPoint, b: PlanPoint, corners: List<PlanPoint>): List<Pair<PlanPoint, PlanPoint>> {
        val horizontal = abs(a.y - b.y) < .001f
        val vertical = abs(a.x - b.x) < .001f
        if (!horizontal && !vertical) return listOf(a to b)

        if (horizontal) {
            val low = minOf(a.x, b.x); val high = maxOf(a.x, b.x)
            val cuts = (listOf(a.x, b.x) + corners.filter { abs(it.y - a.y) < .001f && it.x > low + .001f && it.x < high - .001f }.map { it.x })
                .distinct().sorted().let { if (b.x >= a.x) it else it.reversed() }
            return cuts.zipWithNext { x1, x2 -> PlanPoint(x1, a.y) to PlanPoint(x2, a.y) }
        }

        val low = minOf(a.y, b.y); val high = maxOf(a.y, b.y)
        val cuts = (listOf(a.y, b.y) + corners.filter { abs(it.x - a.x) < .001f && it.y > low + .001f && it.y < high - .001f }.map { it.y })
            .distinct().sorted().let { if (b.y >= a.y) it else it.reversed() }
        return cuts.zipWithNext { y1, y2 -> PlanPoint(a.x, y1) to PlanPoint(a.x, y2) }
    }

    private fun segmentKey(a: PlanPoint, b: PlanPoint): String {
        fun pointKey(p: PlanPoint) = "${(p.x * 100).roundToInt()},${(p.y * 100).roundToInt()}"
        val ka = pointKey(a); val kb = pointKey(b)
        return if (ka <= kb) "$ka|$kb" else "$kb|$ka"
    }

    private fun unitsPerFloor(notes: String): Int = Regex("وحدات/دور=(\\d+)").find(notes)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 2
    private fun boundary()=listOf(PlanPoint(0f,0f),PlanPoint(100f,0f),PlanPoint(100f,100f),PlanPoint(0f,100f))
}
