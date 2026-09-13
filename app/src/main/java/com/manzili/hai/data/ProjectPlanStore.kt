package com.manzili.hai.data

import android.content.Context
import com.manzili.hai.engine.ProjectMemoryEngine
import com.manzili.hai.model.FloorPlan
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ProjectPlanStore(context: Context) {
    data class ProjectSummary(val id: String, val title: String, val revision: Int, val activeConstraints: Int, val roomCount: Int, val updatedAt: Long, val active: Boolean)
    data class VersionSummary(val projectId: String, val revision: Int, val savedAt: Long, val activeConstraints: Int)

    private val prefs = context.getSharedPreferences("manzili_hai_project", Context.MODE_PRIVATE)

    init { migrateSinglePlan() }

    fun activeProjectId(): String? = prefs.getString(KEY_ACTIVE, null)
    fun contains(projectId: String): Boolean = projectId in ids()

    fun createProject(plan: FloorPlan): String {
        val id = UUID.randomUUID().toString()
        upsertProject(id, plan, makeActive = true)
        return id
    }

    /** Insert or replace a project under a stable ID. Used by cloud sync to avoid duplicate IDs. */
    fun upsertProject(projectId: String, plan: FloorPlan, makeActive: Boolean = true): FloorPlan {
        require(projectId.isNotBlank()) { "projectId must not be blank" }
        val ready = ProjectMemoryEngine.reconcile(plan)
        val ids = ids().toMutableSet().apply { add(projectId) }
        val edit = prefs.edit().putStringSet(KEY_IDS, ids)
        if (makeActive) edit.putString(KEY_ACTIVE, projectId)
        edit.apply()
        persist(projectId, ready, snapshot = load(projectId) != ready, makeActive = makeActive)
        return ready
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
        return runCatching { ProjectMemoryEngine.reconcile(PlanStorageCodec.decode(JSONObject(raw))) }.getOrNull()
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
        val p = item.optJSONObject("plan")?.let { runCatching { PlanStorageCodec.decode(it) }.getOrNull() } ?: return@mapNotNull null
        VersionSummary(projectId, item.optInt("revision", p.revision), item.optLong("savedAt", 0L), p.constraints.count { it.active })
    }.sortedWith(compareByDescending<VersionSummary> { it.revision }.thenByDescending { it.savedAt })

    fun loadVersion(projectId: String, revision: Int): FloorPlan? = snapshots(projectId)
        .lastOrNull { it.optInt("revision", -1) == revision }
        ?.optJSONObject("plan")
        ?.let { runCatching { ProjectMemoryEngine.reconcile(PlanStorageCodec.decode(it)) }.getOrNull() }

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

    private fun persist(id: String, plan: FloorPlan, snapshot: Boolean, makeActive: Boolean = true) {
        val now = System.currentTimeMillis()
        val encoded = PlanStorageCodec.encode(plan)
        val e = prefs.edit().putString(currentKey(id), encoded.toString()).putLong(updatedKey(id), now)
        if (makeActive) e.putString(KEY_ACTIVE, id)
        if (snapshot) e.putString(versionsKey(id), addSnapshot(id, plan, now).toString())
        e.apply()
    }

    private fun addSnapshot(id: String, plan: FloorPlan, savedAt: Long): JSONArray {
        val all = snapshots(id).toMutableList()
        val encoded = PlanStorageCodec.encode(plan)
        val same = all.lastOrNull()?.let { it.optInt("revision", -1) == plan.revision && it.optJSONObject("plan")?.toString() == encoded.toString() } == true
        if (!same) all += JSONObject().put("revision", plan.revision).put("savedAt", savedAt).put("plan", encoded)
        while (all.size > 12) all.removeAt(0)
        return JSONArray().apply { all.forEach { put(it) } }
    }

    private fun snapshots(id: String): List<JSONObject> {
        val raw = prefs.getString(versionsKey(id), null) ?: return emptyList()
        return runCatching { val a = JSONArray(raw); (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.getOrDefault(emptyList())
    }

    private fun ids(): Set<String> = prefs.getStringSet(KEY_IDS, emptySet())?.toSet().orEmpty()

    private fun migrateSinglePlan() {
        if (ids().isNotEmpty()) return
        val raw = prefs.getString(KEY_PLAN, null)?.takeIf { it.isNotBlank() } ?: return
        val plan = runCatching { PlanStorageCodec.decode(JSONObject(raw)) }.getOrNull() ?: return
        createProject(plan)
    }

    private fun currentKey(id: String) = "p_${id}_current"
    private fun versionsKey(id: String) = "p_${id}_versions"
    private fun updatedKey(id: String) = "p_${id}_updated"

    companion object {
        private const val KEY_PLAN = "current_plan_v1"
        private const val KEY_IDS = "project_ids_v2"
        private const val KEY_ACTIVE = "active_project_id_v2"
    }
}
