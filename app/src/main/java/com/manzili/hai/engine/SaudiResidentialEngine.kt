package com.manzili.hai.engine

import com.manzili.hai.model.*

/** Saudi-first residential intelligence. Guidance here is architectural, not official approval. */
object SaudiResidentialEngine {
    const val STYLE_KIND = "SAUDI_ARCH_STYLE"
    const val CLIMATE_KIND = "SAUDI_CLIMATE_GUIDANCE"
    const val FAMILY_PRIVACY_KIND = "SAUDI_FAMILY_PRIVACY"
    const val FAMILY_ENTRY_KIND = "SAUDI_FAMILY_ENTRY"
    const val SERVICE_ENTRY_KIND = "SAUDI_SERVICE_ENTRY"
    const val PARKING_KIND = "SAUDI_PARKING"
    const val COURTYARD_KIND = "SAUDI_COURTYARD"
    const val MAID_KIND = "SAUDI_MAID_ROOM"
    const val DRIVER_KIND = "SAUDI_DRIVER_ROOM"
    const val ELEVATOR_KIND = "SAUDI_ELEVATOR"
    const val WOMEN_RECEPTION_KIND = "SAUDI_WOMEN_RECEPTION"

    enum class Climate { HOT_DRY, HOT_HUMID, HIGHLAND_MILD, DESERT_CONTINENTAL }
    enum class Style(val label: String) {
        SAUDI_CONTEMPORARY("سعودي معاصر"), NAJDI_CONTEMPORARY("نجدي معاصر"),
        HIJAZI_CONTEMPORARY("حجازي معاصر"), ASIRI_CONTEMPORARY("عسيري معاصر"),
        GULF_CONTEMPORARY("خليجي سعودي معاصر"), SAUDI_NEOCLASSIC("نيوكلاسيك سعودي")
    }

    data class Brief(
        val city: String,
        val familySize: Int = 6,
        val menMajlis: Boolean = true,
        val womenReception: Boolean = false,
        val familyEntranceSeparate: Boolean = true,
        val serviceEntrance: Boolean = true,
        val parkingCars: Int = 2,
        val maidRoom: Boolean = true,
        val driverRoom: Boolean = false,
        val elevator: Boolean = false,
        val courtyard: Boolean = true,
        val annex: Boolean = false,
        val rooftopService: Boolean = true,
        val architectureStyle: String = "سعودي معاصر",
        val streetSide: String = "شمال",
        val streetWidthM: Double? = null,
        val northDeg: Float = 0f,
        val privacyPriority: Int = 90
    )

    data class Context(val regionLabel: String, val climate: Climate, val defaultStyle: Style, val priorities: List<String>)
    data class Review(val score: Int, val notes: List<String>)

    fun context(cityRaw: String): Context {
        val city = cityRaw.trim().lowercase()
        return when {
            has(city,"جدة","مكة","مكه","المدينة","المدينه","الطائف","ينبع","رابغ") -> Context("الغرب السعودي", Climate.HOT_HUMID, Style.HIJAZI_CONTEMPORARY, listOf("تظليل الواجهات","تهوية مدروسة","خصوصية الفتحات","فصل الضيافة"))
            has(city,"أبها","ابها","خميس","الباحة","الباحه","النماص","تنومة","تنومه","بلقرن") -> Context("مرتفعات الجنوب", Climate.HIGHLAND_MILD, Style.ASIRI_CONTEMPORARY, listOf("الاستفادة من المناخ المعتدل","حماية من الأمطار","إطلالات مضبوطة الخصوصية","تدرج الكتل"))
            has(city,"جازان","جيزان","صبيا","بيش","الدرب","القنفذة","القنفذه") -> Context("الساحل الجنوبي", Climate.HOT_HUMID, Style.SAUDI_CONTEMPORARY, listOf("الظل","التهوية","تقليل الكسب الحراري","خدمات خارجية عملية"))
            has(city,"الدمام","الخبر","الظهران","الجبيل","الأحساء","الاحساء","القطيف","رأس تنورة","راس تنوره") -> Context("المنطقة الشرقية", Climate.HOT_HUMID, Style.GULF_CONTEMPORARY, listOf("الرطوبة والحرارة","مواقف مظللة","مداخل واضحة","خصوصية العائلة"))
            has(city,"تبوك","حائل","الجوف","سكاكا","عرعر","رفحاء","طريف") -> Context("الشمال السعودي", Climate.DESERT_CONTINENTAL, Style.SAUDI_CONTEMPORARY, listOf("مرونة موسمية","حماية من الرياح","كتلة حرارية متوازنة","مجلس مستقل"))
            else -> Context("الوسط السعودي", Climate.HOT_DRY, Style.NAJDI_CONTEMPORARY, listOf("الظل","تقليل الكسب الحراري","حوش عائلي","فصل مسار الضيوف"))
        }
    }

