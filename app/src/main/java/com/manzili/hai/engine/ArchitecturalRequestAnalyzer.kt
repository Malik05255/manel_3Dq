package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan

object ArchitecturalRequestAnalyzer {
    data class Facts(
        val widthM: Double? = null,
        val heightM: Double? = null,
        val areaM2: Double? = null,
        val protectedRooms: List<String> = emptyList(),
        val targetKind: String? = null
    )

    fun parse(plan: FloorPlan, text: String): Facts {
        val normalized = normalizeDigits(text.lowercase())
        val dimensions = Regex("(\\d+(?:\\.\\d+)?)\\s*[x×*]\\s*(\\d+(?:\\.\\d+)?)")
            .find(normalized)
        val w = dimensions?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val h = dimensions?.groupValues?.getOrNull(2)?.toDoubleOrNull()
        val protected = plan.rooms.filter { room ->
            val name = room.name.lowercase()
            normalized.contains(name) && listOf("لا تلمس", "لا تقرب", "لا تغير", "لا تعدل", "حافظ على", "ثبت").any { normalized.contains(it) }
        }.map { it.name }
        return Facts(
            widthM = w,
            heightM = h,
            areaM2 = if (w != null && h != null) w * h else null,
            protectedRooms = protected,
            targetKind = when {
                listOf("صالة", "living", "lounge").any { normalized.contains(it) } -> "صالة"
                listOf("غرفة نوم", "bedroom").any { normalized.contains(it) } -> "غرفة نوم"
                listOf("مكتب", "office").any { normalized.contains(it) } -> "مكتب"
                listOf("مجلس", "majlis").any { normalized.contains(it) } -> "مجلس"
                listOf("مستودع", "store").any { normalized.contains(it) } -> "مستودع"
                else -> null
            }
        )
    }

    fun preflight(plan: FloorPlan, text: String): String {
        val facts = parse(plan, text)
        val graph = SpatialGraphEngine.analyze(plan)
        val flexibleTotal = graph.candidates.sumOf { it.flexibleAreaM2 }
        return buildString {
            facts.areaM2?.let { area ->
                append("المساحة المطلوبة حسابيًا: ${"%.2f".format(area)}م²")
                if (facts.widthM != null && facts.heightM != null) append(" (${facts.widthM}×${facts.heightM}م)")
                append(". ")
                append("إجمالي المساحة المرنة المحافظة المكتشفة محليًا: ${"%.1f".format(flexibleTotal)}م². ")
                if (flexibleTotal + 0.05 < area) append("لا تكفِ المساحة المرنة وحدها؛ أي حل سيحتاج إعادة توزيع أعمق أو تنازلًا واضحًا. ")
            }
            if (facts.protectedRooms.isNotEmpty()) append("المستخدم طلب صراحة حماية: ${facts.protectedRooms.joinToString("، ")}. ")
            facts.targetKind?.let { append("نوع المساحة المستهدفة: $it. ") }
            val best = graph.candidates.take(3)
            if (best.isNotEmpty()) append("مرشحو إعادة التوزيع الأوليون: ${best.joinToString("؛ ") { "${it.roomName} (${"%.1f".format(it.flexibleAreaM2)}م² مرنة)" }}.")
        }.trim()
    }

    private fun normalizeDigits(input: String): String {
        val arabic = "٠١٢٣٤٥٦٧٨٩"
        val eastern = "۰۱۲۳۴۵۶۷۸۹"
        return buildString {
            input.forEach { c ->
                val a = arabic.indexOf(c)
                val e = eastern.indexOf(c)
                append(
                    when {
                        a >= 0 -> ('0'.code + a).toChar()
                        e >= 0 -> ('0'.code + e).toChar()
                        c == '٫' || c == ',' -> '.'
                        else -> c
                    }
                )
            }
        }
    }
}
