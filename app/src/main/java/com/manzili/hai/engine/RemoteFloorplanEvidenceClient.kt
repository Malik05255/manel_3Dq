package com.manzili.hai.engine

import android.content.Context
import android.net.Uri
import com.manzili.hai.data.HaiSettings
import com.manzili.hai.model.FloorLevel
import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.Opening
import com.manzili.hai.model.PlanDimension
import com.manzili.hai.model.PlanPoint
import com.manzili.hai.model.Room
import com.manzili.hai.model.Wall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Transport-only floor-plan client. All inference is performed in the cloud. */
class RemoteFloorplanEvidenceClient(private val context: Context) {
    data class Readiness(
        val ready: Boolean,
        val preferredPath: String,
        val configured: Boolean,
        val modelLabel: String,
        val detail: String
    )

    data class AnalysisProgress(val percent: Int, val label: String)

    data class PageResult(
        val pageIndex: Int,
        val rooms: List<Room>,
        val walls: List<Wall>,
        val openings: List<Opening>,
        val ocrLines: List<PlanTextOcrEngine.SpatialLine>,
        val dimensions: List<PlanDimension>,
        val widthM: Double?,
        val heightM: Double?,
        val modelUsed: String,
        val confidence: Int,
        val geometryConfidence: Int,
        val ocrConfidence: Int,
        val scaleConfidence: Int,
        val wallTopology: Int,
        val dimensionEvidenceCount: Int,
        val warnings: List<String>
    )

    data class Result(val pages: List<PageResult>) {
        val rooms: List<Room> get() = pages.firstOrNull()?.rooms.orEmpty()
        val walls: List<Wall> get() = pages.firstOrNull()?.walls.orEmpty()
        val openings: List<Opening> get() = pages.firstOrNull()?.openings.orEmpty()
        val ocrLines: List<PlanTextOcrEngine.SpatialLine> get() = pages.flatMap { it.ocrLines }
        val modelUsed: String get() = pages.map { it.modelUsed }.distinct().joinToString("+").ifBlank { "hai-source-first-v4" }
        val confidence: Int get() = if (pages.isEmpty()) 0 else pages.map { it.confidence }.average().toInt()
        val warnings: List<String> get() = pages.flatMap { page -> page.warnings.map { "صفحة ${page.pageIndex + 1}: $it" } }

        fun toFloorPlan(title: String): FloorPlan {
            require(pages.isNotEmpty()) { "لم تصل أي صفحة من خدمة القراءة السحابية" }
            val ordered = pages.sortedBy { it.pageIndex }
            val floors = ordered.map { page ->
                FloorLevel(
                    id = "cloud-floor-${page.pageIndex}",
                    name = if (page.pageIndex == 0) "الدور الأرضي" else "الصفحة ${page.pageIndex + 1}",
                    index = page.pageIndex,
                    elevationM = page.pageIndex * 3.2,
                    rooms = page.rooms,
                    walls = page.walls,
                    openings = page.openings
                )
            }
            val first = floors.first()
            val metricPage = ordered
                .filter { it.widthM != null && it.heightM != null }
                .maxByOrNull { it.scaleConfidence }
            return FloorPlan(
                title = title,
                widthM = metricPage?.widthM,
                heightM = metricPage?.heightM,
                rooms = first.rooms,
                walls = first.walls,
                openings = first.openings,
                uncertainties = warnings,
                sourceSummary = "Source-First V4 cloud reader: $modelUsed",
                dimensions = ordered.flatMap { it.dimensions },
                scaleConfidence = metricPage?.scaleConfidence ?: 0,
                floors = floors,
                activeFloorId = first.id
            )
        }
    }

    private data class HttpResult(val code: Int, val successful: Boolean, val body: String)

