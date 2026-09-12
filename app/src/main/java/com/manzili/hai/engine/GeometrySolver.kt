package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.Opening
import com.manzili.hai.model.PlanChange
import com.manzili.hai.model.PlanPoint
import com.manzili.hai.model.PlanProposal
import com.manzili.hai.model.Room
import com.manzili.hai.model.Wall
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Deterministic geometry layer.
 * HAI suggests intent; this engine owns snapping, bounds, overlap checks,
 * topology-aware manipulation and candidate generation.
 */
object GeometrySolver {
    data class Check(
        val feasible: Boolean,
        val errors: List<String> = emptyList(),
        val warnings: List<String> = emptyList()
    )

    data class TargetSize(val widthM: Double, val heightM: Double) {
        val areaM2: Double get() = widthM * heightM
    }

    data class Candidate(
        val title: String,
        val reason: String,
        val plan: FloorPlan,
        val score: Int,
        val review: ArchitecturalEngine.ArchitectReview,
        val changes: List<PlanChange> = emptyList(),
        val geometryWarnings: List<String> = emptyList()
    )

    private enum class Edge { LEFT, RIGHT, TOP, BOTTOM }

    fun brief(plan: FloorPlan, userText: String): String {
        val target = parseTargetSize(userText)
        val locked = plan.rooms.filter { it.locked }.joinToString("، ") { it.name }.ifBlank { "لا يوجد" }
        val graph = SpatialGraphEngine.analyze(plan)
        val flexible = graph.candidates.take(4).joinToString("؛ ") { "${it.roomName}: ${fmt(it.flexibleAreaM2)}م²" }.ifBlank { "غير محسوبة" }
        return buildString {
            append("قيود GeometrySolver: العناصر المقفلة: $locked. ")
            target?.let { append("المقاس المطلوب ${fmt(it.widthM)}×${fmt(it.heightM)}م = ${fmt(it.areaM2)}م². ") }
            append("المساحات المرنة التقريبية: $flexible. ")
            if (plan.widthM == null || plan.heightM == null) append("مقياس المبنى بالمتر غير مكتمل؛ لا يجوز تحويل المتر إلى إحداثيات نسبية بدقة.")
        }
    }

    fun verify(current: FloorPlan, next: FloorPlan): Check {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        next.rooms.forEach { room ->
            if (room.width <= 0f || room.height <= 0f) errors += "أبعاد ${room.name} غير صالحة"
            if (room.x < 0f || room.y < 0f || room.x + room.width > 100.001f || room.y + room.height > 100.001f) {
                errors += "${room.name} خرجت عن حدود المخطط"
            }
        }

        val nextRooms = next.rooms.associateBy { it.id }
        current.rooms.filter { it.locked }.forEach { old ->
            val fresh = nextRooms[old.id]
            if (fresh == null) errors += "الغرفة المقفلة ${old.name} حذفت"
            else if (roomChanged(old, fresh)) errors += "الغرفة المقفلة ${old.name} تغيرت"
        }

        val nextWalls = next.walls.associateBy { it.id }
        current.walls.filter { it.locked }.forEach { old ->
            val fresh = nextWalls[old.id]
            if (fresh == null) errors += "الجدار المقفل ${old.id} حذف"
            else if (wallChanged(old, fresh)) errors += "الجدار المقفل ${old.id} تغير"
        }

        val nextOpenings = next.openings.associateBy { it.id }
        current.openings.filter { it.locked }.forEach { old ->
            val fresh = nextOpenings[old.id]
            if (fresh == null) errors += "الفتحة المقفلة ${old.id} حذفت"
            else if (openingChanged(old, fresh)) errors += "الفتحة المقفلة ${old.id} تغيرت"
        }

        val oldOverlap = overlapPairs(current)
        overlapPairs(next).forEach { (key, value) ->
            val before = oldOverlap[key] ?: 0.0
            if (value > 0.02 && value > before + 0.01) errors += "ظهر تداخل هندسي جديد بين ${key.first} و${key.second}"
        }

        next.openings.forEach { opening ->
            val wall = opening.wallId?.let { nextWalls[it] }
            if (wall == null) {
                warnings += "الفتحة ${opening.id} غير مرتبطة بجدار موثوق"
            } else {
                val distance = pointToSegment(opening.x, opening.y, wall.start.x, wall.start.y, wall.end.x, wall.end.y)
                if (distance > 1.5) errors += "الفتحة ${opening.id} لا تقع على الجدار ${wall.id}"
            }
        }

        val structural = StructuralGeometryEngine.inspect(next)
        errors += structural.errors
        warnings += structural.warnings

        return Check(errors.isEmpty(), errors.distinct(), warnings.distinct())
    }

