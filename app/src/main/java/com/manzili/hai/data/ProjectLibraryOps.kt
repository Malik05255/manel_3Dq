package com.manzili.hai.data

import com.manzili.hai.engine.ProjectMemoryEngine
import com.manzili.hai.model.FloorPlan

/** Project-level operations layered over ProjectPlanStore without mixing project histories. */
class ProjectLibraryOps(private val store: ProjectPlanStore) {
    fun rename(projectId: String, title: String): FloorPlan? {
        val clean = title.trim()
        if (clean.isBlank()) return store.load(projectId)
        val previousActive = store.activeProjectId()
        val current = store.open(projectId) ?: return null
        val renamed = ProjectMemoryEngine.reconcile(current.copy(title = clean))
        store.save(renamed)
        if (previousActive != null && previousActive != projectId) store.open(previousActive)
        return renamed
    }

    fun duplicate(projectId: String, title: String? = null): FloorPlan? {
        val source = store.load(projectId) ?: return null
        val clean = title?.trim()?.takeIf { it.isNotBlank() } ?: "نسخة من ${source.title}"
        val copy = ProjectMemoryEngine.reconcile(source.copy(title = clean, revision = 1))
        val newId = store.createProject(copy)
        return store.load(newId)
    }

    fun delete(projectId: String): FloorPlan? = store.forgetProject(projectId)
}
