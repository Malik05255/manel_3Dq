package com.manzili.hai.data

import android.content.Context
import android.net.Uri

/**
 * Keeps the persisted document URI for each imported project so the original PDF/image can be
 * shown again after process death or app restart. The app already takes a persistable URI grant
 * at import time; this store only keeps the reference, never copies the user's document.
 */
class ProjectSourceStore(context: Context) {
    private val prefs = context.getSharedPreferences("manzili_hai_project_sources", Context.MODE_PRIVATE)

    fun save(projectId: String, uri: Uri?) {
        if (projectId.isBlank()) return
        if (uri == null) {
            prefs.edit().remove(key(projectId)).apply()
        } else {
            prefs.edit().putString(key(projectId), uri.toString()).apply()
        }
    }

    fun load(projectId: String?): Uri? {
        if (projectId.isNullOrBlank()) return null
        val raw = prefs.getString(key(projectId), null)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { Uri.parse(raw) }.getOrNull()
    }

    fun delete(projectId: String) {
        if (projectId.isNotBlank()) prefs.edit().remove(key(projectId)).apply()
    }

    fun copy(fromProjectId: String, toProjectId: String) {
        save(toProjectId, load(fromProjectId))
    }

    private fun key(projectId: String) = "source_$projectId"
}