    fun actionCandidates(plan: FloorPlan, kind: String, id: String, action: String): List<Candidate> {
        val raw = when (kind) {
            "room" -> roomCandidates(plan, id, action.uppercase())
            "opening" -> openingCandidates(plan, id, action.uppercase())
            "wall" -> wallCandidates(plan, id, action.uppercase())
            else -> emptyList()
        }
        return raw.distinctBy { signature(it.plan) }
            .sortedByDescending { it.score }
            .take(4)
    }

    /** Live finger-drag preview. No state is committed here. */
    fun drag(plan: FloorPlan, kind: String, id: String, deltaXPct: Float, deltaYPct: Float): Candidate? {
        if (abs(deltaXPct) + abs(deltaYPct) < .12f) return null
        val next = when (kind) {
            "room" -> translateRoom(plan, id, deltaXPct, deltaYPct)
            "opening" -> moveOpeningToward(plan, id, deltaXPct, deltaYPct)
            "wall" -> moveWallByDrag(plan, id, deltaXPct, deltaYPct)
            else -> null
        } ?: return null
        return buildCandidate(plan, next, "معاينة السحب", "المحرك حرّك العناصر المرتبطة مع الحفاظ على القيود المقروءة.")
    }

    fun toProposal(current: FloorPlan, candidate: Candidate): PlanProposal {
        val geometry = verify(current, candidate.plan)
        if (!geometry.feasible) return rejected(geometry.errors.joinToString(". "))
        val objections = candidate.review.objections
        val message = when {
            objections.isNotEmpty() -> objections.joinToString(" ")
            candidate.review.notes.isNotEmpty() -> candidate.review.notes.first()
            geometry.warnings.isNotEmpty() -> "التعديل ممكن، لكن توجد ملاحظة قراءة: ${geometry.warnings.first()}"
            else -> ""
        }
        return PlanProposal(
            message = message,
            updatedPlan = candidate.plan,
            changes = candidate.changes,
            requiresConfirmation = true,
            confidence = (96 - objections.size * 8 - candidate.review.notes.size * 2).coerceIn(58, 96)
        )
    }

    fun moveRoom(plan: FloorPlan, roomId: String, deltaXPct: Float, deltaYPct: Float): PlanProposal {
        val next = translateRoom(plan, roomId, deltaXPct, deltaYPct)
            ?: return rejected("لا يمكن نقل الغرفة إلى هذا الموقع دون كسر القيود")
        return toProposal(plan, buildCandidate(plan, next, "نقل الغرفة", "نقل مباشر مع تحديث الحدود المشتركة"))
    }

    fun resizeRoom(plan: FloorPlan, roomId: String, deltaWidthPct: Float, deltaHeightPct: Float): PlanProposal {
        val room = plan.rooms.firstOrNull { it.id == roomId } ?: return rejected("لم أجد الغرفة المطلوبة")
        if (room.locked) return rejected("الغرفة «${room.name}» مقفلة")
        if (plan.walls.isNotEmpty()) return rejected("غيّر حجم الغرفة من أحد حدودها حتى تتحدث الجدران والغرف المجاورة بشكل صحيح")
        val fresh = room.copy(
            width = snap((room.width + deltaWidthPct).coerceIn(2f, 100f - room.x)),
            height = snap((room.height + deltaHeightPct).coerceIn(2f, 100f - room.y))
        ).let { it.copy(areaM2 = scaledArea(room, it.width, it.height)) }
        val next = plan.copy(rooms = plan.rooms.map { if (it.id == roomId) fresh else it })
        val check = verify(plan, next)
        if (!check.feasible) return rejected(check.errors.joinToString(". "))
        return toProposal(plan, buildCandidate(plan, next, "تغيير الحجم", "تغيير مباشر مع فحص الأثر"))
    }

