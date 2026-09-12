package com.manzili.hai.data

import com.manzili.hai.model.*
import org.json.JSONArray
import org.json.JSONObject

/** Canonical JSON codec for local project persistence. Older missing fields decode safely. */
object PlanStorageCodec {
    fun encode(plan: FloorPlan): JSONObject {
        val root = JSONObject()
        root.put("schemaVersion", 5)
        root.put("title", plan.title)
        plan.widthM?.let { root.put("widthM", it) }
        plan.heightM?.let { root.put("heightM", it) }
        root.put("revision", plan.revision)
        root.put("sourceSummary", plan.sourceSummary)
        root.put("scaleConfidence", plan.scaleConfidence)
        root.put("saudiRulesEnabled", plan.saudiRulesEnabled)
        plan.northDeg?.let { root.put("northDeg", it) }
        root.put("observations", JSONArray(plan.observations))
        root.put("uncertainties", JSONArray(plan.uncertainties))
        root.put("footprint", encodePoints(plan.footprint))
        root.put("dimensions", JSONArray().apply { plan.dimensions.forEach { put(encodeDimension(it)) } })
        root.put("rooms", JSONArray().apply { plan.rooms.forEach { put(encodeRoom(it)) } })
        root.put("walls", JSONArray().apply { plan.walls.forEach { put(encodeWall(it)) } })
        root.put("openings", JSONArray().apply { plan.openings.forEach { put(encodeOpening(it)) } })
        root.put("elements", JSONArray().apply { plan.elements.forEach { put(encodeElement(it)) } })
        root.put("preferences", encodePreferences(plan.preferences))
        root.put("constraints", JSONArray().apply { plan.constraints.forEach { put(encodeConstraint(it)) } })
        root.put("site", encodeSite(plan.site))
        root.put("floors", JSONArray().apply { plan.floors.forEach { put(encodeFloor(it)) } })
        plan.activeFloorId?.let { root.put("activeFloorId", it) }
        return root
    }

    fun decode(root: JSONObject): FloorPlan {
        val p = root.optJSONObject("preferences")
        val north = root.optDouble("northDeg", Double.NaN).takeIf { !it.isNaN() }?.toFloat()
        return FloorPlan(
            title = root.optString("title", "مشروعي"),
            widthM = positive(root, "widthM"),
            heightM = positive(root, "heightM"),
            rooms = decodeRooms(root.optJSONArray("rooms")),
            walls = decodeWalls(root.optJSONArray("walls")),
            openings = decodeOpenings(root.optJSONArray("openings")),
            observations = decodeStrings(root.optJSONArray("observations")),
            uncertainties = decodeStrings(root.optJSONArray("uncertainties")),
            sourceSummary = root.optString("sourceSummary", ""),
            preferences = PlanPreferences(
                privacyPriority = p?.optInt("privacyPriority", 80)?.coerceIn(0, 100) ?: 80,
                circulationPriority = p?.optInt("circulationPriority", 80)?.coerceIn(0, 100) ?: 80,
                daylightPriority = p?.optInt("daylightPriority", 70)?.coerceIn(0, 100) ?: 70,
                futureFlexibilityPriority = p?.optInt("futureFlexibilityPriority", 60)?.coerceIn(0, 100) ?: 60,
                notes = decodeStrings(p?.optJSONArray("notes"))
            ),
            constraints = decodeConstraints(root.optJSONArray("constraints")),
            revision = root.optInt("revision", 1).coerceAtLeast(1),
            footprint = decodePoints(root.optJSONArray("footprint")),
            dimensions = decodeDimensions(root.optJSONArray("dimensions")),
            scaleConfidence = root.optInt("scaleConfidence", 0).coerceIn(0, 100),
            northDeg = north,
            site = decodeSite(root.optJSONObject("site"), north),
            floors = decodeFloors(root.optJSONArray("floors")),
            activeFloorId = root.optString("activeFloorId").takeIf { it.isNotBlank() },
            saudiRulesEnabled = root.optBoolean("saudiRulesEnabled", false),
            elements = decodeElements(root.optJSONArray("elements"))
        )
    }

