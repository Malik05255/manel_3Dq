package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import kotlin.math.abs
import kotlin.math.hypot

/** Deterministic before/after comparison for durable project revisions. */
object VersionComparisonEngine {
    data class Result(
        val oldRevision: Int,
        val newRevision: Int,
        val changes: List<String>,
        val addedRooms: Int,
        val removedRooms: Int,
        val movedRooms: Int,
        val resizedRooms: Int,
        val wallDelta: Int,
        val openingDelta: Int,
        val activeConstraintDelta: Int
    ) {
        val changed: Boolean get() = changes.isNotEmpty()
        val headline: String get() = if (!changed) "لا توجد فروقات هندسية واضحة" else "${changes.size} فرق بين V$oldRevision و V$newRevision"
    }

    fun compare(old: FloorPlan, current: FloorPlan): Result {
        val notes = mutableListOf<String>()
        val beforeRooms = old.rooms.associateBy { it.id }
        val afterRooms = current.rooms.associateBy { it.id }

        val added = afterRooms.keys - beforeRooms.keys
        val removed = beforeRooms.keys - afterRooms.keys
        added.forEach { id -> notes += "أضيفت غرفة «${afterRooms[id]?.name ?: id}»." }
        removed.forEach { id -> notes += "حُذفت غرفة «${beforeRooms[id]?.name ?: id}»." }

        var moved = 0
        var resized = 0
        (beforeRooms.keys intersect afterRooms.keys).forEach { id ->
            val a = beforeRooms.getValue(id)
            val b = afterRooms.getValue(id)
            val distance = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
            if (distance > .35) {
                moved++
                notes += "نُقلت «${b.name}» على المخطط بمقدار نسبي ${"%.1f".format(distance)}."
            }
            val dimensionsChanged = abs(a.width - b.width) > .35f || abs(a.height - b.height) > .35f
            val areaChanged = abs(a.areaM2 - b.areaM2) > .15
            if (dimensionsChanged || areaChanged) {
                resized++
                notes += if (a.areaM2 > 0 && b.areaM2 > 0) {
                    "تغيرت مساحة «${b.name}» من ${"%.1f".format(a.areaM2)}م² إلى ${"%.1f".format(b.areaM2)}م²."
                } else {
                    "تغيرت أبعاد «${b.name}»."
                }
            }
        }

        val wallDelta = current.walls.size - old.walls.size
        val openingDelta = current.openings.size - old.openings.size
        val activeConstraintDelta = current.constraints.count { it.active } - old.constraints.count { it.active }
        if (wallDelta != 0) notes += if (wallDelta > 0) "أضيف $wallDelta جدار/حد هندسي." else "حُذف ${-wallDelta} جدار/حد هندسي."
        if (openingDelta != 0) notes += if (openingDelta > 0) "أضيفت $openingDelta فتحة باب/نافذة." else "حُذفت ${-openingDelta} فتحة باب/نافذة."
        if (activeConstraintDelta != 0) notes += if (activeConstraintDelta > 0) "زادت قواعد المشروع الفعالة بمقدار $activeConstraintDelta." else "انخفضت قواعد المشروع الفعالة بمقدار ${-activeConstraintDelta}."

        val oldScore = ArchitecturalEngine.score(old)
        val newScore = ArchitecturalEngine.score(current)
        if (oldScore.overall != newScore.overall) notes += "تقييم HAI العام: ${oldScore.overall} ← ${newScore.overall}."
        if (old.preferences.privacyPriority != current.preferences.privacyPriority) notes += "أولوية الخصوصية: ${old.preferences.privacyPriority} ← ${current.preferences.privacyPriority}."

        return Result(
            oldRevision = old.revision,
            newRevision = current.revision,
            changes = notes.distinct(),
            addedRooms = added.size,
            removedRooms = removed.size,
            movedRooms = moved,
            resizedRooms = resized,
            wallDelta = wallDelta,
            openingDelta = openingDelta,
            activeConstraintDelta = activeConstraintDelta
        )
    }
}