    fun moveOpening(plan: FloorPlan, openingId: String, targetX: Float, targetY: Float): PlanProposal {
        val opening = plan.openings.firstOrNull { it.id == openingId } ?: return rejected("لم أجد الباب أو النافذة المطلوبة")
        if (opening.locked) return rejected("العنصر ${opening.id} مقفل")
        val wall = opening.wallId?.let { id -> plan.walls.firstOrNull { it.id == id } }
            ?: return rejected("الفتحة غير مرتبطة بجدار موثوق")
        val t = parameter(targetX, targetY, wall)
        val next = moveOpeningToParameter(plan, openingId, t) ?: return rejected("الموضع الجديد غير صالح")
        return toProposal(plan, buildCandidate(plan, next, "نقل الفتحة", "نقل مع Snap وفحص الاتصال"))
    }

    private fun roomCandidates(plan: FloorPlan, roomId: String, action: String): List<Candidate> {
        val room = plan.rooms.firstOrNull { it.id == roomId } ?: return emptyList()
        if (room.locked) return emptyList()
        val plans: List<Triple<String, FloorPlan, String>> = when (action) {
            "MOVE" -> listOfNotNull(
                translateRoom(plan, roomId, 4f, 0f)?.let { Triple("يمين", it, "نقل الغرفة إلى اليمين مع تحديث حدودها المشتركة") },
                translateRoom(plan, roomId, -4f, 0f)?.let { Triple("يسار", it, "نقل الغرفة إلى اليسار مع تحديث حدودها المشتركة") },
                translateRoom(plan, roomId, 0f, 4f)?.let { Triple("أسفل", it, "نقل الغرفة إلى الأسفل مع تحديث حدودها المشتركة") },
                translateRoom(plan, roomId, 0f, -4f)?.let { Triple("أعلى", it, "نقل الغرفة إلى الأعلى مع تحديث حدودها المشتركة") }
            )
            "EXPAND" -> resizeCandidates(plan, room, true)
            "SHRINK" -> resizeCandidates(plan, room, false)
            else -> emptyList()
        }
        return plans.mapNotNull { (title, next, reason) ->
            val check = verify(plan, next)
            if (!check.feasible) null else buildCandidate(plan, next, title, reason)
        }
    }

    private fun resizeCandidates(plan: FloorPlan, room: Room, expand: Boolean): List<Triple<String, FloorPlan, String>> {
        val step = 3.2f
        return Edge.entries.mapNotNull { edge ->
            val delta = when (edge) {
                Edge.LEFT -> if (expand) -step else step
                Edge.RIGHT -> if (expand) step else -step
                Edge.TOP -> if (expand) -step else step
                Edge.BOTTOM -> if (expand) step else -step
            }
            val wall = boundaryWall(plan, room, edge)
            val next = if (wall != null) {
                when (edge) {
                    Edge.LEFT, Edge.RIGHT -> shiftWall(plan, wall.id, delta, 0f)
                    Edge.TOP, Edge.BOTTOM -> shiftWall(plan, wall.id, 0f, delta)
                }
            } else if (plan.walls.isEmpty()) {
                resizeRoomRect(plan, room.id, edge, delta)
            } else null
            next?.let {
                val check = verify(plan, it)
                if (!check.feasible) return@mapNotNull null
                val label = when (edge) {
                    Edge.LEFT -> "من اليسار"
                    Edge.RIGHT -> "من اليمين"
                    Edge.TOP -> "من الأعلى"
                    Edge.BOTTOM -> "من الأسفل"
                }
                Triple(label, it, if (expand) "تكبير ${room.name} $label مع تعديل الحد المشترك" else "تصغير ${room.name} $label واستثمار المساحة المحررة")
            }
        }
    }

