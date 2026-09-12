package com.manzili.hai.engine

import com.manzili.hai.model.PlanDimension

/** Parses dimension-like text evidence without treating arbitrary plan numbers as geometry. */
object DimensionEvidenceEngine {
    fun extract(texts: List<String>): List<PlanDimension> {
        val out = mutableListOf<PlanDimension>()
        texts.forEachIndexed { sourceIndex, raw ->
            val text = normalizeDigits(raw)
            parsePairs(text, raw, sourceIndex, out)
            parseUnits(text, raw, sourceIndex, out)
        }
        return out
            .filter { it.valueM in 0.25..250.0 }
            .distinctBy { "%.3f".format(it.valueM) + ":" + it.sourceText.trim() }
    }

    private fun parsePairs(text: String, raw: String, sourceIndex: Int, out: MutableList<PlanDimension>) {
        val pair = Regex("(?<!\\d)(\\d{1,3}(?:[.]\\d{1,3})?)\\s*[x×X*]\\s*(\\d{1,3}(?:[.]\\d{1,3})?)\\s*(?:م|m)?(?![\\p{L}])")
        pair.findAll(text).forEachIndexed { index, m ->
            val a = m.groupValues[1].toDoubleOrNull() ?: return@forEachIndexed
            val b = m.groupValues[2].toDoubleOrNull() ?: return@forEachIndexed
            if (a in 0.25..100.0 && b in 0.25..100.0) {
                out += PlanDimension("pair-$sourceIndex-$index-a", "بعد من زوج أبعاد", a, "unknown", confidence = 68, sourceText = raw.take(140))
                out += PlanDimension("pair-$sourceIndex-$index-b", "بعد من زوج أبعاد", b, "unknown", confidence = 68, sourceText = raw.take(140))
            }
        }
    }

    private fun parseUnits(text: String, raw: String, sourceIndex: Int, out: MutableList<PlanDimension>) {
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
                raw.contains("عرض") || raw.contains("طول") || raw.contains("بعد") -> 78
                unit in setOf("cm", "سم", "mm", "ملم", "مم") -> 72
                else -> 62
            }
            out += PlanDimension("unit-$sourceIndex-$index", label(raw), meters, axis(raw), confidence = confidence, sourceText = raw.take(140))
        }
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

    private fun normalizeDigits(value: String): String = buildString {
        value.forEach { ch -> append(when (ch) {
            '٠','۰' -> '0'; '١','۱' -> '1'; '٢','۲' -> '2'; '٣','۳' -> '3'; '٤','۴' -> '4'
            '٥','۵' -> '5'; '٦','۶' -> '6'; '٧','۷' -> '7'; '٨','۸' -> '8'; '٩','۹' -> '9'
            '٫', ',' -> '.'; else -> ch
        }) }
    }
}
