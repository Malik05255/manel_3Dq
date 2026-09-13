package com.manzili.hai.engine

import android.content.Context
import android.net.Uri
import com.manzili.hai.data.HaiSettings
import com.manzili.hai.model.Opening
import com.manzili.hai.model.PlanPoint
import com.manzili.hai.model.Wall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class RemoteFloorplanEvidenceClient(private val context: Context) {
    data class Readiness(
        val ready:Boolean,
        val preferredPath:String,
        val configured:Boolean,
        val modelLabel:String,
        val detail:String
    )

    data class PageResult(
        val pageIndex: Int,
        val walls: List<Wall>,
        val openings: List<Opening>,
        val ocrLines: List<PlanTextOcrEngine.SpatialLine>,
        val modelUsed: String,
        val confidence: Int,
        val warnings: List<String>
    )

    data class Result(val pages: List<PageResult>) {
        val walls: List<Wall> get() = pages.firstOrNull()?.walls.orEmpty()
        val openings: List<Opening> get() = pages.firstOrNull()?.openings.orEmpty()
        val ocrLines: List<PlanTextOcrEngine.SpatialLine> get() = pages.flatMap { it.ocrLines }
        val modelUsed: String get() = pages.map { it.modelUsed }.distinct().joinToString("+").ifBlank { "unknown" }
        val confidence: Int get() = if (pages.isEmpty()) 0 else pages.map { it.confidence }.average().toInt()
        val warnings: List<String> get() = pages.flatMap { page -> page.warnings.map { "صفحة ${page.pageIndex + 1}: $it" } }
    }

    private val settings = HaiSettings(context)
    private val renderer = PdfPageRendererEngine(context)
    private val http = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build()

    val available: Boolean get() = settings.backendConfigured

    suspend fun readiness():Readiness = withContext(Dispatchers.IO) {
        if(!available) return@withContext Readiness(false,"none",false,"none","Backend غير مفعّل أو لا توجد مصادقة")
        val request=Request.Builder()
            .url("${settings.backendBaseUrl}/v1/parser/status")
            .header("Authorization","Bearer ${settings.backendAuthToken}")
            .get()
            .build()
        runCatching {
            http.newCall(request).execute().use { response ->
                val text=response.body?.string().orEmpty()
                if(!response.isSuccessful) return@use Readiness(false,"status-${response.code}",true,"unknown",text.take(180))
                val root=JSONObject(text)
                val model=root.optJSONObject("model")
                Readiness(
                    ready=root.optBoolean("ready",false),
                    preferredPath=root.optString("preferred_path","fallback"),
                    configured=model?.optBoolean("configured",false)?:false,
                    modelLabel=model?.optString("backend","unknown")?:"unknown",
                    detail=root.optString("detail","")
                )
            }
        }.getOrElse { Readiness(false,"network",true,"unknown",it.message.orEmpty()) }
    }

    suspend fun analyze(uri: Uri, maxPdfPages: Int = 8): Result = withContext(Dispatchers.IO) {
        require(available) { "Backend غير مفعّل" }
        val state=readiness()
        require(state.ready) { "Deep Parser غير جاهز: ${state.detail.ifBlank { state.preferredPath }}" }
        val images = renderer.render(uri, maxPdfPages = maxPdfPages.coerceIn(1,12), targetMaxPx = 1800, jpegQuality = 88)
        val pages = images.map { image -> requestPage(image.pageIndex, image.base64Jpeg) }
        Result(pages)
    }

    private fun requestPage(pageIndex: Int, base64: String): PageResult {
        val body = JSONObject().put("image_base64", base64).put("page_index", pageIndex)
        val req = Request.Builder()
            .url("${settings.backendBaseUrl}/v1/parse-floorplan")
            .header("Authorization", "Bearer ${settings.backendAuthToken}")
            .header("Content-Type", "application/json")
            .header("X-Manzili-Parser-Client", "android-0.62")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) error("Remote parser (${res.code}): ${text.take(260)}")
            return parse(pageIndex, JSONObject(text))
        }
    }

    private fun parse(pageIndex: Int, root: JSONObject): PageResult {
        val prefix = "p$pageIndex-"
        val wallsJson = root.optJSONArray("walls")
        val walls = buildList {
            if (wallsJson != null) for (i in 0 until wallsJson.length()) {
                val w = wallsJson.optJSONObject(i) ?: continue
                val a = w.optJSONObject("start") ?: continue
                val b = w.optJSONObject("end") ?: continue
                add(Wall(
                    id = prefix + w.optString("id", "remote-$i"),
                    start = PlanPoint(a.optDouble("x").toFloat().coerceIn(0f,100f), a.optDouble("y").toFloat().coerceIn(0f,100f)),
                    end = PlanPoint(b.optDouble("x").toFloat().coerceIn(0f,100f), b.optDouble("y").toFloat().coerceIn(0f,100f)),
                    kind = w.optString("kind", "remote-segmentation-evidence"),
                    confidence = w.optInt("confidence", 70).coerceIn(0,100)
                ))
            }
        }
        val openingsJson = root.optJSONArray("openings")
        val openings = buildList {
            if (openingsJson != null) for (i in 0 until openingsJson.length()) {
                val o = openingsJson.optJSONObject(i) ?: continue
                val type = o.optString("type").lowercase()
                if (type != "door" && type != "window") continue
                add(Opening(
                    id = prefix + o.optString("id", "remote-opening-$i"),
                    type = type,
                    x = o.optDouble("x").toFloat().coerceIn(0f, 100f),
                    y = o.optDouble("y").toFloat().coerceIn(0f, 100f),
                    width = o.optDouble("width", 1.0).toFloat().coerceIn(.2f, 20f),
                    rotationDeg = o.optDouble("rotation_deg", 0.0).toFloat(),
                    confidence = o.optInt("confidence", 75).coerceIn(0, 100)
                ))
            }
        }
        val ocrJson = root.optJSONArray("ocr_lines")
        val ocr = buildList {
            if (ocrJson != null) for (i in 0 until ocrJson.length()) {
                val o = ocrJson.optJSONObject(i) ?: continue
                val value = o.optString("text").trim()
                if (value.isBlank()) continue
                add(PlanTextOcrEngine.SpatialLine(
                    text = value, pageIndex = pageIndex,
                    leftPct = o.optDouble("left_pct").toFloat().coerceIn(0f,100f),
                    topPct = o.optDouble("top_pct").toFloat().coerceIn(0f,100f),
                    rightPct = o.optDouble("right_pct").toFloat().coerceIn(0f,100f),
                    bottomPct = o.optDouble("bottom_pct").toFloat().coerceIn(0f,100f),
                    confidence = o.optInt("confidence", 70).coerceIn(0,100)
                ))
            }
        }
        val warningsJson = root.optJSONArray("warnings")
        val warnings = buildList {
            if (warningsJson != null) for (i in 0 until warningsJson.length()) warningsJson.optString(i).takeIf { it.isNotBlank() }?.let(::add)
        }
        return PageResult(pageIndex, walls, openings, ocr, root.optString("model_used", "unknown"), root.optInt("confidence", 0), warnings)
    }
}