    private fun openingCandidates(plan: FloorPlan, openingId: String, action: String): List<Candidate> {
        val opening = plan.openings.firstOrNull { it.id == openingId } ?: return emptyList()
        if (opening.locked || action != "MOVE") return emptyList()
        val wall = opening.wallId?.let { id -> plan.walls.firstOrNull { it.id == id } } ?: return emptyList()
        val t = parameter(opening.x, opening.y, wall)
        return listOf(-.22f, -.12f, .12f, .22f).mapNotNull { dt ->
            val next = moveOpeningToParameter(plan, openingId, (t + dt).coerceIn(.08f, .92f)) ?: return@mapNotNull null
            val direction = if (dt < 0) "جهة بداية الجدار" else "جهة نهاية الجدار"
            val check = verify(plan, next)
            if (!check.feasible) null else buildCandidate(plan, next, direction, "نقل الفتحة على نفس الجدار مع Snap وإعادة قراءة اتصالها بالفراغات")
        }
    }

    private fun wallCandidates(plan: FloorPlan, wallId: String, action: String): List<Candidate> {
        val wall = plan.walls.firstOrNull { it.id == wallId } ?: return emptyList()
        if (wall.locked || action != "MOVE" || wall.kind == "external" || wall.confidence < 65) return emptyList()
        val vertical = abs(wall.start.x - wall.end.x) <= 1.1f
        val horizontal = abs(wall.start.y - wall.end.y) <= 1.1f
        if (!vertical && !horizontal) return emptyList()
        val step = 2.8f
        val variants = listOfNotNull(
            (if (vertical) shiftWall(plan, wallId, -step, 0f) else shiftWall(plan, wallId, 0f, -step))?.let { Triple("بديل A", it, "تحريك الجدار في الاتجاه الأول مع تحديث الغرف والفتحات المرتبطة") },
            (if (vertical) shiftWall(plan, wallId, step, 0f) else shiftWall(plan, wallId, 0f, step))?.let { Triple("بديل B", it, "تحريك الجدار في الاتجاه المقابل مع تحديث الغرف والفتحات المرتبطة") }
        )
        return variants.mapNotNull { (title, next, reason) ->
            val check = verify(plan, next)
            if (!check.feasible) null else buildCandidate(plan, next, title, reason)
        }
    }

    private fun translateRoom(plan: FloorPlan, roomId: String, dx: Float, dy: Float): FloorPlan? {
        val room = plan.rooms.firstOrNull { it.id == roomId } ?: return null
        if (room.locked) return null
        if (plan.walls.isEmpty()) {
            val nx = snap((room.x + dx).coerceIn(0f, 100f - room.width))
            val ny = snap((room.y + dy).coerceIn(0f, 100f - room.height))
            return plan.copy(rooms = plan.rooms.map { if (it.id == roomId) it.copy(x = nx, y = ny) else it })
        }

        val left = boundaryWall(plan, room, Edge.LEFT) ?: return null
        val right = boundaryWall(plan, room, Edge.RIGHT) ?: return null
        val top = boundaryWall(plan, room, Edge.TOP) ?: return null
        val bottom = boundaryWall(plan, room, Edge.BOTTOM) ?: return null
        if (listOf(left, right, top, bottom).any { it.kind == "external" || it.locked || it.confidence < 65 }) return null

        var next = plan
        if (abs(dx) > .05f) {
            next = shiftWall(next, left.id, dx, 0f) ?: return null
            next = shiftWall(next, right.id, dx, 0f) ?: return null
        }
        if (abs(dy) > .05f) {
            next = shiftWall(next, top.id, 0f, dy) ?: return null
            next = shiftWall(next, bottom.id, 0f, dy) ?: return null
        }
        return next.takeIf { verify(plan, it).feasible }
    }

    private fun moveOpeningToward(plan: FloorPlan, openingId: String, dx: Float, dy: Float): FloorPlan? {
        val opening = plan.openings.firstOrNull { it.id == openingId } ?: return null
        if (opening.locked) return null
        val wall = opening.wallId?.let { id -> plan.walls.firstOrNull { it.id == id } } ?: return null
        val desiredX = opening.x + dx
        val desiredY = opening.y + dy
        return moveOpeningToParameter(plan, openingId, parameter(desiredX, desiredY, wall))
    }

