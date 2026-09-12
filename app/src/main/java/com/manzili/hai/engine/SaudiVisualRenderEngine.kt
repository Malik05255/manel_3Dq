package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan

/** Visual-only Saudi render profile. It never mutates canonical Geometry V3. */
object SaudiVisualRenderEngine {
    enum class Quality(val label:String) { PREVIEW("سريع"), BALANCED("متوازن"), HIGH("عالي") }
    data class MaterialProfile(val kind:String,val label:String,val roughness:Double,val metallic:Double,val transmission:Double=0.0)
    data class SunProfile(val azimuthDeg:Float,val elevationDeg:Float,val intensity:Float,val climate:String)
    data class FacadeProfile(val style:String,val features:List<String>,val shadingPriority:Int)
    data class Profile(
        val quality:Quality,
        val materials:Map<String,MaterialProfile>,
        val sun:SunProfile,
        val facade:FacadeProfile,
        val warnings:List<String>
    )

    fun build(plan:FloorPlan, quality:Quality=Quality.HIGH):Profile {
        val style=SaudiResidentialEngine.styleLabel(plan)
        val context=SaudiResidentialEngine.context(plan.site.city)
        val climate=SaudiResidentialEngine.climateLabel(plan)
        val north=(plan.site.northDeg ?: plan.northDeg ?: 0f)
        val sunAzimuth=((north+225f)%360f+360f)%360f
        val elevation=when(context.climate) {
            SaudiResidentialEngine.Climate.HOT_DRY -> 48f
            SaudiResidentialEngine.Climate.HOT_HUMID -> 43f
            SaudiResidentialEngine.Climate.HIGHLAND_MILD -> 38f
            SaudiResidentialEngine.Climate.DESERT_CONTINENTAL -> 42f
        }
        val facadeFeatures=when {
            style.contains("نجدي") -> listOf("كتل هادئة","تظليل عميق","تفاصيل رأسية محسوبة","ألوان ترابية معاصرة")
            style.contains("حجازي") -> listOf("فتحات رأسية","شاشات ظل","تفاصيل واجهة خفيفة","معالجة رطوبة الواجهة")
            style.contains("عسيري") -> listOf("تدرج كتل","حماية من المطر","تفاصيل محلية مبسطة","إطلالات مضبوطة الخصوصية")
            style.contains("نيوكلاسيك") -> listOf("محاور متوازنة","إطارات فتحات","كتلة مدخل واضحة","زخرفة محدودة لا تغيّر الفتحات")
            else -> listOf("سعودي معاصر","مداخل واضحة","تظليل","خصوصية","تكامل المواقف والحوش")
        }
        val shading=when(context.climate) {
            SaudiResidentialEngine.Climate.HOT_DRY, SaudiResidentialEngine.Climate.HOT_HUMID -> 95
            else -> 82
        }
        val materials=mapOf(
            "wall" to MaterialProfile("wall","لياسة/حجر واجهات",.82,.02),
            "slab" to MaterialProfile("slab","خرسانة مطفية",.92,0.0),
            "roof" to MaterialProfile("roof","سطح معزول",.88,0.0),
            "door" to MaterialProfile("door","خشب/معدن",.55,.08),
            "window" to MaterialProfile("window","زجاج",.12,.02,.82),
            "structural" to MaterialProfile("structural","خرسانة إنشائية",.95,0.0),
            "saudi-parapet" to MaterialProfile("saudi-parapet","بارابيت واجهة",.84,.01)
        )
        return Profile(
            quality=quality,
            materials=materials,
            sun=SunProfile(sunAzimuth,elevation,if(quality==Quality.HIGH)1f else .78f,climate),
            facade=FacadeProfile(style,facadeFeatures,shading),
            warnings=listOf(
                "الخامات والإضاءة طبقة عرض فقط؛ Geometry V3 هو المصدر الهندسي الوحيد.",
                "ملف الواجهة لا يضيف فتحة أو يحذفها من أجل الشكل."
            )
        )
    }
}
