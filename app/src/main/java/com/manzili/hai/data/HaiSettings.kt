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
        if (!secure.contains("directApiKey")) legacy.getString("apiKey", "")?.takeIf { it.isNotBlank() }?.let {
            secure.edit().putString("directApiKey", it).apply()
        }
    }

    var directEndpoint: String
        get() = legacy.getString("endpoint", "https://openrouter.ai/api/v1/chat/completions")!!
        set(v) = legacy.edit().putString("endpoint", v.trim()).apply()

    var directApiKey: String
        get() = secure.getString("directApiKey", "")!!
        set(v) = secure.edit().putString("directApiKey", v.trim()).apply()

    var model: String
        get() = legacy.getString("model", "google/gemini-2.5-flash")!!
        set(v) = legacy.edit().putString("model", v.trim()).apply()

    var backendMode: Boolean
        get() = legacy.getBoolean("backendMode", true)
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

    var endpoint: String
        get() = if (backendMode && backendBaseUrl.isNotBlank()) "$backendBaseUrl/v1/ai/chat" else directEndpoint
        set(v) { directEndpoint = v }

    var apiKey: String
        get() = if (backendMode) backendAccessToken else directApiKey
        set(v) { directApiKey = v }

    val cloudConfigured: Boolean
        get() = supabaseUrl.isNotBlank() && supabasePublishableKey.isNotBlank()

    val backendConfigured: Boolean
        get() = backendBaseUrl.isNotBlank() && backendAccessToken.isNotBlank()

    val configured: Boolean
        get() = model.isNotBlank() && if (backendMode) backendConfigured else directEndpoint.isNotBlank() && directApiKey.isNotBlank()

    fun clearSession() {
        backendAccessToken = ""
        refreshToken = ""
    }
}
