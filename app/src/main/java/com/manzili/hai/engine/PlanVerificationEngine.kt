package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.PlanDimension

object PlanVerificationEngine {
    data class Issue(val level: String, val title: String, val detail: String, val targetKind: String? = null, val targetId: String? = null)
    data class Report(val plan: FloorPlan, val issues: List<Issue>, val readingConfidence: Int, val scaleConfidence: Int, val confirmedDimensions: Int) {
        val blocking: Boolean get() = issues.any { it.level == "error" }
    }

    fun inspect(input: FloorPlan): Report {
        val dimensionSources = input.dimensions
            .filterNot { it.axis.startsWith("label-") || it.id.startsWith("num-") }
            .map { it.sourceText }
        val derived = deriveDimensionEvidence(listOf(input.sourceSummary) + input.observations + input.uncertainties + dimensionSources)
        val enriched = input.copy(dimensions = (input.dimensions + derived).distinctBy { "${it.id}:${"%.3f".format(it.valueM)}" })
        val polygonReport = PolygonGeometryEngine.inspect(enriched)
        val normalized = polygonReport.plan
        val issues = mutableListOf<Issue>()
        polygonReport.errors.forEach { issues += Issue("error", "مشكلة في هندسة المضلع", it) }
        polygonReport.warnings.forEach { issues += Issue("warning", "تحويل هندسي", it) }

        val structuralCount = normalized.walls.size + normalized.rooms.size
        if (structuralCount == 0) {
            issues += Issue(
                "error",
                "الهندسة غير مكتملة بعد",
                "يمكنك مراجعة المخطط الأصلي وما قرأه النظام، لكن لا يمكن اعتماد المشروع أو الانتقال إلى 3D قبل استخراج حدود أو جدران/غرف قابلة للمراجعة."
            )
        } else if (normalized.rooms.isEmpty() && normalized.walls.size < 3) {
            issues += Issue(
                "error",
                "حدود المخطط غير كافية",
                "تم العثور على أدلة جزئية فقط. راجع الأصل أو استخدم HAI/التعديل حتى تتكون هندسة إنشائية كافية قبل 3D."
            )
        } else if (normalized.rooms.isEmpty() && normalized.walls.size >= 4) {
            issues += Issue(
                "warning",
                "الغرف لم تُحسم بعد",
                "تمت قراءة الجدران، لكن لم تُستخرج مساحات مغلقة موثوقة بعد. لا تعتبر نسبة الثقة قراءة كاملة حتى يظهر عدد الغرف/المساحات."
            )
        }

        normalized.rooms.filter { it.confidence < 70 }.forEach { issues += Issue("warning", "غرفة تحتاج تأكيد", "«${it.name}» ثقة القراءة ${it.confidence}%.", "room", it.id) }
        normalized.walls.filter { it.confidence < 60 }.take(8).forEach { issues += Issue("warning", "جدار غير مؤكد", "الجدار ${it.id} ثقة القراءة ${it.confidence}%.", "wall", it.id) }
        normalized.openings.filter { it.confidence < 65 }.take(8).forEach { issues += Issue("warning", "فتحة تحتاج تأكيد", "${openingLabel(it.type)} ${it.id} ثقة القراءة ${it.confidence}%.", "opening", it.id) }
        normalized.uncertainties.take(8).forEach { issues += Issue("warning", "معلومة غير محسومة", it) }

        val scale = calculateScaleConfidence(normalized)
        if (normalized.widthM == null || normalized.heightM == null) issues += Issue("warning", "المقياس غير مؤكد", "أدخل عرض وطول المبنى أو اعتمد بعدين موثوقين قبل الاعتماد على القياسات بالمتر.")
        else if (scale < 65) issues += Issue("warning", "المقياس يحتاج مراجعة", "الأبعاد الكلية موجودة لكن أدلة الأبعاد المقروءة غير كافية لتأكيد المقياس.")

        val values = normalized.rooms.map { it.confidence } + normalized.walls.map { it.confidence } + normalized.openings.map { it.confidence }
        val elementConfidence = if (values.isEmpty()) 35 else values.average().toInt()
        val roomCoveragePenalty = if (normalized.rooms.isEmpty() && normalized.walls.size >= 4) 24 else 0
        val geometryPenalty = polygonReport.errors.size * 20
        val uncertaintyPenalty = normalized.uncertainties.size * 3
        val reading = (elementConfidence - roomCoveragePenalty - uncertaintyPenalty - geometryPenalty).coerceIn(0, 100)
        val plan = normalized.copy(scaleConfidence = scale)
        val confirmed = plan.dimensions.count {
            !it.axis.startsWith("label-") && !it.id.startsWith("num-") && it.confidence >= 70
        }
        return Report(plan, issues.distinctBy { it.level + it.title + it.detail }, reading, scale, confirmed)
    }

    fun confirmScale(plan: FloorPlan, widthM: Double, heightM: Double): FloorPlan {
        require(widthM > 0.5 && heightM > 0.5) { "أبعاد المبنى غير صالحة" }
        val evidence = plan.dimensions + listOf(
            PlanDimension("manual-width", "عرض المبنى", widthM, "horizontal", confidence = 100, sourceText = "تأكيد المستخدم"),
            PlanDimension("manual-height", "طول المبنى", heightM, "vertical", confidence = 100, sourceText = "تأكيد المستخدم")
        )
        return PolygonGeometryEngine.normalize(plan.copy(widthM = widthM, heightM = heightM, dimensions = evidence.distinctBy { it.id }, scaleConfidence = 100))
    }

    fun deriveDimensionEvidence(texts: List<String>): List<PlanDimension> = DimensionEvidenceEngine.extract(texts)

    private fun calculateScaleConfidence(plan: FloorPlan): Int {
        if (plan.widthM == null || plan.heightM == null) return 0
        val scaleDimensions = plan.dimensions.filterNot { it.axis.startsWith("label-") || it.id.startsWith("num-") }
        val strong = scaleDimensions.count { it.confidence >= 80 }
        val medium = scaleDimensions.count { it.confidence in 60..79 }
        return (55 + strong * 12 + medium * 5).coerceIn(0, 100)
    }

    private fun openingLabel(type: String) = if (type.contains("window", true) || type.contains("ناف")) "النافذة" else "الباب"
}
