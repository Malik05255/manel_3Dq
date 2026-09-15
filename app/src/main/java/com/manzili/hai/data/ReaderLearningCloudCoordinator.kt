package com.manzili.hai.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ReaderLearningCloudCoordinator(
    private val settings: HaiSettings,
    private val cloud: CloudSyncClient,
    private val uploader: ReaderLearningCloudUploader,
    private val state: ReaderLearningCloudStateStore
) {
    data class ShareResult(val caseId: String, val objectPath: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun sharedState(candidateId: String): ReaderLearningCloudState? = state.get(candidateId)

    suspend fun share(candidateId: String, consent: ReaderLearningConsent): ShareResult = withContext(Dispatchers.IO) {
        state.get(candidateId)?.let { return@withContext ShareResult(it.caseId, it.objectPath) }
        val uploaded = uploader.upload(candidateId, consent)
        state.markShared(candidateId, uploaded.caseId, uploaded.objectPath)
        ShareResult(uploaded.caseId, uploaded.objectPath)
    }

    suspend fun withdraw(candidateId: String) = withContext(Dispatchers.IO) {
        val shared = state.get(candidateId) ?: return@withContext
        require(settings.cloudSessionConfigured) { "سجّل الدخول للسحابة أولًا" }

        var access = settings.supabaseAccessToken
        var userId = currentUserId(access)
        if (userId == null && settings.refreshToken.isNotBlank()) {
            access = cloud.refresh().accessToken
            userId = currentUserId(access)
        }
        require(!userId.isNullOrBlank()) { "تعذر التحقق من حساب السحابة" }
        require(shared.objectPath.startsWith("$userId/")) { "ملف المشاركة لا يخص الحساب الحالي" }

        val objectCode = deleteObject(access, shared.objectPath)
        if (objectCode !in 200..299 && objectCode != 404) {
            error("تعذر حذف ملف المشاركة ($objectCode)")
        }
        val metadataCode = deleteMetadata(access, shared.caseId)
        if (metadataCode !in 200..299 && metadataCode != 404) {
            error("حُذف الملف لكن تعذر حذف سجل المشاركة ($metadataCode)")
        }
        state.clear(candidateId)
    }

    private fun currentUserId(access: String): String? {
        val request = Request.Builder()
            .url("${settings.supabaseUrl.trimEnd('/')}/auth/v1/user")
            .header("Authorization", "Bearer $access")
            .header("apikey", settings.supabasePublishableKey)
            .get()
            .build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            JSONObject(response.body?.string().orEmpty()).optString("id").takeIf { it.isNotBlank() }
        }
    }

    private fun deleteObject(access: String, objectPath: String): Int {
        val request = Request.Builder()
            .url("${settings.supabaseUrl.trimEnd('/')}/storage/v1/object/$BUCKET/$objectPath")
            .header("Authorization", "Bearer $access")
            .header("apikey", settings.supabasePublishableKey)
            .delete()
            .build()
        return http.newCall(request).execute().use { it.code }
    }

    private fun deleteMetadata(access: String, caseId: String): Int {
        val request = Request.Builder()
            .url("${settings.supabaseUrl.trimEnd('/')}/rest/v1/manzili_reader_learning_cases?id=eq.$caseId")
            .header("Authorization", "Bearer $access")
            .header("apikey", settings.supabasePublishableKey)
            .header("Prefer", "return=minimal")
            .delete()
            .build()
        return http.newCall(request).execute().use { it.code }
    }

    companion object {
        private const val BUCKET = "manzili-reader-learning-private"
    }
}