    private fun encodeRoom(r: Room): JSONObject = JSONObject().apply {
        put("id", r.id); put("name", r.name); put("type", r.type)
        put("x", r.x); put("y", r.y); put("width", r.width); put("height", r.height)
        put("areaM2", r.areaM2); put("confidence", r.confidence); put("locked", r.locked)
        r.minAreaM2?.let { put("minAreaM2", it) }
        r.preferredAreaM2?.let { put("preferredAreaM2", it) }
        put("polygon", encodePoints(r.polygon))
    }

    private fun encodeWall(w: Wall): JSONObject = JSONObject().apply {
        put("id", w.id); put("start", encodePoint(w.start)); put("end", encodePoint(w.end))
        w.thicknessCm?.let { put("thicknessCm", it) }
        put("kind", w.kind); put("confidence", w.confidence); put("locked", w.locked)
        put("adjacentRoomIds", JSONArray(w.adjacentRoomIds))
    }

    private fun encodeOpening(o: Opening): JSONObject = JSONObject().apply {
        put("id", o.id); put("type", o.type); put("x", o.x); put("y", o.y); put("width", o.width)
        put("rotationDeg", o.rotationDeg); o.wallId?.let { put("wallId", it) }
        put("connectsRoomIds", JSONArray(o.connectsRoomIds)); put("confidence", o.confidence); put("locked", o.locked)
    }

    private fun encodeDimension(d: PlanDimension): JSONObject = JSONObject().apply {
        put("id", d.id); put("label", d.label); put("valueM", d.valueM); put("axis", d.axis)
        put("confidence", d.confidence); put("sourceText", d.sourceText); put("pageIndex", d.pageIndex)
        d.start?.let { put("start", encodePoint(it)) }
        d.end?.let { put("end", encodePoint(it)) }
    }

    private fun encodeElement(e: StructuralElement): JSONObject = JSONObject().apply {
        put("id", e.id); put("type", e.type); put("footprint", encodePoints(e.footprint)); put("rotationDeg", e.rotationDeg)
        e.widthM?.let { put("widthM", it) }; e.depthM?.let { put("depthM", it) }
        put("confidence", e.confidence); put("locked", e.locked); put("connectsFloorIds", JSONArray(e.connectsFloorIds)); put("notes", e.notes)
    }

    private fun encodePreferences(p: PlanPreferences): JSONObject = JSONObject().apply {
        put("privacyPriority", p.privacyPriority); put("circulationPriority", p.circulationPriority)
        put("daylightPriority", p.daylightPriority); put("futureFlexibilityPriority", p.futureFlexibilityPriority)
        put("notes", JSONArray(p.notes))
    }

    private fun encodeConstraint(c: ProjectConstraint): JSONObject = JSONObject().apply {
        put("id", c.id); put("kind", c.kind); put("text", c.text); put("targetIds", JSONArray(c.targetIds))
        c.value?.let { put("value", it) }
        put("hard", c.hard); put("priority", c.priority); put("active", c.active)
    }

    private fun encodeSite(s: SiteContext): JSONObject {
        val roads = JSONArray()
        s.roads.forEach { r ->
            roads.put(JSONObject().apply {
                put("id", r.id); put("name", r.name); put("start", encodePoint(r.start)); put("end", encodePoint(r.end))
                r.widthM?.let { put("widthM", it) }
                put("classification", r.classification)
            })
        }
        return JSONObject().apply {
            put("countryCode", s.countryCode); put("city", s.city); put("plotBoundary", encodePoints(s.plotBoundary))
            s.northDeg?.let { put("northDeg", it) }
            s.frontSetbackM?.let { put("frontSetbackM", it) }
            s.rearSetbackM?.let { put("rearSetbackM", it) }
            s.sideSetbackM?.let { put("sideSetbackM", it) }
            put("roads", roads)
        }
    }

    private fun encodeFloor(f: FloorLevel): JSONObject = JSONObject().apply {
        put("id", f.id); put("name", f.name); put("index", f.index); put("elevationM", f.elevationM)
        f.clearHeightM?.let { put("clearHeightM", it) }
        put("footprint", encodePoints(f.footprint))
        put("rooms", JSONArray().apply { f.rooms.forEach { put(encodeRoom(it)) } })
        put("walls", JSONArray().apply { f.walls.forEach { put(encodeWall(it)) } })
        put("openings", JSONArray().apply { f.openings.forEach { put(encodeOpening(it)) } })
        put("elements", JSONArray().apply { f.elements.forEach { put(encodeElement(it)) } })
    }

