package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan

/**
 * Optional, versioned Saudi-code readiness layer.
 * It is completely inactive unless the project owner explicitly enables it.
 * PASS means the implemented check passed; it is never an official approval.
 */
object SaudiRulesEngine {
    const val EDITION = "2024"
    const val EFFECTIVE_DATE = "2025-06-30"
    const val RESIDENTIAL_CODE = "SBC 1101-1102"

    enum class Status { PASS, NEEDS_DATA, REVIEW, INFO, NOT_APPLICABLE }

    data class Check(
        val id: String,
        val title: String,
        val status: Status,
        val detail: String,
        val source: String,
        val officialRule: Boolean
    )

    data class Report(val checks: List<Check>) {
        val blockingDataGaps: Int get() = checks.count { it.status == Status.NEEDS_DATA }
        val reviewCount: Int get() = checks.count { it.status == Status.REVIEW }
        val codeScopeReady: Boolean get() = checks.any { it.id == "residential-scope" && it.status == Status.PASS }
    }

    fun inspect(plan: FloorPlan): Report {
        if (!plan.saudiRulesEnabled) return Report(emptyList())
        if (!plan.site.countryCode.equals("SA", true)) {
            return Report(listOf(Check("country", "نطاق كود البناء السعودي", Status.NOT_APPLICABLE, "المشروع ليس محددًا داخل السعودية.", "SBC 2024", true)))
        }
        val floors = if (plan.floors.isEmpty()) 1 else plan.floors.size
        val checks = mutableListOf<Check>()
        checks += Check(
            "edition", "إصدار الكود", Status.PASS,
            "طبقة التحقق مضبوطة على إصدار 2024 المطبق من $EFFECTIVE_DATE. هذا لا يعني اعتماد المخطط.",
            "Saudi Building Code 2024", true
        )
        checks += if (floors <= 3) Check(
            "residential-scope", "نطاق الكود السكني", Status.PASS,
            "المشروع الحالي $floors دور؛ يقع ضمن نطاق الفحص الأولي للمباني السكنية حتى ثلاثة أدوار. يلزم تحديد نوع الإشغال بدقة قبل أي تقرير نهائي.",
            RESIDENTIAL_CODE, true
        ) else Check(
            "residential-scope", "نطاق الكود السكني", Status.REVIEW,
            "المشروع يحتوي $floors أدوار؛ لا أطبّق قواعد $RESIDENTIAL_CODE المختصرة عليه تلقائيًا. يلزم مسار كود عام/اختصاصي.",
            RESIDENTIAL_CODE, true
        )
        checks += dataCheck("city", "المدينة/الجهة", plan.site.city.isNotBlank(), "حدد المدينة لأن الاشتراطات البلدية المحلية لا يجوز افتراضها.")
        checks += dataCheck("plot", "حدود الأرض", plan.site.plotBoundary.size >= 3, "أدخل حدود الأرض قبل فحص الارتدادات ونسبة البناء.")
        checks += dataCheck("roads", "الشوارع المحيطة", plan.site.roads.isNotEmpty(), "أدخل الشارع/الشوارع وعروضها قبل أي تحقق بلدي للواجهة والمدخل والارتدادات.")
        checks += dataCheck("scale", "المقياس الهندسي", plan.widthM != null && plan.heightM != null && plan.scaleConfidence >= 70, "ثبّت المقياس والأبعاد قبل مقارنة أي متطلب رقمي بالمخطط.")
        checks += Check(
            "municipal-minima", "الاشتراطات البلدية الرقمية", Status.INFO,
            "لم أضع أرقام ارتدادات أو نسب بناء افتراضية. يجب تحميل قاعدة رسمية خاصة بالمدينة/المخطط التنظيمي قبل إصدار نتيجة مطابقة رقمية.",
            "Municipal rules dataset required", false
        )
        checks += Check(
            "professional-boundary", "حدود النتيجة", Status.INFO,
            "النتيجة داخل التطبيق: اقتراح معماري + تحقق هندسي + نطاق قواعد منفذة فقط، وليست رخصة أو اعتمادًا من مهندس/جهة رسمية.",
            "HAI product boundary", false
        )
        return Report(checks)
    }

    private fun dataCheck(id: String, title: String, ok: Boolean, missing: String) = Check(
        id, title, if (ok) Status.PASS else Status.NEEDS_DATA,
        if (ok) "البيانات المطلوبة متوفرة للفحص اللاحق." else missing,
        "Input readiness", false
    )
}