package com.manzili.hai.data

import com.manzili.hai.engine.ProjectMemoryEngine
import com.manzili.hai.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Portable HAI project archive. Schema 2 preserves canonical polygon/scale evidence. */
class ProjectArchiveStore(private val store: ProjectPlanStore) {
    data class ImportResult(val projectId: String, val plan: FloorPlan, val restoredVersions: Int)

    fun exportProject(projectId: String): String? {
        val current = store.load(projectId) ?: return null
        val versions = store.listVersions(projectId).mapNotNull { store.loadVersion(projectId, it.revision) }.distinctBy { it.revision }.sortedBy { it.revision }
        val history = if (versions.any { it.revision == current.revision }) versions else versions + current
        val payload = JSONArray().apply { history.forEach { put(encode(it)) } }
        return JSONObject().apply {
            put("format", "manzili-hai-project")
            put("schemaVersion", 2)
            put("exportedAt", System.currentTimeMillis())
            put("currentRevision", current.revision)
            put("plans", payload)
            put("sha256", digest(payload.toString()))
        }.toString(2)
    }

    fun importProject(raw: String): ImportResult? {
        if (raw.length !in 20..12_000_000) return null
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val schema = root.optInt("schemaVersion", -1)
        if (root.optString("format") != "manzili-hai-project" || schema !in 1..2) return null
        val payload = root.optJSONArray("plans") ?: return null
        if (payload.length() !in 1..50 || root.optString("sha256") != digest(payload.toString())) return null
        val plans = (0 until payload.length()).mapNotNull { payload.optJSONObject(it)?.let(::decode) }.distinctBy { it.revision }.sortedBy { it.revision }
        if (plans.isEmpty()) return null
        val currentRevision = root.optInt("currentRevision", plans.last().revision)
        val ordered = plans.filter { it.revision != currentRevision } + listOfNotNull(plans.firstOrNull { it.revision == currentRevision } ?: plans.last())
        val first = ProjectMemoryEngine.reconcile(ordered.first())
        val id = store.createProject(first)
        ordered.drop(1).forEach { store.save(ProjectMemoryEngine.reconcile(it)) }
        val plan = store.load(id) ?: return null
        return ImportResult(id, plan, ordered.size)
    }

    private fun encode(plan: FloorPlan): JSONObject = JSONObject().apply {
        put("title", plan.title); plan.widthM?.let { put("widthM", it) }; plan.heightM?.let { put("heightM", it) }
        put("revision", plan.revision); put("sourceSummary", plan.sourceSummary); put("scaleConfidence", plan.scaleConfidence); plan.northDeg?.let { put("northDeg", it) }
        put("observations", JSONArray(plan.observations)); put("uncertainties", JSONArray(plan.uncertainties))
        put("footprint", points(plan.footprint))
        put("dimensions", JSONArray().apply { plan.dimensions.forEach { d -> put(JSONObject().apply {
            put("id", d.id); put("label", d.label); put("valueM", d.valueM); put("axis", d.axis); put("confidence", d.confidence); put("sourceText", d.sourceText)
            d.start?.let { put("start", point(it)) }; d.end?.let { put("end", point(it)) }
        }) } })
        put("rooms", JSONArray().apply { plan.rooms.forEach { r -> put(JSONObject().apply {
            put("id", r.id); put("name", r.name); put("type", r.type); put("x", r.x); put("y", r.y); put("width", r.width); put("height", r.height)
            put("areaM2", r.areaM2); put("confidence", r.confidence); put("locked", r.locked); r.minAreaM2?.let { put("minAreaM2", it) }; r.preferredAreaM2?.let { put("preferredAreaM2", it) }
            put("polygon", points(r.polygon))
        }) } })
        put("walls", JSONArray().apply { plan.walls.forEach { w -> put(JSONObject().apply {
            put("id", w.id); put("start", point(w.start)); put("end", point(w.end)); w.thicknessCm?.let { put("thicknessCm", it) }; put("kind", w.kind); put("confidence", w.confidence); put("locked", w.locked)
        }) } })
        put("openings", JSONArray().apply { plan.openings.forEach { o -> put(JSONObject().apply {
            put("id", o.id); put("type", o.type); put("x", o.x); put("y", o.y); put("width", o.width); put("rotationDeg", o.rotationDeg)
            o.wallId?.let { put("wallId", it) }; put("connectsRoomIds", JSONArray(o.connectsRoomIds)); put("confidence", o.confidence); put("locked", o.locked)
        }) } })
        put("preferences", JSONObject().apply {
            put("privacyPriority", plan.preferences.privacyPriority); put("circulationPriority", plan.preferences.circulationPriority); put("daylightPriority", plan.preferences.daylightPriority)
            put("futureFlexibilityPriority", plan.preferences.futureFlexibilityPriority); put("notes", JSONArray(plan.preferences.notes))
        })
        put("constraints", JSONArray().apply { plan.constraints.forEach { c -> put(JSONObject().apply {
            put("id", c.id); put("kind", c.kind); put("text", c.text); put("targetIds", JSONArray(c.targetIds)); c.value?.let { put("value", it) }; put("hard", c.hard); put("priority", c.priority); put("active", c.active)
        }) } })
    }

