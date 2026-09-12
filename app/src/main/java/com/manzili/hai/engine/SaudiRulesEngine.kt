package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan

/**
 * Optional, versioned Saudi-code readiness layer.
 * It is completely inactive unless the project owner explicitly enables it.
 * PASS means the implemented check passed; it is never an official approval.
 * Official scope facts below are limited to verified SBC 2024 material; local municipal numbers remain data-driven only.
 */
object SaudiRulesEngine {
    const val EDITION = "2024"
    const val EFFECTIVE_DATE = "2025-06-30"
    const val RESIDENTIAL_CODE = "SBC 1101-1102"
    const val OFFICIAL_SCOPE_SOURCE = "SBC 1101 Section 101.2 + Saudi Building Code residential inspection guidance"

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
        val type = SaudiProjectTypeEngine.detect(plan) ?: SaudiProjectTypeEngine.infer(plan)
        val knownResidentialType = type in setOf(
            SaudiProjectTypeEngine.Type.VILLA_ONE,
            SaudiProjectTypeEngine.Type.VILLA_TWO,
            SaudiProjectTypeEngine.Type.DUPLEX,
            SaudiProjectTypeEngine.Type.TOWNHOUSE
        )
        val explicitlyMultiUnit = type in setOf(SaudiProjectTypeEngine.Type.BUILDING_ONE, SaudiProjectTypeEngine.Type.BUILDING_TWO)

        val checks = mutableListOf<Check>()
        checks += Check(
            "edition", "إصدار الكود", Status.PASS,
            "طبقة التحقق تستخدم إصدار كود البناء السعودي 2024 المطبق من 30/06/2025. هذا لا يعني اعتماد المخطط.",
            "Saudi Building Code 2024 official portal", true
        )
        checks += when {
            knownResidentialType -> Check(
                "residential-type", "نوع المبنى ضمن نطاق SBC 1101", Status.PASS,
                "نوع المشروع «${type.label}» من الأنواع السكنية التي يمكن أن تدخل نطاق $RESIDENTIAL_CODE، بشرط تحقق بقية شروط النطاق.",
                OFFICIAL_SCOPE_SOURCE, true
            )
            explicitlyMultiUnit -> Check(
                "residential-type", "نوع المبنى ضمن نطاق SBC 1101", Status.REVIEW,
                "«${type.label}» متعدد الوحدات؛ لا أعتبره تلقائيًا ضمن نطاق $RESIDENTIAL_CODE المختصر. يلزم تحديد الإشغال ومسار الكود المناسب.",
                OFFICIAL_SCOPE_SOURCE, true
            )
            else -> Check(
                "residential-type", "نوع المبنى ضمن نطاق SBC 1101", Status.REVIEW,
                "نوع «${type.label}» لا يطابق تلقائيًا الأنواع المؤكدة في فحص النطاق الحالي؛ يلزم مراجعة نطاق الكود.",
                OFFICIAL_SCOPE_SOURCE, true
            )
        }
        checks += if (floors <= 3) Check(
            "above-grade-floors", "عدد الطوابق فوق الأرض", Status.PASS,
            "النموذج الحالي يحتوي $floors طابق/طوابق، ضمن حد الثلاثة طوابق فوق مستوى الأرض المذكور في نطاق SBC 1101.",
            OFFICIAL_SCOPE_SOURCE, true
        ) else Check(
            "above-grade-floors", "عدد الطوابق فوق الأرض", Status.REVIEW,
            "المشروع يحتوي $floors طوابق؛ يتجاوز حد الثلاثة طوابق فوق الأرض في نطاق SBC 1101 المختصر.",
            OFFICIAL_SCOPE_SOURCE, true
        )

