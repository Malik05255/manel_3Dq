package com.manzili.hai.engine

import com.manzili.hai.model.PlanDimension
import com.manzili.hai.model.PlanPoint
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Preserves every number that OCR can see without pretending that every number is a length.
 * Numeric labels are stored in the existing PlanDimension evidence stream with an axis prefixed
 * by `label-`; scale solvers intentionally ignore those axes.
 */
object PlanNumberEvidenceEngine {
    fun extract(lines: List<PlanTextOcrEngine.SpatialLine>): List<PlanDimension> {
        val out = mutableListOf<PlanDimension>()
        lines.forEachIndexed { lineIndex, line ->
            val normalized = DimensionEvidenceEngine.normalizeDigits(line.text)
                .replace('−', '-')
                .replace('–', '-')
            val centerX = (line.leftPct + line.rightPct) / 2f
            val centerY = (line.topPct + line.bottomPct) / 2f
            val nearEdge = centerX <= 20f || centerX >= 80f || centerY <= 20f || centerY >= 80f
            val horizontal = line.widthPct >= line.heightPct
            val start = if (horizontal) PlanPoint(line.leftPct, centerY) else PlanPoint(centerX, line.topPct)
            val end = if (horizontal) PlanPoint(line.rightPct, centerY) else PlanPoint(centerX, line.bottomPct)

            NUMBER.findAll(normalized).forEachIndexed { tokenIndex, match ->
                val rawNumber = match.value
                val value = rawNumber.toDoubleOrNull() ?: return@forEachIndexed
                if (!value.isFinite() || abs(value) > 1_000_000.0) return@forEachIndexed

                val kind = classify(normalized, match.range, nearEdge)
                val confidence = (line.confidence + when (kind) {
                    "area" -> 12
                    "dimension" -> 8
                    "elevation" -> 7
                    else -> 0
                } + if (rawNumber.contains('.')) 2 else 0).coerceIn(45, 98)

                out += PlanDimension(
                    id = "num-${line.pageIndex}-$lineIndex-$tokenIndex",
                    label = when (kind) {
                        "area" -> "مساحة مقروءة"
                        "dimension" -> "رقم بُعد مقروء"
                        "elevation" -> "منسوب مقروء"
                        else -> "رقم مقروء"
                    },
                    valueM = value,
                    axis = "label-$kind",
                    start = start,
                    end = end,
                    confidence = confidence,
                    sourceText = line.text.take(180),
                    pageIndex = line.pageIndex
                )
            }
        }
        return dedupe(out)
    }

    /** Numeric labels already produced by a vision model use the same safe evidence namespace. */
    fun fromVision(
        id: String,
        rawText: String,
        value: Double,
        kind: String,
        x: Float,
        y: Float,
        pageIndex: Int = 0,
        confidence: Int = 80
    ): PlanDimension? {
        if (!value.isFinite() || abs(value) > 1_000_000.0) return null
        val safeKind = when (kind.lowercase()) {
            "area", "dimension", "elevation", "count", "number" -> kind.lowercase()
            else -> "number"
        }
        val point = PlanPoint(x.coerceIn(0f, 100f), y.coerceIn(0f, 100f))
        return PlanDimension(
            id = "num-ai-$id",
            label = when (safeKind) {
                "area" -> "مساحة مقروءة"
                "dimension" -> "رقم بُعد مقروء"
                "elevation" -> "منسوب مقروء"
                else -> "رقم مقروء"
            },
            valueM = value,
            axis = "label-$safeKind",
            start = point,
            end = point,
            confidence = confidence.coerceIn(0, 100),
            sourceText = rawText.take(180),
            pageIndex = pageIndex.coerceAtLeast(0)
        )
    }

    fun numericLabels(dimensions: List<PlanDimension>): List<PlanDimension> =
        dimensions.filter { it.id.startsWith("num-") || it.axis.startsWith("label-") }

    private fun classify(text: String, range: IntRange, nearEdge: Boolean): String {
        val from = (range.first - 12).coerceAtLeast(0)
        val to = (range.last + 13).coerceAtMost(text.length)
        val around = text.substring(from, to).lowercase()
        return when {
            AREA_MARKERS.any { around.contains(it) } -> "area"
            ELEVATION_MARKERS.any { around.contains(it) } -> "elevation"
            LENGTH_MARKERS.any { around.contains(it) } || nearEdge -> "dimension"
            else -> "number"
        }
    }

    private fun dedupe(input: List<PlanDimension>): List<PlanDimension> = input.distinctBy { item ->
        val x = item.start?.x?.times(2f)?.roundToInt() ?: -1
        val y = item.start?.y?.times(2f)?.roundToInt() ?: -1
        "${item.pageIndex}:${"%.3f".format(item.valueM)}:$x:$y:${item.axis}"
    }

    private val NUMBER = Regex("(?<![\\d.])[+-]?\\d{1,7}(?:[.]\\d{1,4})?(?![\\d.])")
    private val AREA_MARKERS = listOf("m²", "m2", "sqm", "م²", "م2", "م 2", "مساحة")
    private val LENGTH_MARKERS = listOf("mm", "cm", "meter", "metre", "مم", "ملم", "سم", "متر", "بعد", "عرض", "طول")
    private val ELEVATION_MARKERS = listOf("elev", "level", "ffl", "منسوب", "ارتفاع")
}
