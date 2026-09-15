package com.manzili.hai.data

import android.content.Context
import android.net.Uri
import com.manzili.hai.model.FloorPlan
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.time.Instant
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ReaderLearningConsent(
    val ownsOrLicensed: Boolean,
    val deidentified: Boolean,
    val city: String,
    val region: String,
    val projectType: String,
    val floors: Int
) {
    fun validationErrors(): List<String> = buildList {
        if (!ownsOrLicensed) add("يجب تأكيد ملكية المخطط أو وجود إذن لاستخدامه")
        if (!deidentified) add("يجب تأكيد إزالة الأسماء والهواتف وأرقام القطع وأي بيانات تعريفية")
        if (city.trim().length < 2) add("المدينة مطلوبة")
        if (region.trim().length < 2) add("المنطقة مطلوبة")
        if (projectType.trim().length < 2) add("نوع المشروع مطلوب")
        if (floors !in 1..20) add("عدد الأدوار يجب أن يكون بين 1 و20")
    }

    val valid: Boolean get() = validationErrors().isEmpty()
}

data class ReaderLearningExportResult(
    val caseId: String,
    val candidateId: String,
    val assetName: String,
    val referenceName: String,
    val trainingReferenceName: String = ""
)

/**
 * Creates a user-controlled ZIP that can be ingested by the private Saudi Reader benchmark/training store.
 * Nothing is uploaded by this class. The destination Uri is always chosen by the user.
 */
class ReaderLearningDatasetExporter(context: Context) {
    private val appContext = context.applicationContext
    private val corrections = ReaderCorrectionStore(appContext)
    private val sources = ProjectSourceStore(appContext)

    fun export(candidateId: String, destination: Uri, consent: ReaderLearningConsent): ReaderLearningExportResult {
        val errors = consent.validationErrors()
        require(errors.isEmpty()) { errors.joinToString(". ") }

        val candidate = corrections.loadPayload(candidateId)
            ?: error("حالة التصحيح غير موجودة")
        check(candidate.optString("reader_model") == ReaderCorrectionStore.READER_MODEL) {
            "حالة التصحيح ليست من Reader V3 الحالي"
        }

        val projectId = candidate.optString("project_id").takeIf { it.isNotBlank() }
            ?: error("معرّف المشروع غير موجود")
        val sourceUri = sources.load(projectId) ?: error("تعذر الوصول إلى ملف المخطط الأصلي")
        val approved = candidate.optJSONObject("approved_reference")
            ?.let { PlanStorageCodec.decode(it) }
            ?: error("المرجع المصحح غير موجود")
        val baseline = candidate.optJSONObject("reader_result")
            ?.let { PlanStorageCodec.decode(it) }
            ?: error("نتيجة القارئ الأصلية غير موجودة")

        val caseId = caseId(candidateId)
        val extension = sourceExtension(sourceUri)
        val assetName = "assets/$caseId.$extension"
        val referenceName = "labels/$caseId.json"
        val trainingReferenceName = "training_labels/$caseId.json"
        val baselineName = "baselines/$caseId.json"
        val createdAt = Instant.now().toString()

        val manifest = JSONObject().apply {
            put("id", caseId)
            put("city", consent.city.trim())
            put("region", consent.region.trim())
            put("source", "user-consented-private")
            put("asset", assetName)
            put("reference", referenceName)
            put("training_reference", trainingReferenceName)
            put("floors", consent.floors)
            put("license", "explicit-owner-or-license-consent")
            put("deidentified", true)
            // Corrected cases are training material by default. They must never silently leak into test.
            put("split", "train")
            put("project_type", consent.projectType.trim().lowercase(Locale.ROOT))
        }

        val consentRecord = JSONObject().apply {
            put("schema_version", 2)
            put("case_id", caseId)
            put("exported_at_utc", createdAt)
            put("reader_model", ReaderCorrectionStore.READER_MODEL)
            put("owns_or_licensed_attested", consent.ownsOrLicensed)
            put("deidentified_attested", consent.deidentified)
            put("automatic_upload", false)
            put("benchmark_split", "train")
            put("candidate_delta", candidate.optJSONObject("delta") ?: JSONObject())
        }

        val output = appContext.contentResolver.openOutputStream(destination, "w")
            ?: error("تعذر فتح ملف التصدير")
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            putText(zip, "manifest.jsonl", manifest.toString() + "\n")
            putText(zip, referenceName, benchmarkReference(approved).toString(2))
            putText(zip, trainingReferenceName, trainingReference(approved).toString(2))
            putText(zip, baselineName, benchmarkReference(baseline).toString(2))
            putText(zip, "consent/$caseId.json", consentRecord.toString(2))
            putText(
                zip,
                "README.txt",
                "Manzili HAI Reader V3 learning export\n" +
                    "This bundle was exported explicitly by the user. It is TRAIN-only by default.\n" +
                    "training_labels preserves wall thickness, opening rotation and room polygons for fine-tuning.\n" +
                    "No automatic upload occurred. Verify de-identification again before server ingestion.\n"
            )
            zip.putNextEntry(ZipEntry(assetName))
            val input = appContext.contentResolver.openInputStream(sourceUri)
                ?: error("تعذر قراءة ملف المخطط الأصلي")
            input.use { it.copyTo(zip, DEFAULT_BUFFER_SIZE) }
            zip.closeEntry()
        }