        // The current domain model does not encode basements/family count/independent egress/open-side count reliably.
        checks += Check(
            "basement-scope", "الأدوار أسفل الأرض", Status.NEEDS_DATA,
            "نطاق SBC 1101 يذكر حدًا أقصى لطابق واحد أسفل مستوى الأرض. نموذج المشروع الحالي لا يميز القبو بشكل موثوق؛ أدخل هذه البيانات قبل اعتماد النطاق.",
            OFFICIAL_SCOPE_SOURCE, true
        )
        checks += if (explicitlyMultiUnit) Check(
            "family-count", "عدد الأسر", Status.REVIEW,
            "نوع المشروع متعدد الوحدات، بينما نطاق SBC 1101 السكني المبسط يشترط أسرة أو أسرتين بحد أقصى.",
            OFFICIAL_SCOPE_SOURCE, true
        ) else Check(
            "family-count", "عدد الأسر", Status.NEEDS_DATA,
            "يجب تأكيد أن المبنى يخدم أسرة أو أسرتين بحد أقصى قبل اعتبار نطاق SBC 1101 مكتملًا.",
            OFFICIAL_SCOPE_SOURCE, true
        )
        checks += Check(
            "independent-egress", "وسائل الخروج لكل عائلة", Status.NEEDS_DATA,
            "يجب تأكيد وجود وسيلة خروج مستقلة لكل عائلة؛ لا أستنتج ذلك من أسماء الغرف أو الرسم فقط.",
            OFFICIAL_SCOPE_SOURCE, true
        )
        if (type == SaudiProjectTypeEngine.Type.TOWNHOUSE) {
            checks += Check(
                "townhouse-open-sides", "المساحة المفتوحة للتاون هاوس", Status.NEEDS_DATA,
                "للتاؤن هاوس/الفيلا المتلاصقة من جهتين يلزم التحقق من وجود مساحة مفتوحة من جهتين على الأقل وفق دليل النطاق الرسمي.",
                OFFICIAL_SCOPE_SOURCE, true
            )
        }

        val scopeDependencies = checks.filter { it.id in setOf("residential-type","above-grade-floors","basement-scope","family-count","independent-egress","townhouse-open-sides") }
        val scopeStatus = when {
            scopeDependencies.any { it.status == Status.REVIEW } -> Status.REVIEW
            scopeDependencies.any { it.status == Status.NEEDS_DATA } -> Status.NEEDS_DATA
            scopeDependencies.all { it.status == Status.PASS } -> Status.PASS
            else -> Status.REVIEW
        }
        checks += Check(
            "residential-scope", "اكتمال نطاق الكود السكني", scopeStatus,
            when (scopeStatus) {
                Status.PASS -> "شروط النطاق الممثلة في التطبيق مكتملة مبدئيًا؛ استمر لبقية فحوص الكود دون اعتبارها موافقة رسمية."
                Status.NEEDS_DATA -> "نوع/ارتفاع المشروع مبدئيًا مناسب، لكن توجد بيانات نطاق رسمية غير ممثلة بعد ويجب إدخالها."
                else -> "هناك شرط نطاق لا يطابق المسار السكني المختصر؛ يلزم مراجعة مسار الكود العام/المختص."
            },
            OFFICIAL_SCOPE_SOURCE, true
        )

        checks += dataCheck("city", "المدينة/الجهة", plan.site.city.isNotBlank(), "حدد المدينة لأن الاشتراطات البلدية المحلية لا يجوز افتراضها.")
        checks += dataCheck("plot", "حدود الأرض", plan.site.plotBoundary.size >= 3, "أدخل حدود الأرض قبل فحص الارتدادات ونسبة البناء.")
        checks += dataCheck("roads", "الشوارع المحيطة", plan.site.roads.isNotEmpty(), "أدخل الشارع/الشوارع وعروضها قبل أي تحقق بلدي للواجهة والمدخل والارتدادات.")
        checks += dataCheck("scale", "المقياس الهندسي", plan.widthM != null && plan.heightM != null && plan.scaleConfidence >= 70, "ثبّت المقياس والأبعاد قبل مقارنة أي متطلب رقمي بالمخطط.")
        checks += Check(
            "municipal-minima", "الاشتراطات البلدية الرقمية", Status.INFO,
            "لم أضع أرقام ارتدادات أو نسب بناء افتراضية. يجب تحميل قاعدة رسمية خاصة بالمدينة/المخطط التنظيمي قبل إصدار نتيجة مطابقة رقمية.",
            "Official municipal dataset required", false
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
