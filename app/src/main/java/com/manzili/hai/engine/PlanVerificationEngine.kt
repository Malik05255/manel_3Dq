package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.PlanDimension
import com.manzili.hai.model.PlanPoint
import kotlin.math.abs

object PlanVerificationEngine {
    data class Issue(
        val level: String,
        val title: String,
        val detail: String,
        val targetKind: String? = null,
        val targetId: String? = null
    )

    data class Report(
        val plan: FloorPlan,
        val issues: List<Issue>,
        val readingConfidence: Int,
        val scaleConfidence: Int,
        val confirmedDimensions: Int
    ) {
        val blocking: Boolean get() = issues.any { it.level == "error" }
    }

    fun inspect(input: FloorPlan): Report {
        val polygonReport = PolygonGeometryEngine.inspect(input)
        val normalized = polygonReport.plan
        val issues = mutableListOf<Issue>()

        polygonReport.errors.forEach { issues += Issue("error", "مشكلة في هندسة المضلع", it) }
        polygonReport.warnings.forEach { issues += Issue("warning", "تحويل هندسي", it) }

        normalized.rooms.filter { it.confidence < 70 }.forEach {
            issues += Issue("warning", "غرفة تحتاج تأكيد", "«${it.name}» ثقة القراءة ${it.confidence}%.", "room", it.id)
        }
        normalized.walls.filter { it.confidence < 60 }.take(8).forEach {
            issues += Issue("warning", "جدار غير مؤكد", "الجدار ${it.id} ثقة القراءة ${it.confidence}%.", "wall", it.id)
        }
        normalized.openings.filter { it.confidence < 65 }.take(8).forEach {
            issues += Issue("warning", "فتحة تحتاج تأكيد", "${openingLabel(it.type)} ${it.id} ثقة القراءة ${it.confidence}%.", "opening", it.id)
        }

        normalized.uncertainties.take(8).forEach {
            issues += Issue("warning", "معلومة غير محسومة", it)
        }

        val scale = calculateScaleConfidence(normalized)
        if (normalized.widthM == null || normalized.heightM == null) {
            issues += Issue("warning", "المقياس غير مؤكد", "أدخل عرض وطول المبنى أو اعتمد بعدين موثوقين قبل الاعتماد على القياسات بالمتر.")
        } else if (scale < 65) {
            issues += Issue("warning", "المقياس يحتاج مراجعة", "الأبعاد الكلية موجودة لكن أدلة الأبعاد المقروءة غير كافية لتأكيد المقياس.")
        }

        val elementConfidence = buildList {
            addAll(normalized.rooms.map { it.confidence })
            addAll(normalized.walls.map { it.confidence })
            addAll(normalized.openings.map { it.confidence })
        }.let { values -> if (values.isEmpty()) 35 else values.average().toInt() }

        val reading = (elementConfidence - normalized.uncertainties.size * 3 - polygonReport.errors.size * 20)
            .coerceIn(0, 100)
        val plan = normalized.copy(scaleConfidence = scale)
        return Report(plan, issues.distinctBy { it.level + it.title + it.detail }, reading, scale, normalized.dimensions.count { it.confidence >= 70 })
    }

    fun confirmScale(plan: FloorPlan, widthM: Double, heightM: Double): FloorPlan {
        require(widthM > 0.5 && heightM > 0.5) { "أبعاد المبنى غير صالحة" }
        val evidence = plan.dimensions + listOf(
            PlanDimension("manual-width", "عرض المبنى", widthM, "horizontal", confidence = 100, sourceText = "تأكيد المستخدم"),
            PlanDimension("manual-height", "طول المبنى", heightM, "vertical", confidence = 100, sourceText = "تأكيد المستخدم")
        )
        return PolygonGeometryEngine.normalize(
            plan.copy(widthM = widthM, heightM = heightM, dimensions = evidence.distinctBy { it.id }, scaleConfidence = 100)
        )
    }

    fun deriveDimensionEvidence(texts: List<String>): List<PlanDimension> {
        val result = mutableListOf<PlanDimension>()
        val regex = Regex("(?<!\\d)(\\d{1,3}(?:[.,]\\d{1,2})?)\\s*(?:م|متر|m)(?![\\p{L}])", RegexOption.IGNORE_CASE)
        texts.forEachIndexed { index, raw ->
            val normalized = normalizeDigits(raw)
            regex.findAll(normalized).forEachIndexed { matchIndex, match ->
                val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@forEachIndexed
                if (value in 0.3..200.0) {
                    result += PlanDimension(
                        id = "text-$index-$matchIndex",
                        label = "بعد مقروء",
                        valueM = value,
                        confidence = 55,
                        sourceText = raw.take(120)
                    )
                }
            }
        }
        return result
    }

    private fun calculateScaleConfidence(plan: FloorPlan): Int {
        if (plan.widthM == null || plan.heightM == null) return 0
        val strong = plan.dimensions.count { it.confidence >= 80 }
        val medium = plan.dimensions.count { it.confidence in 60..79 }
        return (55 + strong * 12 + medium * 5).coerceIn(0, 100)
    }

    private fun openingLabel(type: String): String = if (type.contains("window", true) || type.contains("ناف")) "النافذة" else "الباب"

    private fun normalizeDigits(value: String): String = buildString {
        value.forEach { ch ->
            append(when (ch) {
                '٠','۰' -> '0'; '١','۱' -> '1'; '٢','۲' -> '2'; '٣','۳' -> '3'; '٤','۴' -> '4'
                '٥','۵' -> '5'; '٦','۶' -> '6'; '٧','۷' -> '7'; '٨','۸' -> '8'; '٩','۹' -> '9'; '٫' -> '.'
                else -> ch
            })
        }
    }
}
