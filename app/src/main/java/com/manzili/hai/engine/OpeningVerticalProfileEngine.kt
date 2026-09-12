package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.Opening
import com.manzili.hai.model.ProjectConstraint

object OpeningVerticalProfileEngine {
    const val HEIGHT_KIND = "OPENING_HEIGHT_M"
    const val SILL_KIND = "OPENING_SILL_M"

    data class Profile(
        val openingId: String,
        val heightM: Double?,
        val sillHeightM: Double?,
        val confidence: Int,
        val verified: Boolean
    )

    fun profile(plan: FloorPlan, opening: Opening): Profile {
        val height = active(plan, HEIGHT_KIND, opening.id)
        val sill = active(plan, SILL_KIND, opening.id)
        val window = isWindow(opening.type)
        val confidence = listOfNotNull(height?.priority, sill?.priority).minOrNull() ?: 0
        val verified = height?.value?.let { it > 0.0 } == true && (!window || sill?.value != null) && confidence >= 70
        return Profile(opening.id, height?.value, if (window) sill?.value else 0.0, confidence, verified)
    }

    fun unverifiedLinkedOpenings(plan: FloorPlan): List<Opening> = allOpenings(plan)
        .filter { !it.wallId.isNullOrBlank() }
        .filterNot { profile(plan, it).verified }

    private fun active(plan: FloorPlan, kind: String, openingId: String): ProjectConstraint? =
        plan.constraints.lastOrNull { it.active && it.kind == kind && openingId in it.targetIds }

    private fun allOpenings(plan: FloorPlan): List<Opening> =
        (plan.openings + plan.floors.flatMap { it.openings }).distinctBy { it.id }

    fun isWindow(type: String): Boolean {
        val t = type.lowercase()
        return "window" in t || "شباك" in t || "نافذة" in t
    }
}
