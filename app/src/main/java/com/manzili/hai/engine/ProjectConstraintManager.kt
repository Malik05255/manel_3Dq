package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.ProjectConstraint

object ProjectConstraintManager {
    fun setActive(plan: FloorPlan, constraintId: String, active: Boolean): FloorPlan {
        val changed = plan.constraints.firstOrNull { it.id == constraintId } ?: return plan
        val constraints = plan.constraints.map { if (it.id == constraintId) it.copy(active = active) else it }
        return normalize(plan.copy(constraints = constraints), changed)
    }

    fun forget(plan: FloorPlan, constraintId: String): FloorPlan {
        val changed = plan.constraints.firstOrNull { it.id == constraintId } ?: return plan
        return normalize(plan.copy(constraints = plan.constraints.filterNot { it.id == constraintId }), changed)
    }

    fun updateValue(plan: FloorPlan, constraintId: String, value: Double): FloorPlan {
        val changed = plan.constraints.firstOrNull { it.id == constraintId } ?: return plan
        val constraints = plan.constraints.map {
            if (it.id == constraintId) it.copy(value = value.coerceAtLeast(0.1)) else it
        }
        return normalize(plan.copy(constraints = constraints), changed)
    }

    fun describe(plan: FloorPlan, c: ProjectConstraint): String = when (c.kind) {
        ProjectMemoryEngine.LOCK_ELEMENT -> {
            val labels = c.targetIds.map { id -> plan.rooms.firstOrNull { it.id == id }?.name ?: id }
            "عدم تغيير ${labels.joinToString("، ").ifBlank { "العنصر" }}"
        }
        ProjectMemoryEngine.MIN_ROOM_AREA -> {
            val room = c.targetIds.firstOrNull()?.let { id -> plan.rooms.firstOrNull { it.id == id } }
            "${room?.name ?: "الغرفة"} لا تصغر عن ${"%.1f".format(c.value ?: 0.0)}م²"
        }
        ProjectMemoryEngine.GUEST_ENTRANCE_INDEPENDENT -> "مدخل الضيوف/المجلس مستقل"
        ProjectMemoryEngine.PRIORITY_PRIVACY -> "الخصوصية أولوية عليا"
        ProjectMemoryEngine.PRIORITY_CIRCULATION -> "سهولة الحركة وتقليل الممرات أولوية"
        ProjectMemoryEngine.PRIORITY_DAYLIGHT -> "الإضاءة الطبيعية أولوية"
        else -> c.text.ifBlank { c.kind }
    }

    private fun normalize(plan: FloorPlan, changed: ProjectConstraint): FloorPlan {
        val active = plan.constraints.filter { it.active }
        var next = plan

        if (changed.kind == ProjectMemoryEngine.LOCK_ELEMENT) {
            val activeLocks = active.filter { it.kind == ProjectMemoryEngine.LOCK_ELEMENT }.flatMap { it.targetIds }.toSet()
            val touched = changed.targetIds.toSet()
            next = next.copy(
                rooms = next.rooms.map { if (it.id in touched) it.copy(locked = it.id in activeLocks) else it },
                walls = next.walls.map { if (it.id in touched) it.copy(locked = it.id in activeLocks) else it },
                openings = next.openings.map { if (it.id in touched) it.copy(locked = it.id in activeLocks) else it }
            )
        }

        if (changed.kind == ProjectMemoryEngine.MIN_ROOM_AREA) {
            val touched = changed.targetIds.toSet()
            val mins = active.filter { it.kind == ProjectMemoryEngine.MIN_ROOM_AREA && it.value != null }
                .flatMap { rule -> rule.targetIds.map { it to rule.value!! } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, values) -> values.maxOrNull() }
            next = next.copy(rooms = next.rooms.map { room ->
                if (room.id in touched) room.copy(minAreaM2 = mins[room.id]) else room
            })
        }

        return ProjectMemoryEngine.reconcile(next)
    }
}