    private fun moveOpeningToParameter(plan: FloorPlan, openingId: String, tWanted: Float): FloorPlan? {
        val opening = plan.openings.firstOrNull { it.id == openingId } ?: return null
        if (opening.locked) return null
        val wall = opening.wallId?.let { id -> plan.walls.firstOrNull { it.id == id } } ?: return null
        if (wall.locked) return null
        val trials = listOf(0f, -.04f, .04f, -.08f, .08f, -.12f, .12f)
        val chosen = trials.map { (tWanted + it).coerceIn(.08f, .92f) }.firstOrNull { t ->
            val p = pointOn(wall, t)
            plan.openings.filter { it.id != opening.id && it.wallId == wall.id }.none { other ->
                hypot((other.x - p.x).toDouble(), (other.y - p.y).toDouble()) < max(2.4, ((opening.width + other.width) * .48).toDouble())
            }
        } ?: return null
        val p = pointOn(wall, chosen)
        val rotation = Math.toDegrees(atan2((wall.end.y - wall.start.y).toDouble(), (wall.end.x - wall.start.x).toDouble())).toFloat()
        val linked = nearRoomIds(plan, p.x, p.y)
        val next = plan.copy(openings = plan.openings.map {
            if (it.id == opening.id) it.copy(x = snap(p.x), y = snap(p.y), rotationDeg = rotation, connectsRoomIds = linked) else it
        })
        return next.takeIf { verify(plan, it).feasible }
    }

    private fun moveWallByDrag(plan: FloorPlan, wallId: String, dx: Float, dy: Float): FloorPlan? {
        val wall = plan.walls.firstOrNull { it.id == wallId } ?: return null
        if (wall.locked || wall.kind == "external" || wall.confidence < 65) return null
        val vertical = abs(wall.start.x - wall.end.x) <= 1.1f
        val horizontal = abs(wall.start.y - wall.end.y) <= 1.1f
        return when {
            vertical -> shiftWall(plan, wallId, dx, 0f)
            horizontal -> shiftWall(plan, wallId, 0f, dy)
            else -> null
        }
    }

    /**
     * Move an internal orthogonal wall and update the room faces and openings attached to it.
     * If no room face can be associated confidently, the move is refused.
     */
    private fun shiftWall(plan: FloorPlan, wallId: String, dxRaw: Float, dyRaw: Float): FloorPlan? {
        val wall = plan.walls.firstOrNull { it.id == wallId } ?: return null
        if (wall.locked || wall.kind == "external" || wall.confidence < 65) return null
        val vertical = abs(wall.start.x - wall.end.x) <= 1.1f
        val horizontal = abs(wall.start.y - wall.end.y) <= 1.1f
        if (!vertical && !horizontal) return null

        val dx = if (vertical) snapDelta(dxRaw) else 0f
        val dy = if (horizontal) snapDelta(dyRaw) else 0f
        if (abs(dx) + abs(dy) < .1f) return null

        val moved = wall.copy(
            start = PlanPoint(wall.start.x + dx, wall.start.y + dy),
            end = PlanPoint(wall.end.x + dx, wall.end.y + dy)
        )
        if (listOf(moved.start.x, moved.start.y, moved.end.x, moved.end.y).any { it !in 0f..100f }) return null

        val changedRooms = mutableMapOf<String, Room>()
        plan.rooms.forEach { room ->
            var fresh = room
            if (vertical && spansOverlap(room.y, room.y + room.height, wall.start.y, wall.end.y) >= min(3f, room.height * .35f)) {
                val x = wall.start.x
                when {
                    abs(room.x + room.width - x) <= 1.8f -> fresh = resizeRight(room, dx)
                    abs(room.x - x) <= 1.8f -> fresh = resizeLeft(room, dx)
                }
            } else if (horizontal && spansOverlap(room.x, room.x + room.width, wall.start.x, wall.end.x) >= min(3f, room.width * .35f)) {
                val y = wall.start.y
                when {
                    abs(room.y + room.height - y) <= 1.8f -> fresh = resizeBottom(room, dy)
                    abs(room.y - y) <= 1.8f -> fresh = resizeTop(room, dy)
                }
            }
            if (fresh != room) {
                if (room.locked || fresh.width < 1f || fresh.height < 1f || fresh.x < 0f || fresh.y < 0f || fresh.x + fresh.width > 100f || fresh.y + fresh.height > 100f) return null
                changedRooms[room.id] = fresh
            }
        }
        if (changedRooms.isEmpty()) return null

        var next = plan.copy(
            rooms = plan.rooms.map { changedRooms[it.id] ?: it },
            walls = plan.walls.map { if (it.id == wall.id) moved else it },
            openings = plan.openings.map { o -> if (o.wallId == wall.id) o.copy(x = o.x + dx, y = o.y + dy) else o }
        )
        next = next.copy(openings = next.openings.map { o ->
            if (o.wallId == wall.id) o.copy(connectsRoomIds = nearRoomIds(next, o.x, o.y)) else o
        })
        return next.takeIf { verify(plan, it).feasible }
    }

