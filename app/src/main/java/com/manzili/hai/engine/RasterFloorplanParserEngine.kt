package com.manzili.hai.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.manzili.hai.model.PlanPoint
import com.manzili.hai.model.Wall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight raster parser that detects long dark wall-like runs directly from pixels.
 * It is intentionally evidence-first: unmatched lines are not silently promoted to truth.
 */
class RasterFloorplanParserEngine(private val context: Context) {
    data class Result(
        val primaryWalls: List<Wall>,
        val pagesAnalyzed: Int,
        val confidence: Int,
        val notes: List<String>
    )

    suspend fun analyze(uri: Uri, maxPdfPages: Int = 4): Result = withContext(Dispatchers.IO) {
        val type = context.contentResolver.getType(uri).orEmpty()
        if (type == "application/pdf") analyzePdf(uri, maxPdfPages.coerceIn(1, 6)) else {
            val bitmap = context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
                ?: return@withContext Result(emptyList(), 0, 0, listOf("تعذر فك الصورة للـRaster parser"))
            val walls = detectWalls(bitmap, 0)
            Result(walls, 1, confidence(walls), listOf("Raster parser قرأ ${walls.size} خطًا جداريًا مرشحًا من الصفحة الأولى."))
        }
    }

    private fun analyzePdf(uri: Uri, maxPages: Int): Result {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: return Result(emptyList(), 0, 0, listOf("تعذر فتح PDF للـRaster parser"))
        return PdfRenderer(pfd).use { renderer ->
            if (renderer.pageCount == 0) return@use Result(emptyList(), 0, 0, listOf("PDF بلا صفحات"))
            val count = min(renderer.pageCount, maxPages)
            var firstPageWalls: List<Wall> = emptyList()
            val counts = mutableListOf<Int>()
            for (pageIndex in 0 until count) {
                renderer.openPage(pageIndex).use { page ->
                    val maxSide = 1400f
                    val scale = min(maxSide / page.width.coerceAtLeast(1), maxSide / page.height.coerceAtLeast(1))
                    val w = (page.width * scale).toInt().coerceAtLeast(1)
                    val h = (page.height * scale).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val walls = detectWalls(bitmap, pageIndex)
                    if (pageIndex == 0) firstPageWalls = walls
                    counts += walls.size
                    bitmap.recycle()
                }
            }
            Result(
                firstPageWalls,
                count,
                confidence(firstPageWalls),
                listOf("Raster parser فحص $count صفحة. مرشحات الخطوط حسب الصفحة: ${counts.joinToString("، ")}.")
            )
        }
    }

    private fun detectWalls(source: Bitmap, pageIndex: Int): List<Wall> {
        val bitmap = if (max(source.width, source.height) > 1500) {
            val ratio = 1500f / max(source.width, source.height)
            Bitmap.createScaledBitmap(source, (source.width * ratio).toInt().coerceAtLeast(1), (source.height * ratio).toInt().coerceAtLeast(1), true)
        } else source
        val w = bitmap.width
        val h = bitmap.height
        if (w < 80 || h < 80) return emptyList()
        val step = max(2, min(w, h) / 420)
        val minHorizontal = max(20, (w * .085f).toInt())
        val minVertical = max(20, (h * .085f).toInt())
        val raw = mutableListOf<Wall>()

        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                if (dark(bitmap.getPixel(x, y))) {
                    val start = x
                    var end = x
                    while (end + step < w && darkNeighborhood(bitmap, end + step, y, step, true)) end += step
                    if (end - start >= minHorizontal) raw += wall(pageIndex, raw.size, start, y, end, y, w, h, 69)
                    x = max(x + step, end + step)
                } else x += step
            }
            y += step
        }

        var x = 0
        while (x < w) {
            var yy = 0
            while (yy < h) {
                if (dark(bitmap.getPixel(x, yy))) {
                    val start = yy
                    var end = yy
                    while (end + step < h && darkNeighborhood(bitmap, x, end + step, step, false)) end += step
                    if (end - start >= minVertical) raw += wall(pageIndex, raw.size, x, start, x, end, w, h, 69)
                    yy = max(yy + step, end + step)
                } else yy += step
            }
            x += step
        }

        val merged = mutableListOf<Wall>()
        raw.sortedByDescending { lengthPct(it) }.forEach { candidate ->
            val duplicate = merged.any { similar(it, candidate) }
            if (!duplicate) merged += candidate
        }
        if (bitmap !== source) bitmap.recycle()
        return merged.take(180)
    }

    private fun darkNeighborhood(bitmap: Bitmap, x: Int, y: Int, r: Int, horizontal: Boolean): Boolean {
        val offsets = if (horizontal) listOf(-r, 0, r) else listOf(-r, 0, r)
        var hits = 0
        offsets.forEach { d ->
            val px = if (horizontal) x else (x + d).coerceIn(0, bitmap.width - 1)
            val py = if (horizontal) (y + d).coerceIn(0, bitmap.height - 1) else y
            if (dark(bitmap.getPixel(px.coerceIn(0, bitmap.width - 1), py.coerceIn(0, bitmap.height - 1)))) hits++
        }
        return hits >= 2
    }

    private fun dark(pixel: Int): Boolean {
        val luma = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
        return luma < 92
    }

    private fun wall(page: Int, index: Int, x1: Int, y1: Int, x2: Int, y2: Int, w: Int, h: Int, confidence: Int): Wall = Wall(
        id = "raster-p${page}-w$index",
        start = PlanPoint(x1.toFloat() / w * 100f, y1.toFloat() / h * 100f),
        end = PlanPoint(x2.toFloat() / w * 100f, y2.toFloat() / h * 100f),
        kind = "raster-evidence",
        confidence = confidence
    )

    private fun lengthPct(w: Wall): Float = abs(w.end.x - w.start.x) + abs(w.end.y - w.start.y)

    private fun similar(a: Wall, b: Wall): Boolean {
        val ah = abs(a.start.y - a.end.y) < 1.1f
        val bh = abs(b.start.y - b.end.y) < 1.1f
        if (ah != bh) return false
        return if (ah) {
            abs(a.start.y - b.start.y) <= 1.2f && overlap(a.start.x, a.end.x, b.start.x, b.end.x) >= 8f
        } else {
            abs(a.start.x - b.start.x) <= 1.2f && overlap(a.start.y, a.end.y, b.start.y, b.end.y) >= 8f
        }
    }

    private fun overlap(a1: Float, a2: Float, b1: Float, b2: Float): Float =
        (min(max(a1, a2), max(b1, b2)) - max(min(a1, a2), min(b1, b2))).coerceAtLeast(0f)

    private fun confidence(walls: List<Wall>): Int = when {
        walls.size >= 12 -> 78
        walls.size >= 6 -> 68
        walls.isNotEmpty() -> 55
        else -> 0
    }
}
