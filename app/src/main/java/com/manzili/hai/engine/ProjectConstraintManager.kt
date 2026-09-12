package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan

object ProjectConstraintManager {
    fun setActive(plan: FloorPlan, constraintId: String, active: Boolean): FloorPlan {
        val constraints = plan.constraints.map { if (it.id == constraintId) it.copy(active = active) else it }
        return ProjectMemoryEngine.reconcile(plan.copy(constraints = constraints))
    }
}
