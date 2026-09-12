package com.manzili.hai.data

import com.manzili.hai.model.*
import org.json.JSONArray
import org.json.JSONObject

/** Canonical JSON codec for local project persistence. Missing fields remain backward compatible. */
object PlanStorageCodec {
    fun encode(plan: FloorPlan): JSONObject = JSONObject().apply {
        put("schemaVersion", 4)
        put("title", plan.title)
        plan.widthM?.let { put("widthM", it) }
        plan.heightM?.let { put("heightM", it) }
        put("revision", plan.revision)
        put("sourceSummary", plan.sourceSummary)
        put("scaleConfidence", plan.scaleConfidence)
        put("saudiRulesEnabled", plan.saudiRulesEnabled)
        plan.northDeg?.let { put("northDeg", it) }
        put("observations", JSONArray(plan.observations))
        put("uncertainties", JSONArray(plan.uncertainties))
        put("footprint", points(plan.footprint))
        put("dimensions", JSONArray().apply { plan.dimensions.forEach { put(dimension(it)) } })
        put("rooms", JSONArray().apply { plan.rooms.forEach { put(room(it)) } })
        put("walls", JSONArray().apply { plan.walls.forEach { put(wall(it)) } })
        put("openings", JSONArray().apply { plan.openings.forEach { put(opening(it)) } })
        put("preferences", preferences(plan.preferences))
        put("constraints", JSONArray().apply { plan.constraints.forEach { put(constraint(it)) } })
        put("site", site(plan.site))
        put("floors", JSONArray().apply { plan.floors.forEach { put(floor(it)) } })
        plan.activeFloorId?.let { put("activeFloorId", it) }
    }

    fun decode(root: JSONObject): FloorPlan {
        val prefs = root.optJSONObject("preferences")
        val north = root.optDouble("northDeg", Double.NaN).takeIf { !it.isNaN() }?.toFloat()
        return FloorPlan(
            title = root.optString("title", "مشروعي"),
            widthM = positive(root, "widthM"),
            heightM = positive(root, "heightM"),
            rooms = rooms(root.optJSONArray("rooms")),
            walls = walls(root.optJSONArray("walls")),
            openings = openings(root.optJSONArray("openings")),
            observations = strings(root.optJSONArray("observations")),
            uncertainties = strings(root.optJSONArray("uncertainties")),
            sourceSummary = root.optString("sourceSummary", ""),
            preferences = PlanPreferences(
                privacyPriority = prefs?.optInt("privacyPriority", 80)?.coerceIn(0, 100) ?: 80,
                circulationPriority = prefs?.optInt("circulationPriority", 80)?.coerceIn(0, 100) ?: 80,
                daylightPriority = prefs?.optInt("daylightPriority", 70)?.coerceIn(0, 100) ?: 70,
                futureFlexibilityPriority = prefs?.optInt("futureFlexibilityPriority", 60)?.coerceIn(0, 100) ?: 60,
                notes = strings(prefs?.optJSONArray("notes"))
            ),
            constraints = constraints(root.optJSONArray("constraints")),
            revision = root.optInt("revision", 1).coerceAtLeast(1),
            footprint = readPoints(root.optJSONArray("footprint")),
            dimensions = dimensions(root.optJSONArray("dimensions")),
            scaleConfidence = root.optInt("scaleConfidence", 0).coerceIn(0, 100),
            northDeg = north,
            site = decodeSite(root.optJSONObject("site"), north),
            floors = floors(root.optJSONArray("floors")),
            activeFloorId = root.optString("activeFloorId").takeIf { it.isNotBlank() },
            saudiRulesEnabled = root.optBoolean("saudiRulesEnabled", false)
        )
    }

    private fun room(r: Room) = JSONObject().apply {
        put("id", r.id); put("name", r.name); put("type", r.type); put("x", r.x); put("y", r.y); put("width", r.width); put("height", r.height)
        put("areaM2", r.areaM2); put("confidence", r.confidence); put("locked", r.locked)
        r.minAreaM2?.let { put("minAreaM2", it) }; r.preferredAreaM2?.let { put("preferredAreaM2", it) }
        put("polygon", points(r.polygon))
    }

