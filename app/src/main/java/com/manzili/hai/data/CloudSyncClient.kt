package com.manzili.hai.data

import com.manzili.hai.model.FloorPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class CloudSyncClient(private val settings: HaiSettings) {
    data class Session(val accessToken: String, val refreshToken: String, val userId: String, val email: String)
    data class RemoteProjectSummary(val id: String, val title: String, val revision: Int, val updatedAt: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun signIn(email: String, password: String): Session = withContext(Dispatchers.IO) {
        require(settings.cloudConfigured) { "أدخل إعدادات Supabase HTTPS أولًا" }
        auth("/auth/v1/token?grant_type=password", JSONObject().put("email", email.trim()).put("password", password), true)
    }

    suspend fun signUp(email: String, password: String): Session = withContext(Dispatchers.IO) {
        require(settings.cloudConfigured) { "أدخل إعدادات Supabase HTTPS أولًا" }
        auth("/auth/v1/signup", JSONObject().put("email", email.trim()).put("password", password), false)
    }

    suspend fun refresh(): Session = withContext(Dispatchers.IO) {
        require(settings.refreshToken.isNotBlank()) { "لا توجد جلسة قابلة للتحديث" }
        auth("/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", settings.refreshToken), true)
    }

    fun signOut() {
        settings.clearSession()
    }

    suspend fun listProjects(): List<RemoteProjectSummary> = withContext(Dispatchers.IO) {
        val text = executeSessionRequest { token ->
            Request.Builder()
                .url("${requireBackendUrl()}/v1/projects")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
        }
        val rows = JSONArray(text)
        (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val id = row.optString("id")
            if (id.isBlank()) return@mapNotNull null
            RemoteProjectSummary(
                id = id,
                title = row.optString("title", "مشروعي"),
                revision = row.optInt("revision", 1),
                updatedAt = row.optString("updated_at", "")
            )
        }
    }

    suspend fun upload(projectId: String, plan: FloorPlan): Unit = withContext(Dispatchers.IO) {
        require(projectId.isNotBlank()) { "معرّف المشروع المحلي غير متاح" }
        val body = JSONObject()
            .put("title", plan.title)
            .put("revision", plan.revision)
            .put("plan", PlanStorageCodec.encode(plan))
        executeSessionRequest { token ->
            Request.Builder()
                .url("${requireBackendUrl()}/v1/projects/$projectId")
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .put(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
        }
    }

    suspend fun download(projectId: String): FloorPlan = withContext(Dispatchers.IO) {
        require(projectId.isNotBlank()) { "معرّف المشروع السحابي غير صحيح" }
        val text = executeSessionRequest { token ->
            Request.Builder()
                .url("${requireBackendUrl()}/v1/projects/$projectId")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
        }
        val root = JSONObject(text)
        PlanStorageCodec.decode(root.getJSONObject("plan"))
    }

    private fun requireBackendUrl(): String {
        val base = settings.backendBaseUrl.trim().trimEnd('/')
        require(base.startsWith("https://")) { "Cloud Sync يتطلب Backend HTTPS" }
        return base
    }

    private suspend fun executeSessionRequest(build: (String) -> Request): String {
        require(settings.cloudConfigured) { "إعدادات Supabase غير مكتملة" }
        var token = settings.supabaseAccessToken
        require(token.isNotBlank()) { "سجّل الدخول للسحابة أولًا" }

        fun execute(access: String): Pair<Int, String> = http.newCall(build(access)).execute().use { response ->
            response.code to response.body?.string().orEmpty()
        }

        var (code, text) = execute(token)
        if (code == 401 && settings.refreshToken.isNotBlank()) {
            token = refresh().accessToken
            val retry = execute(token)
            code = retry.first
            text = retry.second
        }
        if (code !in 200..299) error("فشل Cloud Sync ($code): ${text.take(260)}")
        return text
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
            if (access.isNotBlank()) settings.supabaseAccessToken = access
            if (refresh.isNotBlank()) settings.refreshToken = refresh
            return Session(access, refresh, user.optString("id"), user.optString("email"))
        }
    }
}
