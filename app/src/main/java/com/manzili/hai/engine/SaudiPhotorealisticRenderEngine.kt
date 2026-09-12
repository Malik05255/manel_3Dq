package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan

/** Render-only production profile. Never mutates Geometry V3. */
object SaudiPhotorealisticRenderEngine {
    enum class ClimateProfile { HOT_DRY, HOT_HUMID, HIGHLAND, CONTINENTAL }
    data class Profile(
        val qualityLabel:String,
        val sunIntensity:Float,
        val ambientLabel:String,
        val climate:ClimateProfile,
        val tone:String,
        val textureScaleM:Float,
        val contactShadows:Boolean,
        val landscaping:Boolean,
        val glassTransmission:Boolean,
        val warnings:List<String>
    )

    fun build(plan:FloorPlan):Profile {
        val climate=when(SaudiResidentialEngine.context(plan.site.city).climate){
            SaudiResidentialEngine.Climate.HOT_HUMID->ClimateProfile.HOT_HUMID
            SaudiResidentialEngine.Climate.HOT_DRY->ClimateProfile.HOT_DRY
            SaudiResidentialEngine.Climate.HIGHLAND_MILD->ClimateProfile.HIGHLAND
            SaudiResidentialEngine.Climate.DESERT_CONTINENTAL->ClimateProfile.CONTINENTAL
        }
        val sun=when(climate){
            ClimateProfile.HOT_DRY->118_000f
            ClimateProfile.HOT_HUMID->105_000f
            ClimateProfile.HIGHLAND->92_000f
            ClimateProfile.CONTINENTAL->112_000f
        }
        val tone=when(climate){
            ClimateProfile.HOT_DRY->"neutral-warm"
            ClimateProfile.HOT_HUMID->"soft-hazy"
            ClimateProfile.HIGHLAND->"clear-cool"
            ClimateProfile.CONTINENTAL->"neutral-high-contrast"
        }
        return Profile(
            qualityLabel="PBR Textured V2",
            sunIntensity=sun,
            ambientLabel="Filament physically-based",
            climate=climate,
            tone=tone,
            textureScaleM=1.6f,
            contactShadows=true,
            landscaping=true,
            glassTransmission=true,
            warnings=listOf(
                "الخامات والإضاءة طبقة عرض؛ Geometry V3 يبقى المصدر الهندسي الوحيد.",
                "ملمس الحجر/اللياسة/الخشب مضمن داخل GLB ويعمل دون اتصال خارجي.",
                "عناصر الموقع الخضراء والمواقف Presentation فقط ولا تنشئ حدودًا أو ارتدادات نظامية."
            )
        )
    }
}
