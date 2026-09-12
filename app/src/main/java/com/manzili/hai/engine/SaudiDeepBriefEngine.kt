package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.ProjectConstraint

/** Detailed lifestyle/site brief layered on top of the stable SaudiResidentialEngine.Brief. */
object SaudiDeepBriefEngine {
    const val ELDERLY = "SAUDI_ELDERLY_GROUND_SUITE"
    const val GUEST_SUITE = "SAUDI_GUEST_SUITE"
    const val STORAGE = "SAUDI_STORAGE"
    const val PANTRY = "SAUDI_PANTRY"
    const val LAUNDRY = "SAUDI_LAUNDRY"
    const val FUTURE = "SAUDI_FUTURE_EXPANSION"
    const val NEIGHBOR = "SAUDI_NEIGHBOR_EXPOSURE"
    const val CORNER = "SAUDI_CORNER_PLOT"
    const val USER_SETBACK = "USER_CONFIRMED_SETBACK"

    data class Brief(
        val base: SaudiResidentialEngine.Brief,
        val elderlyGroundSuite: Boolean = false,
        val guestSuite: Boolean = false,
        val storageRoom: Boolean = true,
        val pantry: Boolean = false,
        val laundryRoom: Boolean = true,
        val futureExpansion: Boolean = true,
        val futureFloors: Int = 0,
        val neighborExposure: String = "غير محدد",
        val cornerPlot: Boolean = false,
        val carEntranceSide: String = "نفس جهة الشارع",
        val frontSetbackM: Double? = null,
        val rearSetbackM: Double? = null,
        val sideSetbackM: Double? = null
    )

    fun apply(plan: FloorPlan, brief: Brief): FloorPlan {
        val base = SaudiResidentialEngine.apply(plan, brief.base)
        val rules = buildList {
            if (brief.elderlyGroundSuite) add(ProjectConstraint("deep-elderly", ELDERLY, "جناح أرضي لكبير سن مع تقليل مسار الحركة", hard = true, priority = 96))
            if (brief.guestSuite) add(ProjectConstraint("deep-guest-suite", GUEST_SUITE, "جناح ضيف مستقل عن غرف الأسرة", hard = false, priority = 82))
            if (brief.storageRoom) add(ProjectConstraint("deep-storage", STORAGE, "مخزن منزلي قريب من الخدمة", hard = false, priority = 70))
            if (brief.pantry) add(ProjectConstraint("deep-pantry", PANTRY, "بانتري/تحضير مساعد مرتبط بالمطبخ", hard = false, priority = 68))
            if (brief.laundryRoom) add(ProjectConstraint("deep-laundry", LAUNDRY, "غرفة غسيل مستقلة ضمن مسار الخدمة", hard = false, priority = 74))
            if (brief.futureExpansion) add(ProjectConstraint("deep-future", FUTURE, "حماية قابلية التوسع المستقبلية${if (brief.futureFloors > 0) " حتى ${brief.futureFloors} أدوار إضافية" else ""}", value = brief.futureFloors.toDouble(), hard = false, priority = 88))
            if (brief.neighborExposure.isNotBlank() && brief.neighborExposure != "غير محدد") add(ProjectConstraint("deep-neighbor", NEIGHBOR, "انكشاف الجيران: ${brief.neighborExposure}", hard = false, priority = 85))
            if (brief.cornerPlot) add(ProjectConstraint("deep-corner", CORNER, "قطعة زاوية؛ عالج واجهتين ومساري الدخول دون خلط الخصوصية", hard = false, priority = 80))
            brief.frontSetbackM?.takeIf { it >= 0 }?.let { add(ProjectConstraint("deep-front-setback", USER_SETBACK, "ارتداد أمامي أدخله المستخدم: ${it}م؛ غير مصنف كرقم بلدي موثق", value = it, hard = true, priority = 100)) }
            brief.rearSetbackM?.takeIf { it >= 0 }?.let { add(ProjectConstraint("deep-rear-setback", USER_SETBACK, "ارتداد خلفي أدخله المستخدم: ${it}م؛ غير مصنف كرقم بلدي موثق", value = it, hard = true, priority = 100)) }
            brief.sideSetbackM?.takeIf { it >= 0 }?.let { add(ProjectConstraint("deep-side-setback", USER_SETBACK, "ارتداد جانبي أدخله المستخدم: ${it}م؛ غير مصنف كرقم بلدي موثق", value = it, hard = true, priority = 100)) }
        }
        return base.copy(
            constraints = (base.constraints.filterNot { old -> rules.any { it.id == old.id } } + rules).distinctBy { it.id },
            site = base.site.copy(
                frontSetbackM = brief.frontSetbackM ?: base.site.frontSetbackM,
                rearSetbackM = brief.rearSetbackM ?: base.site.rearSetbackM,
                sideSetbackM = brief.sideSetbackM ?: base.site.sideSetbackM
            ),
            preferences = base.preferences.copy(
                futureFlexibilityPriority = if (brief.futureExpansion) maxOf(base.preferences.futureFlexibilityPriority, 90) else base.preferences.futureFlexibilityPriority
            ),
            observations = (base.observations + listOf(
                "تفاصيل الموقع: جيران=${brief.neighborExposure} • زاوية=${brief.cornerPlot} • مدخل سيارات=${brief.carEntranceSide}.",
                "احتياجات مستقبلية: توسع=${brief.futureExpansion} • أدوار إضافية=${brief.futureFloors} • كبير سن أرضي=${brief.elderlyGroundSuite}."
            )).distinct()
        )
    }
}