    fun normalize(plan: FloorPlan): FloorPlan {
        if (!plan.site.countryCode.equals("SA", true)) return plan
        val ctx = context(plan.site.city)
        val constraints = plan.constraints.toMutableList()
        if (constraints.none { it.kind == STYLE_KIND }) constraints += ProjectConstraint("saudi-style-auto", STYLE_KIND, "هوية 3D افتراضية قابلة للتغيير: ${ctx.defaultStyle.label}", hard=false, priority=35)
        if (constraints.none { it.kind == CLIMATE_KIND }) constraints += ProjectConstraint("saudi-climate-auto", CLIMATE_KIND, "إرشاد مناخي: ${ctx.regionLabel} • ${ctx.priorities.joinToString("، ")}", hard=false, priority=40)
        return plan.copy(
            constraints = constraints.distinctBy { it.id },
            observations = (plan.observations + "سياق سعودي: ${ctx.regionLabel}. الإرشاد المناخي/المعماري لا يساوي اشتراطًا رسميًا.").distinct()
        )
    }

    fun apply(plan: FloorPlan, brief: Brief): FloorPlan {
        val ctx = context(brief.city)
        val style = Style.entries.firstOrNull { brief.architectureStyle.contains(it.label) } ?: ctx.defaultStyle
        val rules = buildList {
            add(ProjectConstraint("saudi-style", STYLE_KIND, "الهوية المعمارية: ${style.label}", hard=false, priority=60))
            add(ProjectConstraint("saudi-climate", CLIMATE_KIND, "${ctx.regionLabel}: ${ctx.priorities.joinToString("، ")}", hard=false, priority=72))
            add(ProjectConstraint("saudi-family-privacy", FAMILY_PRIVACY_KIND, "الضيوف لا يعبرون قلب منطقة العائلة", hard=brief.privacyPriority>=85, priority=brief.privacyPriority))
            if (brief.familyEntranceSeparate) add(ProjectConstraint("saudi-family-entry", FAMILY_ENTRY_KIND, "مدخل العائلة منفصل عن الضيوف", hard=false, priority=92))
            if (brief.serviceEntrance) add(ProjectConstraint("saudi-service-entry", SERVICE_ENTRY_KIND, "مسار خدمة مستقل قدر الإمكان", hard=false, priority=84))
            if (brief.parkingCars>0) add(ProjectConstraint("saudi-parking", PARKING_KIND, "حجز مساحة تصميمية لـ ${brief.parkingCars} سيارة داخل الأرض دون اعتبارها ارتدادًا نظاميًا", value=brief.parkingCars.toDouble(), hard=false, priority=86))
            if (brief.courtyard) add(ProjectConstraint("saudi-courtyard", COURTYARD_KIND, "حوش/فناء عائلي خاص", hard=false, priority=80))
            if (brief.maidRoom) add(ProjectConstraint("saudi-maid", MAID_KIND, "غرفة عاملة منزلية قرب الخدمات", hard=false, priority=78))
            if (brief.driverRoom) add(ProjectConstraint("saudi-driver", DRIVER_KIND, "غرفة سائق قرب المدخل الخارجي", hard=false, priority=72))
            if (brief.elevator) add(ProjectConstraint("saudi-elevator", ELEVATOR_KIND, "حجز نواة مصعد مرتبطة بالدرج", hard=false, priority=90))
            if (brief.womenReception) add(ProjectConstraint("saudi-women", WOMEN_RECEPTION_KIND, "استقبال نساء مستقل أو شبه مستقل حسب المساحة", hard=false, priority=76))
        }
        val roads = brief.streetWidthM?.takeIf { it>0 }?.let { width -> listOf(road(brief.streetSide,width)) }.orEmpty()
        val merged = (plan.constraints.filterNot { old -> rules.any { it.kind==old.kind } } + rules).distinctBy { it.id }
        return normalize(plan.copy(
            site = plan.site.copy(countryCode="SA", city=brief.city, roads=if(roads.isNotEmpty()) roads else plan.site.roads, northDeg=brief.northDeg),
            northDeg = brief.northDeg,
            saudiRulesEnabled = plan.saudiRulesEnabled,
            constraints = merged,
            observations = (plan.observations + listOf("برنامج أسرة سعودية: ${brief.familySize} أفراد • ${brief.parkingCars} سيارة.", "الهوية: ${style.label} • ${ctx.regionLabel}.", "الاشتراطات الرسمية اختيارية ولا تُفعّل تلقائيًا بواسطة محرك التصميم.")).distinct()
        ))
    }

