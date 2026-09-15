package com.manzili.hai.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.manzili.hai.model.FloorPlan
import org.json.JSONObject
import java.util.UUID

/**
 * Short-lived encrypted bridge between a completed reader job and the project the user later approves.
 * It lets ProjectPlanStore capture a before/after correction pair without changing UI contracts.
 */
class ReaderLearningSessionStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = EncryptedSharedPreferences.create(
        appContext,
        PREFS_NAME,
        MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun arm(workId: UUID, readerResult: FloorPlan) {
        prefs.edit()
            .putString(KEY_WORK_ID, workId.toString())
            .putLong(KEY_CREATED_AT, System.currentTimeMillis())
            .putString(KEY_BASELINE_PLAN, PlanStorageCodec.encode(readerResult).toString())
            .commit()
    }

    /** Returns and consumes the baseline only when the next created project is plausibly the imported plan. */
    fun claimFor(approvedPlan: FloorPlan): FloorPlan? {
        val createdAt = prefs.getLong(KEY_CREATED_AT, 0L)
        if (createdAt <= 0L || System.currentTimeMillis() - createdAt > MAX_AGE_MS) {
            clear()
            return null
        }
        val raw = prefs.getString(KEY_BASELINE_PLAN, null) ?: return null
        val baseline = runCatching { PlanStorageCodec.decode(JSONObject(raw)) }.getOrNull() ?: run {
            clear()
            return null
        }
        if (!ReaderLearningEligibility.isLikelySameImportedProject(baseline, approvedPlan)) return null
        clear()
        return baseline
    }

    fun clear() {
        prefs.edit().clear().commit()
    }

    companion object {
        private const val PREFS_NAME = "manzili_hai_reader_learning_session_secure_v1"
        private const val KEY_WORK_ID = "work_id"
        private const val KEY_CREATED_AT = "created_at"
        private const val KEY_BASELINE_PLAN = "baseline_plan"
        private const val MAX_AGE_MS = 6L * 60L * 60L * 1000L
    }
}

object ReaderLearningEligibility {
    fun isLikelySameImportedProject(baseline: FloorPlan, approved: FloorPlan): Boolean {
        if (baseline.title.isNotBlank() && approved.title != baseline.title) return false
        if (baseline.sourceSummary.isNotBlank() && approved.sourceSummary == baseline.sourceSummary) return true

        val baselineIds = buildSet {
            baseline.rooms.forEach { add("r:${it.id}") }
            baseline.walls.forEach { add("w:${it.id}") }
            baseline.openings.forEach { add("o:${it.id}") }
        }
        if (baselineIds.isEmpty()) return baseline.title == approved.title

        val approvedIds = buildSet {
            approved.rooms.forEach { add("r:${it.id}") }
            approved.walls.forEach { add("w:${it.id}") }
            approved.openings.forEach { add("o:${it.id}") }
        }
        return baselineIds.any { it in approvedIds }
    }
}
