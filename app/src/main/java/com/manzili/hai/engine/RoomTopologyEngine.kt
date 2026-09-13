package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.PlanPoint
import com.manzili.hai.model.Room
import com.manzili.hai.model.Wall
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Local fallback for imported plans where wall recovery succeeds but room extraction does not.
 * It rasterizes the recovered wall topology, temporarily bridges door-sized gaps, flood-fills the
 * exterior, then converts enclosed components into reviewable room candidates. Existing visual or
 * Deep Parser rooms always win; this engine only fills genuinely missing enclosed spaces.
 */
object RoomTopologyEngine {
    data class Result(
        val plan: FloorPlan,
        val inferredRooms: Int,
        val candidateRooms: Int
    )

    private const val GRID = 180
    private const val MAX_LOGICAL_GAP_PCT = 7.5f

    fun recover(input: FloorPlan): Result {
        if (input.walls.size < 4) return Result(input, 0, 0)

        val candidates = inferRooms(input)
        if (candidates.isEmpty()) return Result(input, 0, 0)

        if (input.rooms.isEmpty()) {
            val plan = input.copy(
                rooms = candidates,
                observations = (input.observations +
                    "استعاد HAI ${candidates.size} مساحة مغلقة محليًا من شبكة الجدران بعد إغلاق فجوات الأبواب مؤقتًا للحساب فقط.").distinct(),
                uncertainties = (input.uncertainties +
                    "الغرف المستعادة من طوبولوجيا الجدران تحتاج مقارنة بصرية قبل الاعتماد النهائي، خصوصًا المساحات المفتوحة والممرات.").distinct()
            )
            return Result(plan, candidates.size, candidates.size)
        }

        val missing = candidates.filter { inferred ->
            input.rooms.none { existing -> overlapsExisting(inferred, existing) }
        }.filter { it.confidence >= 78 }

        if (missing.isEmpty()) return Result(input, 0, candidates.size)
        val capped = missing.take(20)
        val plan = input.copy(
            rooms = input.rooms + capped,
            observations = (input.observations +
                "أضاف HAI ${capped.size} مساحة مغلقة كانت مفقودة من القراءة المرئية بالاعتماد على شبكة الجدران.").distinct(),
            uncertainties = (input.uncertainties +
                "بعض المساحات أضيفت كاستنتاج طوبولوجي؛ راجع أسماءها وحدودها قبل الاعتماد.").distinct()
        )
        return Result(plan, capped.size, candidates.size)
    }