        corrections.markExported(candidateId)
        return ReaderLearningExportResult(caseId, candidateId, assetName, referenceName, trainingReferenceName)
    }

    private fun sourceExtension(uri: Uri): String {
        val mime = appContext.contentResolver.getType(uri).orEmpty().lowercase(Locale.ROOT)
        return when {
            mime == "application/pdf" -> "pdf"
            mime == "image/png" -> "png"
            mime == "image/webp" -> "webp"
            mime == "image/heic" || mime == "image/heif" -> "heic"
            mime == "image/jpeg" || mime == "image/jpg" -> "jpg"
            else -> uri.lastPathSegment?.substringAfterLast('.', "jpg")
                ?.lowercase(Locale.ROOT)
                ?.takeIf { it.matches(Regex("[a-z0-9]{2,5}")) }
                ?: "jpg"
        }
    }

    private fun caseId(candidateId: String): String {
        val suffix = candidateId.removePrefix("correction-").replace("-", "").take(16)
        return "hai-train-$suffix"
    }

    private fun putText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    companion object {
        /** Converts the approved Android plan to the exact structure scored by backend/app/benchmark_metrics.py. */
        fun benchmarkReference(plan: FloorPlan): JSONObject = JSONObject().apply {
            put("walls", JSONArray().apply {
                plan.walls.forEach { wall ->
                    put(JSONObject().apply {
                        put("start", JSONObject().put("x", wall.start.x).put("y", wall.start.y))
                        put("end", JSONObject().put("x", wall.end.x).put("y", wall.end.y))
                    })
                }
            })
            put("rooms", JSONArray().apply {
                plan.rooms.forEach { room ->
                    put(JSONObject().apply {
                        put("x", room.x)
                        put("y", room.y)
                        put("width", room.width)
                        put("height", room.height)
                        put("type", room.type)
                    })
                }
            })
            put("openings", JSONArray().apply {
                plan.openings.forEach { opening ->
                    put(JSONObject().apply {
                        put("x", opening.x)
                        put("y", opening.y)
                        put("width", opening.width)
                        put("type", opening.type)
                    })
                }
            })
            put("metric", JSONObject().apply {
                put("dimensions", JSONArray().apply {
                    plan.dimensions.forEach { dimension ->
                        put(JSONObject().put("value_m", dimension.valueM))
                    }
                    if (plan.dimensions.isEmpty()) {
                        plan.widthM?.let { put(JSONObject().put("value_m", it)) }
                        plan.heightM?.let { put(JSONObject().put("value_m", it)) }
                    }
                })
            })
        }

        /**
         * Fine-tuning reference. Coordinates stay in the Reader's 0..100 plan space, but unlike the
         * benchmark reference this preserves the geometry needed to rasterize semantic masks.
         */
        fun trainingReference(plan: FloorPlan): JSONObject = JSONObject().apply {
            put("schema_version", 1)
            put("coordinate_space", "percent-0-100")
            plan.widthM?.let { put("width_m", it) }
            plan.heightM?.let { put("height_m", it) }
            put("rooms", JSONArray().apply {
                plan.rooms.forEach { room ->
                    put(JSONObject().apply {
                        put("id", room.id)
                        put("type", room.type)
                        put("x", room.x)
                        put("y", room.y)
                        put("width", room.width)
                        put("height", room.height)
                        put("polygon", JSONArray().apply {
                            room.polygon.forEach { point ->
                                put(JSONObject().put("x", point.x).put("y", point.y))
                            }
                        })
                    })
                }
            })
            put("walls", JSONArray().apply {
                plan.walls.forEach { wall ->
                    put(JSONObject().apply {
                        put("id", wall.id)
                        put("start", JSONObject().put("x", wall.start.x).put("y", wall.start.y))
                        put("end", JSONObject().put("x", wall.end.x).put("y", wall.end.y))
                        wall.thicknessCm?.let { put("thickness_cm", it) }
                        put("kind", wall.kind)
                    })
                }
            })
            put("openings", JSONArray().apply {
                plan.openings.forEach { opening ->
                    put(JSONObject().apply {
                        put("id", opening.id)
                        put("type", opening.type)
                        put("x", opening.x)
                        put("y", opening.y)
                        put("width", opening.width)
                        put("rotation_deg", opening.rotationDeg)
                        opening.wallId?.let { put("wall_id", it) }
                    })
                }
            })
        }
    }
}
