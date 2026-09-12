package com.manzili.hai.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.Base64
import com.manzili.hai.data.HaiSettings
import com.manzili.hai.engine.ArchitecturalEngine
import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.PlanProposal
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
        .readTimeout(150, TimeUnit.SECONDS)
        .build()

    suspend fun analyzePlan(uri: Uri): FloorPlan = withContext(Dispatchers.IO) {
        check(settings.configured) { "أدخل إعدادات الذكاء الاصطناعي أولًا" }
        val base64 = renderToBase64Jpeg(uri)
        ArchitectJson.parsePlan(chatVision(ANALYZE_PROMPT, base64))
    }

    suspend fun createNewPlan(requirements: String): FloorPlan = withContext(Dispatchers.IO) {
        check(settings.configured) { "أدخل إعدادات الذكاء الاصطناعي أولًا" }
        val prompt = NEW_PLAN_PROMPT + "\n\nبرنامج المشروع كما أجاب العميل:\n" + requirements
        ArchitectJson.parsePlan(chatText(prompt))
    }

    suspend fun proposeChange(plan: FloorPlan, userText: String): PlanProposal = withContext(Dispatchers.IO) {
        check(settings.configured) { "أدخل إعدادات الذكاء الاصطناعي أولًا" }
        val compact = planToJson(plan)
        val deterministicBrief = ArchitecturalEngine.compactBrief(plan)
        val prompt = CHANGE_PROMPT +
            "\n\nتقييم المحرك الهندسي المحلي:\n$deterministicBrief" +
            "\n\nالمخطط الحالي (هو المرجع الوحيد للأبعاد الموجودة):\n$compact" +
            "\n\nطلب العميل:\n$userText"
        ArchitectJson.parseProposal(chatText(prompt))
    }

    suspend fun advise(plan: FloorPlan, userText: String): String {
        return proposeChange(plan, userText).message
    }

    private fun planToJson(plan: FloorPlan): JSONObject = JSONObject().apply {
        put("title", plan.title)
        plan.widthM?.let { put("building_width_m", it) }
        plan.heightM?.let { put("building_height_m", it) }
        put("revision", plan.revision)
        put("summary", plan.sourceSummary)
        put("rooms", JSONArray().apply {
            plan.rooms.forEach { r ->
                put(JSONObject().apply {
                    put("id", r.id)
                    put("name", r.name)
                    put("type", r.type)
                    put("area_m2", r.areaM2)
                    put("x", r.x)
                    put("y", r.y)
                    put("width", r.width)
                    put("height", r.height)
                    put("confidence", r.confidence)
                    put("locked", r.locked)
                    r.minAreaM2?.let { put("min_area_m2", it) }
                    r.preferredAreaM2?.let { put("preferred_area_m2", it) }
                })
            }
        })
        put("observations", JSONArray(plan.observations))
        put("uncertainties", JSONArray(plan.uncertainties))
        put("preferences", JSONObject().apply {
            put("privacy", plan.preferences.privacyPriority)
            put("circulation", plan.preferences.circulationPriority)
            put("daylight", plan.preferences.daylightPriority)
            put("future_flexibility", plan.preferences.futureFlexibilityPriority)
            put("notes", JSONArray(plan.preferences.notes))
        })
    }

    private fun chatText(prompt: String): String {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
            .put(JSONObject().put("role", "user").put("content", prompt))
        return request(messages)
    }

    private fun chatVision(prompt: String, base64: String): String {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$base64")))
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
            .put(JSONObject().put("role", "user").put("content", content))
        return request(messages)
    }

    private fun request(messages: JSONArray): String {
        val payload = JSONObject()
            .put("model", settings.model)
            .put("messages", messages)
            .put("temperature", 0.08)
        val req = Request.Builder()
            .url(settings.endpoint)
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) error("فشل اتصال HAI (${res.code}): ${body.take(350)}")
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
                    val scale = 1800f / page.width.coerceAtLeast(1)
                    val w = 1800
                    val h = (page.height * scale).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        } else {
            context.contentResolver.openInputStream(uri).use {
                BitmapFactory.decodeStream(it) ?: error("تعذر قراءة الصورة")
            }
        }
        val max = 2000
        val resized = if (bitmap.width > max || bitmap.height > max) {
            val ratio = minOf(max.toFloat() / bitmap.width, max.toFloat() / bitmap.height)
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
        } else bitmap
        val out = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        private val SYSTEM_PROMPT = """
أنت HAI Architect داخل تطبيق «منزلي HAI». تعامل مع كل مخطط كبيانات هندسية، لا كصورة جميلة. لا تختلق أبعادًا ولا تدعي أن جدارًا إنشائي ما لم يثبت ذلك في المصدر. افصل دائمًا بين: ما هو مقروء، ما هو استنتاج تخطيطي، وما يحتاج تأكيدًا من المستخدم. الأولوية للسلامة المنطقية، الخصوصية، الحركة، العلاقات الوظيفية، وقلة المساحات المهدرة. افهم العربية واللهجة السعودية. لا تدّع اعتمادًا هندسيًا رسميًا أو مطابقة كود ما لم يتم فحص قاعدة كود موثقة منفصلة.
""".trimIndent()

        private val ANALYZE_PROMPT = """
افحص المخطط بصريًا بدقة. أعد JSON فقط دون Markdown بالشكل التالي:
{
 "title":"...",
 "building_width_m":0,
 "building_height_m":0,
 "summary":"ملخص مهني قصير يصف التنظيم وأهم نقطة تحتاج تحقق",
 "rooms":[
   {"id":"r1","name":"الصالة","type":"living","x":0,"y":0,"width":20,"height":20,"area_m2":0,"confidence":95,"locked":false,"min_area_m2":0,"preferred_area_m2":0}
 ],
 "observations":["..."],
 "uncertainties":["..."],
 "preferences":{"privacy":80,"circulation":80,"daylight":70,"future_flexibility":60,"notes":[]},
 "revision":1
}
إحداثيات x,y,width,height نسب مئوية 0..100 بالنسبة إلى حدود المخطط وتستخدم للرسم الهندسي التقريبي. area_m2 لا تضعها إلا إذا كانت مكتوبة أو قابلة للحساب من أبعاد واضحة؛ وإلا 0. حافظ على هوية كل غرفة بمعرف id ثابت. ميّز غرف النوم والمجلس والصالة والمطبخ والحمامات والممرات والدرج والمصعد والخدمات. إذا لم تستطع تأكيد باب أو بعد أو حدود غرفة، اذكر ذلك في uncertainties بدل التخمين. لا تستنتج الجدران الإنشائية من السماكة البصرية وحدها.
""".trimIndent()

        private val NEW_PLAN_PROMPT = """
صمم مخططًا أوليًا سكنيًا منطقيًا من برنامج العميل. أعد JSON فقط بنفس schema المستخدم في تحليل المخطط. لا تملأ الفراغ بصريًا بشكل عشوائي: كوّن مناطق ضيوف، عائلة، نوم، وخدمات بعلاقات واضحة. قلل الممرات، حافظ على الخصوصية، واربط المطبخ والخدمات منطقيًا. لا تسمح بتداخل المستطيلات المستخدمة لتمثيل الغرف. إذا كانت أبعاد الأرض أو عدد الأدوار أو مدخل الشارع غير واضحة ولا يمكن اتخاذ قرار مسؤول بدونها، ضع السؤال في uncertainties بدل اختراع قيمة. min_area_m2 يمثل حدًا تصميميًا محافظًا تقترحه لهذا المشروع وليس كودًا نظاميًا، وpreferred_area_m2 يمثل المساحة المرغوبة. لا تصف المخطط بأنه معتمد هندسيًا.
""".trimIndent()

        private val CHANGE_PROMPT = """
حلل طلب التعديل كما يفعل معماري متمرس، ثم أعد JSON فقط بالشكل:
{
 "message":"شرح مهني مختصر للقرار والتنازل أو السؤال الحاسم",
 "requires_confirmation":true,
 "confidence":0,
 "changes":[
   {"room_id":"r1","room_name":"المطبخ","action":"RESIZE|MOVE|ADD|DELETE|NO_CHANGE","before_area_m2":16,"after_area_m2":13,"note":"سبب التغيير"}
 ],
 "updated_plan": null
}

قواعد صارمة:
1) إذا كان الطلب سؤالًا أو توجد معلومة حاسمة ناقصة: updated_plan = null واسأل سؤالًا واحدًا فقط.
2) إذا وجدت حلاً هندسيًا: updated_plan يجب أن يحتوي المخطط كاملًا بنفس schema، وليس الغرف المتغيرة فقط.
3) أي غرفة locked=true ممنوع تحريكها أو تغيير مساحتها أو حذفها.
4) حافظ على ids للغرف القائمة، وأعط الغرف الجديدة ids جديدة.
5) لا تقل «تم»؛ استخدم «أقترح» حتى يعتمد المستخدم التعديل داخل التطبيق.
6) عند طلب أبعاد مثل 3×3 احسب المساحة صراحة، ولا تأخذ مساحة من غرفة أخرى بدون توضيح قبل/بعد في changes.
7) لا تسمح بتداخل الغرف أو خروجها من حدود 0..100.
8) لا تغيّر غرفًا لا تحتاجها فقط لتحسين شكل الرسم.
9) إذا خفضت مساحة غرفة تحت min_area_m2 فاعتبر ذلك تنازلًا مهمًا واشرحه.
10) إذا كان المخطط الأصلي منخفض الثقة في المنطقة المطلوبة، اطلب تأكيدًا بدل إعادة رسمها بثقة زائفة.
""".trimIndent()
    }
}
