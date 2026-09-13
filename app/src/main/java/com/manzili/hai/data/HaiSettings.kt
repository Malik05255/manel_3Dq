package com.manzili.hai.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Connection settings. Production backend is the default path; secrets remain encrypted on-device. */
class HaiSettings(context: Context) {
    private val legacy = context.getSharedPreferences("hai_ai", Context.MODE_PRIVATE)
    private val secure = EncryptedSharedPreferences.create(
        context,
        "hai_secure_connection_v2",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    init {
        if (!secure.contains(KEY_DIRECT_API_KEY)) {
            legacy.getString("apiKey", "")?.takeIf { it.isNotBlank() }?.let {
                secure.edit().putString(KEY_DIRECT_API_KEY, it).apply()
            }
        }

        // v2 used one ambiguous key for both a static backend token and a Supabase session.
        // Migrate once without discarding existing installations: JWT-shaped values are treated
        // as Supabase sessions, other values as service tokens.
        val old = secure.getString(KEY_LEGACY_BACKEND_TOKEN, "").orEmpty().trim()
        if (old.isNotBlank() && !secure.contains(KEY_BACKEND_SERVICE_TOKEN) && !secure.contains(KEY_SUPABASE_ACCESS_TOKEN)) {
            val target = if (looksLikeJwt(old)) KEY_SUPABASE_ACCESS_TOKEN else KEY_BACKEND_SERVICE_TOKEN
            secure.edit().putString(target, old).apply()
        }
    }

    var directEndpoint: String
        get() = legacy.getString("endpoint", "https://openrouter.ai/api/v1/chat/completions")!!
        set(v) = legacy.edit().putString("endpoint", v.trim()).apply()

    var directApiKey: String
        get() = secure.getString(KEY_DIRECT_API_KEY, "")!!
        set(v) = secure.edit().putString(KEY_DIRECT_API_KEY, v.trim()).apply()

    var model: String
        get() = legacy.getString("model", "google/gemini-2.5-flash")!!
        set(v) = legacy.edit().putString("model", v.trim()).apply()

    var backendMode: Boolean
        get() = legacy.getBoolean("backendMode", true)
        set(v) = legacy.edit().putBoolean("backendMode", v).apply()

    var backendBaseUrl: String
        get() = legacy.getString("backendBaseUrl", "https://manzili-hai-deep-parser.onrender.com")!!
        set(v) = legacy.edit().putString("backendBaseUrl", v.trim().trimEnd('/')).apply()

    /** Static/service token used for parser + AI when the server accepts MANZILI_API_TOKEN. */
    var backendServiceToken: String
        get() = secure.getString(KEY_BACKEND_SERVICE_TOKEN, "")!!
        set(v) = secure.edit().putString(KEY_BACKEND_SERVICE_TOKEN, v.trim()).apply()

    /** Supabase user access token. Never reuse this field as the static backend credential. */
    var supabaseAccessToken: String
        get() = secure.getString(KEY_SUPABASE_ACCESS_TOKEN, "")!!
        set(v) = secure.edit().putString(KEY_SUPABASE_ACCESS_TOKEN, v.trim()).apply()

    /** Effective bearer token for authenticated backend calls. User session wins when present. */
    val backendAuthToken: String
        get() = supabaseAccessToken.ifBlank { backendServiceToken }

    /** Compatibility alias for old callers; writes now mean service-token writes only. */
    @Deprecated("Use backendServiceToken or supabaseAccessToken explicitly")
    var backendAccessToken: String
        get() = backendAuthToken
        set(v) { backendServiceToken = v }

    var supabaseUrl: String
        get() = legacy.getString("supabaseUrl", "")!!
        set(v) = legacy.edit().putString("supabaseUrl", v.trim().trimEnd('/')).apply()

    var supabasePublishableKey: String
        get() = legacy.getString("supabasePublishableKey", "")!!
        set(v) = legacy.edit().putString("supabasePublishableKey", v.trim()).apply()

    var refreshToken: String
        get() = secure.getString(KEY_REFRESH_TOKEN, "")!!
        set(v) = secure.edit().putString(KEY_REFRESH_TOKEN, v.trim()).apply()

    var endpoint: String
        get() = if (backendMode && backendBaseUrl.isNotBlank()) "$backendBaseUrl/v1/ai/chat" else directEndpoint
        set(v) { directEndpoint = v }

    var apiKey: String
        get() = if (backendMode) backendAuthToken else directApiKey
        set(v) { directApiKey = v }

    val cloudConfigured: Boolean
        get() = supabaseUrl.startsWith("https://") && supabasePublishableKey.isNotBlank()

    val cloudSessionConfigured: Boolean
        get() = cloudConfigured && supabaseAccessToken.isNotBlank()

    val backendConfigured: Boolean
        get() = backendBaseUrl.startsWith("https://") && backendAuthToken.isNotBlank()

    val configured: Boolean
        get() = model.isNotBlank() && if (backendMode) backendConfigured else directEndpoint.startsWith("https://") && directApiKey.isNotBlank()

    fun clearSession() {
        supabaseAccessToken = ""
        refreshToken = ""
    }

    fun clearBackendServiceToken() {
        backendServiceToken = ""
    }

    private fun looksLikeJwt(value: String): Boolean = value.count { it == '.' } == 2 && value.length > 40

    companion object {
        private const val KEY_DIRECT_API_KEY = "directApiKey"
        private const val KEY_BACKEND_SERVICE_TOKEN = "backendServiceToken"
        private const val KEY_SUPABASE_ACCESS_TOKEN = "supabaseAccessToken"
        private const val KEY_REFRESH_TOKEN = "refreshToken"
        private const val KEY_LEGACY_BACKEND_TOKEN = "backendAccessToken"
    }
}