    internal fun inferRooms(input: FloorPlan): List<Room> {
        val walls = logicalWalls(input.walls)
        if (walls.size < 4) return emptyList()

        val blocked = Array(GRID) { BooleanArray(GRID) }
        walls.forEach { drawWall(blocked, it) }
        closeSmallPixelGaps(blocked)

        val outside = Array(GRID) { BooleanArray(GRID) }
        val queueX = IntArray(GRID * GRID)
        val queueY = IntArray(GRID * GRID)
        var head = 0
        var tail = 0

        fun enqueue(x: Int, y: Int) {
            if (x !in 0 until GRID || y !in 0 until GRID) return
            if (blocked[y][x] || outside[y][x]) return
            outside[y][x] = true
            queueX[tail] = x
            queueY[tail] = y
            tail++
        }

        for (i in 0 until GRID) {
            enqueue(i, 0); enqueue(i, GRID - 1); enqueue(0, i); enqueue(GRID - 1, i)
        }
        val dx = intArrayOf(1, -1, 0, 0)
        val dy = intArrayOf(0, 0, 1, -1)
        while (head < tail) {
            val x = queueX[head]
            val y = queueY[head]
            head++
            for (k in 0..3) enqueue(x + dx[k], y + dy[k])
        }

        val seen = Array(GRID) { BooleanArray(GRID) }
        val components = mutableListOf<Component>()
        val minCells = max(36, (GRID * GRID * .0015).toInt())
        val maxCells = (GRID * GRID * .48).toInt()

        for (y0 in 1 until GRID - 1) {
            for (x0 in 1 until GRID - 1) {
                if (blocked[y0][x0] || outside[y0][x0] || seen[y0][x0]) continue
                head = 0
                tail = 0
                queueX[tail] = x0
                queueY[tail] = y0
                tail++
                seen[y0][x0] = true
                var count = 0
                var minX = x0
                var maxX = x0
                var minY = y0
                var maxY = y0

                while (head < tail) {
                    val x = queueX[head]
                    val y = queueY[head]
                    head++
                    count++
                    minX = min(minX, x); maxX = max(maxX, x)
                    minY = min(minY, y); maxY = max(maxY, y)
                    for (k in 0..3) {
                        val nx = x + dx[k]
                        val ny = y + dy[k]
                        if (nx !in 1 until GRID - 1 || ny !in 1 until GRID - 1) continue
                        if (blocked[ny][nx] || outside[ny][nx] || seen[ny][nx]) continue
                        seen[ny][nx] = true
                        queueX[tail] = nx
                        queueY[tail] = ny
                        tail++
                    }
                }

                if (count !in minCells..maxCells) continue
                val widthPct = (maxX - minX + 1) * 100f / GRID
                val heightPct = (maxY - minY + 1) * 100f / GRID
                if (widthPct < 2.2f || heightPct < 2.2f) continue
                val bboxCells = (maxX - minX + 1) * (maxY - minY + 1)
                val fill = count.toFloat() / bboxCells.coerceAtLeast(1)
                if (fill < .18f) continue
                components += Component(count, minX, minY, maxX, maxY, fill)
            }
        }

        if (components.isEmpty()) return emptyList()
        val wallConfidence = input.walls.map { it.confidence }.average().takeIf { !it.isNaN() } ?: 70.0
        val buildingArea = input.widthM?.let { w -> input.heightM?.let { h -> w * h } }

        return components
            .sortedWith(compareBy<Component> { it.minY }.thenBy { it.minX })
            .take(40)
            .mapIndexed { index, c ->
                val x = c.minX * 100f / GRID
                val y = c.minY * 100f / GRID
                val width = (c.maxX - c.minX + 1) * 100f / GRID
                val height = (c.maxY - c.minY + 1) * 100f / GRID
                val confidence = (wallConfidence * .72 + c.fill * 18.0 + 6.0).roundToInt().coerceIn(68, 90)
                val area = buildingArea?.let { it * (c.cells.toDouble() / (GRID * GRID).toDouble()) } ?: 0.0
                Room(
                    id = "topology-room-${index + 1}",
                    name = "مساحة مكتشفة ${index + 1}",
                    type = "topology-inferred",
                    x = x,
                    y = y,
                    width = width,
                    height = height,
                    areaM2 = area,
                    confidence = confidence,
                    polygon = listOf(
                        PlanPoint(x, y),
                        PlanPoint((x + width).coerceAtMost(100f), y),
                        PlanPoint((x + width).coerceAtMost(100f), (y + height).coerceAtMost(100f)),
                        PlanPoint(x, (y + height).coerceAtMost(100f))
                    )
                )
            }
    }

    private data class Component(
        val cells: Int,
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int,
        val fill: Float
    )

    private fun logicalWalls(input: List<Wall>): List<Wall> {
        val horizontal = input.filter { abs(it.start.y - it.end.y) <= 1.7f }
        val vertical = input.filter { abs(it.start.x - it.end.x) <= 1.7f }
        val diagonal = input.filterNot { it in horizontal || it in vertical }
        return mergeAxis(horizontal, horizontalAxis = true) + mergeAxis(vertical, horizontalAxis = false) + diagonal
    }

