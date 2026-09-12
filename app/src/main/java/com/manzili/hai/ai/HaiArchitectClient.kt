package com.manzili.hai.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.Base64
import com.manzili.hai.data.HaiSettings
import com.manzili.hai.model.FloorPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class HaiArchitectClient(private val context: Context) {
    private val settings = HaiSettings(context)
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun analyzePlan(uri: Uri): FloorPlan = withContext(Dispatchers.IO) {
        check(settings.configured) { "أدخل إعدادات الذكاء الاصطناعي أولًا" }
        val base64 = renderToBase64Jpeg(uri)
        val prompt = ANALYZE_PROMPT
        val response = chatVision(prompt, base64)
        ArchitectJson.parsePlan(response)
    }

    suspend fun createNewPlan(requirements: String): FloorPlan = withContext(Dispatchers.IO) {
        check(settings.configured) { "أدخل إعدادات الذكاء الاصطناعي أولًا" }
        val prompt = NEW_PLAN_PROMPT + "\n\nمتطلبات العميل:\n" + requirements
        ArchitectJson.parsePlan(chatText(prompt))
    }

    suspend fun advise(plan: FloorPlan, userText: String): String = withContext(Dispatchers.IO) {
        check(settings.configured) { "أدخل إعدادات الذكاء الاصطناعي أولًا" }
        val compact = JSONObject().apply {
            put("title", plan.title)
            put("rooms", JSONArray().apply { plan.rooms.forEach { r -> put(JSONObject().apply {
                put("name", r.name); put("type", r.type); put("area_m2", r.areaM2)
                put("x", r.x); put("y", r.y); put("width", r.width); put("height", r.height)
                put("confidence", r.confidence); put("locked", r.locked)
            }) } })
            put("uncertainties", JSONArray(plan.uncertainties))
        }
        chatText(ADVISER_PROMPT + "\n\nالمخطط الحالي:\n$compact\n\nطلب العميل:\n$userText")
    }

    private fun chatText(prompt: String): String {
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", prompt))
        return request(messages)
    }

    private fun chatVision(prompt: String, base64: String): String {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$base64")))
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", content))
        return request(messages)
    }

    private fun request(messages: JSONArray): String {
        val payload = JSONObject().put("model", settings.model).put("messages", messages).put("temperature", 0.15)
        val req = Request.Builder()
            .url(settings.endpoint)
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) error("فشل اتصال HAI (${res.code}): ${body.take(300)}")
            val root = JSONObject(body)
            return root.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        }
    }

    private fun renderToBase64Jpeg(uri: Uri): String {
        val type = context.contentResolver.getType(uri).orEmpty()
        val bitmap = if (type == "application/pdf") {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("تعذر فتح PDF")
            PdfRenderer(pfd).use { renderer ->
                renderer.openPage(0).use { page ->
                    val scale = 1600f / page.width.coerceAtLeast(1)
                    val w = 1600
                    val h = (page.height * scale).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                }
            }
        } else {
            context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) ?: error("تعذر قراءة الصورة") }
        }
        val max = 1800
        val resized = if (bitmap.width > max || bitmap.height > max) {
            val ratio = minOf(max.toFloat() / bitmap.width, max.toFloat() / bitmap.height)
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
        } else bitmap
        val out = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.JPEG, 88, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        private val ANALYZE_PROMPT = """
أنت HAI Architect، محلل مخططات سكنية شديد الدقة. افحص المخطط بصريًا كما يفعل معماري خبير. لا تخمن الأبعاد غير المقروءة. أعد JSON فقط دون Markdown بالشكل:
{
 "title":"...", "building_width_m":0, "building_height_m":0,
 "summary":"ملخص مهني قصير",
 "rooms":[{"id":"r1","name":"الصالة","type":"living","x":0,"y":0,"width":20,"height":20,"area_m2":0,"confidence":95}],
 "observations":["..."], "uncertainties":["..."]
}
إحداثيات x,y,width,height نسب مئوية 0..100 بالنسبة إلى حدود المخطط، وتستخدم للرسم فقط. area_m2 يجب أن تكون من النص/الأبعاد الواضحة أو 0 عند عدم اليقين. ميّز الغرف والممرات والدرج والخدمات. لا تدّع معرفة جدار إنشائي من الرسم إذا لم يكن موضحًا.
""".trimIndent()

        private val NEW_PLAN_PROMPT = """
أنت معماري سكني خبير. حوّل متطلبات العميل إلى مخطط أولي منطقي قابل للتطوير. احترم الخصوصية والحركة وقصر الممرات وعلاقات الضيوف/العائلة والخدمات. أعد JSON فقط بنفس schema: title, building_width_m, building_height_m, summary, rooms[id,name,type,x,y,width,height,area_m2,confidence], observations, uncertainties. إحداثيات الرسم 0..100 ولا تسمح بتداخل الغرف. إن كانت معلومات جوهرية ناقصة اذكرها في uncertainties ولا تخترعها.
""".trimIndent()

        private val ADVISER_PROMPT = """
أنت «مهندس HAI» داخل تطبيق منزلي HAI. تصرف كمستشار معماري متمرس جدًا: افهم الطلب، افحص القيود والمساحات والعلاقات قبل الاقتراح، واذكر التنازلات بأرقام عندما تسمح البيانات. لا تقل «تم» قبل وجود حل هندسي قابل للتنفيذ. إذا كان الطلب مثل إضافة صالة 3×3، احسب 9م² وابحث عن بدائل، واشرح أي غرفة ستتأثر واستأذن العميل قبل التضحية بمساحة مهمة. لا تعتبر جدارًا إنشائيًا إلا إذا ثبت ذلك. عند نقص المعلومات اسأل سؤالًا واحدًا حاسمًا بدل التخمين. اكتب بالعربية الواضحة المختصرة، ويمكنك فهم اللهجة السعودية.
""".trimIndent()
    }
}
