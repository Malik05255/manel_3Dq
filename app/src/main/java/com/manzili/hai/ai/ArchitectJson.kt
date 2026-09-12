package com.manzili.hai.ai

import com.manzili.hai.engine.ArchitecturalEngine
import com.manzili.hai.model.*
import org.json.JSONArray
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
        val plan = parsePlanObject(JSONObject(extractJson(raw)))
        return plan.copy(sourceSummary = ArchitecturalEngine.initialArchitectMessage(plan))
    }

    fun parseProposal(raw: String): PlanProposal {
        val root = JSONObject(extractJson(raw))
        val updated = root.optJSONObject("updated_plan")?.let { parsePlanObject(it) }
        val changesJson = root.optJSONArray("changes")
        val changes = buildList {
            if (changesJson != null) for (i in 0 until changesJson.length()) {
                val c = changesJson.optJSONObject(i) ?: continue
                add(PlanChange(
                    roomId = c.optString("room_id").takeIf { it.isNotBlank() },
                    roomName = c.optString("room_name", ""),
                    action = c.optString("action", "MODIFY"),
                    beforeAreaM2 = optPositiveDouble(c, "before_area_m2"),
                    afterAreaM2 = optPositiveDouble(c, "after_area_m2"),
                    note = c.optString("note", "")
                ))
            }
        }
        return PlanProposal(
            message = root.optString("message", "راجعت الطلب هندسيًا."),
            updatedPlan = updated,
            changes = changes,
            requiresConfirmation = root.optBoolean("requires_confirmation", true),
            confidence = root.optInt("confidence", if (updated == null) 0 else 70).coerceIn(0, 100)
        )
    }

    private fun parsePlanObject(root: JSONObject): FloorPlan {
        val rooms = root.optJSONArray("rooms")?.objects()?.mapIndexed { i, r ->
            Room(
                id = r.optString("id", "r$i"),
                name = r.optString("name", "غرفة"),
                type = r.optString("type", "room"),
                x = pct(r.optDouble("x", 0.0)),
                y = pct(r.optDouble("y", 0.0)),
                width = r.optDouble("width", 20.0).toFloat().coerceIn(1f, 100f),
                height = r.optDouble("height", 20.0).toFloat().coerceIn(1f, 100f),
                areaM2 = r.optDouble("area_m2", 0.0).coerceAtLeast(0.0),
                confidence = r.optInt("confidence", 80).coerceIn(0, 100),
                locked = r.optBoolean("locked", false),
                minAreaM2 = optPositiveDouble(r, "min_area_m2"),
                preferredAreaM2 = optPositiveDouble(r, "preferred_area_m2"),
                polygon = points(r.optJSONArray("polygon"))
            )
        }.orEmpty()

        val walls = root.optJSONArray("walls")?.objects()?.mapIndexed { i, w ->
            val start = point(w.optJSONObject("start")) ?: PlanPoint(0f, 0f)
            val end = point(w.optJSONObject("end")) ?: PlanPoint(0f, 0f)
            Wall(
                id = w.optString("id", "w$i"),
                start = start,
                end = end,
                thicknessCm = optPositiveDouble(w, "thickness_cm"),
                kind = w.optString("kind", "unknown"),
                confidence = w.optInt("confidence", 80).coerceIn(0, 100),
                locked = w.optBoolean("locked", false)
            )
        }.orEmpty()

        val openings = root.optJSONArray("openings")?.objects()?.mapIndexed { i, o ->
            Opening(
                id = o.optString("id", "o$i"),
                type = o.optString("type", "door"),
                x = pct(o.optDouble("x", 0.0)),
                y = pct(o.optDouble("y", 0.0)),
                width = o.optDouble("width", 3.0).toFloat().coerceIn(0.3f, 30f),
                rotationDeg = o.optDouble("rotation_deg", 0.0).toFloat(),
                wallId = o.optString("wall_id").takeIf { it.isNotBlank() },
                connectsRoomIds = strings(o, "connects_room_ids"),
                confidence = o.optInt("confidence", 80).coerceIn(0, 100),
                locked = o.optBoolean("locked", false)
            )
        }.orEmpty()

        val dimensions = root.optJSONArray("dimensions")?.objects()?.mapIndexedNotNull { i, d ->
            val value = optPositiveDouble(d, "value_m") ?: return@mapIndexedNotNull null
            PlanDimension(
                id = d.optString("id", "d$i"),
                label = d.optString("label", "بعد مقروء"),
                valueM = value,
                axis = d.optString("axis", "unknown"),
                start = point(d.optJSONObject("start")),
                end = point(d.optJSONObject("end")),
                confidence = d.optInt("confidence", 65).coerceIn(0, 100),
                sourceText = d.optString("source_text", "")
            )
        }.orEmpty()

        val prefsJson = root.optJSONObject("preferences")
        val preferences = PlanPreferences(
            privacyPriority = prefsJson?.optInt("privacy", 80)?.coerceIn(0, 100) ?: 80,
            circulationPriority = prefsJson?.optInt("circulation", 80)?.coerceIn(0, 100) ?: 80,
            daylightPriority = prefsJson?.optInt("daylight", 70)?.coerceIn(0, 100) ?: 70,
            futureFlexibilityPriority = prefsJson?.optInt("future_flexibility", 60)?.coerceIn(0, 100) ?: 60,
            notes = strings(prefsJson, "notes")
        )

        val north = root.optDouble("north_deg", Double.NaN).takeIf { !it.isNaN() }?.toFloat()
        return FloorPlan(
            title = root.optString("title", "المخطط"),
            widthM = optPositiveDouble(root, "building_width_m"),
            heightM = optPositiveDouble(root, "building_height_m"),
            rooms = rooms,
            walls = walls,
            openings = openings,
            observations = strings(root, "observations"),
            uncertainties = strings(root, "uncertainties"),
            sourceSummary = root.optString("summary", ""),
            preferences = preferences,
            revision = root.optInt("revision", 1).coerceAtLeast(1),
            footprint = points(root.optJSONArray("footprint")),
            dimensions = dimensions,
            scaleConfidence = root.optInt("scale_confidence", 0).coerceIn(0, 100),
            northDeg = north
        )
    }

    private fun point(root: JSONObject?): PlanPoint? {
        if (root == null) return null
        if (!root.has("x") || !root.has("y")) return null
        return PlanPoint(pct(root.optDouble("x", 0.0)), pct(root.optDouble("y", 0.0)))
    }

    private fun points(array: JSONArray?): List<PlanPoint> = if (array == null) emptyList() else
        (0 until array.length()).mapNotNull { point(array.optJSONObject(it)) }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

    private fun strings(root: JSONObject?, key: String): List<String> {
        if (root == null) return emptyList()
        val a = root.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
    }

    private fun optPositiveDouble(root: JSONObject, key: String): Double? {
        val value = root.optDouble(key, Double.NaN)
        return value.takeIf { !it.isNaN() && it > 0.0 }
    }

    private fun pct(value: Double): Float = value.toFloat().coerceIn(0f, 100f)
}
