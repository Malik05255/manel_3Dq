package com.manzili.hai.data

import android.content.Context
import com.manzili.hai.engine.ProjectMemoryEngine
import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.Opening
import com.manzili.hai.model.PlanPoint
import com.manzili.hai.model.PlanPreferences
import com.manzili.hai.model.ProjectConstraint
import com.manzili.hai.model.Room
import com.manzili.hai.model.Wall
import org.json.JSONArray
import org.json.JSONObject

class ProjectPlanStore(context: Context) {
    private val prefs = context.getSharedPreferences("manzili_hai_project", Context.MODE_PRIVATE)

    fun save(plan: FloorPlan) {
        prefs.edit().putString(KEY_PLAN, encode(ProjectMemoryEngine.reconcile(plan)).toString()).apply()
    }

    fun load(): FloorPlan? {
        val raw = prefs.getString(KEY_PLAN, null)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { ProjectMemoryEngine.reconcile(decode(JSONObject(raw))) }.getOrNull()
    }

    fun clear() {
        prefs.edit().remove(KEY_PLAN).apply()
    }

    private fun encode(plan: FloorPlan): JSONObject = JSONObject().apply {
        put("title", plan.title)
        plan.widthM?.let { put("widthM", it) }
        plan.heightM?.let { put("heightM", it) }
        put("revision", plan.revision)
        put("sourceSummary", plan.sourceSummary)
        put("observations", JSONArray(plan.observations))
        put("uncertainties", JSONArray(plan.uncertainties))
        put("rooms", JSONArray().apply {
            plan.rooms.forEach { r ->
                put(JSONObject().apply {
                    put("id", r.id); put("name", r.name); put("type", r.type)
                    put("x", r.x); put("y", r.y); put("width", r.width); put("height", r.height)
                    put("areaM2", r.areaM2); put("confidence", r.confidence); put("locked", r.locked)
                    r.minAreaM2?.let { put("minAreaM2", it) }
                    r.preferredAreaM2?.let { put("preferredAreaM2", it) }
                })
            }
        })
        put("walls", JSONArray().apply {
            plan.walls.forEach { w ->
                put(JSONObject().apply {
                    put("id", w.id)
                    put("start", JSONObject().put("x", w.start.x).put("y", w.start.y))
                    put("end", JSONObject().put("x", w.end.x).put("y", w.end.y))
                    w.thicknessCm?.let { put("thicknessCm", it) }
                    put("kind", w.kind); put("confidence", w.confidence); put("locked", w.locked)
                })
            }
        })
        put("openings", JSONArray().apply {
            plan.openings.forEach { o ->
                put(JSONObject().apply {
                    put("id", o.id); put("type", o.type); put("x", o.x); put("y", o.y); put("width", o.width)
                    put("rotationDeg", o.rotationDeg)
                    o.wallId?.let { put("wallId", it) }
                    put("connectsRoomIds", JSONArray(o.connectsRoomIds))
                    put("confidence", o.confidence); put("locked", o.locked)
                })
            }
        })
        put("preferences", JSONObject().apply {
            put("privacyPriority", plan.preferences.privacyPriority)
            put("circulationPriority", plan.preferences.circulationPriority)
            put("daylightPriority", plan.preferences.daylightPriority)
            put("futureFlexibilityPriority", plan.preferences.futureFlexibilityPriority)
            put("notes", JSONArray(plan.preferences.notes))
        })
        put("constraints", JSONArray().apply {
            plan.constraints.forEach { c ->
                put(JSONObject().apply {
                    put("id", c.id); put("kind", c.kind); put("text", c.text)
                    put("targetIds", JSONArray(c.targetIds))
                    c.value?.let { put("value", it) }
                    put("hard", c.hard); put("priority", c.priority); put("active", c.active)
                })
            }
        })
    }