    private fun wall(w: Wall) = JSONObject().apply {
        put("id", w.id); put("start", point(w.start)); put("end", point(w.end)); w.thicknessCm?.let { put("thicknessCm", it) }
        put("kind", w.kind); put("confidence", w.confidence); put("locked", w.locked)
    }

    private fun opening(o: Opening) = JSONObject().apply {
        put("id", o.id); put("type", o.type); put("x", o.x); put("y", o.y); put("width", o.width); put("rotationDeg", o.rotationDeg)
        o.wallId?.let { put("wallId", it) }; put("connectsRoomIds", JSONArray(o.connectsRoomIds)); put("confidence", o.confidence); put("locked", o.locked)
    }

    private fun dimension(d: PlanDimension) = JSONObject().apply {
        put("id", d.id); put("label", d.label); put("valueM", d.valueM); put("axis", d.axis); put("confidence", d.confidence); put("sourceText", d.sourceText)
        d.start?.let { put("start", point(it)) }; d.end?.let { put("end", point(it)) }
    }

    private fun preferences(p: PlanPreferences) = JSONObject().apply {
        put("privacyPriority", p.privacyPriority); put("circulationPriority", p.circulationPriority); put("daylightPriority", p.daylightPriority)
        put("futureFlexibilityPriority", p.futureFlexibilityPriority); put("notes", JSONArray(p.notes))
    }

    private fun constraint(c: ProjectConstraint) = JSONObject().apply {
        put("id", c.id); put("kind", c.kind); put("text", c.text); put("targetIds", JSONArray(c.targetIds)); c.value?.let { put("value", it) }
        put("hard", c.hard); put("priority", c.priority); put("active", c.active)
    }

    private fun site(s: SiteContext) = JSONObject().apply {
        put("countryCode", s.countryCode); put("city", s.city); put("plotBoundary", points(s.plotBoundary)); s.northDeg?.let { put("northDeg", it) }
        s.frontSetbackM?.let { put("frontSetbackM", it) }; s.rearSetbackM?.let { put("rearSetbackM", it) }; s.sideSetbackM?.let { put("sideSetbackM", it) }
        put("roads", JSONArray().apply { s.roads.forEach { r -> put(JSONObject().apply {
            put("id", r.id); put("name", r.name); put("start", point(r.start)); put("end", point(r.end)); r.widthM?.let { put("widthM", it) }; put("classification", r.classification)
        }) } })
    }

    private fun floor(f: FloorLevel) = JSONObject().apply {
        put("id", f.id); put("name", f.name); put("index", f.index); put("elevationM", f.elevationM); f.clearHeightM?.let { put("clearHeightM", it) }
        put("footprint", points(f.footprint)); put("rooms", JSONArray().apply { f.rooms.forEach { put(room(it)) } })
        put("walls", JSONArray().apply { f.walls.forEach { put(wall(it)) } }); put("openings", JSONArray().apply { f.openings.forEach { put(opening(it)) } })
    }

    private fun rooms(a: JSONArray?): List<Room> = a.objects().mapIndexed { i, r -> Room(
        id = r.optString("id", "r$i"), name = r.optString("name", "غرفة"), type = r.optString("type", "room"),
        x = r.optDouble("x", 0.0).toFloat(), y = r.optDouble("y", 0.0).toFloat(), width = r.optDouble("width", 20.0).toFloat(), height = r.optDouble("height", 20.0).toFloat(),
        areaM2 = r.optDouble("areaM2", 0.0).coerceAtLeast(0.0), confidence = r.optInt("confidence", 80).coerceIn(0, 100), locked = r.optBoolean("locked", false),
        minAreaM2 = positive(r, "minAreaM2"), preferredAreaM2 = positive(r, "preferredAreaM2"), polygon = readPoints(r.optJSONArray("polygon"))
    ) }

    private fun walls(a: JSONArray?): List<Wall> = a.objects().mapIndexed { i, w -> Wall(
        id = w.optString("id", "w$i"), start = readPoint(w.optJSONObject("start")) ?: PlanPoint(0f, 0f), end = readPoint(w.optJSONObject("end")) ?: PlanPoint(0f, 0f),
        thicknessCm = positive(w, "thicknessCm"), kind = w.optString("kind", "unknown"), confidence = w.optInt("confidence", 80).coerceIn(0, 100), locked = w.optBoolean("locked", false)
    ) }

