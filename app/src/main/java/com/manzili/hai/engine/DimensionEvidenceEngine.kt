package com.manzili.hai.engine

import com.manzili.hai.model.PlanDimension
import com.manzili.hai.model.PlanPoint
import kotlin.math.abs
import kotlin.math.roundToInt

/** Parses dimension-like text evidence without treating arbitrary plan numbers as geometry. */
object DimensionEvidenceEngine {
    fun extract(texts: List<String>): List<PlanDimension> {
        val out = mutableListOf<PlanDimension>()
        texts.forEachIndexed { sourceIndex, raw -> parse(raw, sourceIndex, 0, null, out) }
        return dedupe(out)
    }

    fun extractSpatial(lines: List<PlanTextOcrEngine.SpatialLine>): List<PlanDimension> {
        val out = mutableListOf<PlanDimension>()
        lines.forEachIndexed { sourceIndex, line ->
            val horizontal = line.widthPct >= line.heightPct
            val start = if (horizontal) PlanPoint(line.leftPct, (line.topPct + line.bottomPct) / 2f)
            else PlanPoint((line.leftPct + line.rightPct) / 2f, line.topPct)
            val end = if (horizontal) PlanPoint(line.rightPct, (line.topPct + line.bottomPct) / 2f)
            else PlanPoint((line.leftPct + line.rightPct) / 2f, line.bottomPct)
            parse(line.text, sourceIndex, line.pageIndex, start to end, out)
            parseBareExteriorNumber(line, sourceIndex, start, end, out)
        }
        return dedupe(out)
    }

    private fun parse(
        raw: String,
        sourceIndex: Int,
        page: Int,
        span: Pair<PlanPoint, PlanPoint>?,
        out: MutableList<PlanDimension>
    ) {
        val text = normalizeDigits(raw)
        parsePairs(text, raw, sourceIndex, page, span, out)
        parseUnits(text, raw, sourceIndex, page, span, out)
    }

    /**
     * Architectural dimension chains very often contain only numbers (for example 1.77, 1.40, 6)
     * without repeating the unit on every segment. Those labels were previously discarded, which
     * is why HAI could visibly see a dimension chain but still leave width/height blank.
     *
     * We only promote a bare number when it is spatially close to a page edge. This keeps room
     * numbers/areas in the drawing interior from being silently treated as building dimensions.
     */
    private fun parseBareExteriorNumber(
        line: PlanTextOcrEngine.SpatialLine,
        sourceIndex: Int,
        start: PlanPoint,
        end: PlanPoint,
        out: MutableList<PlanDimension>
    ) {
        val normalized = normalizeDigits(line.text).trim().replace(" ", "")
        val match = Regex("^[+-]?(\\d{1,3}(?:[.]\\d{1,3})?)$").matchEntire(normalized) ?: return
        val value = match.groupValues[1].toDoubleOrNull() ?: return
        if (value !in 0.25..100.0) return

        val horizontal = abs(end.x - start.x) >= abs(end.y - start.y)
        val cross = if (horizontal) (start.y + end.y) / 2f else (start.x + end.x) / 2f
        val nearEdge = cross <= 20f || cross >= 80f
        if (!nearEdge) return

        val veryNearEdge = cross <= 13f || cross >= 87f
        out += PlanDimension(
            id = "edge-${line.pageIndex}-$sourceIndex",
            label = "بعد خارجي مقروء",
            valueM = value,
            axis = if (horizontal) "horizontal" else "vertical",
            start = start,
            end = end,
            confidence = if (veryNearEdge) 76 else 68,
            sourceText = line.text.take(140),
            pageIndex = line.pageIndex
        )
    }