    private fun resizeRoomRect(plan: FloorPlan, roomId: String, edge: Edge, delta: Float): FloorPlan? {
        val room = plan.rooms.firstOrNull { it.id == roomId } ?: return null
        if (room.locked) return null
        val fresh = when (edge) {
            Edge.LEFT -> resizeLeft(room, delta)
            Edge.RIGHT -> resizeRight(room, delta)
            Edge.TOP -> resizeTop(room, delta)
            Edge.BOTTOM -> resizeBottom(room, delta)
        }
        if (fresh.width < 1f || fresh.height < 1f || fresh.x < 0f || fresh.y < 0f || fresh.x + fresh.width > 100f || fresh.y + fresh.height > 100f) return null
        return plan.copy(rooms = plan.rooms.map { if (it.id == roomId) fresh else it })
    }

    private fun resizeLeft(room: Room, delta: Float): Room {
        val newWidth = room.width - delta
        return room.copy(x = room.x + delta, width = newWidth, areaM2 = scaledArea(room, newWidth, room.height))
    }

    private fun resizeRight(room: Room, delta: Float): Room {
        val newWidth = room.width + delta
        return room.copy(width = newWidth, areaM2 = scaledArea(room, newWidth, room.height))
    }

    private fun resizeTop(room: Room, delta: Float): Room {
        val newHeight = room.height - delta
        return room.copy(y = room.y + delta, height = newHeight, areaM2 = scaledArea(room, room.width, newHeight))
    }

    private fun resizeBottom(room: Room, delta: Float): Room {
        val newHeight = room.height + delta
        return room.copy(height = newHeight, areaM2 = scaledArea(room, room.width, newHeight))
    }

    private fun scaledArea(room: Room, width: Float, height: Float): Double {
        if (room.areaM2 <= 0 || room.width <= .01f || room.height <= .01f) return room.areaM2
        val ratio = (width * height / (room.width * room.height)).toDouble()
        return (room.areaM2 * ratio).coerceAtLeast(0.0)
    }

    private fun boundaryWall(plan: FloorPlan, room: Room, edge: Edge): Wall? {
        val target = when (edge) {
            Edge.LEFT -> room.x
            Edge.RIGHT -> room.x + room.width
            Edge.TOP -> room.y
            Edge.BOTTOM -> room.y + room.height
        }
        val verticalEdge = edge == Edge.LEFT || edge == Edge.RIGHT
        return plan.walls.mapNotNull { wall ->
            val vertical = abs(wall.start.x - wall.end.x) <= 1.1f
            val horizontal = abs(wall.start.y - wall.end.y) <= 1.1f
            if ((verticalEdge && !vertical) || (!verticalEdge && !horizontal)) return@mapNotNull null
            val distance = if (verticalEdge) abs(wall.start.x - target) else abs(wall.start.y - target)
            if (distance > 2.0f) return@mapNotNull null
            val overlap = if (verticalEdge) spansOverlap(room.y, room.y + room.height, wall.start.y, wall.end.y)
            else spansOverlap(room.x, room.x + room.width, wall.start.x, wall.end.x)
            val needed = if (verticalEdge) min(3f, room.height * .35f) else min(3f, room.width * .35f)
            if (overlap < needed) null else wall to (distance - overlap * .02f)
        }.minByOrNull { it.second }?.first
    }

