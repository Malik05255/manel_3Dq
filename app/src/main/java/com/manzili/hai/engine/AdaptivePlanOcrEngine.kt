package com.manzili.hai.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

class AdaptivePlanOcrEngine(private val context: Context) {
    private val base = PlanTextOcrEngine(context)

    suspend fun readSpatial(uri: Uri, maxPdfPages: Int = 8): PlanTextOcrEngine.Result {
        val first = base.readSpatial(uri, maxPdfPages)
        if (!needsRecovery(first.lines)) return first
        val recovered = runCatching { highResolutionPass(uri, maxPdfPages) }.getOrNull() ?: return first
        val merged = dedupe(first.lines + recovered.lines)
        return PlanTextOcrEngine.Result(
            lines = merged.take(2600),
            pagesAnalyzed = maxOf(first.pagesAnalyzed, recovered.pagesAnalyzed),
            truncated = first.truncated || recovered.truncated || merged.size > 2600
        )
    }

    private fun needsRecovery(lines: List<PlanTextOcrEngine.SpatialLine>): Boolean {
        val numeric = lines.count { looksNumeric(it.text) }
        val edgeNumeric = lines.count {
            val cx = (it.leftPct + it.rightPct) / 2f
            (cx < 18f || cx > 82f || it.topPct < 15f || it.bottomPct > 85f) && looksNumeric(it.text)
        }
        return lines.size < 30 || numeric < 12 || edgeNumeric < 5
    }

