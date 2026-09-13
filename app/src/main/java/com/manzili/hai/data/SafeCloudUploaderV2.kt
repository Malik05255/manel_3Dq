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

class SafeCloudUploaderV2(
    private val settings: HaiSettings,
    private val cloud: CloudSyncClient
) {
    data class UploadResult(val plan: FloorPlan, val revision: Int)

    class Conflict(val currentRevision: Int?) : IllegalStateException(
        currentRevision?.let { "يوجد إصدار سحابي أحدث (V$it). نزّل النسخة الأحدث أو حدّث القائمة قبل الرفع." }
            ?: "تغير المشروع السحابي قبل اكتمال الرفع. حدّث القائمة وحاول مجددًا."
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun upload(projectId: String, plan: FloorPlan): UploadResult = withContext(Dispatchers.IO) {
        require(projectId.isNotBlank()) { "معرّف المشروع المحلي غير متاح" }
        val observed = cloud.listProjects().firstOrNull { it.id == projectId }
        if (observed != null && observed.revision > plan.revision) {
            throw Conflict(observed.revision)
        }

        val targetRevision = observed?.let { maxOf(plan.revision, it.revision + 1) }
            ?: maxOf(1, plan.revision)
        val encoded = PlanStorageCodec.encode(plan.copy(revision = targetRevision))
            .put("_cloud_expected_revision", observed?.revision ?: JSONObject.NULL)
        val body = JSONObject()
            .put("title", plan.title)
            .put("revision", targetRevision)
            .put("plan", encoded)

        val text = execute(projectId, body)
        val root = JSONObject(text)
        val revision = root.optInt("revision", targetRevision).coerceAtLeast(1)
        val synced = root.optJSONObject("plan")
            ?.let(PlanStorageCodec::decode)
            ?.copy(revision = revision)
            ?: plan.copy(revision = revision)
        UploadResult(synced, revision)
    }

    private fun backendUrl(): String {
        val base = settings.backendBaseUrl.trim().trimEnd('/')
        require(base.startsWith("https://")) { "Cloud Sync يتطلب Backend HTTPS" }
        return base
    }

    private fun request(projectId: String, body: JSONObject, access: String): Request = Request.Builder()
        .url("${backendUrl()}/v1/projects/$projectId")
        .header("Authorization", "Bearer $access")
        .header("Content-Type", "application/json")
        .put(body.toString().toRequestBody("application/json".toMediaType()))
        .build()

    private suspend fun execute(projectId: String, body: JSONObject): String {
        var access = settings.supabaseAccessToken
        require(access.isNotBlank()) { "سجّل الدخول للسحابة أولًا" }

        fun call(token: String): Pair<Int, String> = http.newCall(request(projectId, body, token)).execute().use {
            it.code to it.body?.string().orEmpty()
        }

        var (code, text) = call(access)
        if (code == 401 && settings.refreshToken.isNotBlank()) {
            access = cloud.refresh().accessToken
            val retry = call(access)
            code = retry.first
            text = retry.second
        }
        if (code == 409) {
            val current = runCatching { cloud.listProjects().firstOrNull { it.id == projectId }?.revision }.getOrNull()
            throw Conflict(current)
        }
        if (code !in 200..299) error("فشل Cloud Sync ($code): ${text.take(260)}")
        return text
    }
}