    private fun openings(a: JSONArray?): List<Opening> = a.objects().mapIndexed { i, o -> Opening(
        id = o.optString("id", "o$i"), type = o.optString("type", "door"), x = o.optDouble("x", 0.0).toFloat(), y = o.optDouble("y", 0.0).toFloat(),
        width = o.optDouble("width", 3.0).toFloat(), rotationDeg = o.optDouble("rotationDeg", 0.0).toFloat(), wallId = o.optString("wallId").takeIf { it.isNotBlank() },
        connectsRoomIds = strings(o.optJSONArray("connectsRoomIds")), confidence = o.optInt("confidence", 80).coerceIn(0, 100), locked = o.optBoolean("locked", false)
    ) }

    private fun dimensions(a: JSONArray?): List<PlanDimension> = a.objects().mapIndexedNotNull { i, d ->
        val value = positive(d, "valueM") ?: return@mapIndexedNotNull null
        PlanDimension(d.optString("id", "d$i"), d.optString("label", "بعد"), value, d.optString("axis", "unknown"), readPoint(d.optJSONObject("start")), readPoint(d.optJSONObject("end")), d.optInt("confidence", 70).coerceIn(0, 100), d.optString("sourceText", ""))
    }

    private fun constraints(a: JSONArray?): List<ProjectConstraint> = a.objects().mapIndexed { i, c -> ProjectConstraint(
        c.optString("id", "constraint-$i"), c.optString("kind", "NOTE"), c.optString("text", ""), strings(c.optJSONArray("targetIds")), positive(c, "value"), c.optBoolean("hard", true), c.optInt("priority", 90).coerceIn(0, 100), c.optBoolean("active", true)
    ) }

    private fun decodeSite(o: JSONObject?, legacyNorth: Float?): SiteContext {
        if (o == null) return SiteContext(northDeg = legacyNorth)
        val roads = o.optJSONArray("roads").objects().mapIndexed { i, r -> RoadEdge(
            id = r.optString("id", "road-$i"), name = r.optString("name", "شارع"), start = readPoint(r.optJSONObject("start")) ?: PlanPoint(0f, 0f),
            end = readPoint(r.optJSONObject("end")) ?: PlanPoint(100f, 0f), widthM = positive(r, "widthM"), classification = r.optString("classification", "unknown")
        )
        return SiteContext(
            countryCode = o.optString("countryCode", "SA"), city = o.optString("city", ""), plotBoundary = readPoints(o.optJSONArray("plotBoundary")), roads = roads,
            northDeg = o.optDouble("northDeg", Double.NaN).takeIf { !it.isNaN() }?.toFloat() ?: legacyNorth,
            frontSetbackM = positive(o, "frontSetbackM"), rearSetbackM = positive(o, "rearSetbackM"), sideSetbackM = positive(o, "sideSetbackM")
        )
    }

    private fun floors(a: JSONArray?): List<FloorLevel> = a.objects().mapIndexed { i, f -> FloorLevel(
        id = f.optString("id", "floor-$i"), name = f.optString("name", "الدور ${i + 1}"), index = f.optInt("index", i), elevationM = f.optDouble("elevationM", 0.0),
        clearHeightM = positive(f, "clearHeightM"), footprint = readPoints(f.optJSONArray("footprint")), rooms = rooms(f.optJSONArray("rooms")), walls = walls(f.optJSONArray("walls")), openings = openings(f.optJSONArray("openings"))
    ) }

    private fun point(p: PlanPoint) = JSONObject().put("x", p.x).put("y", p.y)
    private fun points(list: List<PlanPoint>) = JSONArray().apply { list.forEach { put(point(it)) } }
    private fun readPoint(o: JSONObject?): PlanPoint? = o?.let { PlanPoint(it.optDouble("x", 0.0).toFloat().coerceIn(0f, 100f), it.optDouble("y", 0.0).toFloat().coerceIn(0f, 100f)) }
    private fun readPoints(a: JSONArray?): List<PlanPoint> = a.objects().mapNotNull { readPoint(it) }
    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
    private fun positive(o: JSONObject, key: String): Double? = o.optDouble(key, Double.NaN).takeIf { !it.isNaN() && it > 0.0 }
}