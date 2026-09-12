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

    fun confirm(plan: FloorPlan, openingId: String, heightM: Double, sillHeightM: Double? = null): FloorPlan {
        val opening = allOpenings(plan).firstOrNull { it.id == openingId } ?: return plan
        if (heightM !in 0.4..6.0) return plan
        val window = isWindow(opening.type)
        if (window && (sillHeightM == null || sillHeightM !in 0.0..3.5)) return plan
        val next = plan.constraints.filterNot {
            openingId in it.targetIds && it.kind in setOf(HEIGHT_KIND, SILL_KIND)
        }.toMutableList()
        next += ProjectConstraint(
            id = "opening-height-$openingId",
            kind = HEIGHT_KIND,
            text = "ارتفاع فتحة مؤكد من المستخدم",
            targetIds = listOf(openingId),
            value = heightM,
            hard = true,
            priority = 100,
            active = true
        )
        if (window) {
            next += ProjectConstraint(
                id = "opening-sill-$openingId",
                kind = SILL_KIND,
                text = "جلسة نافذة مؤكدة من المستخدم",
                targetIds = listOf(openingId),
                value = sillHeightM,
                hard = true,
                priority = 100,
                active = true
            )
        }
        return plan.copy(constraints = next)
    }

    fun clear(plan: FloorPlan, openingId: String): FloorPlan = plan.copy(
        constraints = plan.constraints.filterNot {
            openingId in it.targetIds && it.kind in setOf(HEIGHT_KIND, SILL_KIND)
        }
    )

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
