package com.manzili.hai.data

import android.content.Context
import com.manzili.hai.engine.ProjectMemoryEngine
import com.manzili.hai.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ProjectPlanStore(context: Context) {
    data class ProjectSummary(val id: String, val title: String, val revision: Int, val activeConstraints: Int, val roomCount: Int, val updatedAt: Long, val active: Boolean)
    data class VersionSummary(val projectId: String, val revision: Int, val savedAt: Long, val activeConstraints: Int)

    private val prefs = context.getSharedPreferences("manzili_hai_project", Context.MODE_PRIVATE)

    init { migrateSinglePlan() }

    fun activeProjectId(): String? = prefs.getString(KEY_ACTIVE, null)

    fun createProject(plan: FloorPlan): String {
        val id = UUID.randomUUID().toString()
        val ids = ids().toMutableSet().apply { add(id) }
        prefs.edit().putStringSet(KEY_IDS, ids).putString(KEY_ACTIVE, id).apply()
        persist(id, ProjectMemoryEngine.reconcile(plan), true)
        return id
    }

    fun save(plan: FloorPlan) {
        val active = activeProjectId()
        if (active == null) { createProject(plan); return }
        val ready = ProjectMemoryEngine.reconcile(plan)
        persist(active, ready, load(active) != ready)
    }

    fun load(): FloorPlan? {
        activeProjectId()?.let { load(it)?.let { plan -> return plan } }
        val first = listProjects().firstOrNull() ?: return null
        prefs.edit().putString(KEY_ACTIVE, first.id).apply()
        return load(first.id)
    }

    fun load(projectId: String): FloorPlan? {
        if (projectId !in ids()) return null
        val raw = prefs.getString(currentKey(projectId), null) ?: return null
        return runCatching { ProjectMemoryEngine.reconcile(decode(JSONObject(raw))) }.getOrNull()
    }

    fun open(projectId: String): FloorPlan? {
        val plan = load(projectId) ?: return null
        prefs.edit().putString(KEY_ACTIVE, projectId).apply()
        return plan
    }

    fun listProjects(): List<ProjectSummary> = ids().mapNotNull { id ->
        val plan = load(id) ?: return@mapNotNull null
        ProjectSummary(id, plan.title, plan.revision, plan.constraints.count { it.active }, plan.rooms.size, prefs.getLong(updatedKey(id), 0L), id == activeProjectId())
    }.sortedByDescending { it.updatedAt }

    fun listVersions(projectId: String): List<VersionSummary> = snapshots(projectId).mapNotNull { item ->
        val p = item.optJSONObject("plan")?.let { runCatching { decode(it) }.getOrNull() } ?: return@mapNotNull null
        VersionSummary(projectId, item.optInt("revision", p.revision), item.optLong("savedAt", 0L), p.constraints.count { it.active })
    }.sortedWith(compareByDescending<VersionSummary> { it.revision }.thenByDescending { it.savedAt })

    fun loadVersion(projectId: String, revision: Int): FloorPlan? = snapshots(projectId)
        .lastOrNull { it.optInt("revision", -1) == revision }
        ?.optJSONObject("plan")
        ?.let { runCatching { ProjectMemoryEngine.reconcile(decode(it)) }.getOrNull() }

    fun restoreVersion(projectId: String, revision: Int): FloorPlan? {
        val old = loadVersion(projectId, revision) ?: return null
        val current = load(projectId) ?: old
        val restored = ProjectMemoryEngine.reconcile(old.copy(revision = current.revision + 1))
        prefs.edit().putString(KEY_ACTIVE, projectId).apply()
        persist(projectId, restored, true)
        return restored
    }

    fun forgetProject(projectId: String): FloorPlan? {
        val remaining = ids().toMutableSet().apply { remove(projectId) }
        prefs.edit().putStringSet(KEY_IDS, remaining).remove(currentKey(projectId)).remove(versionsKey(projectId)).remove(updatedKey(projectId)).apply()
        if (activeProjectId() == projectId) prefs.edit().remove(KEY_ACTIVE).apply()
        val next = listProjects().firstOrNull()
        if (next != null) prefs.edit().putString(KEY_ACTIVE, next.id).apply()
        return next?.let { load(it.id) }
    }

    private fun persist(id: String, plan: FloorPlan, snapshot: Boolean) {
        val now = System.currentTimeMillis()
        val e = prefs.edit().putString(currentKey(id), encode(plan).toString()).putLong(updatedKey(id), now).putString(KEY_ACTIVE, id)
        if (snapshot) e.putString(versionsKey(id), addSnapshot(id, plan, now).toString())
        e.apply()
    }

    private fun addSnapshot(id: String, plan: FloorPlan, savedAt: Long): JSONArray {
        val all = snapshots(id).toMutableList()
        val encoded = encode(plan)
        val same = all.lastOrNull()?.let { it.optInt("revision", -1) == plan.revision && it.optJSONObject("plan")?.toString() == encoded.toString() } == true
        if (!same) all += JSONObject().put("revision", plan.revision).put("savedAt", savedAt).put("plan", encoded)
        while (all.size > 12) all.removeAt(0)
        return JSONArray().apply { all.forEach { put(it) } }
    }

    private fun snapshots(id: String): List<JSONObject> {
        val raw = prefs.getString(versionsKey(id), null) ?: return emptyList()
        return runCatching { JSONArray(raw).objects() }.getOrDefault(emptyList())
    }

    private fun ids(): Set<String> = prefs.getStringSet(KEY_IDS, emptySet())?.toSet().orEmpty()

    private fun migrateSinglePlan() {
        if (ids().isNotEmpty()) return
        val raw = prefs.getString(KEY_PLAN, null)?.takeIf { it.isNotBlank() } ?: return
        val plan = runCatching { decode(JSONObject(raw)) }.getOrNull() ?: return
        createProject(plan)
    }

    private fun encode(plan: FloorPlan): JSONObject = JSONObject().apply {
        put("title", plan.title); plan.widthM?.let { put("widthM", it) }; plan.heightM?.let { put("heightM", it) }
        put("revision", plan.revision); put("sourceSummary", plan.sourceSummary); put("observations", JSONArray(plan.observations)); put("uncertainties", JSONArray(plan.uncertainties))
        put("rooms", JSONArray().apply { plan.rooms.forEach { r -> put(JSONObject().apply {
            put("id", r.id); put("name", r.name); put("type", r.type); put("x", r.x); put("y", r.y); put("width", r.width); put("height", r.height)
            put("areaM2", r.areaM2); put("confidence", r.confidence); put("locked", r.locked); r.minAreaM2?.let { put("minAreaM2", it) }; r.preferredAreaM2?.let { put("preferredAreaM2", it) }
        }) } })
        put("walls", JSONArray().apply { plan.walls.forEach { w -> put(JSONObject().apply {
            put("id", w.id); put("start", JSONObject().put("x", w.start.x).put("y", w.start.y)); put("end", JSONObject().put("x", w.end.x).put("y", w.end.y))
            w.thicknessCm?.let { put("thicknessCm", it) }; put("kind", w.kind); put("confidence", w.confidence); put("locked", w.locked)
        }) } })
        put("openings", JSONArray().apply { plan.openings.forEach { o -> put(JSONObject().apply {
            put("id", o.id); put("type", o.type); put("x", o.x); put("y", o.y); put("width", o.width); put("rotationDeg", o.rotationDeg); o.wallId?.let { put("wallId", it) }
            put("connectsRoomIds", JSONArray(o.connectsRoomIds)); put("confidence", o.confidence); put("locked", o.locked)
        }) } })
        put("preferences", JSONObject().apply {
            put("privacyPriority", plan.preferences.privacyPriority); put("circulationPriority", plan.preferences.circulationPriority); put("daylightPriority", plan.preferences.daylightPriority)
            put("futureFlexibilityPriority", plan.preferences.futureFlexibilityPriority); put("notes", JSONArray(plan.preferences.notes))
        })
        put("constraints", JSONArray().apply { plan.constraints.forEach { c -> put(JSONObject().apply {
            put("id", c.id); put("kind", c.kind); put("text", c.text); put("targetIds", JSONArray(c.targetIds)); c.value?.let { put("value", it) }
            put("hard", c.hard); put("priority", c.priority); put("active", c.active)
        }) } })
    }

    private fun decode(root: JSONObject): FloorPlan {
        val rooms = root.optJSONArray("rooms")?.objects()?.mapIndexed { i, r -> Room(
            r.optString("id", "r$i"), r.optString("name", "غرفة"), r.optString("type", "room"), r.optDouble("x", 0.0).toFloat(), r.optDouble("y", 0.0).toFloat(),
            r.optDouble("width", 20.0).toFloat(), r.optDouble("height", 20.0).toFloat(), r.optDouble("areaM2", 0.0).coerceAtLeast(0.0), r.optInt("confidence", 80).coerceIn(0, 100),
            r.optBoolean("locked", false), positive(r, "minAreaM2"), positive(r, "preferredAreaM2")
        ) }.orEmpty()
        val walls = root.optJSONArray("walls")?.objects()?.mapIndexed { i, w ->
            val a = w.optJSONObject("start") ?: JSONObject(); val b = w.optJSONObject("end") ?: JSONObject()
            Wall(w.optString("id", "w$i"), PlanPoint(a.optDouble("x", 0.0).toFloat(), a.optDouble("y", 0.0).toFloat()), PlanPoint(b.optDouble("x", 0.0).toFloat(), b.optDouble("y", 0.0).toFloat()), positive(w, "thicknessCm"), w.optString("kind", "unknown"), w.optInt("confidence", 80).coerceIn(0, 100), w.optBoolean("locked", false))
        }.orEmpty()
        val openings = root.optJSONArray("openings")?.objects()?.mapIndexed { i, o -> Opening(
            o.optString("id", "o$i"), o.optString("type", "door"), o.optDouble("x", 0.0).toFloat(), o.optDouble("y", 0.0).toFloat(), o.optDouble("width", 3.0).toFloat(), o.optDouble("rotationDeg", 0.0).toFloat(),
            o.optString("wallId").takeIf { it.isNotBlank() }, strings(o.optJSONArray("connectsRoomIds")), o.optInt("confidence", 80).coerceIn(0, 100), o.optBoolean("locked", false)
        ) }.orEmpty()
        val p = root.optJSONObject("preferences")
        val preferences = PlanPreferences(p?.optInt("privacyPriority", 80)?.coerceIn(0,100) ?: 80, p?.optInt("circulationPriority", 80)?.coerceIn(0,100) ?: 80, p?.optInt("daylightPriority", 70)?.coerceIn(0,100) ?: 70, p?.optInt("futureFlexibilityPriority", 60)?.coerceIn(0,100) ?: 60, strings(p?.optJSONArray("notes")))
        val constraints = root.optJSONArray("constraints")?.objects()?.mapIndexed { i, c -> ProjectConstraint(c.optString("id", "constraint-$i"), c.optString("kind", "NOTE"), c.optString("text", ""), strings(c.optJSONArray("targetIds")), positive(c, "value"), c.optBoolean("hard", true), c.optInt("priority", 90).coerceIn(0,100), c.optBoolean("active", true)) }.orEmpty()
        return FloorPlan(root.optString("title", "مشروعي"), positive(root, "widthM"), positive(root, "heightM"), rooms, walls, openings, strings(root.optJSONArray("observations")), strings(root.optJSONArray("uncertainties")), root.optString("sourceSummary", ""), preferences, constraints, root.optInt("revision", 1).coerceAtLeast(1))
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
    private fun positive(o: JSONObject, key: String): Double? = o.optDouble(key, Double.NaN).takeIf { !it.isNaN() && it > 0.0 }
    private fun currentKey(id: String) = "p_${id}_current"
    private fun versionsKey(id: String) = "p_${id}_versions"
    private fun updatedKey(id: String) = "p_${id}_updated"

    companion object {
        private const val KEY_PLAN = "current_plan_v1"
        private const val KEY_IDS = "project_ids_v2"
        private const val KEY_ACTIVE = "active_project_id_v2"
    }
}