    private suspend fun highResolutionPass(uri: Uri, maxPdfPages: Int): PlanTextOcrEngine.Result = withContext(Dispatchers.IO) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val type = context.contentResolver.getType(uri).orEmpty()
            if (type == "application/pdf") readPdf(uri, recognizer, maxPdfPages.coerceIn(1, 8))
            else readImage(uri, recognizer)
        } finally {
            recognizer.close()
        }
    }

    private suspend fun readImage(uri: Uri, recognizer: TextRecognizer): PlanTextOcrEngine.Result {
        val original = context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
            ?: return PlanTextOcrEngine.Result(emptyList(), 0, false)
        return try {
            val maxSide = max(original.width, original.height).coerceAtLeast(1)
            val target = 3800f
            val ratio = (target / maxSide).coerceAtLeast(1f)
            val hi = if (ratio > 1.03f) Bitmap.createScaledBitmap(
                original,
                (original.width * ratio).toInt().coerceAtLeast(1),
                (original.height * ratio).toInt().coerceAtLeast(1),
                true
            ) else original
            try {
                val all = recognizeFullAndTiles(recognizer, hi, 0)
                val lines = dedupe(all)
                PlanTextOcrEngine.Result(lines.take(1400), 1, lines.size > 1400)
            } finally {
                if (hi !== original) hi.recycle()
            }
        } finally {
            original.recycle()
        }
    }

    private suspend fun readPdf(uri: Uri, recognizer: TextRecognizer, maxPages: Int): PlanTextOcrEngine.Result {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: return PlanTextOcrEngine.Result(emptyList(), 0, false)
        return PdfRenderer(pfd).use { renderer ->
            val count = minOf(renderer.pageCount, maxPages)
            val all = mutableListOf<PlanTextOcrEngine.SpatialLine>()
            for (pageIndex in 0 until count) {
                renderer.openPage(pageIndex).use { page ->
                    val targetWidth = 3600
                    val ratio = targetWidth.toFloat() / page.width.coerceAtLeast(1)
                    val targetHeight = (page.height * ratio).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                    try {
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        all += recognizeFullAndTiles(recognizer, bitmap, pageIndex)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
            val lines = dedupe(all)
            PlanTextOcrEngine.Result(lines.take(2600), count, renderer.pageCount > count || lines.size > 2600)
        }
    }

    /**
     * Full-page OCR keeps context. Three overlapping samples on each axis then magnify
     * tiny dimensions/labels near walls and sheet edges without losing their page coordinates.
     */
    private suspend fun recognizeFullAndTiles(
        recognizer: TextRecognizer,
        bitmap: Bitmap,
        pageIndex: Int
    ): List<PlanTextOcrEngine.SpatialLine> {
        val out = mutableListOf<PlanTextOcrEngine.SpatialLine>()
        out += recognize(recognizer, bitmap, pageIndex, 0, 0, bitmap.width, bitmap.height, 76)

        val tileWidth = (bitmap.width * .46f).toInt().coerceIn(80, bitmap.width)
        val tileHeight = (bitmap.height * .46f).toInt().coerceIn(80, bitmap.height)
        val startsX = listOf(
            0,
            ((bitmap.width - tileWidth) / 2).coerceAtLeast(0),
            (bitmap.width - tileWidth).coerceAtLeast(0)
        ).distinct()
        val startsY = listOf(
            0,
            ((bitmap.height - tileHeight) / 2).coerceAtLeast(0),
            (bitmap.height - tileHeight).coerceAtLeast(0)
        ).distinct()

        for (top in startsY) {
            for (left in startsX) {
                val rect = Rect(
                    left,
                    top,
                    (left + tileWidth).coerceAtMost(bitmap.width),
                    (top + tileHeight).coerceAtMost(bitmap.height)
                )
                if (rect.width() < 80 || rect.height() < 80) continue
                val tile = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
                try {
                    out += recognize(
                        recognizer = recognizer,
                        bitmap = tile,
                        pageIndex = pageIndex,
                        originX = rect.left,
                        originY = rect.top,
                        fullWidth = bitmap.width,
                        fullHeight = bitmap.height,
                        confidence = 70
                    )
                } finally {
                    tile.recycle()
                }
            }
        }
        return out
    }

    private suspend fun recognize(
        recognizer: TextRecognizer,
        bitmap: Bitmap,
        pageIndex: Int,
        originX: Int,
        originY: Int,
        fullWidth: Int,
        fullHeight: Int,
        confidence: Int
    ): List<PlanTextOcrEngine.SpatialLine> = suspendCancellableCoroutine { continuation ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val fw = fullWidth.coerceAtLeast(1).toFloat()
                val fh = fullHeight.coerceAtLeast(1).toFloat()
                val lines = result.textBlocks.flatMap { block ->
                    block.lines.mapNotNull { line ->
                        val box = line.boundingBox ?: return@mapNotNull null
                        val text = line.text.trim()
                        if (text.isBlank()) return@mapNotNull null
                        PlanTextOcrEngine.SpatialLine(
                            text = text,
                            pageIndex = pageIndex,
                            leftPct = ((box.left + originX) / fw * 100f).coerceIn(0f, 100f),
                            topPct = ((box.top + originY) / fh * 100f).coerceIn(0f, 100f),
                            rightPct = ((box.right + originX) / fw * 100f).coerceIn(0f, 100f),
                            bottomPct = ((box.bottom + originY) / fh * 100f).coerceIn(0f, 100f),
                            confidence = confidence
                        )
                    }
                }
                if (continuation.isActive) continuation.resume(lines)
            }
            .addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
    }

    private fun looksNumeric(text: String): Boolean {
        val normalized = DimensionEvidenceEngine.normalizeDigits(text)
        return Regex(".*\\d+(?:[.,]\\d+)?(?:\\s*(?:m|m2|m²|م|سم|مم|°))?.*", RegexOption.IGNORE_CASE).matches(normalized)
    }

    private fun dedupe(lines: List<PlanTextOcrEngine.SpatialLine>): List<PlanTextOcrEngine.SpatialLine> = lines
        .sortedByDescending { it.confidence }
        .distinctBy {
            val cx = (((it.leftPct + it.rightPct) / 2f) * 2f).toInt()
            val cy = (((it.topPct + it.bottomPct) / 2f) * 2f).toInt()
            "${it.pageIndex}:$cx:$cy:${DimensionEvidenceEngine.normalizeDigits(it.text).trim().lowercase()}"
        }
}