    private fun decode(root: JSONObject): FloorPlan? = runCatching {
        val rooms = root.optJSONArray("rooms")?.objects()?.mapIndexed { i, r -> Room(
            r.optString("id", "r$i"), r.optString("name", "غرفة"), r.optString("type", "room"), r.optDouble("x",0.0).toFloat(), r.optDouble("y",0.0).toFloat(),
            r.optDouble("width",20.0).toFloat(), r.optDouble("height",20.0).toFloat(), r.optDouble("areaM2",0.0).coerceAtLeast(0.0), r.optInt("confidence",80).coerceIn(0,100),
            r.optBoolean("locked",false), positive(r,"minAreaM2"), positive(r,"preferredAreaM2"), readPoints(r.optJSONArray("polygon"))
        ) }.orEmpty()
        val walls = root.optJSONArray("walls")?.objects()?.mapIndexed { i, w -> Wall(
            w.optString("id","w$i"), readPoint(w.optJSONObject("start")) ?: PlanPoint(0f,0f), readPoint(w.optJSONObject("end")) ?: PlanPoint(0f,0f),
            positive(w,"thicknessCm"), w.optString("kind","unknown"), w.optInt("confidence",80).coerceIn(0,100), w.optBoolean("locked",false)
        ) }.orEmpty()
        val openings = root.optJSONArray("openings")?.objects()?.mapIndexed { i, o -> Opening(
            o.optString("id","o$i"), o.optString("type","door"), o.optDouble("x",0.0).toFloat(), o.optDouble("y",0.0).toFloat(), o.optDouble("width",3.0).toFloat(), o.optDouble("rotationDeg",0.0).toFloat(),
            o.optString("wallId").takeIf { it.isNotBlank() }, strings(o.optJSONArray("connectsRoomIds")), o.optInt("confidence",80).coerceIn(0,100), o.optBoolean("locked",false)
        ) }.orEmpty()
        val dimensions = root.optJSONArray("dimensions")?.objects()?.mapIndexedNotNull { i, d ->
            val value = positive(d,"valueM") ?: return@mapIndexedNotNull null
            PlanDimension(d.optString("id","d$i"), d.optString("label","بعد"), value, d.optString("axis","unknown"), readPoint(d.optJSONObject("start")), readPoint(d.optJSONObject("end")), d.optInt("confidence",70).coerceIn(0,100), d.optString("sourceText",""))
        }.orEmpty()
        val p = root.optJSONObject("preferences")
        val prefs = PlanPreferences(p?.optInt("privacyPriority",80)?.coerceIn(0,100) ?: 80, p?.optInt("circulationPriority",80)?.coerceIn(0,100) ?: 80, p?.optInt("daylightPriority",70)?.coerceIn(0,100) ?: 70, p?.optInt("futureFlexibilityPriority",60)?.coerceIn(0,100) ?: 60, strings(p?.optJSONArray("notes")))
        val constraints = root.optJSONArray("constraints")?.objects()?.mapIndexed { i, c -> ProjectConstraint(c.optString("id","constraint-$i"), c.optString("kind","NOTE"), c.optString("text",""), strings(c.optJSONArray("targetIds")), positive(c,"value"), c.optBoolean("hard",true), c.optInt("priority",90).coerceIn(0,100), c.optBoolean("active",true)) }.orEmpty()
        FloorPlan(
            title = root.optString("title","مشروعي"), widthM = positive(root,"widthM"), heightM = positive(root,"heightM"), rooms = rooms, walls = walls, openings = openings,
            observations = strings(root.optJSONArray("observations")), uncertainties = strings(root.optJSONArray("uncertainties")), sourceSummary = root.optString("sourceSummary",""), preferences = prefs,
            constraints = constraints, revision = root.optInt("revision",1).coerceAtLeast(1), footprint = readPoints(root.optJSONArray("footprint")), dimensions = dimensions,
            scaleConfidence = root.optInt("scaleConfidence",0).coerceIn(0,100), northDeg = root.optDouble("northDeg",Double.NaN).takeIf { !it.isNaN() }?.toFloat()
        )
    }.getOrNull()

    private fun point(p: PlanPoint) = JSONObject().put("x", p.x).put("y", p.y)
    private fun points(list: List<PlanPoint>) = JSONArray().apply { list.forEach { put(point(it)) } }
    private fun readPoint(o: JSONObject?): PlanPoint? = o?.let { PlanPoint(it.optDouble("x",0.0).toFloat().coerceIn(0f,100f), it.optDouble("y",0.0).toFloat().coerceIn(0f,100f)) }
    private fun readPoints(a: JSONArray?): List<PlanPoint> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { readPoint(a.optJSONObject(it)) }
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
    private fun positive(o: JSONObject, key: String): Double? = o.optDouble(key, Double.NaN).takeIf { !it.isNaN() && it > 0.0 }
    private fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
