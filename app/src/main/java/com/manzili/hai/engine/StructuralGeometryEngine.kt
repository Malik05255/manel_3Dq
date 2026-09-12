package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.Opening
import com.manzili.hai.model.Wall
import kotlin.math.abs
import kotlin.math.hypot

object StructuralGeometryEngine {
    data class Report(
        val errors: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
        val wallCount: Int = 0,
        val doorCount: Int = 0,
        val windowCount: Int = 0,
        val confidence: Int = 0
    )

    fun inspect(plan: FloorPlan): Report {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val wallIds = plan.walls.map { it.id }.toSet()

        plan.walls.forEach { wall ->
            if (wall.start.x !in 0f..100f || wall.start.y !in 0f..100f || wall.end.x !in 0f..100f || wall.end.y !in 0f..100f) {
                errors += "الجدار ${wall.id} خارج حدود المخطط"
            }
            if (length(wall) < 0.35) warnings += "الجدار ${wall.id} قصير جدًا وقد يكون قراءة غير مؤكدة"
            if (wall.confidence < 60) warnings += "ثقة قراءة الجدار ${wall.id} منخفضة (${wall.confidence}%)"
        }

        plan.openings.forEach { opening ->
            if (opening.x !in 0f..100f || opening.y !in 0f..100f) errors += "${openingLabel(opening)} خارج حدود المخطط"
            if (opening.width <= 0f) errors += "عرض ${openingLabel(opening)} غير صالح"
            if (opening.wallId != null && opening.wallId !in wallIds) warnings += "${openingLabel(opening)} مرتبط بجدار غير موجود"
            if (opening.confidence < 60) warnings += "ثقة قراءة ${openingLabel(opening)} منخفضة (${opening.confidence}%)"
        }

        val confidenceValues = buildList {
            addAll(plan.walls.map { it.confidence })
            addAll(plan.openings.map { it.confidence })
        }
        return Report(
            errors = errors.distinct(),
            warnings = warnings.distinct(),
            wallCount = plan.walls.size,
            doorCount = plan.openings.count { it.type.lowercase().contains("door") || it.type.contains("باب") },
            windowCount = plan.openings.count { it.type.lowercase().contains("window") || it.type.contains("ناف") },
            confidence = if (confidenceValues.isEmpty()) 0 else confidenceValues.average().toInt().coerceIn(0, 100)
        )
    }

    fun compare(current: FloorPlan, next: FloorPlan): Report {
        val base = inspect(next)
        val errors = base.errors.toMutableList()
        val warnings = base.warnings.toMutableList()
        val nextWalls = next.walls.associateBy { it.id }
        val nextOpenings = next.openings.associateBy { it.id }

        current.walls.filter { it.locked }.forEach { old ->
            val fresh = nextWalls[old.id]
            if (fresh == null) errors += "الجدار المقفل ${old.id} حُذف"
            else if (wallChanged(old, fresh)) errors += "الجدار المقفل ${old.id} تم تغييره"
        }
        current.openings.filter { it.locked }.forEach { old ->
            val fresh = nextOpenings[old.id]
            if (fresh == null) errors += "الفتحة المقفلة ${old.id} حُذفت"
            else if (openingChanged(old, fresh)) errors += "الفتحة المقفلة ${old.id} تم تغييرها"
        }

        if (current.walls.isNotEmpty() && next.walls.isEmpty()) {
            errors += "الاقتراح أسقط جميع بيانات الجدران من المخطط"
        }
        if (current.openings.isNotEmpty() && next.openings.isEmpty()) {
            warnings += "الاقتراح أسقط بيانات الأبواب والنوافذ؛ يلزم التحقق قبل الاعتماد"
        }

        val removedWalls = current.walls.map { it.id }.toSet() - nextWalls.keys
        if (removedWalls.size >= 2) warnings += "الاقتراح حذف ${removedWalls.size} جدران؛ راجع أثر ذلك على التوزيع"

        return base.copy(errors = errors.distinct(), warnings = warnings.distinct())
    }

    fun compactBrief(plan: FloorPlan): String {
        val r = inspect(plan)
        if (r.wallCount == 0 && plan.openings.isEmpty()) return "لا توجد بعد بيانات جدران/فتحات موثوقة؛ التعديل البنيوي يحتاج حذرًا إضافيًا."
        return "الجدران ${r.wallCount}، الأبواب ${r.doorCount}، النوافذ ${r.windowCount}، ثقة البنية ${r.confidence}%."
    }

    private fun length(w: Wall): Double = hypot((w.end.x - w.start.x).toDouble(), (w.end.y - w.start.y).toDouble())

    private fun wallChanged(a: Wall, b: Wall): Boolean =
        abs(a.start.x - b.start.x) > .18f || abs(a.start.y - b.start.y) > .18f ||
            abs(a.end.x - b.end.x) > .18f || abs(a.end.y - b.end.y) > .18f ||
            ((a.thicknessCm ?: 0.0) > 0 && (b.thicknessCm ?: 0.0) > 0 && abs((a.thicknessCm ?: 0.0) - (b.thicknessCm ?: 0.0)) > 1.5)

    private fun openingChanged(a: Opening, b: Opening): Boolean =
        abs(a.x - b.x) > .18f || abs(a.y - b.y) > .18f || abs(a.width - b.width) > .18f || abs(a.rotationDeg - b.rotationDeg) > 2f

    private fun openingLabel(o: Opening): String = if (o.type.lowercase().contains("window") || o.type.contains("ناف")) "النافذة ${o.id}" else "الباب ${o.id}"
}
