package com.manzili.hai.data

import android.content.Context

class HaiSettings(context: Context) {
    private val prefs = context.getSharedPreferences("hai_ai", Context.MODE_PRIVATE)

    var endpoint: String
        get() = prefs.getString("endpoint", "https://openrouter.ai/api/v1/chat/completions")!!
        set(v) = prefs.edit().putString("endpoint", v.trim()).apply()

    var apiKey: String
        get() = prefs.getString("apiKey", "")!!
        set(v) = prefs.edit().putString("apiKey", v.trim()).apply()

    var model: String
        get() = prefs.getString("model", "google/gemini-2.5-flash")!!
        set(v) = prefs.edit().putString("model", v.trim()).apply()

    val configured: Boolean get() = endpoint.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}
