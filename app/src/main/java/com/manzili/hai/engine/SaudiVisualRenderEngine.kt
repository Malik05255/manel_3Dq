package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan

object SaudiVisualRenderEngine {
    enum class Quality(val label:String) { PREVIEW("سريع"), BALANCED("متوازن"), HIGH("عالي"), ULTRA("فائق") }
    data class MaterialProfile(
        val kind:String,
        val label:String,
        val roughness:Double,
        val metallic:Double,
        val transmission:Double=0.0,
        val clearCoat:Double=0.0,
        val normalStrength:Double=1.0
    )
    data class SunProfile(val azimuthDeg:Float,val elevationDeg:Float,val intensity:Float,val climate:String,val softness:Float)
    data class FacadeProfile(val style:String,val features:List<String>,val shadingPriority:Int)
    data class Profile(
        val quality:Quality,
        val materials:Map<String,MaterialProfile>,
        val sun:SunProfile,
        val facade:FacadeProfile,
        val renderScale:Float,
        val shadowQuality:Int,
        val anisotropy:Int,
        val warnings:List<String>
    )

    fun build(plan:FloorPlan, quality:Quality=Quality.ULTRA):Profile {
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
        val ultra = quality == Quality.ULTRA
        val materials=mapOf(
            "wall" to MaterialProfile("wall","لياسة/حجر واجهات",if(ultra).64 else .82,.01,clearCoat=if(ultra).08 else 0.0,normalStrength=if(ultra)1.35 else 1.0),
            "slab" to MaterialProfile("slab","خرسانة مطفية",if(ultra).82 else .92,0.0,normalStrength=if(ultra)1.25 else 1.0),
            "roof" to MaterialProfile("roof","سطح معزول",if(ultra).78 else .88,0.0,normalStrength=if(ultra)1.20 else 1.0),
            "door" to MaterialProfile("door","خشب/معدن",if(ultra).38 else .55,.08,clearCoat=if(ultra).18 else 0.0,normalStrength=if(ultra)1.30 else 1.0),
            "window" to MaterialProfile("window","زجاج",if(ultra).06 else .12,.02,if(ultra).94 else .82,clearCoat=if(ultra).35 else 0.0),
            "structural" to MaterialProfile("structural","خرسانة إنشائية",if(ultra).86 else .95,0.0,normalStrength=if(ultra)1.35 else 1.0),
            "saudi-parapet" to MaterialProfile("saudi-parapet","بارابيت واجهة",if(ultra).70 else .84,.01,normalStrength=if(ultra)1.20 else 1.0)
        )
        val renderScale=when(quality){
            Quality.PREVIEW -> .75f
            Quality.BALANCED -> 1f
            Quality.HIGH -> 1.25f
            Quality.ULTRA -> 1.75f
        }
        val shadowQuality=when(quality){
            Quality.PREVIEW -> 1
            Quality.BALANCED -> 2
            Quality.HIGH -> 3
            Quality.ULTRA -> 4
        }
        return Profile(
            quality=quality,
            materials=materials,
            sun=SunProfile(sunAzimuth,elevation,if(ultra)1.16f else if(quality==Quality.HIGH)1f else .78f,climate,softness=if(ultra).86f else .68f),
            facade=FacadeProfile(style,facadeFeatures,shading),
            renderScale=renderScale,
            shadowQuality=shadowQuality,
            anisotropy=if(ultra)16 else if(quality==Quality.HIGH)8 else 4,
            warnings=emptyList()
        )
    }
}