    private fun parsePairs(
        text: String,
        raw: String,
        sourceIndex: Int,
        page: Int,
        span: Pair<PlanPoint, PlanPoint>?,
        out: MutableList<PlanDimension>
    ) {
        val pair = Regex("(?<!\\d)(\\d{1,3}(?:[.]\\d{1,3})?)\\s*[x×X*]\\s*(\\d{1,3}(?:[.]\\d{1,3})?)\\s*(?:م|m)?(?![\\p{L}])")
        pair.findAll(text).forEachIndexed { index, m ->
            val a = m.groupValues[1].toDoubleOrNull() ?: return@forEachIndexed
            val b = m.groupValues[2].toDoubleOrNull() ?: return@forEachIndexed
            if (a in 0.25..100.0 && b in 0.25..100.0) {
                out += PlanDimension("pair-$page-$sourceIndex-$index-a", "بعد من زوج أبعاد", a, "unknown", span?.first, span?.second, 72, raw.take(140), page)
                out += PlanDimension("pair-$page-$sourceIndex-$index-b", "بعد من زوج أبعاد", b, "unknown", span?.first, span?.second, 72, raw.take(140), page)
            }
        }
    }

    private fun parseUnits(
        text: String,
        raw: String,
        sourceIndex: Int,
        page: Int,
        span: Pair<PlanPoint, PlanPoint>?,
        out: MutableList<PlanDimension>
    ) {
        val unitPattern = Regex("(?<!\\d)(\\d{1,5}(?:[.]\\d{1,3})?)\\s*(mm|ملم|مم|cm|سم|متر|م)(?![\\p{L}])", RegexOption.IGNORE_CASE)
        unitPattern.findAll(text).forEachIndexed { index, m ->
            val n = m.groupValues[1].toDoubleOrNull() ?: return@forEachIndexed
            val unit = m.groupValues[2].lowercase()
            val meters = when (unit) {
                "mm", "ملم", "مم" -> n / 1000.0
                "cm", "سم" -> n / 100.0
                else -> n
            }
            val confidence = when {
                raw.contains("عرض") || raw.contains("طول") || raw.contains("بعد") -> 84
                unit in setOf("cm", "سم", "mm", "ملم", "مم") -> 78
                span != null -> 70
                else -> 62
            }
            val inferredAxis = when {
                span != null && abs(span.second.x - span.first.x) > abs(span.second.y - span.first.y) -> "horizontal"
                span != null -> "vertical"
                else -> axis(raw)
            }
            out += PlanDimension(
                id = "unit-$page-$sourceIndex-$index",
                label = label(raw),
                valueM = meters,
                axis = inferredAxis,
                start = span?.first,
                end = span?.second,
                confidence = confidence,
                sourceText = raw.take(140),
                pageIndex = page
            )
        }
    }

    private fun dedupe(input: List<PlanDimension>): List<PlanDimension> = input
        .filter { it.valueM in 0.25..250.0 }
        .distinctBy { dimension ->
            val sx = dimension.start?.x?.times(2f)?.roundToInt() ?: -1
            val sy = dimension.start?.y?.times(2f)?.roundToInt() ?: -1
            val ex = dimension.end?.x?.times(2f)?.roundToInt() ?: -1
            val ey = dimension.end?.y?.times(2f)?.roundToInt() ?: -1
            "${dimension.pageIndex}:${"%.3f".format(dimension.valueM)}:$sx:$sy:$ex:$ey"
        }

    private fun label(raw: String): String = when {
        raw.contains("عرض") -> "عرض مقروء"
        raw.contains("طول") -> "طول مقروء"
        raw.contains("ارتفاع") -> "ارتفاع مقروء"
        else -> "بعد OCR/نصي"
    }

    private fun axis(raw: String): String = when {
        raw.contains("عرض") || raw.contains("أفقي") -> "horizontal"
        raw.contains("طول") || raw.contains("رأسي") -> "vertical"
        else -> "unknown"
    }

    internal fun normalizeDigits(value: String): String = buildString {
        value.forEach { ch -> append(when (ch) {
            '٠','۰' -> '0'; '١','۱' -> '1'; '٢','۲' -> '2'; '٣','۳' -> '3'; '٤','۴' -> '4'
            '٥','۵' -> '5'; '٦','۶' -> '6'; '٧','۷' -> '7'; '٨','۸' -> '8'; '٩','۹' -> '9'
            '٫', ',' -> '.'; else -> ch
        }) }
    }
}
