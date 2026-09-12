package com.manzili.hai.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.Base64
import com.manzili.hai.data.HaiSettings
import com.manzili.hai.engine.ArchitecturalEngine
import com.manzili.hai.engine.ArchitecturalRepairEngine
import com.manzili.hai.engine.ArchitecturalRequestAnalyzer
import com.manzili.hai.engine.GeometrySolver
import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.PlanProposal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class HaiArchitectClient(private val context: Context) {
    private val settings = HaiSettings(context)
    private val router = AiProviderRouter(context)

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
        val requestPreflight = ArchitecturalRequestAnalyzer.preflight(plan, userText)
        val solverBrief = GeometrySolver.brief(plan, userText)
        val prompt = CHANGE_PROMPT +
            "\n\nتقييم المحرك الهندسي المحلي:\n$deterministicBrief" +
            "\n\nفحص الطلب الحسابي قبل استدعاء الذكاء:\n$requestPreflight" +
            "\n\nفحص GeometrySolver قبل الاقتراح:\n$solverBrief" +
            "\n\nالمخطط الحالي (هو المرجع الوحيد للأبعاد الموجودة):\n$compact" +
            "\n\nطلب العميل:\n$userText"

        val proposal = ArchitectJson.parseProposal(chatText(prompt))
        val next = proposal.updatedPlan ?: return@withContext proposal
        val geometry = GeometrySolver.verify(plan, next)
        if (!geometry.feasible) {
            return@withContext proposal.copy(
                message = "رفضت الحل المقترح قبل عرضه لأن GeometrySolver وجد: ${geometry.errors.joinToString("، ")}",
                updatedPlan = null,
                confidence = 100
            )
        }

        val review = ArchitecturalEngine.architecturalReview(plan, next)
        if (!review.hasMaterialObjection) return@withContext proposal

        val repairs = ArchitecturalRepairEngine.alternatives(plan, next, limit = 3)
        val best = repairs.firstOrNull { it.remainingObjections == 0 }
        if (best != null) {
            val second = repairs.drop(1).firstOrNull { it.remainingObjections == 0 }
            val alternativesText = buildString {
                append("\n\nاعتراض HAI: ").append(review.objections.joinToString(" "))
                append("\n\nالبديل الذي أوصي به: ").append(best.movement.ifBlank { best.candidate.reason })
                append(". ").append(best.candidate.reason)
                if (second != null) append("\nبديل ثانٍ متاح: ").append(second.movement.ifBlank { second.candidate.reason }).append(".")
                append("\nأعرض البديل الآمن كمعاينة، ولن يُعتمد إلا بموافقتك.")
            }
            return@withContext PlanProposal(
                message = proposal.message + alternativesText,
                updatedPlan = best.candidate.plan,
                changes = best.candidate.changes,
                requiresConfirmation = true,
                confidence = (94 - best.candidate.review.notes.size * 2).coerceIn(65, 96)
            )
        }

        proposal.copy(
            message = proposal.message + "\n\nاعتراض HAI: " + review.objections.joinToString(" ") +
                "\nلم أجد بديلًا هندسيًا آمنًا تلقائيًا يحافظ على القيود الحالية، لذلك لن أعرض التعديل للاعتماد.",
            updatedPlan = null,
            confidence = 100
        )
    }

    suspend fun advise(plan: FloorPlan, userText: String): String = proposeChange(plan, userText).message

    private fun planToJson(plan: FloorPlan): JSONObject = JSONObject().apply {
        put("title", plan.title)
        plan.widthM?.let { put("building_width_m", it) }
        plan.heightM?.let { put("building_height_m", it) }
        put("revision", plan.revision)
        put("summary", plan.sourceSummary)
        put("rooms", JSONArray().apply {
            plan.rooms.forEach { r ->
                put(JSONObject().apply {
                    put("id", r.id); put("name", r.name); put("type", r.type); put("area_m2", r.areaM2)
                    put("x", r.x); put("y", r.y); put("width", r.width); put("height", r.height)
                    put("confidence", r.confidence); put("locked", r.locked)
                    r.minAreaM2?.let { put("min_area_m2", it) }; r.preferredAreaM2?.let { put("preferred_area_m2", it) }
                })
            }
        })
        put("walls", JSONArray().apply {
            plan.walls.forEach { w ->
                put(JSONObject().apply {
                    put("id", w.id); put("start", JSONObject().put("x", w.start.x).put("y", w.start.y)); put("end", JSONObject().put("x", w.end.x).put("y", w.end.y))
                    w.thicknessCm?.let { put("thickness_cm", it) }; put("kind", w.kind); put("confidence", w.confidence); put("locked", w.locked)
                })
            }
        })
        put("openings", JSONArray().apply {
            plan.openings.forEach { o ->
                put(JSONObject().apply {
                    put("id", o.id); put("type", o.type); put("x", o.x); put("y", o.y); put("width", o.width); put("rotation_deg", o.rotationDeg)
                    o.wallId?.let { put("wall_id", it) }; put("connects_room_ids", JSONArray(o.connectsRoomIds)); put("confidence", o.confidence); put("locked", o.locked)
                })
            }
        })
        put("observations", JSONArray(plan.observations))
        put("uncertainties", JSONArray(plan.uncertainties))
        put("preferences", JSONObject().apply {
            put("privacy", plan.preferences.privacyPriority); put("circulation", plan.preferences.circulationPriority)
            put("daylight", plan.preferences.daylightPriority); put("future_flexibility", plan.preferences.futureFlexibilityPriority)
            put("notes", JSONArray(plan.preferences.notes))
        })
    }

    private suspend fun chatText(prompt: String): String = router.complete(SYSTEM_PROMPT, prompt)

    private suspend fun chatVision(prompt: String, base64: String): String = router.complete(SYSTEM_PROMPT, prompt, base64)

    private fun renderToBase64Jpeg(uri: Uri): String {
        val type = context.contentResolver.getType(uri).orEmpty()
        val bitmap = if (type == "application/pdf") {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("تعذر فتح PDF")
            PdfRenderer(pfd).use { renderer ->
                renderer.openPage(0).use { page ->
                    val scale = 1800f / page.width.coerceAtLeast(1)
                    val w = 1800
                    val h = (page.height * scale).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                }
            }
        } else {
            context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) ?: error("تعذر قراءة الصورة") }
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
أنت HAI Architect داخل تطبيق «منزلي HAI». تعامل مع كل مخطط كبيانات هندسية، لا كصورة جميلة. افصل بوضوح بين ما هو مقروء، ما هو استنتاج تخطيطي، وما يحتاج تأكيدًا من المستخدم. لا تختلق أبعادًا، ولا تدّعي أن جدارًا إنشائيًا ما لم يثبت ذلك في المصدر. تعامل مع الجدران والأبواب والنوافذ كعناصر مستقلة يجب الحفاظ على هويتها أثناء التعديل. الأولوية للسلامة المنطقية، الخصوصية، الحركة، العلاقات الوظيفية، وقلة المساحات المهدرة. افهم العربية واللهجة السعودية. لا تدّع اعتمادًا هندسيًا رسميًا أو مطابقة كود ما لم يتم فحص قاعدة كود موثقة منفصلة.
""".trimIndent()

        private val ANALYZE_PROMPT = """
