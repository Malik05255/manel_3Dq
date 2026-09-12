package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan

/** Relative construction sequence for Saudi villas. Percentages are planning weights, not durations or costs. */
object SaudiConstruction4DEngine {
    data class Phase(
        val id:String,
        val title:String,
        val startPct:Int,
        val endPct:Int,
        val scope:String,
        val saudiNote:String
    )

    data class Timeline(val phases:List<Phase>, val climate:String, val warnings:List<String>)

    fun build(plan:FloorPlan):Timeline {
        val climate=SaudiResidentialEngine.climateLabel(plan)
        val floors=if(plan.floors.isEmpty())1 else plan.floors.size
        val phases=listOf(
            Phase("site","تجهيز الموقع والتنسيق",0,5,"رفع الموقع، حدود العمل، نقاط الخدمات وتجهيز الوصول.","لا تعتبر هذه المرحلة بديلًا عن الرخصة أو الرفع المساحي الرسمي."),
            Phase("earth","الحفر والأساسات",5,16,"الحفر، طبقات التأسيس، القواعد والعزل الأرضي حسب التصميم الإنشائي.","تنفيذ الأساسات يعتمد على تقرير التربة والتصميم الإنشائي المعتمد."),
            Phase("structure","الهيكل الخرساني",16,37,"الأعمدة والجسور والبلاطات والسلالم لعدد $floors دور.","التسلسل هنا تخطيطي؛ الشدة والتسليح والصب يحددها الفريق الإنشائي."),
            Phase("block","المباني والغلاف",37,47,"القواطع، الجدران الخارجية، فتحات الأبواب والنوافذ.","تحقق من مواقع الفتحات والخصوصية قبل إغلاق الأعمال."),
            Phase("mep1","تمديدات MEP الأولية",47,60,"كهرباء وسباكة وتكييف وتمديدات منخفضة التيار قبل الإقفال.","في الفلل السعودية يجب تثبيت مواقع المكيفات والخزانات والخدمات مبكرًا لتقليل التكسير."),
            Phase("waterproof","العزل المائي والحراري",60,67,"الأسطح ودورات المياه والمناطق الرطبة والغلاف الحراري.",climateNote(plan)),
            Phase("plaster","اللياسة والأرضيات التأسيسية",67,75,"لياسة، سكريد، أسقف وتجهيزات ما قبل التشطيب.","اختبر الأعمال المخفية قبل تغطيتها."),
            Phase("finish","التشطيبات الداخلية",75,89,"أرضيات، دهانات، نجارة، أدوات صحية، مطابخ وإنارة.","رتب مناطق الضيافة والعائلة والخدمة كحزم استلام منفصلة."),
            Phase("facade","الواجهات والأعمال الخارجية",89,96,"واجهة الفيلا، الحوش، المداخل، المواقف والتنسيق الخارجي.","الهوية الحالية: ${SaudiResidentialEngine.styleLabel(plan)}؛ لا تغيّر الفتحات المعتمدة من أجل الزخرفة فقط."),
            Phase("handover","الاختبارات والتسليم",96,100,"تشغيل الأنظمة، اختبارات التسريب، الملاحظات، التنظيف وملفات التسليم.","وثّق الاختبارات والمخططات النهائية As-built قبل الإغلاق.")
        )
        val warnings=buildList {
            add("4D هنا ترتيب زمني نسبي وليس جدول مقاول أو مدة تنفيذ ملزمة.")
            if(plan.widthM==null||plan.heightM==null) add("المقياس الهندسي غير مكتمل؛ لا تستخدم 4D لاستخراج كميات.")
            if(!plan.site.countryCode.equals("SA",true)) add("المشروع غير محدد كسعودي؛ طبقة 4D السعودية تعرض مرجعًا عامًا فقط.")
        }
        return Timeline(phases,climate,warnings)
    }

    private fun climateNote(plan:FloorPlan):String = when(SaudiResidentialEngine.context(plan.site.city).climate) {
        SaudiResidentialEngine.Climate.HOT_HUMID -> "أعطِ عناية خاصة للرطوبة والعزل المائي وحماية الغلاف قبل التشطيبات."
        SaudiResidentialEngine.Climate.HOT_DRY -> "راجع العزل الحراري والسطح والفتحات المعرضة للشمس قبل إغلاق الواجهة."
        SaudiResidentialEngine.Climate.HIGHLAND_MILD -> "راجع تصريف مياه الأمطار وعزل السطح والتفاصيل الخارجية قبل التشطيبات."
        SaudiResidentialEngine.Climate.DESERT_CONTINENTAL -> "راجع العزل الحراري وفواصل الغلاف والحماية من التباين الحراري."
    }
}
