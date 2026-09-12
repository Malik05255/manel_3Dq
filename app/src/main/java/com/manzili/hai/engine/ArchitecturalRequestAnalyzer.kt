package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan

object ArchitecturalRequestAnalyzer {
    data class Facts(
        val widthM: Double? = null,
        val heightM: Double? = null,
        val areaM2: Double? = null,
        val protectedRooms: List<String> = emptyList(),
        val mentionedRooms: List<String> = emptyList(),
        val targetKind: String? = null,
        val actionKind: String = "GENERAL",
        val structuralRisk: Boolean = false
    )

    fun parse(plan: FloorPlan, text: String): Facts {
        val normalized = normalizeDigits(text.lowercase())
        val dimensions = Regex("(\\d+(?:\\.\\d+)?)\\s*[x×*]\\s*(\\d+(?:\\.\\d+)?)").find(normalized)
        val w = dimensions?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val h = dimensions?.groupValues?.getOrNull(2)?.toDoubleOrNull()
        val mentioned = plan.rooms.filter { normalized.contains(it.name.lowercase()) }.map { it.name }
        val protected = plan.rooms.filter { room ->
            val name = room.name.lowercase()
            normalized.contains(name) && listOf("لا تلمس", "لا تقرب", "لا تغير", "لا تعدل", "حافظ على", "ثبت").any { normalized.contains(it) }
        }.map { it.name }
        val action = when {
            listOf("احذف", "شيل", "الغ", "remove", "delete").any { normalized.contains(it) } -> "DELETE"
            listOf("حرك الباب", "انقل الباب", "غير مكان الباب", "move door").any { normalized.contains(it) } -> "MOVE_DOOR"
            listOf("افتح باب", "اضف باب", "أضف باب", "باب بين", "add door").any { normalized.contains(it) } -> "ADD_DOOR"
            listOf("نافذة", "شباك", "window").any { normalized.contains(it) } && listOf("اضف", "أضف", "كبر", "حرك", "add", "move").any { normalized.contains(it) } -> "EDIT_WINDOW"
            listOf("حرك الجدار", "انقل الجدار", "اكسر الجدار", "ازل الجدار", "أزل الجدار", "move wall", "remove wall").any { normalized.contains(it) } -> "EDIT_WALL"
            listOf("كبر", "وسع", "زود", "enlarge", "expand").any { normalized.contains(it) } -> "EXPAND_ROOM"
            listOf("صغر", "قلل", "shrink").any { normalized.contains(it) } -> "SHRINK_ROOM"
            listOf("اضف", "أضف", "ابي", "أبي", "اريد", "أريد", "add").any { normalized.contains(it) } -> "ADD_SPACE"
            else -> "GENERAL"
        }
        val structuralRisk = action in setOf("EDIT_WALL", "MOVE_DOOR", "ADD_DOOR", "EDIT_WINDOW") ||
            listOf("حامل", "انشائي", "إنشائي", "عمود", "beam", "column", "load bearing").any { normalized.contains(it) }
        return Facts(
            widthM = w,
            heightM = h,
            areaM2 = if (w != null && h != null) w * h else null,
            protectedRooms = protected,
            mentionedRooms = mentioned,
            targetKind = when {
                listOf("صالة", "living", "lounge").any { normalized.contains(it) } -> "صالة"
                listOf("غرفة نوم", "bedroom").any { normalized.contains(it) } -> "غرفة نوم"
                listOf("مكتب", "office").any { normalized.contains(it) } -> "مكتب"
                listOf("مجلس", "majlis").any { normalized.contains(it) } -> "مجلس"
                listOf("مستودع", "store").any { normalized.contains(it) } -> "مستودع"
                listOf("حمام", "دورة مياه", "bathroom").any { normalized.contains(it) } -> "حمام"
                else -> null
            },
            actionKind = action,
            structuralRisk = structuralRisk
        )
    }

    fun preflight(plan: FloorPlan, text: String): String {
        val facts = parse(plan, text)
        val graph = SpatialGraphEngine.analyze(plan)
        val structure = StructuralGeometryEngine.inspect(plan)
        val flexibleTotal = graph.candidates.sumOf { it.flexibleAreaM2 }
        return buildString {
            append("نوع الطلب المصنف محليًا: ${facts.actionKind}. ")
            facts.areaM2?.let { area ->
                append("المساحة المطلوبة حسابيًا: ${"%.2f".format(area)}م²")
                if (facts.widthM != null && facts.heightM != null) append(" (${facts.widthM}×${facts.heightM}م)")
                append(". إجمالي المساحة المرنة المحافظة المكتشفة محليًا: ${"%.1f".format(flexibleTotal)}م². ")
                if (flexibleTotal + 0.05 < area) append("لا تكفِ المساحة المرنة وحدها؛ أي حل سيحتاج إعادة توزيع أعمق أو تنازلًا واضحًا. ")
            }
            if (facts.mentionedRooms.isNotEmpty()) append("الغرف المذكورة في الطلب: ${facts.mentionedRooms.joinToString("، ")}. ")
            if (facts.protectedRooms.isNotEmpty()) append("المستخدم طلب صراحة حماية: ${facts.protectedRooms.joinToString("، ")}. ")
            facts.targetKind?.let { append("نوع المساحة المستهدفة: $it. ") }
            if (facts.structuralRisk) {
                append("الطلب يمس جدارًا/بابًا/نافذة؛ لا يجوز افتراض الحالة الإنشائية. ")
                append("بيانات البنية الحالية: ${structure.wallCount} جدار، ${structure.doorCount} باب، ${structure.windowCount} نافذة، ثقة ${structure.confidence}%. ")
                if (structure.wallCount == 0) append("لا توجد هندسة جدران موثوقة كافية، لذا يلزم سؤال تأكيدي قبل تعديل بنيوي. ")
            }
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
