package com.manzili.hai.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Explicit-consent upload only. Nothing calls this class automatically. */
class ReaderLearningCloudUploader(
    context: Context,
    private val settings: HaiSettings,
    private val cloud: CloudSyncClient
) {
    data class UploadResult(val caseId: String, val objectPath: String)

    private val appContext = context.applicationContext
    private val corrections = ReaderCorrectionStore(appContext)
    private val sources = ProjectSourceStore(appContext)
    private val http = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun upload(candidateId: String, consent: ReaderLearningConsent): UploadResult = withContext(Dispatchers.IO) {
        val errors = consent.validationErrors()
        require(errors.isEmpty()) { errors.joinToString(". ") }
        require(settings.cloudConfigured) { "إعدادات Supabase غير مكتملة" }
        require(settings.supabaseAccessToken.isNotBlank()) { "سجّل الدخول للسحابة أولًا" }

        val temp = File.createTempFile("hai-reader-learning-", ".zip", appContext.cacheDir)
        try {
            val caseId = writeBundle(candidateId, consent, temp)
            require(temp.length() <= MAX_UPLOAD_BYTES) { "حجم حالة التعلم أكبر من 25MB" }

            var access = settings.supabaseAccessToken
            var userId = currentUserId(access)
            if (userId == null && settings.refreshToken.isNotBlank()) {
                access = cloud.refresh().accessToken
                userId = currentUserId(access)
            }
            require(!userId.isNullOrBlank()) { "تعذر التحقق من حساب السحابة" }

            val objectPath = "$userId/$caseId.zip"
            val upload = uploadObject(access, objectPath, temp)
            if (upload !in 200..299) error("تعذر رفع Dataset الخاص ($upload)")

            val metadata = insertMetadata(access, userId, candidateId, caseId, objectPath, consent)
            if (metadata !in 200..299) {
                deleteObject(access, objectPath)
                error("تم رفض بيانات حالة التعلم ($metadata)")
            }
            corrections.markExported(candidateId)
            UploadResult(caseId, objectPath)
        } finally {
            temp.delete()
        }
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

    private fun uploadObject(access: String, objectPath: String, file: File): Int {
        val request = Request.Builder()
            .url("${settings.supabaseUrl.trimEnd('/')}/storage/v1/object/$BUCKET/$objectPath")
            .header("Authorization", "Bearer $access")
            .header("apikey", settings.supabasePublishableKey)
            .header("x-upsert", "false")
            .post(file.asRequestBody("application/zip".toMediaType()))
            .build()
        return http.newCall(request).execute().use { it.code }
    }

    private fun insertMetadata(
        access: String,
        userId: String,
        candidateId: String,
        caseId: String,
        objectPath: String,
        consent: ReaderLearningConsent
    ): Int {
        val body = JSONObject()
            .put("id", caseId)
            .put("user_id", userId)
            .put("candidate_id", candidateId)
            .put("object_path", objectPath)
            .put("reader_model", ReaderCorrectionStore.READER_MODEL)
            .put("city", consent.city.trim())
            .put("region", consent.region.trim())
            .put("project_type", consent.projectType.trim().lowercase(Locale.ROOT))
            .put("floors", consent.floors)
            .put("consent_schema", 2)
        val request = Request.Builder()
            .url("${settings.supabaseUrl.trimEnd('/')}/rest/v1/manzili_reader_learning_cases")
            .header("Authorization", "Bearer $access")
            .header("apikey", settings.supabasePublishableKey)
            .header("Content-Type", "application/json")
            .header("Prefer", "return=minimal")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return http.newCall(request).execute().use { it.code }
    }

    private fun deleteObject(access: String, objectPath: String) {
        val request = Request.Builder()
            .url("${settings.supabaseUrl.trimEnd('/')}/storage/v1/object/$BUCKET/$objectPath")
            .header("Authorization", "Bearer $access")
            .header("apikey", settings.supabasePublishableKey)
            .delete()
            .build()
        runCatching { http.newCall(request).execute().close() }
    }

    private fun writeBundle(candidateId: String, consent: ReaderLearningConsent, output: File): String {
        val candidate = corrections.loadPayload(candidateId) ?: error("حالة التصحيح غير موجودة")
        check(candidate.optString("reader_model") == ReaderCorrectionStore.READER_MODEL) { "إصدار القارئ لا يطابق Reader V3" }
        val projectId = candidate.optString("project_id").takeIf { it.isNotBlank() } ?: error("معرّف المشروع غير موجود")
        val sourceUri = sources.load(projectId) ?: error("تعذر الوصول إلى المخطط الأصلي")
        val approved = candidate.optJSONObject("approved_reference")?.let(PlanStorageCodec::decode) ?: error("المرجع المصحح غير موجود")
        val baseline = candidate.optJSONObject("reader_result")?.let(PlanStorageCodec::decode) ?: error("نتيجة القارئ الأصلية غير موجودة")
        val caseId = caseId(candidateId)
        val assetName = "assets/$caseId.${sourceExtension(sourceUri)}"
        val referenceName = "labels/$caseId.json"
        val trainingName = "training_labels/$caseId.json"
        val baselineName = "baselines/$caseId.json"
        val manifest = JSONObject()
            .put("id", caseId)
            .put("city", consent.city.trim())
            .put("region", consent.region.trim())
            .put("source", "user-consented-private")
            .put("asset", assetName)
            .put("reference", referenceName)
            .put("training_reference", trainingName)
            .put("floors", consent.floors)
            .put("license", "explicit-owner-or-license-consent")
            .put("deidentified", true)
            .put("split", "train")
            .put("project_type", consent.projectType.trim().lowercase(Locale.ROOT))
        val consentJson = JSONObject()
            .put("schema_version", 2)
            .put("case_id", caseId)
            .put("exported_at_utc", Instant.now().toString())
            .put("reader_model", ReaderCorrectionStore.READER_MODEL)
            .put("owns_or_licensed_attested", true)
            .put("deidentified_attested", true)
            .put("automatic_upload", false)
            .put("upload_initiated_by_user", true)
            .put("benchmark_split", "train")
            .put("candidate_delta", candidate.optJSONObject("delta") ?: JSONObject())

        ZipOutputStream(BufferedOutputStream(FileOutputStream(output))).use { zip ->
            putText(zip, "manifest.jsonl", manifest.toString() + "\n")
            putText(zip, referenceName, ReaderLearningDatasetExporter.benchmarkReference(approved).toString(2))
            putText(zip, trainingName, ReaderLearningDatasetExporter.trainingReference(approved).toString(2))
            putText(zip, baselineName, ReaderLearningDatasetExporter.benchmarkReference(baseline).toString(2))
            putText(zip, "consent/$caseId.json", consentJson.toString(2))
            zip.putNextEntry(ZipEntry(assetName))
            appContext.contentResolver.openInputStream(sourceUri)?.use { it.copyTo(zip, DEFAULT_BUFFER_SIZE) }
                ?: error("تعذر قراءة المخطط الأصلي")
            zip.closeEntry()
        }
        return caseId
    }

    private fun sourceExtension(uri: Uri): String {
        val mime = appContext.contentResolver.getType(uri).orEmpty().lowercase(Locale.ROOT)
        return when (mime) {
            "application/pdf" -> "pdf"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/heic", "image/heif" -> "heic"
            else -> "jpg"
        }
    }

    private fun caseId(candidateId: String): String =
        "hai-train-${candidateId.removePrefix("correction-").replace("-", "").take(16)}"

    private fun putText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    companion object {
        private const val BUCKET = "manzili-reader-learning-private"
        private const val MAX_UPLOAD_BYTES = 25L * 1024L * 1024L
    }
}
