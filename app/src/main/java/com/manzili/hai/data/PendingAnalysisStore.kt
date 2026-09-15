package com.manzili.hai.data

import android.content.Context
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.manzili.hai.engine.SaudiProjectTypeEngine
import com.manzili.hai.model.FloorPlan
import org.json.JSONObject
import java.util.UUID

/** Durable hand-off between the background floor-plan worker and the Compose UI. */
class PendingAnalysisStore(context: Context) {
    data class Completed(
        val workId: UUID,
        val source: Uri,
        val type: SaudiProjectTypeEngine.Type,
        val plan: FloorPlan,
    )

    private val appContext = context.applicationContext
    private val prefs = EncryptedSharedPreferences.create(
        appContext,
        PREFS_NAME,
        MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun begin(workId: UUID, source: Uri, type: SaudiProjectTypeEngine.Type) {
        prefs.edit()
            .putString(KEY_ACTIVE_WORK_ID, workId.toString())
            .putString(KEY_SOURCE_URI, source.toString())
            .putString(KEY_PROJECT_TYPE, type.name)
            .remove(KEY_RESULT_WORK_ID)
            .remove(KEY_RESULT_PLAN)
            .remove(KEY_FAILURE)
            .commit()
    }

    fun activeWorkId(): UUID? = prefs.getString(KEY_ACTIVE_WORK_ID, null)
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    fun saveResult(workId: UUID, plan: FloorPlan): Boolean {
        if (activeWorkId() != workId) return false
        val saved = prefs.edit()
            .putString(KEY_RESULT_WORK_ID, workId.toString())
            .putString(KEY_RESULT_PLAN, PlanStorageCodec.encode(plan).toString())
            .remove(KEY_FAILURE)
            .commit()
        if (saved) ReaderLearningSessionStore(appContext).arm(workId, plan)
        return saved
    }

    fun markFailure(workId: UUID, message: String) {
        if (activeWorkId() != workId) return
        prefs.edit().putString(KEY_FAILURE, message.take(500)).commit()
    }

    fun failure(workId: UUID): String? {
        if (activeWorkId() != workId) return null
        return prefs.getString(KEY_FAILURE, null)
    }

    fun loadResult(workId: UUID): Completed? {
        if (prefs.getString(KEY_RESULT_WORK_ID, null) != workId.toString()) return null
        val source = prefs.getString(KEY_SOURCE_URI, null)?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return null
        val typeRaw = prefs.getString(KEY_PROJECT_TYPE, null) ?: return null
        val type = SaudiProjectTypeEngine.Type.entries.firstOrNull { it.name == typeRaw } ?: return null
        val rawPlan = prefs.getString(KEY_RESULT_PLAN, null) ?: return null
        val plan = runCatching { PlanStorageCodec.decode(JSONObject(rawPlan)) }.getOrNull() ?: return null
        return Completed(workId, source, type, plan)
    }

    fun consume(workId: UUID): Completed? {
        val result = loadResult(workId) ?: return null
        clear(workId)
        return result
    }

    fun clear(workId: UUID) {
        if (activeWorkId() != workId && prefs.getString(KEY_RESULT_WORK_ID, null) != workId.toString()) return
        prefs.edit()
            .remove(KEY_ACTIVE_WORK_ID)
            .remove(KEY_SOURCE_URI)
            .remove(KEY_PROJECT_TYPE)
            .remove(KEY_RESULT_WORK_ID)
            .remove(KEY_RESULT_PLAN)
            .remove(KEY_FAILURE)
            .commit()
    }

    companion object {
        private const val PREFS_NAME = "manzili_hai_pending_analysis_secure_v1"
        private const val KEY_ACTIVE_WORK_ID = "active_work_id"
        private const val KEY_SOURCE_URI = "source_uri"
        private const val KEY_PROJECT_TYPE = "project_type"
        private const val KEY_RESULT_WORK_ID = "result_work_id"
        private const val KEY_RESULT_PLAN = "result_plan"
        private const val KEY_FAILURE = "failure"
    }
}