    private fun decodeRooms(a: JSONArray?): List<Room> {
        if (a == null) return emptyList()
        val out = mutableListOf<Room>()
        for (i in 0 until a.length()) {
            val r = a.optJSONObject(i) ?: continue
            out += Room(
                id = r.optString("id", "r$i"), name = r.optString("name", "غرفة"), type = r.optString("type", "room"),
                x = r.optDouble("x", 0.0).toFloat(), y = r.optDouble("y", 0.0).toFloat(),
                width = r.optDouble("width", 20.0).toFloat(), height = r.optDouble("height", 20.0).toFloat(),
                areaM2 = r.optDouble("areaM2", 0.0).coerceAtLeast(0.0), confidence = r.optInt("confidence", 80).coerceIn(0, 100),
                locked = r.optBoolean("locked", false), minAreaM2 = positive(r, "minAreaM2"), preferredAreaM2 = positive(r, "preferredAreaM2"),
                polygon = decodePoints(r.optJSONArray("polygon"))
            )
        }
        return out
    }

    private fun decodeWalls(a: JSONArray?): List<Wall> {
        if (a == null) return emptyList()
        val out = mutableListOf<Wall>()
        for (i in 0 until a.length()) {
            val w = a.optJSONObject(i) ?: continue
            out += Wall(
                id = w.optString("id", "w$i"),
                start = decodePoint(w.optJSONObject("start")) ?: PlanPoint(0f, 0f),
                end = decodePoint(w.optJSONObject("end")) ?: PlanPoint(0f, 0f),
                thicknessCm = positive(w, "thicknessCm"), kind = w.optString("kind", "unknown"),
                confidence = w.optInt("confidence", 80).coerceIn(0, 100), locked = w.optBoolean("locked", false),
                adjacentRoomIds = decodeStrings(w.optJSONArray("adjacentRoomIds"))
            )
        }
        return out
    }

