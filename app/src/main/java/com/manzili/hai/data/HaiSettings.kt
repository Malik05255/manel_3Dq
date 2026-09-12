package com.manzili.hai.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray

/** AI provider settings. API keys are encrypted on-device and are never bundled in the APK. */
class HaiSettings(context: Context) {
    private val legacy = context.getSharedPreferences("hai_ai", Context.MODE_PRIVATE)
    private val secure = EncryptedSharedPreferences.create(
        context,
        "hai_secure_connection_v3",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    init {
        val oldSecure = runCatching {
            EncryptedSharedPreferences.create(
                context,
                "hai_secure_connection_v2",
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrNull()

        fun migrateSecret(oldKey: String, newKey: String = oldKey) {
            if (secure.contains(newKey)) return
            oldSecure?.getString(oldKey, "")?.takeIf { it.isNotBlank() }?.let {
                secure.edit().putString(newKey, it).apply()
            }
        }
        migrateSecret("backendAccessToken")
        migrateSecret("refreshToken")

        if (!secure.contains("openRouterApiKey")) {
            val old = oldSecure?.getString("directApiKey", "").orEmpty()
            val endpoint = legacy.getString("endpoint", "").orEmpty()
            if (old.isNotBlank() && endpoint.contains("openrouter", true)) {
                secure.edit().putString("openRouterApiKey", old).apply()
            }
        }
        if (!legacy.contains("openRouterModels")) {
            legacy.getString("model", "")?.takeIf { it.isNotBlank() }?.let { saveList("openRouterModels", listOf(it)) }
        }
    }

    var openRouterApiKey: String
        get() = secure.getString("openRouterApiKey", "")!!
        set(v) = secure.edit().putString("openRouterApiKey", v.trim()).apply()

    var googleApiKey: String
        get() = secure.getString("googleApiKey", "")!!
        set(v) = secure.edit().putString("googleApiKey", v.trim()).apply()

    var openRouterModels: List<String>
        get() = readList("openRouterModels")
        set(v) = saveList("openRouterModels", v)

    var googleModels: List<String>
        get() = readList("googleModels")
        set(v) = saveList("googleModels", v)

    var smartRoutingEnabled: Boolean
        get() = legacy.getBoolean("smartRoutingEnabled", true)
        set(v) = legacy.edit().putBoolean("smartRoutingEnabled", v).apply()

    var nanoEnabled: Boolean
        get() = legacy.getBoolean("nanoEnabled", true)
        set(v) = legacy.edit().putBoolean("nanoEnabled", v).apply()

    var lastWorkingProvider: String
        get() = legacy.getString("lastWorkingProvider", "")!!
        set(v) = legacy.edit().putString("lastWorkingProvider", v).apply()

    var lastWorkingModel: String
        get() = legacy.getString("lastWorkingModel", "")!!
        set(v) = legacy.edit().putString("lastWorkingModel", v).apply()

    // Existing backend/parser settings remain intact for Deep Parser.
    var backendMode: Boolean
        get() = legacy.getBoolean("backendMode", false)
        set(v) = legacy.edit().putBoolean("backendMode", v).apply()

    var backendBaseUrl: String
        get() = legacy.getString("backendBaseUrl", "https://manzili-hai-deep-parser.onrender.com")!!
        set(v) = legacy.edit().putString("backendBaseUrl", v.trim().trimEnd('/')).apply()

    var backendAccessToken: String
        get() = secure.getString("backendAccessToken", "")!!
        set(v) = secure.edit().putString("backendAccessToken", v.trim()).apply()

    var supabaseUrl: String
        get() = legacy.getString("supabaseUrl", "")!!
        set(v) = legacy.edit().putString("supabaseUrl", v.trim().trimEnd('/')).apply()

    var supabasePublishableKey: String
        get() = legacy.getString("supabasePublishableKey", "")!!
        set(v) = legacy.edit().putString("supabasePublishableKey", v.trim()).apply()

    var refreshToken: String
        get() = secure.getString("refreshToken", "")!!
        set(v) = secure.edit().putString("refreshToken", v.trim()).apply()

    // Legacy compatibility for older call sites. New AI traffic uses AiProviderRouter.
    var directEndpoint: String
        get() = legacy.getString("endpoint", "https://openrouter.ai/api/v1/chat/completions")!!
        set(v) = legacy.edit().putString("endpoint", v.trim()).apply()

    var directApiKey: String
        get() = openRouterApiKey
        set(v) { openRouterApiKey = v }

    var model: String
        get() = openRouterModels.firstOrNull() ?: googleModels.firstOrNull() ?: "openrouter/free"
        set(v) { if (v.isNotBlank()) openRouterModels = listOf(v.trim()) }

    val cloudConfigured: Boolean
        get() = supabaseUrl.isNotBlank() && supabasePublishableKey.isNotBlank()

    val backendConfigured: Boolean
        get() = backendBaseUrl.isNotBlank() && backendAccessToken.isNotBlank()

    val configured: Boolean
        get() = nanoEnabled ||
            (openRouterApiKey.isNotBlank() && openRouterModels.isNotEmpty()) ||
            (googleApiKey.isNotBlank() && googleModels.isNotEmpty())

    var endpoint: String
        get() = directEndpoint
        set(v) { directEndpoint = v }

    var apiKey: String
        get() = openRouterApiKey
        set(v) { openRouterApiKey = v }

    fun clearSession() {
        backendAccessToken = ""
        refreshToken = ""
    }

    private fun readList(key: String): List<String> = runCatching {
        val arr = JSONArray(legacy.getString(key, "[]") ?: "[]")
        buildList {
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }.getOrDefault(emptyList())

    private fun saveList(key: String, values: List<String>) {
        val clean = values.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        legacy.edit().putString(key, JSONArray(clean).toString()).apply()
    }
}
