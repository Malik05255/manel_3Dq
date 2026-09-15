package com.manzili.hai.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

data class ReaderLearningCloudState(
    val caseId: String,
    val objectPath: String
)

class ReaderLearningCloudStateStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context.applicationContext,
        PREFS_NAME,
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun get(candidateId: String): ReaderLearningCloudState? {
        val raw = prefs.getString(key(candidateId), null) ?: return null
        return runCatching {
            val value = JSONObject(raw)
            val caseId = value.optString("case_id")
            val objectPath = value.optString("object_path")
            if (caseId.isBlank() || objectPath.isBlank()) null
            else ReaderLearningCloudState(caseId, objectPath)
        }.getOrNull()
    }

    fun markShared(candidateId: String, caseId: String, objectPath: String) {
        require(candidateId.isNotBlank() && caseId.isNotBlank() && objectPath.isNotBlank())
        val value = JSONObject()
            .put("case_id", caseId)
            .put("object_path", objectPath)
        prefs.edit().putString(key(candidateId), value.toString()).apply()
    }

    fun clear(candidateId: String) {
        prefs.edit().remove(key(candidateId)).apply()
    }

    private fun key(candidateId: String): String = "candidate_$candidateId"

    companion object {
        private const val PREFS_NAME = "manzili_reader_learning_cloud_state_v1"
    }
}