    private fun decodeOpenings(a: JSONArray?): List<Opening> {
        if (a == null) return emptyList()
        val out = mutableListOf<Opening>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            out += Opening(
                id = o.optString("id", "o$i"), type = o.optString("type", "door"), x = o.optDouble("x", 0.0).toFloat(), y = o.optDouble("y", 0.0).toFloat(),
                width = o.optDouble("width", 3.0).toFloat(), rotationDeg = o.optDouble("rotationDeg", 0.0).toFloat(),
                wallId = o.optString("wallId").takeIf { it.isNotBlank() }, connectsRoomIds = decodeStrings(o.optJSONArray("connectsRoomIds")),
                confidence = o.optInt("confidence", 80).coerceIn(0, 100), locked = o.optBoolean("locked", false)
            )
        }
        return out
    }

    private fun decodeDimensions(a: JSONArray?): List<PlanDimension> {
        if (a == null) return emptyList()
        val out = mutableListOf<PlanDimension>()
        for (i in 0 until a.length()) {
            val d = a.optJSONObject(i) ?: continue
            val value = positive(d, "valueM") ?: continue
            out += PlanDimension(
                id = d.optString("id", "d$i"), label = d.optString("label", "بعد"), valueM = value, axis = d.optString("axis", "unknown"),
                start = decodePoint(d.optJSONObject("start")), end = decodePoint(d.optJSONObject("end")),
                confidence = d.optInt("confidence", 70).coerceIn(0, 100), sourceText = d.optString("sourceText", ""),
                pageIndex = d.optInt("pageIndex", 0).coerceAtLeast(0)
            )
        }
        return out
    }

    private fun decodeElements(a: JSONArray?): List<StructuralElement> {
        if (a == null) return emptyList()
        val out = mutableListOf<StructuralElement>()
        for (i in 0 until a.length()) {
            val e = a.optJSONObject(i) ?: continue
            val footprint = decodePoints(e.optJSONArray("footprint"))
            if (footprint.isEmpty()) continue
            out += StructuralElement(
                id = e.optString("id", "element-$i"), type = e.optString("type", "unknown"), footprint = footprint,
                rotationDeg = e.optDouble("rotationDeg", 0.0).toFloat(), widthM = positive(e, "widthM"), depthM = positive(e, "depthM"),
                confidence = e.optInt("confidence", 80).coerceIn(0, 100), locked = e.optBoolean("locked", false),
                connectsFloorIds = decodeStrings(e.optJSONArray("connectsFloorIds")), notes = e.optString("notes", "")
            )
        }
        return out
    }

    private fun decodeConstraints(a: JSONArray?): List<ProjectConstraint> {
        if (a == null) return emptyList()
        val out = mutableListOf<ProjectConstraint>()
        for (i in 0 until a.length()) {
            val c = a.optJSONObject(i) ?: continue
            out += ProjectConstraint(
                id = c.optString("id", "constraint-$i"), kind = c.optString("kind", "NOTE"), text = c.optString("text", ""),
                targetIds = decodeStrings(c.optJSONArray("targetIds")), value = positive(c, "value"), hard = c.optBoolean("hard", true),
                priority = c.optInt("priority", 90).coerceIn(0, 100), active = c.optBoolean("active", true)
            )
        }
        return out
    }

    private fun decodeSite(o: JSONObject?, legacyNorth: Float?): SiteContext {
        if (o == null) return SiteContext(northDeg = legacyNorth)
        val roads = mutableListOf<RoadEdge>()
        val a = o.optJSONArray("roads")
        if (a != null) {
            for (i in 0 until a.length()) {
                val r = a.optJSONObject(i) ?: continue
                roads += RoadEdge(
                    id = r.optString("id", "road-$i"), name = r.optString("name", "شارع"),
                    start = decodePoint(r.optJSONObject("start")) ?: PlanPoint(0f, 0f),
                    end = decodePoint(r.optJSONObject("end")) ?: PlanPoint(100f, 0f),
                    widthM = positive(r, "widthM"), classification = r.optString("classification", "unknown")
                )
            }
        }
        val north = o.optDouble("northDeg", Double.NaN).takeIf { !it.isNaN() }?.toFloat() ?: legacyNorth
        return SiteContext(
            countryCode = o.optString("countryCode", "SA"), city = o.optString("city", ""),
            plotBoundary = decodePoints(o.optJSONArray("plotBoundary")), roads = roads, northDeg = north,
            frontSetbackM = positive(o, "frontSetbackM"), rearSetbackM = positive(o, "rearSetbackM"), sideSetbackM = positive(o, "sideSetbackM")
        )
    }

    private fun decodeFloors(a: JSONArray?): List<FloorLevel> {
        if (a == null) return emptyList()
        val out = mutableListOf<FloorLevel>()
        for (i in 0 until a.length()) {
            val f = a.optJSONObject(i) ?: continue
            out += FloorLevel(
                id = f.optString("id", "floor-$i"), name = f.optString("name", "الدور ${i + 1}"), index = f.optInt("index", i),
                elevationM = f.optDouble("elevationM", 0.0), clearHeightM = positive(f, "clearHeightM"),
                footprint = decodePoints(f.optJSONArray("footprint")), rooms = decodeRooms(f.optJSONArray("rooms")),
                walls = decodeWalls(f.optJSONArray("walls")), openings = decodeOpenings(f.optJSONArray("openings")),
                elements = decodeElements(f.optJSONArray("elements"))
            )
        }
        return out
    }

    private fun encodePoint(p: PlanPoint): JSONObject = JSONObject().put("x", p.x).put("y", p.y)

    private fun encodePoints(points: List<PlanPoint>): JSONArray = JSONArray().apply {
        points.forEach { put(encodePoint(it)) }
    }

    private fun decodePoint(o: JSONObject?): PlanPoint? = o?.let {
        PlanPoint(it.optDouble("x", 0.0).toFloat().coerceIn(0f, 100f), it.optDouble("y", 0.0).toFloat().coerceIn(0f, 100f))
    }

    private fun decodePoints(a: JSONArray?): List<PlanPoint> {
        if (a == null) return emptyList()
        val out = mutableListOf<PlanPoint>()
        for (i in 0 until a.length()) decodePoint(a.optJSONObject(i))?.let(out::add)
        return out
    }

    private fun decodeStrings(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let(out::add)
        return out
    }

    private fun positive(o: JSONObject, key: String): Double? = o.optDouble(key, Double.NaN).takeIf { !it.isNaN() && it > 0.0 }
}