    private fun nearRoomIds(plan: FloorPlan, x: Float, y: Float): List<String> = plan.rooms
        .map { it to pointRectDistance(x, y, it) }
        .filter { it.second <= 1.8f }
        .sortedBy { it.second }
        .take(2)
        .map { it.first.id }

    private fun pointRectDistance(px: Float, py: Float, r: Room): Float {
        val dx = max(max(r.x - px, 0f), px - (r.x + r.width))
        val dy = max(max(r.y - py, 0f), py - (r.y + r.height))
        return hypot(dx.toDouble(), dy.toDouble()).toFloat()
    }

    private fun parameter(x: Float, y: Float, wall: Wall): Float {
        val dx = wall.end.x - wall.start.x
        val dy = wall.end.y - wall.start.y
        val den = dx * dx + dy * dy
        if (den < .0001f) return .5f
        return (((x - wall.start.x) * dx + (y - wall.start.y) * dy) / den).coerceIn(0f, 1f)
    }

    private fun pointOn(wall: Wall, t: Float) = PlanPoint(
        wall.start.x + (wall.end.x - wall.start.x) * t,
        wall.start.y + (wall.end.y - wall.start.y) * t
    )

    private fun spansOverlap(a0: Float, a1: Float, b0: Float, b1: Float): Float {
        val lowA = min(a0, a1); val highA = max(a0, a1)
        val lowB = min(b0, b1); val highB = max(b0, b1)
        return (min(highA, highB) - max(lowA, lowB)).coerceAtLeast(0f)
    }

    private fun buildCandidate(current: FloorPlan, next: FloorPlan, title: String, reason: String): Candidate {
        val review = ArchitecturalEngine.architecturalReview(current, next)
        val check = verify(current, next)
        val before = ArchitecturalEngine.score(current)
        val after = ArchitecturalEngine.score(next)
        val delta = (after.overall - before.overall).coerceIn(-20, 20)
        val score = (88 + delta - review.objections.size * 14 - review.notes.size * 2 - check.warnings.size.coerceAtMost(3) * 2).coerceIn(15, 99)
        return Candidate(title, reason, next, score, review, diff(current, next), check.warnings)
    }

    private fun diff(current: FloorPlan, next: FloorPlan): List<PlanChange> {
        val changes = mutableListOf<PlanChange>()
        val nextRooms = next.rooms.associateBy { it.id }
        current.rooms.forEach { old ->
            val fresh = nextRooms[old.id] ?: return@forEach
            if (roomChanged(old, fresh)) {
                changes += PlanChange(old.id, old.name, "MOVE_RESIZE", old.areaM2.takeIf { it > 0 }, fresh.areaM2.takeIf { it > 0 }, "تحدثت حدود الغرفة والعناصر المشتركة هندسيًا")
            }
        }
        val nextOpenings = next.openings.associateBy { it.id }
        current.openings.forEach { old ->
            val fresh = nextOpenings[old.id] ?: return@forEach
            if (openingChanged(old, fresh)) {
                val label = if (old.type.lowercase().contains("window") || old.type.contains("ناف")) "نافذة ${old.id}" else "باب ${old.id}"
                changes += PlanChange(null, label, "MOVE_OPENING", null, null, "تم نقل الفتحة مع Snap وإعادة فحص اتصالها")
            }
        }
        val nextWalls = next.walls.associateBy { it.id }
        current.walls.forEach { old ->
            val fresh = nextWalls[old.id] ?: return@forEach
            if (wallChanged(old, fresh)) changes += PlanChange(null, "جدار ${old.id}", "MOVE_WALL", null, null, "تحرك الجدار وتحدثت الغرف والفتحات المرتبطة به")
        }
        return changes.distinctBy { "${it.action}:${it.roomId}:${it.roomName}" }
    }

