package com.manzili.hai.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.Base64
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
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class RemoteFloorplanEvidenceClient(private val context: Context) {
    data class Result(
        val walls: List<Wall>,
        val openings: List<Opening>,
        val ocrLines: List<PlanTextOcrEngine.SpatialLine>,
        val modelUsed: String,
        val confidence: Int,
        val warnings: List<String>
    )

    private val settings = HaiSettings(context)
    private val http = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build()

    val available: Boolean get() = settings.backendConfigured

    suspend fun analyze(uri: Uri): Result = withContext(Dispatchers.IO) {
        require(available) { "Backend غير مفعّل" }
        val body = JSONObject().put("image_base64", render(uri))
        val req = Request.Builder()
            .url("${settings.backendBaseUrl}/v1/parse-floorplan")
            .header("Authorization", "Bearer ${settings.backendAccessToken}")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) error("Remote parser (${res.code}): ${text.take(260)}")
            parse(JSONObject(text))
        }
    }

    private fun parse(root: JSONObject): Result {
        val wallsJson = root.optJSONArray("walls")
        val walls = buildList {
            if (wallsJson != null) for (i in 0 until wallsJson.length()) {
                val w = wallsJson.optJSONObject(i) ?: continue
                val a = w.optJSONObject("start") ?: continue
                val b = w.optJSONObject("end") ?: continue
                add(Wall(
                    id = w.optString("id", "remote-$i"),
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
                    id = o.optString("id", "remote-opening-$i"),
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
                    text = value, pageIndex = 0,
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
        return Result(walls, openings, ocr, root.optString("model_used", "unknown"), root.optInt("confidence", 0), warnings)
    }

    private fun render(uri: Uri): String {
        val type = context.contentResolver.getType(uri).orEmpty()
        val bitmap = if (type == "application/pdf") {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("تعذر فتح PDF")
            PdfRenderer(pfd).use { renderer ->
                require(renderer.pageCount > 0) { "PDF بلا صفحات" }
                renderer.openPage(0).use { page ->
                    val scale = minOf(1800f / page.width.coerceAtLeast(1), 1800f / page.height.coerceAtLeast(1))
                    val w = (page.width * scale).toInt().coerceAtLeast(1)
                    val h = (page.height * scale).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                }
            }
        } else context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) ?: error("تعذر قراءة الصورة") }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
