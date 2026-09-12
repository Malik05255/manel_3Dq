package com.manzili.hai.data

import com.manzili.hai.model.FloorPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class CloudSyncClient(private val settings: HaiSettings) {
    data class Session(val accessToken: String, val refreshToken: String, val userId: String, val email: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun signIn(email: String, password: String): Session = withContext(Dispatchers.IO) {
        require(settings.cloudConfigured) { "أدخل إعدادات Supabase أولًا" }
        auth("/auth/v1/token?grant_type=password", JSONObject().put("email", email.trim()).put("password", password), true)
    }

    suspend fun signUp(email: String, password: String): Session = withContext(Dispatchers.IO) {
        require(settings.cloudConfigured) { "أدخل إعدادات Supabase أولًا" }
        auth("/auth/v1/signup", JSONObject().put("email", email.trim()).put("password", password), false)
    }

    suspend fun refresh(): Session = withContext(Dispatchers.IO) {
        require(settings.refreshToken.isNotBlank()) { "لا توجد جلسة قابلة للتحديث" }
        auth("/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", settings.refreshToken), true)
    }

    suspend fun upload(projectId: String, plan: FloorPlan): Unit = withContext(Dispatchers.IO) {
        require(settings.backendConfigured) { "فعّل Backend الآمن وسجّل الدخول أولًا" }
        val body = JSONObject()
            .put("title", plan.title)
            .put("revision", plan.revision)
            .put("plan", PlanStorageCodec.encode(plan))
        val req = Request.Builder()
            .url("${settings.backendBaseUrl}/v1/projects/$projectId")
            .header("Authorization", "Bearer ${settings.backendAccessToken}")
            .header("Content-Type", "application/json")
            .put(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) error("فشل رفع المشروع (${res.code}): ${text.take(240)}")
        }
    }

    suspend fun download(projectId: String): FloorPlan = withContext(Dispatchers.IO) {
        require(settings.backendConfigured) { "فعّل Backend الآمن وسجّل الدخول أولًا" }
        val req = Request.Builder()
            .url("${settings.backendBaseUrl}/v1/projects/$projectId")
            .header("Authorization", "Bearer ${settings.backendAccessToken}")
            .get().build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) error("فشل تنزيل المشروع (${res.code}): ${text.take(240)}")
            val root = JSONObject(text)
            PlanStorageCodec.decode(root.getJSONObject("plan"))
        }
    }

    private fun auth(path: String, body: JSONObject, requireAccess: Boolean): Session {
        val req = Request.Builder()
            .url(settings.supabaseUrl + path)
            .header("apikey", settings.supabasePublishableKey)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) error("فشل تسجيل الحساب (${res.code}): ${text.take(260)}")
            val root = JSONObject(text)
            val access = root.optString("access_token")
            val refresh = root.optString("refresh_token")
            val user = root.optJSONObject("user") ?: JSONObject()
            if (requireAccess && access.isBlank()) error("الحساب يحتاج تأكيد البريد أو لم تُنشأ جلسة")
            if (access.isNotBlank()) settings.backendAccessToken = access
            if (refresh.isNotBlank()) settings.refreshToken = refresh
            return Session(access, refresh, user.optString("id"), user.optString("email"))
        }
    }
}
