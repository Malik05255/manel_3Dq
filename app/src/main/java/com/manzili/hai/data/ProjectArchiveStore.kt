package com.manzili.hai.data

import com.manzili.hai.engine.ProjectMemoryEngine
import com.manzili.hai.model.FloorPlan
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Portable HAI project archive. Schema 5 preserves spatial OCR evidence and Geometry V3 elements/topology. */
class ProjectArchiveStore(private val store: ProjectPlanStore) {
    data class ImportResult(val projectId: String, val plan: FloorPlan, val restoredVersions: Int)

    fun exportProject(projectId: String): String? {
        val current = store.load(projectId) ?: return null
        val versions = store.listVersions(projectId)
            .mapNotNull { store.loadVersion(projectId, it.revision) }
            .distinctBy { it.revision }
            .sortedBy { it.revision }
        val history = if (versions.any { it.revision == current.revision }) versions else versions + current
        val payload = JSONArray().apply { history.forEach { put(PlanStorageCodec.encode(it)) } }
        return JSONObject().apply {
            put("format", "manzili-hai-project")
            put("schemaVersion", 5)
            put("exportedAt", System.currentTimeMillis())
            put("currentRevision", current.revision)
            put("plans", payload)
            put("sha256", digest(payload.toString()))
        }.toString(2)
    }

    fun importProject(raw: String): ImportResult? {
        if (raw.length !in 20..16_000_000) return null
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val schema = root.optInt("schemaVersion", -1)
        if (root.optString("format") != "manzili-hai-project" || schema !in 1..5) return null
        val payload = root.optJSONArray("plans") ?: return null
        if (payload.length() !in 1..50 || root.optString("sha256") != digest(payload.toString())) return null
        val plans = (0 until payload.length())
            .mapNotNull { payload.optJSONObject(it)?.let { json -> runCatching { PlanStorageCodec.decode(json) }.getOrNull() } }
            .distinctBy { it.revision }
            .sortedBy { it.revision }
        if (plans.isEmpty()) return null
        val currentRevision = root.optInt("currentRevision", plans.last().revision)
        val ordered = plans.filter { it.revision != currentRevision } + listOfNotNull(plans.firstOrNull { it.revision == currentRevision } ?: plans.last())
        val first = ProjectMemoryEngine.reconcile(ordered.first())
        val id = store.createProject(first)
        ordered.drop(1).forEach { store.save(ProjectMemoryEngine.reconcile(it)) }
        val plan = store.load(id) ?: return null
        return ImportResult(id, plan, ordered.size)
    }

    private fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
