package com.manzili.hai.ai

import android.content.Context
import android.net.Uri
import com.manzili.hai.data.HaiSettings
import com.manzili.hai.engine.MultiPagePlanFusionEngine
import com.manzili.hai.engine.PdfPageRendererEngine
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

/** Multi-page vision path. Images continue through HaiArchitectClient; PDFs are read page-by-page. */
class MultiPageHaiPlanAnalyzer(private val context:Context) {
    private val settings=HaiSettings(context)
    private val renderer=PdfPageRendererEngine(context)
    private val single=HaiArchitectClient(context)
    private val http=OkHttpClient.Builder().connectTimeout(30,TimeUnit.SECONDS).readTimeout(180,TimeUnit.SECONDS).build()
    val available:Boolean get()=settings.configured

    suspend fun analyze(uri:Uri,maxPdfPages:Int=5):FloorPlan=withContext(Dispatchers.IO) {
        check(settings.configured){"أدخل إعدادات الذكاء الاصطناعي أولًا"}
        if(!renderer.isPdf(uri)) return@withContext single.analyzePlan(uri)
        val total=renderer.pageCount(uri)
        val images=renderer.render(uri,maxPdfPages=maxPdfPages,targetMaxPx=1800,jpegQuality=88)
        val plans=images.map { page ->
            val prompt=ANALYZE_PROMPT+"\n\nهذه الصفحة رقم ${page.pageIndex+1} من PDF. حلّل هذه الصفحة وحدها ولا تنقل عناصر من صفحات أخرى."
            ArchitectJson.parsePlan(chatVision(prompt,page.base64Jpeg))
        }
        MultiPagePlanFusionEngine.merge(plans,totalPdfPages=total)
    }

    private fun chatVision(prompt:String,base64:String):String {
        val content=JSONArray()
            .put(JSONObject().put("type","text").put("text",prompt))
            .put(JSONObject().put("type","image_url").put("image_url",JSONObject().put("url","data:image/jpeg;base64,$base64")))
        val messages=JSONArray()
            .put(JSONObject().put("role","system").put("content",SYSTEM_PROMPT))
            .put(JSONObject().put("role","user").put("content",content))
        val payload=JSONObject().put("model",settings.model).put("messages",messages).put("temperature",0.05)
        val req=Request.Builder().url(settings.endpoint)
            .header("Authorization","Bearer ${settings.apiKey}")
            .header("Content-Type","application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { res ->
            val body=res.body?.string().orEmpty()
            if(!res.isSuccessful) error("فشل تحليل صفحة PDF (${res.code}): ${body.take(320)}")
            return JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        }
    }

    companion object {
        private val SYSTEM_PROMPT="""
أنت HAI Architect داخل تطبيق منزلي HAI. اقرأ المخطط كبيانات هندسية. افصل بين المقروء والاستنتاج وما يحتاج تأكيدًا. لا تختلق أبعادًا أو صفة إنشائية. حافظ على هوية الغرف والجدران والفتحات. افهم المخططات السكنية السعودية وخصوصية الضيافة والعائلة، لكن لا تدّع مطابقة كود أو اشتراط رسمي بلا قاعدة موثقة.
""".trimIndent()

        private val ANALYZE_PROMPT="""
أعد JSON فقط دون Markdown:
{
 "title":"...","building_width_m":0,"building_height_m":0,"summary":"...",
 "rooms":[{"id":"r1","name":"غرفة","type":"room","x":0,"y":0,"width":20,"height":20,"area_m2":0,"confidence":90,"locked":false}],
 "walls":[{"id":"w1","start":{"x":0,"y":0},"end":{"x":20,"y":0},"thickness_cm":0,"kind":"external|internal|unknown","confidence":90,"locked":false}],
 "openings":[{"id":"o1","type":"door|window","x":10,"y":0,"width":4,"rotation_deg":0,"wall_id":"w1","connects_room_ids":["r1"],"confidence":90,"locked":false}],
 "observations":[],"uncertainties":[],
 "preferences":{"privacy":80,"circulation":80,"daylight":70,"future_flexibility":60,"notes":[]},"revision":1
}
الإحداثيات نسب 0..100. لا تضع area_m2 إلا إذا كانت مكتوبة أو محسوبة من أبعاد واضحة. اربط الفتحات بالجدار فقط عند وضوح العلاقة. عند الشك استخدم uncertainties ولا تخمن.
""".trimIndent()
    }
}
