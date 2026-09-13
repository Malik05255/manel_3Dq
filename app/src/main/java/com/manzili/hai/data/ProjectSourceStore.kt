package com.manzili.hai.data

import android.content.Context
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Keeps the persisted document URI for each imported project so the original PDF/image can be
 * shown again after process death or app restart. The app already takes a persistable URI grant
 * at import time; this store only keeps the reference, never copies the user's document.
 *
 * URI references are encrypted at rest. Existing plaintext references are migrated once.
 */
class ProjectSourceStore(context: Context) {
    private val appContext = context.applicationContext
    private val legacy = appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
    private val secure = EncryptedSharedPreferences.create(
        appContext,
        SECURE_PREFS_NAME,
        MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    init { migrateLegacyReferences() }

    fun save(projectId: String, uri: Uri?) {
        if (projectId.isBlank()) return
        if (uri == null) {
            secure.edit().remove(key(projectId)).apply()
        } else {
            secure.edit().putString(key(projectId), uri.toString()).apply()
        }
    }

    fun load(projectId: String?): Uri? {
        if (projectId.isNullOrBlank()) return null
        val raw = secure.getString(key(projectId), null)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { Uri.parse(raw) }.getOrNull()
    }

    fun delete(projectId: String) {
        if (projectId.isNotBlank()) secure.edit().remove(key(projectId)).apply()
    }

    fun copy(fromProjectId: String, toProjectId: String) {
        save(toProjectId, load(fromProjectId))
    }

    private fun migrateLegacyReferences() {
        if (secure.getBoolean(KEY_MIGRATED, false)) return
        val editor = secure.edit()
        legacy.all.forEach { (key, value) ->
            if (!key.startsWith("source_") || secure.contains(key)) return@forEach
            (value as? String)?.takeIf { it.isNotBlank() }?.let { editor.putString(key, it) }
        }
        val committed = editor.putBoolean(KEY_MIGRATED, true).commit()
        if (committed) legacy.edit().clear().apply()
    }

    private fun key(projectId: String) = "source_$projectId"

    companion object {
        private const val LEGACY_PREFS_NAME = "manzili_hai_project_sources"
        private const val SECURE_PREFS_NAME = "manzili_hai_project_sources_secure_v1"
        private const val KEY_MIGRATED = "secure_sources_migrated_v1"
    }
}