    private fun parseTargetSize(text: String): TargetSize? {
        val normalized = normalizeDigits(text).replace('×', 'x').replace('X', 'x')
        val match = Regex("(\\d+(?:\\.\\d+)?)\\s*x\\s*(\\d+(?:\\.\\d+)?)").find(normalized) ?: return null
        val a = match.groupValues[1].toDoubleOrNull() ?: return null
        val b = match.groupValues[2].toDoubleOrNull() ?: return null
        if (a <= 0 || b <= 0 || a > 100 || b > 100) return null
        return TargetSize(a, b)
    }

    private fun normalizeDigits(text: String): String = buildString {
        text.forEach { c ->
            append(
                when (c) {
                    '٠', '۰' -> '0'; '١', '۱' -> '1'; '٢', '۲' -> '2'; '٣', '۳' -> '3'; '٤', '۴' -> '4'
                    '٥', '۵' -> '5'; '٦', '۶' -> '6'; '٧', '۷' -> '7'; '٨', '۸' -> '8'; '٩', '۹' -> '9'
                    else -> c
                }
            )
        }
    }

    private fun overlapPairs(plan: FloorPlan): Map<Pair<String, String>, Double> {
        val result = mutableMapOf<Pair<String, String>, Double>()
        for (i in plan.rooms.indices) for (j in i + 1 until plan.rooms.size) {
            val a = plan.rooms[i]
            val b = plan.rooms[j]
            val overlap = overlapRatio(a, b)
            if (overlap > 0) {
                val names = listOf(a.name, b.name).sorted()
                result[names[0] to names[1]] = overlap
            }
        }
        return result
    }

    private fun overlapRatio(a: Room, b: Room): Double {
        val left = max(a.x, b.x)
        val top = max(a.y, b.y)
        val right = min(a.x + a.width, b.x + b.width)
        val bottom = min(a.y + a.height, b.y + b.height)
        if (right <= left || bottom <= top) return 0.0
        val intersection = (right - left) * (bottom - top)
        val base = min(a.width * a.height, b.width * b.height).coerceAtLeast(0.01f)
        return (intersection / base).toDouble()
    }

    private fun roomChanged(a: Room, b: Room): Boolean =
        abs(a.x - b.x) > 0.1f || abs(a.y - b.y) > 0.1f || abs(a.width - b.width) > 0.1f || abs(a.height - b.height) > 0.1f

    private fun wallChanged(a: Wall, b: Wall): Boolean =
        abs(a.start.x - b.start.x) > 0.1f || abs(a.start.y - b.start.y) > 0.1f ||
            abs(a.end.x - b.end.x) > 0.1f || abs(a.end.y - b.end.y) > 0.1f

    private fun openingChanged(a: Opening, b: Opening): Boolean =
        abs(a.x - b.x) > 0.1f || abs(a.y - b.y) > 0.1f || a.wallId != b.wallId || abs(a.width - b.width) > 0.1f

    private fun snap(v: Float): Float = (round(v * 2f) / 2f).coerceIn(0f, 100f)
    private fun snapDelta(v: Float): Float = round(v * 2f) / 2f

    private fun pointToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Double {
        val dx = bx - ax; val dy = by - ay
        if (abs(dx) < .0001f && abs(dy) < .0001f) return hypot((px - ax).toDouble(), (py - ay).toDouble())
        val t = (((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
        val x = ax + t * dx; val y = ay + t * dy
        return hypot((px - x).toDouble(), (py - y).toDouble())
    }

    private fun signature(plan: FloorPlan): String = buildString {
        plan.rooms.forEach { append(it.id).append(':').append("%.1f".format(it.x)).append(',').append("%.1f".format(it.y)).append(',').append("%.1f".format(it.width)).append(',').append("%.1f".format(it.height)).append(';') }
        plan.openings.forEach { append(it.id).append('@').append("%.1f".format(it.x)).append(',').append("%.1f".format(it.y)).append(';') }
        plan.walls.forEach { append(it.id).append('#').append("%.1f".format(it.start.x)).append(',').append("%.1f".format(it.start.y)).append(';') }
    }

    private fun rejected(message: String) = PlanProposal(
        message = message,
        updatedPlan = null,
        requiresConfirmation = true,
        confidence = 100
    )

    private fun fmt(v: Double) = "%.1f".format(v)
}