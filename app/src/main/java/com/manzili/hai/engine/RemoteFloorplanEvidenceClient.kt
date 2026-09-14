package com.manzili.hai.engine

import android.content.Context
import android.net.Uri
import com.manzili.hai.data.HaiSettings
import com.manzili.hai.model.FloorLevel
import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.Opening
import com.manzili.hai.model.PlanPoint
import com.manzili.hai.model.Room
import com.manzili.hai.model.Wall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
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

    data class PageResult(
        val pageIndex: Int,
        val rooms: List<Room>,
        val walls: List<Wall>,
        val openings: List<Opening>,
        val ocrLines: List<PlanTextOcrEngine.SpatialLine>,
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
            return FloorPlan(
                title = title,
                rooms = first.rooms,
                walls = first.walls,
                openings = first.openings,
                uncertainties = warnings,
                sourceSummary = "Cloud-only floor-plan reader: $modelUsed",
                floors = floors,
                activeFloorId = first.id,
                scaleConfidence = ordered.maxOfOrNull { it.scaleConfidence } ?: 0
            )
        }
    }

    private val settings = HaiSettings(context)
    private val renderer = PdfPageRendererEngine(context)
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .callTimeout(330, TimeUnit.SECONDS)
        .build()

    private val parserClient = "android-cloud-only-v1"
    val available: Boolean
        get() = settings.backendBaseUrl.startsWith("https://") && settings.backendAuthToken.isNotBlank()

    private fun Request.Builder.withCloudHeaders(): Request.Builder {
        header("X-Manzili-Parser-Client", parserClient)
        header("Authorization", "Bearer ${settings.backendAuthToken}")
        return this
    }

    suspend fun readiness(): Readiness = withContext(Dispatchers.IO) {
        if (!available) return@withContext Readiness(false, "none", false, "none", "بيانات دخول خدمة القراءة السحابية غير مهيأة")
        val request = Request.Builder()
            .url("${settings.backendBaseUrl}/readyz")
            .withCloudHeaders()
            .get()
            .build()
        runCatching {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@use Readiness(false, "status-${response.code}", false, "modal-cloud-only", text.take(220))
                val root = JSONObject(text)
                Readiness(
                    ready = root.optBoolean("ok", false),
                    preferredPath = root.optString("reader", "modal-cloud-only"),
                    configured = root.optBoolean("modal_reader_configured", true),
                    modelLabel = root.optString("reader", "modal-cloud-only"),
                    detail = root.optString("detail", "")
                )
            }
        }.getOrElse { Readiness(false, "network", false, "modal-cloud-only", it.message.orEmpty()) }
    }

    suspend fun analyze(uri: Uri, maxPdfPages: Int = 8): Result = withContext(Dispatchers.IO) {
        require(available) { "خدمة القراءة السحابية غير مهيأة" }
        val state = readiness()
        require(state.ready) { "خدمة القراءة السحابية غير جاهزة: ${state.detail.ifBlank { state.preferredPath }}" }
        val images = renderer.render(uri, maxPdfPages.coerceIn(1, 12), targetMaxPx = 3200, jpegQuality = 96)
        require(images.isNotEmpty()) { "تعذر تجهيز المخطط للرفع" }
        Result(images.map { requestPage(it.pageIndex, it.base64Jpeg) })
    }

    private fun requestPage(pageIndex: Int, base64: String): PageResult {
        val body = JSONObject().put("image_base64", base64).put("page_index", pageIndex)
        val req = Request.Builder()
            .url("${settings.backendBaseUrl}/v1/parse-floorplan")
            .withCloudHeaders()
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) error("Cloud reader (${res.code}): ${text.take(320)}")
            return parse(pageIndex, JSONObject(text))
        }
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
