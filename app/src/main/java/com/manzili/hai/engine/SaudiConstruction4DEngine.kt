package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.PlanPoint
import kotlin.math.abs

/** 4D V2: dependencies + editable planning durations + metric-safe quantity references. */
object SaudiConstruction4DEngine {
    data class QuantityRef(val label:String,val value:Double,val unit:String,val verified:Boolean,val note:String)
    data class Phase(
        val id:String,
        val title:String,
        val startPct:Int,
        val endPct:Int,
        val scope:String,
        val saudiNote:String,
        val planningDays:Int=0,
        val dependencies:List<String> = emptyList(),
        val quantities:List<QuantityRef> = emptyList()
    )
    data class Timeline(
        val phases:List<Phase>,
        val climate:String,
        val warnings:List<String>,
        val totalPlanningDays:Int,
        val metricQuantitiesReady:Boolean
    )

    fun build(plan:FloorPlan):Timeline {
        val climate=SaudiResidentialEngine.climateLabel(plan)
        val floors=if(plan.floors.isEmpty())1 else plan.floors.size
        val metricReady=plan.widthM!=null&&plan.heightM!=null&&plan.scaleConfidence>=70
        val floorArea=if(metricReady)grossFloorArea(plan) else null
        val areaRef=floorArea?.let { QuantityRef("مساحة أدوار مرجعية",it,"م²",true,"مشتقة من حدود Geometry V3 والمقياس المؤكد؛ ليست BOQ نهائية.") }
        fun q(vararg refs:QuantityRef?)=refs.filterNotNull()
        val phases=listOf(
            Phase("site","تجهيز الموقع والتنسيق",0,5,"رفع الموقع، حدود العمل، نقاط الخدمات وتجهيز الوصول.","لا تعتبر هذه المرحلة بديلًا عن الرخصة أو الرفع المساحي الرسمي.",7),
            Phase("earth","الحفر والأساسات",5,16,"الحفر، طبقات التأسيس، القواعد والعزل الأرضي حسب التصميم الإنشائي.","تنفيذ الأساسات يعتمد على تقرير التربة والتصميم الإنشائي المعتمد.",21,listOf("site")),
            Phase("structure","الهيكل الخرساني",16,37,"الأعمدة والجسور والبلاطات والسلالم لعدد $floors دور.","التسلسل هنا تخطيطي؛ الشدة والتسليح والصب يحددها الفريق الإنشائي.",35,listOf("earth"),q(areaRef)),
            Phase("block","المباني والغلاف",37,47,"القواطع، الجدران الخارجية، فتحات الأبواب والنوافذ.","تحقق من مواقع الفتحات والخصوصية قبل إغلاق الأعمال.",18,listOf("structure"),q(areaRef)),
            Phase("mep1","تمديدات MEP الأولية",47,60,"كهرباء وسباكة وتكييف وتمديدات منخفضة التيار قبل الإقفال.","ثبت مواقع المكيفات والخزانات والخدمات مبكرًا لتقليل التكسير.",24,listOf("block")),
            Phase("waterproof","العزل المائي والحراري",60,67,"الأسطح ودورات المياه والمناطق الرطبة والغلاف الحراري.",climateNote(plan),12,listOf("mep1"),q(areaRef)),
            Phase("plaster","اللياسة والأرضيات التأسيسية",67,75,"لياسة، سكريد، أسقف وتجهيزات ما قبل التشطيب.","اختبر الأعمال المخفية قبل تغطيتها.",20,listOf("waterproof"),q(areaRef)),
            Phase("finish","التشطيبات الداخلية",75,89,"أرضيات، دهانات، نجارة، أدوات صحية، مطابخ وإنارة.","رتب مناطق الضيافة والعائلة والخدمة كحزم استلام منفصلة.",40,listOf("plaster"),q(areaRef)),
            Phase("facade","الواجهات والأعمال الخارجية",89,96,"واجهة المشروع، الحوش، المداخل، المواقف والتنسيق الخارجي.","الهوية الحالية: ${SaudiResidentialEngine.styleLabel(plan)}؛ لا تغيّر الفتحات المعتمدة من أجل الزخرفة فقط.",20,listOf("structure")),
            Phase("handover","الاختبارات والتسليم",96,100,"تشغيل الأنظمة، اختبارات التسريب، الملاحظات، التنظيف وملفات التسليم.","وثّق الاختبارات والمخططات النهائية As-built قبل الإغلاق.",10,listOf("finish","facade"))
        )
        val warnings=buildList {
            add("4D هنا ترتيب زمني تخطيطي وليس جدول مقاول أو مدة تنفيذ ملزمة.")
            add("المدد أيام تخطيط ابتدائية قابلة للتعديل وليست التزام مقاول أو مدة عقدية.")
            if(!metricReady)add("المقياس غير مؤكد؛ أوقفت الكميات الرقمية بدل تخمينها.")
            else add("الكميات الظاهرة مراجع Geometry فقط؛ لا تعتبر حصر كميات تنفيذيًا أو تسعيرًا.")
            if(!plan.site.countryCode.equals("SA",true))add("المشروع غير محدد كسعودي؛ طبقة 4D تعرض مرجعًا عامًا فقط.")
        }
        return Timeline(phases,climate,warnings,phases.sumOf { it.planningDays },metricReady)
    }

    private fun grossFloorArea(plan:FloorPlan):Double {
        val sx=plan.widthM!!/100.0;val sy=plan.heightM!!/100.0
        val floors=plan.floors.ifEmpty { listOf(com.manzili.hai.model.FloorLevel("floor-0","الأرضي",0,footprint=plan.footprint)) }
        return floors.sumOf { f->polygonArea(f.footprint.ifEmpty { plan.footprint })*sx*sy }.coerceAtLeast(0.0)
    }
    private fun polygonArea(poly:List<PlanPoint>):Double {if(poly.size<3)return 0.0;var s=0.0;poly.indices.forEach{i->val a=poly[i];val b=poly[(i+1)%poly.size];s+=a.x*b.y-b.x*a.y};return abs(s)/2.0}
    private fun climateNote(plan:FloorPlan)=when(SaudiResidentialEngine.context(plan.site.city).climate){
        SaudiResidentialEngine.Climate.HOT_HUMID->"أعطِ عناية للرطوبة والعزل المائي وحماية الغلاف قبل التشطيبات."
        SaudiResidentialEngine.Climate.HOT_DRY->"راجع العزل الحراري والسطح والفتحات المعرضة للشمس قبل إغلاق الواجهة."
        SaudiResidentialEngine.Climate.HIGHLAND_MILD->"راجع تصريف مياه الأمطار وعزل السطح والتفاصيل الخارجية قبل التشطيبات."
        SaudiResidentialEngine.Climate.DESERT_CONTINENTAL->"راجع العزل الحراري وفواصل الغلاف والحماية من التباين الحراري."
    }
}
