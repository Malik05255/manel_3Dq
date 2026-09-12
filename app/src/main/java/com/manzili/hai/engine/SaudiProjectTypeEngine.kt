package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.ProjectConstraint

object SaudiProjectTypeEngine {
    const val KIND = "SAUDI_PROJECT_TYPE"

    enum class Type(
        val label: String,
        val subtitle: String,
        val defaultFloors: Int,
        val defaultBedrooms: Int,
        val defaultPrivacy: Int,
        val apartmentMode: Boolean = false
    ) {
        VILLA_ONE("فيلا دور واحد", "سكن عائلي كامل في دور أرضي", 1, 4, 94),
        VILLA_TWO("فيلا دورين", "ضيافة وخدمات أرضي + غرف عائلة أعلى", 2, 4, 94),
        BUILDING_ONE("عمارة دور واحد", "وحدات أو شقق في مستوى واحد", 1, 4, 88, true),
        BUILDING_TWO("عمارة دورين", "وحدات متعددة مع نواة حركة وخدمات", 2, 6, 88, true),
        TOWNHOUSE("تاون هاوس", "مساحة أضيق وامتداد رأسي وخصوصية واجهات", 2, 3, 92),
        DUPLEX("دوبلكس", "وحدتان مستقلتان أو شبه مستقلتين", 2, 6, 93),
        TRADITIONAL("بيت شعبي / تقليدي", "حل اقتصادي عملي بفناء وخدمات مباشرة", 1, 3, 90),
        REST_HOUSE("استراحة", "ضيافة ومجلس وحوش واستخدام موسمي", 1, 2, 86)
    }

    data class Profile(
        val questions: List<String>,
        val priorities: List<String>,
        val recommendedFeatures: List<String>
    )

    fun profile(type: Type): Profile = when(type) {
        Type.VILLA_ONE -> Profile(
            listOf("هل تحتاج جناح كبير سن أرضي؟","هل تريد فصل مجلس الرجال عن العائلة؟","كم موقف سيارة؟","هل تريد حوشًا عائليًا خاصًا؟"),
            listOf("الخصوصية","قصر الحركة","إضاءة طبيعية","إمكانية توسع رأسي مستقبلًا"),
            listOf("مجلس","صالة عائلية","مطبخ","غسيل","مخزن","حوش")
        )
        Type.VILLA_TWO -> Profile(
            listOf("هل غرف النوم كلها في الدور الأول؟","هل تحتاج مصعدًا؟","هل تريد جناح ضيف أرضي؟","هل تخطط لدور مستقبلي؟"),
            listOf("فصل الضيافة","نواة درج/مصعد","خصوصية غرف النوم","تجميع الخدمات رأسيًا"),
            listOf("مجلس","صالة عائلية","جناح رئيسي","مصعد اختياري","سطح خدمات")
        )
        Type.BUILDING_ONE -> Profile(
            listOf("كم وحدة/شقة؟","هل لكل وحدة مدخل مستقل؟","هل المواقف داخل الأرض؟","هل توجد عدادات وخدمات مستقلة؟"),
            listOf("استقلال الوحدات","كفاءة الممرات","الخدمات المشتركة","مواقف واضحة"),
            listOf("وحدات مستقلة","عدادات خدمات","مواقف","مداخل واضحة")
        )
        Type.BUILDING_TWO -> Profile(
            listOf("كم شقة في كل دور؟","هل تحتاج مصعدًا؟","هل الدرج مشترك؟","هل تريد شقة مالك مستقلة؟"),
            listOf("نواة رأسية","استقلال الشقق","السلامة المنطقية للحركة","تجميع MEP"),
            listOf("درج مشترك","مصعد اختياري","لوبي","مناور/خدمات")
        )
        Type.TOWNHOUSE -> Profile(
            listOf("هل الوحدة وسطية أم طرفية؟","كم عرض الواجهة؟","هل يوجد موقف أمامي؟","هل تريد فناء خلفيًا؟"),
            listOf("استغلال العرض","الامتداد الرأسي","الضوء من واجهتين","خصوصية الجيران"),
            listOf("سلم مركزي","فناء خلفي","موقف أمامي","تخزين ذكي")
        )
        Type.DUPLEX -> Profile(
            listOf("الوحدتان متجاورتان أم فوق بعض؟","هل المداخل منفصلة بالكامل؟","هل المواقف منفصلة؟","هل الخدمات مستقلة؟"),
            listOf("استقلال الوحدتين","عدم تداخل الضيوف","عزل الحركة","وضوح الخدمات"),
            listOf("مدخلان","موقفان مستقلان","خدمات منفصلة","فصل صوتي منطقي")
        )
        Type.TRADITIONAL -> Profile(
            listOf("هل الأولوية للاقتصاد في البناء؟","هل تريد مجلسًا كبيرًا؟","هل الحوش عنصر أساسي؟","هل التوسع المستقبلي مهم؟"),
            listOf("بساطة التنفيذ","تقليل الممرات","تهوية طبيعية","سهولة التوسعة"),
            listOf("حوش","مجلس","مطبخ مباشر","غرف مرنة")
        )
        Type.REST_HOUSE -> Profile(
            listOf("عائلية أم للضيوف؟","هل تحتاج مسبحًا أو جلسات خارجية؟","هل يوجد قسم رجال ونساء؟","هل المبيت مطلوب؟"),
            listOf("الضيافة","الحوش","سهولة الوصول","فصل الاستخدامات"),
            listOf("مجلس","جلسات خارجية","مطبخ خدمة","دورات مياه ضيوف")
        )
    }

    fun apply(plan: FloorPlan, type: Type): FloorPlan {
        val constraint = ProjectConstraint(
            id = "saudi-project-type",
            kind = KIND,
            text = "نوع المشروع: ${type.label}",
            hard = true,
            priority = 100
        )
        return plan.copy(
            constraints = (plan.constraints.filterNot { it.kind == KIND } + constraint).distinctBy { it.id },
            observations = (plan.observations + "تصنيف المشروع المعتمد: ${type.label}. ${type.subtitle}").distinct()
        )
    }

    fun detect(plan: FloorPlan): Type? {
        val text = plan.constraints.firstOrNull { it.kind == KIND && it.active }?.text.orEmpty()
        return Type.entries.firstOrNull { text.contains(it.label) }
    }

    fun infer(plan: FloorPlan): Type {
        detect(plan)?.let { return it }
        val floors = if (plan.floors.isEmpty()) 1 else plan.floors.size
        val rooms = if (plan.floors.isEmpty()) plan.rooms else plan.floors.flatMap { it.rooms }
        val apartmentHints = rooms.count { it.name.contains("شقة") || it.type.contains("apartment", true) }
        return when {
            apartmentHints >= 2 && floors >= 2 -> Type.BUILDING_TWO
            apartmentHints >= 2 -> Type.BUILDING_ONE
            floors >= 2 -> Type.VILLA_TWO
            else -> Type.VILLA_ONE
        }
    }
}
