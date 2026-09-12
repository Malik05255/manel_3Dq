package com.manzili.hai.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class SupabaseOtpAuth(private val settings: HaiSettings) {
    private val http = OkHttpClient()

    suspend fun requestCode(email: String): Unit = withContext(Dispatchers.IO) {
        require(settings.cloudConfigured) { "إعدادات Supabase غير مكتملة" }
        val body = JSONObject().put("email", email.trim()).put("create_user", true)
        call("/auth/v1/otp", body).use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) error("تعذر إرسال الرمز (${res.code}): ${text.take(220)}")
        }
    }

    suspend fun verifyCode(email: String, code: String): CloudSyncClient.Session = withContext(Dispatchers.IO) {
        require(settings.cloudConfigured) { "إعدادات Supabase غير مكتملة" }
        val body = JSONObject().put("email", email.trim()).put("token", code.trim()).put("type", "email")
        call("/auth/v1/verify", body).use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) error("تعذر التحقق (${res.code}): ${text.take(220)}")
            val root = JSONObject(text)
            val access = root.optString("access_token")
            val refresh = root.optString("refresh_token")
            require(access.isNotBlank()) { "لم تُنشأ جلسة وصول" }
            settings.backendAccessToken = access
            settings.refreshToken = refresh
            val user = root.optJSONObject("user") ?: JSONObject()
            CloudSyncClient.Session(access, refresh, user.optString("id"), user.optString("email"))
        }
    }

    private fun call(path: String, body: JSONObject) = http.newCall(
        Request.Builder()
            .url(settings.supabaseUrl + path)
            .header("apikey", settings.supabasePublishableKey)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
    ).execute()
}
