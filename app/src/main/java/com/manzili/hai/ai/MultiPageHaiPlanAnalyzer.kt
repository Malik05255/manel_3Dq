package com.manzili.hai.ai

import android.content.Context
import android.net.Uri
import com.manzili.hai.data.HaiSettings
import com.manzili.hai.engine.MultiPagePlanFusionEngine
import com.manzili.hai.engine.PdfPageRendererEngine
import com.manzili.hai.engine.SaudiProjectTypeEngine
import com.manzili.hai.model.FloorPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MultiPageHaiPlanAnalyzer(private val context:Context) {
    private val settings=HaiSettings(context)
    private val renderer=PdfPageRendererEngine(context)
    private val http=OkHttpClient.Builder().connectTimeout(30,TimeUnit.SECONDS).readTimeout(180,TimeUnit.SECONDS).build()
    val available:Boolean get()=settings.configured

    suspend fun analyze(
        uri:Uri,
        maxPdfPages:Int=8,
        projectType:SaudiProjectTypeEngine.Type?=null
    ):FloorPlan=withContext(Dispatchers.IO) {
        check(settings.configured){"أدخل إعدادات الذكاء الاصطناعي أولًا"}
        val isPdf=renderer.isPdf(uri)
        val total=if(isPdf) renderer.pageCount(uri) else 1
        // Preserve small dimensions, wall breaks and room labels before the vision provider downsamples.
        val images=renderer.render(uri,maxPdfPages=maxPdfPages,targetMaxPx=3200,jpegQuality=97)
        val typeContext=projectType?.let { type ->
            "\n\nاختيار المستخدم المسبق: نوع المشروع هو «${type.label}». استخدمه فقط لفهم وظيفة المساحات والتسميات؛ لا تجبر الصورة على عناصر غير ظاهرة، ولا تحول اختيار المستخدم إلى دليل بصري."
        }.orEmpty()
        val plans=images.map { page ->
            val pageContext=if(isPdf) {
                "\n\nهذه الصفحة رقم ${page.pageIndex+1} من PDF. حلّل هذه الصفحة وحدها ولا تنقل عناصر من صفحات أخرى."
            } else {
                "\n\nهذه صورة واحدة للمخطط. اقرأ ما يظهر فقط ولا تفترض صفحات أو أدوارًا إضافية."
            }
            ArchitectJson.parsePlan(chatVision(ANALYZE_PROMPT+typeContext+pageContext,page.base64Jpeg))
        }
        val merged=MultiPagePlanFusionEngine.merge(plans,totalPdfPages=total)
        projectType?.let { SaudiProjectTypeEngine.apply(merged,it) } ?: merged
    }

    private fun chatVision(prompt:String,base64:String):String {
        val content=JSONArray()
            .put(JSONObject().put("type","text").put("text",prompt))
            .put(JSONObject().put("type","image_url").put("image_url",JSONObject().put("url","data:image/jpeg;base64,$base64")))
        val messages=JSONArray()
            .put(JSONObject().put("role","system").put("content",SYSTEM_PROMPT))
            .put(JSONObject().put("role","user").put("content",content))
        val payload=JSONObject().put("model",settings.model).put("messages",messages).put("temperature",0.01)
        val req=Request.Builder().url(settings.endpoint)
            .header("Authorization","Bearer ${settings.apiKey}")
            .header("Content-Type","application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { res ->
            val body=res.body?.string().orEmpty()
            if(!res.isSuccessful) error("فشل تحليل المخطط (${res.code}): ${body.take(320)}")
            return JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        }
    }

    companion object {
        private val SYSTEM_PROMPT="""
أنت HAI Architect داخل تطبيق منزلي HAI. اقرأ المخطط كبيانات هندسية دقيقة. افصل بين المقروء والاستنتاج وما يحتاج تأكيدًا. لا تختلق أبعادًا أو صفة إنشائية. حافظ على هوية الغرف والجدران والفتحات. افهم المخططات السكنية السعودية وخصوصية الضيافة والعائلة، لكن لا تدّع مطابقة كود أو اشتراط رسمي بلا قاعدة موثقة. مهمتك في القراءة هي الاكتمال: لا تُسقط مساحة مغلقة ظاهرة، ولا تُسقط رقمًا مرئيًا فقط لأنه لا تعرف معناه. عندما لا تعرف تسمية مساحة مغلقة، أدرجها باسم «مساحة غير مسماة» وثقة أقل بدل تجاهلها. افحص الخطوط الرفيعة والقديمة والمنحنية والمقطوعة بسبب الأبواب، ولا تحول الانحناء الحقيقي إلى خط مستقيم واحد.
""".trimIndent()

        private val ANALYZE_PROMPT="""
أعد JSON فقط دون Markdown:
{
 "title":"...","building_width_m":0,"building_height_m":0,"summary":"...",
 "rooms":[{"id":"r1","name":"غرفة","type":"room","x":0,"y":0,"width":20,"height":20,"area_m2":0,"confidence":90,"locked":false}],
 "walls":[{"id":"w1","start":{"x":0,"y":0},"end":{"x":20,"y":0},"thickness_cm":0,"kind":"external|internal|unknown","confidence":90,"locked":false}],
 "openings":[{"id":"o1","type":"door|window","x":10,"y":0,"width":4,"rotation_deg":0,"wall_id":"w1","connects_room_ids":["r1"],"confidence":90,"locked":false}],
 "dimensions":[{"id":"d1","label":"بعد","value_m":4.2,"axis":"horizontal|vertical|unknown","start":{"x":0,"y":0},"end":{"x":20,"y":0},"confidence":90,"source_text":"4.2"}],
 "numeric_labels":[{"id":"n1","raw_text":"19.88 m²","value":19.88,"kind":"area|dimension|elevation|count|number","x":50,"y":50,"page_index":0,"confidence":95}],
 "observations":[],"uncertainties":[],
 "preferences":{"privacy":80,"circulation":80,"daylight":70,"future_flexibility":60,"notes":[]},"revision":1
}

قواعد إلزامية للقراءة:
1) الإحداثيات نسب 0..100 بالنسبة إلى حدود الصورة نفسها.
2) مرّ بصريًا على المخطط من أعلى اليسار إلى أسفل اليمين، ثم راجعه مرة ثانية بالعكس قبل إخراج JSON.
3) rooms يجب أن تحتوي كل مساحة وظيفية مغلقة أو شبه مغلقة يمكن تمييز حدودها: غرف النوم، المجالس، الصالات، المطابخ، الحمامات، المغاسل، المخازن، الدرج، الممرات المغلقة وأي مساحة أخرى. إذا كانت الحدود واضحة والاسم غير مقروء، أدرجها «مساحة غير مسماة» بثقة منخفضة بدل حذفها.
4) لا تعتبر الأثاث أو النصوص جدرانًا. استخرج الجدران كمسارات هندسية، واجمع الأجزاء المتصلة بصريًا قدر الإمكان. الباب لا يلغي هوية الجدار الذي يقع عليه.
5) استخرج كل باب وكل نافذة ظاهرة، واربطها بالجدار فقط عندما تكون العلاقة واضحة.
6) numeric_labels يجب أن تسجل كل رقم ظاهر في المخطط بلا استثناء قدر الإمكان: أبعاد الأطراف، مساحات الغرف، المناسيب، أرقام الملاحظات وأي رقم آخر. احتفظ بالنص الأصلي في raw_text ولا تحوله إلى طول إذا كان معناه مساحة أو غير معروف.
7) dimensions مخصصة فقط للأطوال/العروض/الارتفاعات التي يمكن فهمها كقياس طولي. numeric_labels أوسع وتشمل جميع الأرقام.
8) area_m2 لا تضعها إلا إذا كانت مكتوبة بوضوح داخل المساحة أو قابلة للحساب من أبعاد مؤكدة؛ وإلا 0.
9) لا تخمن رقمًا غير مقروء. عند وجود رقم جزئي أو ملتبس ضعه في uncertainties بدل اختلاق قيمة.
10) قبل الإخراج قارن عدد rooms بصريًا بعدد الفراغات المحاطة بالجدران، وقارن walls بالرسم مرة ثانية لتجنب السهو.
11) الجدار المنحني أو القوسي لا تمثله بقطعة مستقيمة واحدة. مثّله كسلسلة من 6 إلى 24 مقطعًا قصيرًا متصلًا في walls حسب شدة الانحناء، مع id متسلسل مثل curve1-01 وcurve1-02. حافظ على مسار القوس ونقطتي بدايته ونهايته بصريًا.
12) إذا كان المخطط قديمًا أو باهتًا، افحص كل ربع بصريًا بصورة مستقلة، ثم طابق الجدران والأرقام على مستوى الصفحة كاملة. ارفع uncertainty بدل إسقاط العنصر عندما يكون موجودًا لكن دقته غير كافية.
13) لا ترفع confidence إلى 100 إلا إذا كان العنصر واضحًا وغير ملتبس ويمكن تحديد حدوده أو قيمته دون افتراض.
""".trimIndent()
    }
}