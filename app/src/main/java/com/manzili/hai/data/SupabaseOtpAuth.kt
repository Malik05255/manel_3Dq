package com.manzili.hai.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SupabaseOtpAuth(private val settings: HaiSettings) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    suspend fun requestCode(email: String): Unit = withContext(Dispatchers.IO) {
        require(settings.cloudConfigured) { "إعدادات Supabase غير مكتملة أو الرابط ليس HTTPS" }
        val normalized = email.trim().lowercase()
        require(normalized.contains('@')) { "البريد الإلكتروني غير صحيح" }
        val body = JSONObject().put("email", normalized).put("create_user", true)
        call("/auth/v1/otp", body).use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) error("تعذر إرسال الرمز (${res.code}): ${text.take(220)}")
        }
    }

    suspend fun verifyCode(email: String, code: String): CloudSyncClient.Session = withContext(Dispatchers.IO) {
        require(settings.cloudConfigured) { "إعدادات Supabase غير مكتملة أو الرابط ليس HTTPS" }
        val normalized = email.trim().lowercase()
        val token = code.trim()
        require(token.isNotBlank()) { "أدخل رمز التحقق" }
        val body = JSONObject().put("email", normalized).put("token", token).put("type", "email")
        call("/auth/v1/verify", body).use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) error("تعذر التحقق (${res.code}): ${text.take(220)}")
            val root = JSONObject(text)
            val access = root.optString("access_token")
            val refresh = root.optString("refresh_token")
            require(access.isNotBlank()) { "لم تُنشأ جلسة وصول" }
            settings.supabaseAccessToken = access
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