افحص المخطط بصريًا بدقة. أعد JSON فقط دون Markdown بهذا المخطط:
{
 "title":"...",
 "building_width_m":0,
 "building_height_m":0,
 "summary":"ملخص مهني قصير",
 "rooms":[{"id":"r1","name":"الصالة","type":"living","x":0,"y":0,"width":20,"height":20,"area_m2":0,"confidence":95,"locked":false,"min_area_m2":0,"preferred_area_m2":0}],
 "walls":[{"id":"w1","start":{"x":0,"y":0},"end":{"x":20,"y":0},"thickness_cm":20,"kind":"external|internal|unknown","confidence":90,"locked":false}],
 "openings":[{"id":"o1","type":"door|window","x":10,"y":0,"width":4,"rotation_deg":0,"wall_id":"w1","connects_room_ids":["r1"],"confidence":90,"locked":false}],
 "observations":["..."],
 "uncertainties":["..."],
 "preferences":{"privacy":80,"circulation":80,"daylight":70,"future_flexibility":60,"notes":[]},
 "revision":1
}
قواعد القراءة:
1) x,y,width,height وإحداثيات الجدران والفتحات نسب مئوية 0..100 بالنسبة إلى حدود المخطط.
2) area_m2 لا تضعها إلا إذا كانت مكتوبة أو قابلة للحساب من أبعاد واضحة؛ وإلا 0.
3) استخرج الجدران المرئية كخطوط مستقلة قدر الإمكان، وحافظ على ids ثابتة.
4) استخرج الأبواب والنوافذ واربطها بالجدار والغرف فقط عندما تكون العلاقة واضحة.
5) لا تستنتج أن الجدار حامل أو إنشائي من السماكة البصرية وحدها؛ استخدم kind=unknown عند الشك.
6) إذا لم تستطع تأكيد باب أو نافذة أو بعد أو حدود غرفة، ضع المشكلة في uncertainties بدل التخمين.
7) لا تحول أثاثًا أو رموزًا زخرفية إلى جدران أو غرف.
""".trimIndent()

        private val NEW_PLAN_PROMPT = """