    fun inspect(plan: FloorPlan): Review {
        val rooms = allRooms(plan)
        val notes = mutableListOf<String>()
        var score = 50
        val majlis = rooms.firstOrNull { it.type.equals("majlis",true) || it.name.contains("مجلس") }
        val family = rooms.firstOrNull { it.type.equals("living",true) || it.name.contains("صالة عائل") }
        val kitchen = rooms.any { it.type.equals("kitchen",true) || it.name.contains("مطبخ") }
        val service = rooms.any { it.type.lowercase() in setOf("laundry","service","maid") || it.name.contains("غسيل") || it.name.contains("خدمة") }
        if (majlis!=null) score+=12 else notes += "لا يظهر مجلس ضيوف واضح."
        if (family!=null) score+=10 else notes += "لا تظهر صالة عائلية واضحة."
        if (kitchen) score+=8 else notes += "المطبخ غير واضح."
        if (service) score+=6 else notes += "منطقة الخدمة تحتاج وضوحًا أكبر."
        if (majlis!=null && family!=null) {
            val separation = kotlin.math.abs(majlis.x-family.x)+kotlin.math.abs(majlis.y-family.y)
            if (separation>=26f) score+=12 else notes += "فصل الضيوف عن قلب العائلة ضعيف نسبيًا."
        }
        if (plan.constraints.any { it.kind==PARKING_KIND }) score+=4
        if (plan.constraints.any { it.kind==COURTYARD_KIND }) score+=3
        if (plan.site.countryCode.equals("SA",true)) score+=5
        val ctx=context(plan.site.city)
        notes += "سياق ${ctx.regionLabel}: ${ctx.priorities.take(2).joinToString("، ")} (إرشاد تصميمي)."
        return Review(score.coerceIn(0,100), notes.distinct())
    }

    fun styleLabel(plan: FloorPlan): String {
        val text=plan.constraints.firstOrNull { it.kind==STYLE_KIND && it.active }?.text.orEmpty()
        return Style.entries.firstOrNull { text.contains(it.label) }?.label ?: context(plan.site.city).defaultStyle.label
    }

    fun climateLabel(plan: FloorPlan): String { val c=context(plan.site.city); return "${c.regionLabel} • ${when(c.climate){ Climate.HOT_DRY->"حار جاف"; Climate.HOT_HUMID->"حار رطب"; Climate.HIGHLAND_MILD->"مرتفعات معتدلة"; Climate.DESERT_CONTINENTAL->"صحراوي متباين" }}" }

    private fun allRooms(plan: FloorPlan)=if(plan.floors.isNotEmpty()) plan.floors.flatMap { it.rooms } else plan.rooms
    private fun has(v:String,vararg terms:String)=terms.any { v.contains(it.lowercase()) }
    private fun road(side:String,width:Double)=when(side.trim()){
        "جنوب"->RoadEdge("main-road","الشارع الرئيسي",PlanPoint(0f,100f),PlanPoint(100f,100f),width,"user-input")
        "شرق"->RoadEdge("main-road","الشارع الرئيسي",PlanPoint(100f,0f),PlanPoint(100f,100f),width,"user-input")
        "غرب"->RoadEdge("main-road","الشارع الرئيسي",PlanPoint(0f,0f),PlanPoint(0f,100f),width,"user-input")
        else->RoadEdge("main-road","الشارع الرئيسي",PlanPoint(0f,0f),PlanPoint(100f,0f),width,"user-input")
    }
}
