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
        val modelUsed: String get() = pages.map { it.modelUsed }.distinct().joinToString("+").ifBlank { "modal-cloud-only" }
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
                sourceSummary = "Cloud-only floor-plan reader: $modelUsed",
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
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .callTimeout(330, TimeUnit.SECONDS)
        .build()

    private val parserClient = "android-cloud-only-v1"

    /** Parser access is intentionally public-through-gateway; no device secret is required. */
    val available: Boolean
        get() = settings.backendBaseUrl.startsWith("https://")

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
                    if (continuation.isActive) continuation.resumeWith(Result.failure(e))
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

    suspend fun readiness(): Readiness {
        if (!available) return Readiness(false, "none", false, "none", "رابط خدمة القراءة السحابية غير مهيأ")
        val request = Request.Builder()
            .url("${settings.backendBaseUrl}/v1/public/parser/status")
            .withCloudHeaders()
            .get()
            .build()
        return runCatching {
            val response = executeCancellable(request)
            if (!response.successful) {
                Readiness(false, "status-${response.code}", false, "modal-cloud-only", response.body.take(220))
            } else {
                val root = JSONObject(response.body)
                Readiness(
                    ready = root.optBoolean("ready", false),
                    preferredPath = root.optString("preferred_path", "modal-cloud-only"),
                    configured = root.optBoolean("modal_reader_configured", root.optBoolean("ready", false)),
                    modelLabel = root.optString("reader", "modal-cloud-only"),
                    detail = root.optString("detail", "")
                )
            }
        }.getOrElse { Readiness(false, "network", false, "modal-cloud-only", it.message.orEmpty()) }
    }

    suspend fun analyze(
        uri: Uri,
        maxPdfPages: Int = 8,
        onProgress: suspend (AnalysisProgress) -> Unit = {}
    ): Result {
        require(available) { "خدمة القراءة السحابية غير مهيأة" }
        onProgress(AnalysisProgress(0, "بدء العملية"))

        val state = readiness()
        require(state.ready) { "خدمة القراءة السحابية غير جاهزة: ${state.detail.ifBlank { state.preferredPath }}" }
        onProgress(AnalysisProgress(8, "تم الاتصال بالخدمة السحابية"))

        val images = withContext(Dispatchers.IO) {
            renderer.render(uri, maxPdfPages.coerceIn(1, 12), targetMaxPx = 3200, jpegQuality = 96)
        }
        require(images.isNotEmpty()) { "تعذر تجهيز المخطط للرفع" }
        onProgress(AnalysisProgress(18, "تم تجهيز ${images.size} صفحة للرفع"))

        val pages = ArrayList<PageResult>(images.size)
        images.forEachIndexed { index, image ->
            val start = 18 + (index * 76 / images.size.coerceAtLeast(1))
            onProgress(AnalysisProgress(start.coerceAtMost(90), "تحليل الصفحة ${index + 1} من ${images.size} سحابيًا"))
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
        val req = Request.Builder()
            .url("${settings.backendBaseUrl}/v1/public/parse-floorplan")
            .withCloudHeaders()
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val response = executeCancellable(req)
        if (!response.successful) error("Cloud reader (${response.code}): ${response.body.take(320)}")
        return parse(pageIndex, JSONObject(response.body))
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
            modelUsed = root.optString("model_used", "modal-cloud-reader"),
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