    private fun mergeAxis(walls: List<Wall>, horizontalAxis: Boolean): List<Wall> {
        if (walls.isEmpty()) return emptyList()
        val groups = walls.groupBy { wall ->
            val cross = if (horizontalAxis) (wall.start.y + wall.end.y) / 2f else (wall.start.x + wall.end.x) / 2f
            (cross * 2f).roundToInt()
        }
        val out = mutableListOf<Wall>()
        groups.values.forEachIndexed { groupIndex, group ->
            val spans = group.map { wall ->
                val a = if (horizontalAxis) min(wall.start.x, wall.end.x) else min(wall.start.y, wall.end.y)
                val b = if (horizontalAxis) max(wall.start.x, wall.end.x) else max(wall.start.y, wall.end.y)
                Triple(a, b, wall)
            }.sortedBy { it.first }
            var start = spans.first().first
            var end = spans.first().second
            var confidence = spans.first().third.confidence
            val crossValues = mutableListOf<Float>().apply {
                val w = spans.first().third
                add(if (horizontalAxis) (w.start.y + w.end.y) / 2f else (w.start.x + w.end.x) / 2f)
            }
            var mergedIndex = 0

            fun flush() {
                val cross = crossValues.average().toFloat()
                val wall = if (horizontalAxis) {
                    Wall("topology-h-$groupIndex-${mergedIndex++}", PlanPoint(start, cross), PlanPoint(end, cross), kind = "topology-logical", confidence = confidence)
                } else {
                    Wall("topology-v-$groupIndex-${mergedIndex++}", PlanPoint(cross, start), PlanPoint(cross, end), kind = "topology-logical", confidence = confidence)
                }
                if (wallLength(wall) >= 1.2f) out += wall
            }

            for (i in 1 until spans.size) {
                val s = spans[i]
                val gap = s.first - end
                if (gap <= MAX_LOGICAL_GAP_PCT) {
                    end = max(end, s.second)
                    confidence = max(confidence, s.third.confidence)
                    val w = s.third
                    crossValues += if (horizontalAxis) (w.start.y + w.end.y) / 2f else (w.start.x + w.end.x) / 2f
                } else {
                    flush()
                    start = s.first
                    end = s.second
                    confidence = s.third.confidence
                    crossValues.clear()
                    val w = s.third
                    crossValues += if (horizontalAxis) (w.start.y + w.end.y) / 2f else (w.start.x + w.end.x) / 2f
                }
            }
            flush()
        }
        return out
    }

    private fun drawWall(grid: Array<BooleanArray>, wall: Wall) {
        val x1 = pctToGrid(wall.start.x)
        val y1 = pctToGrid(wall.start.y)
        val x2 = pctToGrid(wall.end.x)
        val y2 = pctToGrid(wall.end.y)
        val steps = max(abs(x2 - x1), abs(y2 - y1)).coerceAtLeast(1)
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val x = (x1 + (x2 - x1) * t).roundToInt().coerceIn(0, GRID - 1)
            val y = (y1 + (y2 - y1) * t).roundToInt().coerceIn(0, GRID - 1)
            mark(grid, x, y, radius = 1)
        }
    }

    /** Closing is only for topology/counting; it never changes the persisted wall geometry. */
    private fun closeSmallPixelGaps(grid: Array<BooleanArray>) {
        val maxGap = ceil(GRID * .055).toInt().coerceAtLeast(3)
        repeat(2) {
            for (y in 0 until GRID) {
                var last = -1
                for (x in 0 until GRID) {
                    if (!grid[y][x]) continue
                    if (last >= 0 && x - last - 1 in 1..maxGap) {
                        for (fill in last..x) grid[y][fill] = true
                    }
                    last = x
                }
            }
            for (x in 0 until GRID) {
                var last = -1
                for (y in 0 until GRID) {
                    if (!grid[y][x]) continue
                    if (last >= 0 && y - last - 1 in 1..maxGap) {
                        for (fill in last..y) grid[fill][x] = true
                    }
                    last = y
                }
            }
        }
    }

    private fun mark(grid: Array<BooleanArray>, x: Int, y: Int, radius: Int) {
        for (yy in (y - radius).coerceAtLeast(0)..(y + radius).coerceAtMost(GRID - 1)) {
            for (xx in (x - radius).coerceAtLeast(0)..(x + radius).coerceAtMost(GRID - 1)) grid[yy][xx] = true
        }
    }

    private fun pctToGrid(value: Float): Int = (value.coerceIn(0f, 100f) / 100f * (GRID - 1)).roundToInt()

    private fun wallLength(wall: Wall): Float = hypot(
        (wall.end.x - wall.start.x).toDouble(),
        (wall.end.y - wall.start.y).toDouble()
    ).toFloat()

    private fun overlapsExisting(a: Room, b: Room): Boolean {
        val ax2 = a.x + a.width
        val ay2 = a.y + a.height
        val bx2 = b.x + b.width
        val by2 = b.y + b.height
        val ix = (min(ax2, bx2) - max(a.x, b.x)).coerceAtLeast(0f)
        val iy = (min(ay2, by2) - max(a.y, b.y)).coerceAtLeast(0f)
        val intersection = ix * iy
        val aArea = (a.width * a.height).coerceAtLeast(.01f)
        val bArea = (b.width * b.height).coerceAtLeast(.01f)
        val smaller = min(aArea, bArea)
        val centerX = a.x + a.width / 2f
        val centerY = a.y + a.height / 2f
        val centerInside = centerX in b.x..bx2 && centerY in b.y..by2
        return centerInside || intersection / smaller >= .42f
    }
}
