package com.manzili.hai.ai

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.Room
import org.json.JSONObject

object ArchitectJson {
    fun extractJson(raw: String): String {
        val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val first = clean.indexOf('{')
        val last = clean.lastIndexOf('}')
        require(first >= 0 && last > first) { "لم يرجع النموذج JSON صالحًا" }
        return clean.substring(first, last + 1)
    }

    fun parsePlan(raw: String): FloorPlan {
        val root = JSONObject(extractJson(raw))
        val roomsJson = root.optJSONArray("rooms")
        val rooms = buildList {
            if (roomsJson != null) for (i in 0 until roomsJson.length()) {
                val r = roomsJson.getJSONObject(i)
                add(Room(
                    id = r.optString("id", "r$i"),
                    name = r.optString("name", "غرفة"),
                    type = r.optString("type", "room"),
                    x = r.optDouble("x", 0.0).toFloat().coerceIn(0f, 100f),
                    y = r.optDouble("y", 0.0).toFloat().coerceIn(0f, 100f),
                    width = r.optDouble("width", 20.0).toFloat().coerceIn(2f, 100f),
                    height = r.optDouble("height", 20.0).toFloat().coerceIn(2f, 100f),
                    areaM2 = r.optDouble("area_m2", 0.0),
                    confidence = r.optInt("confidence", 80).coerceIn(0, 100)
                ))
            }
        }
        fun strings(key: String): List<String> {
            val a = root.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
        }
        return FloorPlan(
            title = root.optString("title", "المخطط"),
            widthM = root.optDouble("building_width_m").takeIf { !it.isNaN() && it > 0 },
            heightM = root.optDouble("building_height_m").takeIf { !it.isNaN() && it > 0 },
            rooms = rooms,
            observations = strings("observations"),
            uncertainties = strings("uncertainties"),
            sourceSummary = root.optString("summary", "")
        )
    }
}