    private val settings = HaiSettings(context)
    private val renderer = PdfPageRendererEngine(context)
    private val http = OkHttpClient.Builder()
        .dns(ResilientDns())
        .connectTimeout(18, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .callTimeout(330, TimeUnit.SECONDS)
        .build()

    private val parserClient = "android-source-first-v4"
    private val bridgeBaseUrl = "https://abavsspydbpkudhswmzp.supabase.co/functions/v1/hai-floorplan-gateway"

    @Volatile
    private var activeBaseUrl: String? = null

    /**
     * The Render gateway remains the preferred production path. The Supabase edge bridge is a
     * transport-only DNS failover so Android devices that cannot resolve an onrender.com host do
     * not lose floor-plan analysis entirely. Both paths terminate at the same cloud gateway and
     * therefore the same authoritative Source-First Reader V4.
     */
    private fun candidateBaseUrls(): List<String> = buildList {
        settings.backendBaseUrl.trim().trimEnd('/').takeIf { it.startsWith("https://") }?.let(::add)
        add(bridgeBaseUrl)
    }.distinct()

    private fun statusUrl(baseUrl: String): String =
        if (baseUrl == bridgeBaseUrl) baseUrl else "$baseUrl/v1/public/parser/status"

    private fun parseUrl(baseUrl: String): String =
        if (baseUrl == bridgeBaseUrl) baseUrl else "$baseUrl/v1/public/parse-floorplan"

    /** Parser access is intentionally public-through-gateway; no device secret is required. */
    val available: Boolean
        get() = candidateBaseUrls().isNotEmpty()

    private fun Request.Builder.withCloudHeaders(): Request.Builder {
        header("X-Manzili-Parser-Client", parserClient)
        val token = settings.backendAuthToken
        if (token.isNotBlank()) header("Authorization", "Bearer $token")
        return this
    }

    private suspend fun executeCancellable(request: Request): HttpResult =
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWith(kotlin.Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            HttpResult(
                                code = it.code,
                                successful = it.isSuccessful,
                                body = it.body?.string().orEmpty()
                            )
                        }
                    }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            })
        }

    private fun responseDetail(response: HttpResult): String? = runCatching {
        JSONObject(response.body).optString("detail").trim().takeIf { it.isNotBlank() }
    }.getOrNull()

    suspend fun readiness(): Readiness {
        if (!available) return Readiness(false, "none", false, "none", "رابط خدمة القراءة السحابية غير مهيأ")

        var lastDetail = ""
        for (baseUrl in candidateBaseUrls()) {
            val request = Request.Builder()
                .url(statusUrl(baseUrl))
                .withCloudHeaders()
                .get()
                .build()

            val response = try {
                executeCancellable(request)
            } catch (_: IOException) {
                lastDetail = if (baseUrl == bridgeBaseUrl) {
                    "تعذر الاتصال بمسار الشبكة الاحتياطي"
                } else {
                    "تعذر حل عنوان بوابة القراءة؛ يجري استخدام مسار الشبكة الاحتياطي"
                }
                continue
            }

            if (!response.successful) {
                lastDetail = responseDetail(response) ?: "بوابة القراءة أعادت HTTP ${response.code}"
                continue
            }

            val root = try {
                JSONObject(response.body)
            } catch (_: Exception) {
                lastDetail = "استجابة بوابة القراءة غير صالحة"
                continue
            }
            val ready = root.optBoolean("ready", false)
            if (!ready) {
                lastDetail = root.optString("detail", "Source-First Reader V4 لم يصبح جاهزًا بعد")
                continue
            }

            activeBaseUrl = baseUrl
            return Readiness(
                ready = true,
                preferredPath = root.optString("preferred_path", "hai-source-first-v4"),
                configured = root.optBoolean("reader_configured", root.optBoolean("modal_reader_configured", true)),
                modelLabel = root.optString("reader", "hai-source-first-v4"),
                detail = if (baseUrl == bridgeBaseUrl) "تم الاتصال عبر مسار الشبكة الاحتياطي" else root.optString("detail", "")
            )
        }

        activeBaseUrl = null
        return Readiness(
            ready = false,
            preferredPath = "network-failover",
            configured = false,
            modelLabel = "hai-source-first-v4",
            detail = lastDetail.ifBlank { "تعذر الوصول إلى بوابة Source-First V4 عبر مسارات الشبكة المتاحة" }
        )
    }

    suspend fun analyze(
        uri: Uri,
        maxPdfPages: Int = 8,
        onProgress: suspend (AnalysisProgress) -> Unit = {}
    ): Result {
        require(available) { "خدمة القراءة السحابية غير مهيأة" }
        onProgress(AnalysisProgress(0, "بدء العملية"))

        val state = readiness()
        require(state.ready) { "خدمة Source-First V4 غير جاهزة: ${state.detail.ifBlank { state.preferredPath }}" }
        onProgress(AnalysisProgress(8, if (activeBaseUrl == bridgeBaseUrl) "تم الاتصال عبر مسار الشبكة الاحتياطي" else "تم الاتصال بخدمة Source-First V4"))

        val images = withContext(Dispatchers.IO) {
            renderer.render(uri, maxPdfPages.coerceIn(1, 12), targetMaxPx = 3200, jpegQuality = 96)
        }
        require(images.isNotEmpty()) { "تعذر تجهيز المخطط للرفع" }
        onProgress(AnalysisProgress(18, "تم تجهيز ${images.size} صفحة للرفع"))

        val pages = ArrayList<PageResult>(images.size)
        images.forEachIndexed { index, image ->
            val start = 18 + (index * 76 / images.size.coerceAtLeast(1))
            onProgress(AnalysisProgress(start.coerceAtMost(90), "تحليل الصفحة ${index + 1} من ${images.size} عبر Source-First V4"))
            pages += requestPage(image.pageIndex, image.base64Jpeg)
            val done = 18 + ((index + 1) * 76 / images.size.coerceAtLeast(1))
            onProgress(AnalysisProgress(done.coerceAtMost(94), "تم استلام الصفحة ${index + 1} من ${images.size}"))
        }

        onProgress(AnalysisProgress(98, "دمج نتيجة القراءة"))
        val result = Result(pages)
        onProgress(AnalysisProgress(100, "اكتمل التحليل"))
        return result
    }

    private suspend fun requestPage(pageIndex: Int, base64: String): PageResult {
        val body = JSONObject().put("image_base64", base64).put("page_index", pageIndex)
        val orderedTargets = buildList {
            activeBaseUrl?.let(::add)
            addAll(candidateBaseUrls())
        }.distinct()

        var lastDetail = ""
        for (baseUrl in orderedTargets) {
            val req = Request.Builder()
                .url(parseUrl(baseUrl))
                .withCloudHeaders()
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = try {
                executeCancellable(req)
            } catch (_: IOException) {
                lastDetail = if (baseUrl == bridgeBaseUrl) {
                    "تعذر الاتصال بمسار الشبكة الاحتياطي"
                } else {
                    "تعذر الاتصال ببوابة القراءة الأساسية"
                }
                continue
            }

            if (response.successful) {
                activeBaseUrl = baseUrl
                return parse(pageIndex, JSONObject(response.body))
            }

            lastDetail = responseDetail(response)
                ?: if (response.code in setOf(502, 503, 504)) {
                    "Source-First Reader V4 ما زال يبدأ التشغيل (HTTP ${response.code})"
                } else {
                    "بوابة القراءة أعادت HTTP ${response.code}"
                }
            if (response.code !in setOf(408, 425, 429, 500, 502, 503, 504)) {
                continue
            }
        }

        error("تعذر إكمال تحليل المخطط عبر Source-First V4. $lastDetail")
    }

    private fun parse(pageIndex: Int, root: JSONObject): PageResult {
        require(!root.optBoolean("local_inference", false)) { "رفضت نتيجة قراءة محلية" }
        val prefix = "p$pageIndex-"
        val rooms = buildList {
            val arr = root.optJSONArray("rooms") ?: return@buildList
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val polygon = buildList {
                    val points = r.optJSONArray("polygon") ?: return@buildList
                    for (j in 0 until points.length()) {
                        val p = points.optJSONObject(j) ?: continue
                        add(PlanPoint(p.optDouble("x").toFloat().coerceIn(0f, 100f), p.optDouble("y").toFloat().coerceIn(0f, 100f)))
                    }
                }
                add(Room(
                    id = prefix + r.optString("id", "cloud-room-$i"),
                    name = r.optString("name", "مساحة مكتشفة ${i + 1}"),
                    type = r.optString("type", "unknown"),
                    x = r.optDouble("x").toFloat().coerceIn(0f, 100f),
                    y = r.optDouble("y").toFloat().coerceIn(0f, 100f),
                    width = r.optDouble("width").toFloat().coerceIn(.1f, 100f),
                    height = r.optDouble("height").toFloat().coerceIn(.1f, 100f),
                    areaM2 = r.optDouble("area_m2", 0.0),
                    confidence = r.optInt("confidence", 0).coerceIn(0, 100),
                    polygon = polygon
                ))
            }
        }

        val walls = buildList {
            val arr = root.optJSONArray("walls") ?: return@buildList
            for (i in 0 until arr.length()) {
                val w = arr.optJSONObject(i) ?: continue
                val a = w.optJSONObject("start") ?: continue
                val b = w.optJSONObject("end") ?: continue
                add(Wall(
                    id = prefix + w.optString("id", "cloud-wall-$i"),
                    start = PlanPoint(a.optDouble("x").toFloat().coerceIn(0f, 100f), a.optDouble("y").toFloat().coerceIn(0f, 100f)),
                    end = PlanPoint(b.optDouble("x").toFloat().coerceIn(0f, 100f), b.optDouble("y").toFloat().coerceIn(0f, 100f)),
                    kind = w.optString("kind", "cloud-reader-wall"),
                    confidence = w.optInt("confidence", 0).coerceIn(0, 100)
                ))
            }
        }

        val openings = buildList {
            val arr = root.optJSONArray("openings") ?: return@buildList
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val type = o.optString("type").lowercase()
                if (type != "door" && type != "window") continue
                val rawWallId = o.optString("wallId").takeIf { it.isNotBlank() }
                add(Opening(
                    id = prefix + o.optString("id", "cloud-opening-$i"),
                    type = type,
                    x = o.optDouble("x").toFloat().coerceIn(0f, 100f),
                    y = o.optDouble("y").toFloat().coerceIn(0f, 100f),
                    width = o.optDouble("width", 1.0).toFloat().coerceIn(.2f, 20f),
                    rotationDeg = o.optDouble("rotation_deg", 0.0).toFloat(),
                    wallId = rawWallId?.let { prefix + it },
                    confidence = o.optInt("confidence", 0).coerceIn(0, 100)
                ))
            }
        }

        val ocr = buildList {
            val arr = root.optJSONArray("ocr_lines") ?: return@buildList
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val value = o.optString("text").trim()
                if (value.isBlank()) continue
                add(PlanTextOcrEngine.SpatialLine(
                    text = value,
                    pageIndex = pageIndex,
                    leftPct = o.optDouble("left_pct").toFloat().coerceIn(0f, 100f),
                    topPct = o.optDouble("top_pct").toFloat().coerceIn(0f, 100f),
                    rightPct = o.optDouble("right_pct").toFloat().coerceIn(0f, 100f),
                    bottomPct = o.optDouble("bottom_pct").toFloat().coerceIn(0f, 100f),
                    confidence = o.optInt("confidence", 0).coerceIn(0, 100)
                ))
            }
        }

        val metric = root.optJSONObject("metric")
        val widthM = metric?.optDouble("width_m", Double.NaN)?.takeIf { it.isFinite() && it > 0.5 }
        val heightM = metric?.optDouble("height_m", Double.NaN)?.takeIf { it.isFinite() && it > 0.5 }
        val dimensions = buildList {
            val arr = metric?.optJSONArray("dimensions") ?: return@buildList
            for (i in 0 until arr.length()) {
                val d = arr.optJSONObject(i) ?: continue
                val valueM = d.optDouble("value_m", Double.NaN)
                if (!valueM.isFinite() || valueM <= 0.0) continue
                val axis = d.optString("axis", "unknown")
                add(PlanDimension(
                    id = prefix + "cloud-dimension-$i",
                    label = "${"%.2f".format(valueM)} م",
                    valueM = valueM,
                    axis = axis,
                    confidence = d.optInt("confidence", 0).coerceIn(0, 100),
                    sourceText = d.optString("text", ""),
                    pageIndex = pageIndex
                ))
            }
        }

        val warnings = buildList {
            val arr = root.optJSONArray("warnings") ?: return@buildList
            for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let(::add)
        }
        val quality = root.optJSONObject("quality")
        return PageResult(
            pageIndex = pageIndex,
            rooms = rooms,
            walls = walls,
            openings = openings,
            ocrLines = ocr,
            dimensions = dimensions,
            widthM = widthM,
            heightM = heightM,
            modelUsed = root.optString("model_used", "hai-source-first-v4"),
            confidence = root.optInt("confidence", 0).coerceIn(0, 100),
            geometryConfidence = quality?.optInt("geometry", 0)?.coerceIn(0, 100) ?: 0,
            ocrConfidence = quality?.optInt("ocr", 0)?.coerceIn(0, 100) ?: 0,
            scaleConfidence = quality?.optInt("scale_evidence", 0)?.coerceIn(0, 100) ?: 0,
            wallTopology = quality?.optInt("wall_topology", 0)?.coerceIn(0, 100) ?: 0,
            dimensionEvidenceCount = quality?.optInt("dimension_evidence", 0)?.coerceAtLeast(0) ?: 0,
            warnings = warnings
        )
    }
}