    private fun decode(root: JSONObject): FloorPlan {
        val rooms = root.optJSONArray("rooms")?.objects()?.mapIndexed { index, r ->
            Room(
                id = r.optString("id", "r$index"),
                name = r.optString("name", "غرفة"),
                type = r.optString("type", "room"),
                x = r.optDouble("x", 0.0).toFloat(), y = r.optDouble("y", 0.0).toFloat(),
                width = r.optDouble("width", 20.0).toFloat(), height = r.optDouble("height", 20.0).toFloat(),
                areaM2 = r.optDouble("areaM2", 0.0).coerceAtLeast(0.0),
                confidence = r.optInt("confidence", 80).coerceIn(0, 100),
                locked = r.optBoolean("locked", false),
                minAreaM2 = positive(r, "minAreaM2"),
                preferredAreaM2 = positive(r, "preferredAreaM2")
            )
        }.orEmpty()
        val walls = root.optJSONArray("walls")?.objects()?.mapIndexed { index, w ->
            val start = w.optJSONObject("start") ?: JSONObject()
            val end = w.optJSONObject("end") ?: JSONObject()
            Wall(
                id = w.optString("id", "w$index"),
                start = PlanPoint(start.optDouble("x", 0.0).toFloat(), start.optDouble("y", 0.0).toFloat()),
                end = PlanPoint(end.optDouble("x", 0.0).toFloat(), end.optDouble("y", 0.0).toFloat()),
                thicknessCm = positive(w, "thicknessCm"),
                kind = w.optString("kind", "unknown"),
                confidence = w.optInt("confidence", 80).coerceIn(0, 100),
                locked = w.optBoolean("locked", false)
            )
        }.orEmpty()
        val openings = root.optJSONArray("openings")?.objects()?.mapIndexed { index, o ->
            Opening(
                id = o.optString("id", "o$index"), type = o.optString("type", "door"),
                x = o.optDouble("x", 0.0).toFloat(), y = o.optDouble("y", 0.0).toFloat(),
                width = o.optDouble("width", 3.0).toFloat(), rotationDeg = o.optDouble("rotationDeg", 0.0).toFloat(),
                wallId = o.optString("wallId").takeIf { it.isNotBlank() },
                connectsRoomIds = strings(o.optJSONArray("connectsRoomIds")),
                confidence = o.optInt("confidence", 80).coerceIn(0, 100), locked = o.optBoolean("locked", false)
            )
        }.orEmpty()
        val p = root.optJSONObject("preferences")
        val preferences = PlanPreferences(
            privacyPriority = p?.optInt("privacyPriority", 80)?.coerceIn(0, 100) ?: 80,
            circulationPriority = p?.optInt("circulationPriority", 80)?.coerceIn(0, 100) ?: 80,
            daylightPriority = p?.optInt("daylightPriority", 70)?.coerceIn(0, 100) ?: 70,
            futureFlexibilityPriority = p?.optInt("futureFlexibilityPriority", 60)?.coerceIn(0, 100) ?: 60,
            notes = strings(p?.optJSONArray("notes"))
        )
        val constraints = root.optJSONArray("constraints")?.objects()?.mapIndexed { index, c ->
            ProjectConstraint(
                id = c.optString("id", "constraint-$index"),
                kind = c.optString("kind", "NOTE"),
                text = c.optString("text", ""),
                targetIds = strings(c.optJSONArray("targetIds")),
                value = positive(c, "value"),
                hard = c.optBoolean("hard", true),
                priority = c.optInt("priority", 90).coerceIn(0, 100),
                active = c.optBoolean("active", true)
            )
        }.orEmpty()
        return FloorPlan(
            title = root.optString("title", "مشروعي"),
            widthM = positive(root, "widthM"), heightM = positive(root, "heightM"),
            rooms = rooms, walls = walls, openings = openings,
            observations = strings(root.optJSONArray("observations")),
            uncertainties = strings(root.optJSONArray("uncertainties")),
            sourceSummary = root.optString("sourceSummary", ""),
            preferences = preferences,
            constraints = constraints,
            revision = root.optInt("revision", 1).coerceAtLeast(1)
        )
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
    private fun strings(array: JSONArray?): List<String> = if (array == null) emptyList() else (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
    private fun positive(root: JSONObject, key: String): Double? = root.optDouble(key, Double.NaN).takeIf { !it.isNaN() && it > 0.0 }

    companion object { private const val KEY_PLAN = "current_plan_v1" }
}
