package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.PlanDimension
import kotlin.math.min
import kotlin.math.roundToInt

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
        polygonReport.warnings.forEach { issues += Issue("warning", "تصحيح هندسي تلقائي", it) }

        val structuralCount = normalized.walls.size + normalized.rooms.size
        if (structuralCount == 0) {
            issues += Issue("error", "الهندسة غير مكتملة بعد", "يمكنك مراجعة المخطط الأصلي وما قرأه النظام، لكن لا يمكن اعتماد المشروع أو الانتقال إلى 3D قبل استخراج حدود أو جدران/غرف قابلة للمراجعة.")
        } else if (normalized.rooms.isEmpty() && normalized.walls.size < 3) {
            issues += Issue("error", "حدود المخطط غير كافية", "تم العثور على أدلة جزئية فقط. راجع الأصل أو استخدم HAI/التعديل حتى تتكون هندسة إنشائية كافية قبل 3D.")
        } else if (normalized.rooms.isEmpty() && normalized.walls.size >= 4) {
            issues += Issue("warning", "الغرف لم تُحسم بعد", "تمت قراءة الجدران، لكن لم تُستخرج مساحات مغلقة موثوقة بعد. لا تعتبر نسبة الثقة قراءة كاملة حتى يظهر عدد الغرف/المساحات.")
        }

        val uncalibratedCloud = isUncalibratedCloud(normalized)
        normalized.rooms.filter { it.confidence in 1..69 }.forEach { issues += Issue("warning", "غرفة تحتاج تأكيد", "«${it.name}» ثقة القراءة ${it.confidence}%.", "room", it.id) }
        normalized.walls.filter { it.confidence in 1..59 }.take(8).forEach { issues += Issue("warning", "جدار غير مؤكد", "الجدار ${it.id} ثقة القراءة ${it.confidence}%.", "wall", it.id) }
        normalized.openings.filter { it.confidence in 1..64 }.take(8).forEach { issues += Issue("warning", "فتحة تحتاج تأكيد", "${openingLabel(it.type)} ${it.id} ثقة القراءة ${it.confidence}%.", "opening", it.id) }
        if (uncalibratedCloud && (normalized.rooms + emptyList()).isNotEmpty() &&
            (normalized.rooms.map { it.confidence } + normalized.walls.map { it.confidence } + normalized.openings.map { it.confidence }).none { it > 0 }
        ) {
            issues += Issue(
                "warning",
                "النموذج لا يرسل احتمالًا معايرًا",
                "النسبة المعروضة هنا تقيس اكتمال الهندسة وتماسكها وأدلة القياس، ولا تعامل القيمة 0 القادمة من النموذج على أنها 0% دقة."
            )
        }
        normalized.uncertainties.take(8).forEach { issues += Issue("warning", "معلومة غير محسومة", it) }

        val scale = calculateScaleConfidence(normalized)
        if (normalized.widthM == null || normalized.heightM == null) issues += Issue("warning", "المقياس غير مؤكد", "أدخل عرض وطول المبنى أو اعتمد بعدين موثوقين قبل الاعتماد على القياسات بالمتر.")
        else if (scale < 65) issues += Issue("warning", "المقياس يحتاج مراجعة", "الأبعاد الكلية موجودة لكن أدلة الأبعاد المقروءة غير كافية لتأكيد المقياس.")

        val reading = calculateReadingConfidence(normalized, scale, polygonReport.errors.size)
        val plan = normalized.copy(scaleConfidence = scale)
        val confirmed = plan.dimensions.count { isIndependentDimension(it) && it.confidence >= 70 }
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

    private fun calculateReadingConfidence(plan: FloorPlan, scale: Int, geometryErrors: Int): Int {
        val roomCount = plan.rooms.size
        val wallCount = plan.walls.size
        val openingCount = plan.openings.size
        val rawValues = plan.rooms.map { it.confidence } + plan.walls.map { it.confidence } + plan.openings.map { it.confidence }
        val calibratedValues = rawValues.filter { it > 0 }
        val elementScore = if (calibratedValues.isEmpty()) 0 else calibratedValues.average().roundToInt().coerceIn(0, 100)
        val uncalibratedCloud = isUncalibratedCloud(plan) && calibratedValues.isEmpty()

        val numericCount = PlanNumberEvidenceEngine.numericLabels(plan.dimensions).size
        val confirmedDimensions = plan.dimensions.count { isIndependentDimension(it) && it.confidence >= 70 }
        val supportedWalls = plan.walls.count { wall ->
            val kind = wall.kind.lowercase()
            kind.contains("consensus") || kind.contains("raster") || kind.contains("remote") || wall.id.startsWith("rv2-")
        }
        val inferredRooms = plan.rooms.count { it.id.startsWith("topology-room-") || it.type.contains("topology", true) }
        val manualEvidence = plan.dimensions.count { isUserConfirmedDimension(it) }

        val expectedWalls = maxOf(4, roomCount * 2)
        val wallCoverage = if (wallCount == 0) 0 else (wallCount * 100 / expectedWalls).coerceIn(0, 100)
        val roomCoverage = if (roomCount == 0) 0 else (42 + roomCount * 8).coerceAtMost(100)
        val openingCoverage = when {
            roomCount == 0 -> 0
            openingCount == 0 -> 0
            else -> (openingCount * 100 / maxOf(1, roomCount)).coerceIn(0, 100)
        }
        val topologyScore = (wallCoverage * .55 + roomCoverage * .35 + openingCoverage * .10).roundToInt()

        val wallSupportScore = if (wallCount == 0) 0 else (supportedWalls * 100 / wallCount).coerceIn(0, 100)
        val numberScore = (numericCount * 7).coerceAtMost(100)
        val dimensionScore = (confirmedDimensions * 24).coerceAtMost(100)
        val evidenceScore = (wallSupportScore * .50 + numberScore * .20 + dimensionScore * .30).roundToInt()

        var score = if (uncalibratedCloud) {
            // A model value of zero means "not calibrated" in the Modal contract, not "0% correct".
            (topologyScore * .48 + evidenceScore * .34 + scale * .18).roundToInt()
        } else {
            (elementScore * .20 + topologyScore * .34 + evidenceScore * .30 + scale * .16).roundToInt()
        }

        var cap = 100
        if (uncalibratedCloud) cap = min(cap, 82)
        if (wallCount < 4) cap = min(cap, 55)
        if (roomCount == 0) cap = min(cap, 50)
        if (roomCount > 0 && wallCount < maxOf(4, roomCount)) cap = min(cap, 68)
        if (confirmedDimensions == 0 && numericCount == 0 && manualEvidence == 0) cap = min(cap, 70)
        if (supportedWalls == 0 && manualEvidence == 0) cap = min(cap, 78)
        if (scale < 50) cap = min(cap, 82)
        if (inferredRooms > 0 && inferredRooms * 2 >= roomCount.coerceAtLeast(1)) cap = min(cap, 80)
        if (openingCount == 0 && roomCount >= 3) cap = min(cap, 82)
        if (geometryErrors > 0) cap = min(cap, 55)

        val uncertaintyPenalty = (plan.uncertainties.size * 2).coerceAtMost(18)
        score = min(score, cap) - uncertaintyPenalty
        return score.coerceIn(0, 100)
    }

    private fun calculateScaleConfidence(plan: FloorPlan): Int {
        if (plan.widthM == null || plan.heightM == null) return 0
        val scaleDimensions = plan.dimensions.filter(::isIndependentDimension)
        val manual = scaleDimensions.count(::isUserConfirmedDimension)
        if (manual >= 2) return 100

        val strong = scaleDimensions.count { it.confidence >= 80 && it.sourceText.isNotBlank() }
        val medium = scaleDimensions.count { it.confidence in 60..79 && it.sourceText.isNotBlank() }
        val axes = scaleDimensions.filter { it.confidence >= 65 && it.axis in setOf("horizontal", "vertical") }.map { it.axis }.distinct().size

        return when {
            strong >= 2 && axes >= 2 -> (72 + (strong - 2) * 7 + medium * 3).coerceAtMost(96)
            strong >= 2 -> (62 + (strong - 2) * 6 + medium * 3).coerceAtMost(88)
            strong == 1 && medium >= 1 -> 55
            strong == 1 -> 46
            medium >= 2 && axes >= 2 -> 50
            medium >= 2 -> 42
            medium == 1 -> 34
            else -> 24
        }
    }

    private fun isIndependentDimension(dimension: PlanDimension): Boolean {
        val id = dimension.id.lowercase()
        return id.startsWith("manual-") ||
            id.startsWith("unit-") ||
            id.startsWith("pair-") ||
            id.startsWith("edge-") ||
            (id.contains("cloud-dimension-") && dimension.axis in setOf("horizontal", "vertical") && dimension.sourceText.isNotBlank()) ||
            isUserConfirmedDimension(dimension)
    }

    private fun isUncalibratedCloud(plan: FloorPlan): Boolean =
        plan.sourceSummary.contains("Cloud-only floor-plan reader", ignoreCase = true) ||
            plan.sourceSummary.contains("modal:raster2seq", ignoreCase = true)

    private fun isUserConfirmedDimension(dimension: PlanDimension): Boolean =
        dimension.sourceText.contains("تأكيد المستخدم") || dimension.sourceText.contains("إدخال المستخدم")

    private fun openingLabel(type: String) = if (type.contains("window", true) || type.contains("ناف")) "النافذة" else "الباب"
}