صمم مخططًا أوليًا سكنيًا منطقيًا من برنامج العميل. أعد JSON فقط بنفس schema المستخدم في تحليل المخطط، بما فيه rooms وwalls وopenings. كوّن مناطق ضيوف، عائلة، نوم، وخدمات بعلاقات واضحة، وقلل الممرات وحافظ على الخصوصية. أنشئ الجدران والفتحات بما يتوافق مع الغرف بدل إرجاع مستطيلات غرف فقط. لا تسمح بتداخل الغرف. إذا كانت أبعاد الأرض أو عدد الأدوار أو مدخل الشارع غير واضحة ولا يمكن اتخاذ قرار مسؤول بدونها، ضع السؤال في uncertainties بدل اختراع قيمة. min_area_m2 حد تصميمي محافظ مقترح للمشروع وليس كودًا نظاميًا. لا تصف المخطط بأنه معتمد هندسيًا.
""".trimIndent()

        private val CHANGE_PROMPT = """
حلل طلب التعديل كما يفعل معماري متمرس، ثم أعد JSON فقط بالشكل:
{
 "message":"شرح مهني مختصر للقرار والتنازل أو السؤال الحاسم",
 "requires_confirmation":true,
 "confidence":0,
 "changes":[{"room_id":"r1","room_name":"المطبخ","action":"RESIZE|MOVE|ADD|DELETE|NO_CHANGE","before_area_m2":16,"after_area_m2":13,"note":"سبب التغيير"}],
 "updated_plan": null
}

قواعد صارمة:
1) إذا كان الطلب سؤالًا أو توجد معلومة حاسمة ناقصة: updated_plan = null واسأل سؤالًا واحدًا فقط.
2) إذا وجدت حلاً هندسيًا: updated_plan يجب أن يحتوي المخطط كاملًا بنفس schema، بما فيه rooms وwalls وopenings.
3) أي غرفة أو جدار أو فتحة locked=true ممنوع تغييرها أو حذفها.
4) حافظ على ids للعناصر القائمة، وأعط العناصر الجديدة ids جديدة.
5) لا تقل «تم»؛ استخدم «أقترح» حتى يعتمد المستخدم التعديل داخل التطبيق.
6) عند طلب أبعاد مثل 3×3 احسب المساحة صراحة، ولا تأخذ مساحة من غرفة أخرى بدون توضيح قبل/بعد في changes.
7) لا تسمح بتداخل الغرف أو خروج أي عنصر عن حدود 0..100.
8) إذا حرّكت جدارًا، حدّث الأبواب والنوافذ المرتبطة به بشكل متسق أو اشرح لماذا تحتاج تأكيدًا.
9) لا تغيّر غرفًا أو جدرانًا لا تحتاجها فقط لتحسين شكل الرسم.
10) إذا خفضت مساحة غرفة تحت min_area_m2 فاعتبر ذلك تنازلًا مهمًا واشرحه.
11) إذا كانت المنطقة المطلوبة منخفضة الثقة، اطلب تأكيدًا بدل إعادة رسمها بثقة زائفة.
12) عامل نتائج الفحص الحسابي والمحرك الهندسي المحلي كحقائق يجب احترامها، ولا تدّع توفر مساحة إذا أظهر الفحص عكس ذلك إلا مع شرح إعادة التوزيع المطلوبة.
13) إذا ذكر الفحص غرفًا طلب المستخدم حمايتها، لا تغيرها حتى لو لم تكن مقفلة سابقًا في المشروع.
14) GeometrySolver هو الحكم النهائي في الحدود والتداخلات وموضع الفتحات على الجدران؛ لا تحاول تجاوز نتيجة الفحص المحلي.
15) إذا كان النقل أو التكبير سليمًا وظيفيًا فلا تصنع اعتراضًا لمجرد الشرح. اعترض فقط عندما يوجد أثر واضح واذكر الأثر المحدد.
16) إذا اعترضت على حل، لا تتوقف عند الرفض: اقترح أقرب بديل يحافظ على نية العميل، لكن اترك للمحرك المحلي التحقق النهائي منه.
""".trimIndent()
    }
}
