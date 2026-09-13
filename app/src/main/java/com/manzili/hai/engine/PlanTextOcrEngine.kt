package com.manzili.hai.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
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

/**
 * On-device OCR evidence. Every line keeps its page and normalized location.
 * Small architectural dimensions are deliberately upscaled, and the first page receives
 * an extra rotated pass only when vertical edge dimensions were not read in the normal pass.
 */
class PlanTextOcrEngine(private val context: Context) {
    data class SpatialLine(
        val text: String,
        val pageIndex: Int,
        val leftPct: Float,
        val topPct: Float,
        val rightPct: Float,
        val bottomPct: Float,
        val confidence: Int = 72
    ) {
        val widthPct: Float get() = (rightPct - leftPct).coerceAtLeast(0f)
        val heightPct: Float get() = (bottomPct - topPct).coerceAtLeast(0f)
    }

    data class Result(
        val lines: List<SpatialLine>,
        val pagesAnalyzed: Int,
        val truncated: Boolean
    )

    suspend fun read(uri: Uri): List<String> = readSpatial(uri).lines
        .map { it.text.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .take(240)

    suspend fun readSpatial(uri: Uri, maxPdfPages: Int = 5): Result = withContext(Dispatchers.IO) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val type = context.contentResolver.getType(uri).orEmpty()
            if (type == "application/pdf") readPdf(uri, recognizer, maxPdfPages.coerceIn(1, 8))
            else readImage(uri, recognizer)
        } finally {
            recognizer.close()
        }
    }

    private suspend fun readImage(uri: Uri, recognizer: TextRecognizer): Result {
        val original = context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
        if (original != null) {
            val bitmap = upscaleForDimensions(original)
            val full = recognize(recognizer, InputImage.fromBitmap(bitmap, 0), 0, bitmap.width, bitmap.height)
            val extra = if (needsVerticalDimensionPass(full)) {
                val cw = recognizeRotated(recognizer, bitmap, 0, clockwise = true)
                if (hasVerticalEdgeNumber(cw)) cw else cw + recognizeRotated(recognizer, bitmap, 0, clockwise = false)
            } else emptyList()
            if (bitmap !== original) bitmap.recycle()
            original.recycle()
            val lines = dedupe(full + extra)
            return Result(lines.take(700), 1, lines.size > 700)
        }

        val image = InputImage.fromFilePath(context, uri)
        val lines = recognize(recognizer, image, 0, image.width, image.height)
        return Result(lines.take(500), 1, lines.size > 500)
    }

    private suspend fun readPdf(uri: Uri, recognizer: TextRecognizer, maxPages: Int): Result {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("تعذر فتح PDF للـOCR")
        return PdfRenderer(pfd).use { renderer ->
            require(renderer.pageCount > 0) { "PDF بلا صفحات" }
            val count = minOf(renderer.pageCount, maxPages)
            val all = mutableListOf<SpatialLine>()
            for (pageIndex in 0 until count) {
                renderer.openPage(pageIndex).use { page ->
                    val targetWidth = 2600
                    val scale = targetWidth.toFloat() / page.width.coerceAtLeast(1)
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(targetWidth, height, Bitmap.Config.ARGB_8888)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val full = recognize(recognizer, InputImage.fromBitmap(bitmap, 0), pageIndex, bitmap.width, bitmap.height)
                    all += full
                    if (pageIndex == 0 && needsVerticalDimensionPass(full)) {
                        val cw = recognizeRotated(recognizer, bitmap, pageIndex, clockwise = true)
                        all += cw
                        if (!hasVerticalEdgeNumber(cw)) all += recognizeRotated(recognizer, bitmap, pageIndex, clockwise = false)
                    }
                    bitmap.recycle()
                }
            }
            val lines = dedupe(all)
            Result(lines.take(1500), count, renderer.pageCount > count || lines.size > 1500)
        }
    }

    private fun upscaleForDimensions(source: Bitmap): Bitmap {
        val maxSide = max(source.width, source.height)
        if (maxSide >= 2200) return source
        val ratio = 2400f / maxSide.coerceAtLeast(1)
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }

    private suspend fun recognize(
        recognizer: TextRecognizer,
        image: InputImage,
        pageIndex: Int,
        imageWidth: Int,
        imageHeight: Int
    ): List<SpatialLine> = suspendCancellableCoroutine { continuation ->
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val w = imageWidth.coerceAtLeast(1).toFloat()
                val h = imageHeight.coerceAtLeast(1).toFloat()
                val lines = result.textBlocks.flatMap { block ->
                    block.lines.mapNotNull { line ->
                        val box = line.boundingBox ?: return@mapNotNull null
                        val text = line.text.trim()
                        if (text.isBlank()) return@mapNotNull null
                        SpatialLine(
                            text = text,
                            pageIndex = pageIndex,
                            leftPct = (box.left / w * 100f).coerceIn(0f, 100f),
                            topPct = (box.top / h * 100f).coerceIn(0f, 100f),
                            rightPct = (box.right / w * 100f).coerceIn(0f, 100f),
                            bottomPct = (box.bottom / h * 100f).coerceIn(0f, 100f)
                        )
                    }
                }
                if (continuation.isActive) continuation.resume(lines)
            }
            .addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
    }

    /** Rotate only for OCR, then map the recognized rectangles back to the original page. */
    private suspend fun recognizeRotated(
        recognizer: TextRecognizer,
        source: Bitmap,
        pageIndex: Int,
        clockwise: Boolean
    ): List<SpatialLine> {
        val matrix = Matrix().apply { postRotate(if (clockwise) 90f else -90f) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        return try {
            suspendCancellableCoroutine { continuation ->
                recognizer.process(InputImage.fromBitmap(rotated, 0))
                    .addOnSuccessListener { result ->
                        val ow = source.width.coerceAtLeast(1).toFloat()
                        val oh = source.height.coerceAtLeast(1).toFloat()
                        val lines = result.textBlocks.flatMap { block ->
                            block.lines.mapNotNull { line ->
                                val box = line.boundingBox ?: return@mapNotNull null
                                val text = line.text.trim()
                                if (text.isBlank()) return@mapNotNull null

                                val left: Float
                                val top: Float
                                val right: Float
                                val bottom: Float
                                if (clockwise) {
                                    left = box.top.toFloat()
                                    right = box.bottom.toFloat()
                                    top = oh - box.right.toFloat()
                                    bottom = oh - box.left.toFloat()
                                } else {
                                    left = ow - box.bottom.toFloat()
                                    right = ow - box.top.toFloat()
                                    top = box.left.toFloat()
                                    bottom = box.right.toFloat()
                                }

                                SpatialLine(
                                    text = text,
                                    pageIndex = pageIndex,
                                    leftPct = (left / ow * 100f).coerceIn(0f, 100f),
                                    topPct = (top / oh * 100f).coerceIn(0f, 100f),
                                    rightPct = (right / ow * 100f).coerceIn(0f, 100f),
                                    bottomPct = (bottom / oh * 100f).coerceIn(0f, 100f),
                                    confidence = 68
                                )
                            }
                        }
                        if (continuation.isActive) continuation.resume(lines)
                    }
                    .addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
            }
        } finally {
            rotated.recycle()
        }
    }

    private fun needsVerticalDimensionPass(lines: List<SpatialLine>): Boolean = !hasVerticalEdgeNumber(lines)

    private fun hasVerticalEdgeNumber(lines: List<SpatialLine>): Boolean = lines.any { line ->
        val centerX = (line.leftPct + line.rightPct) / 2f
        val nearSide = centerX <= 20f || centerX >= 80f
        val normalized = DimensionEvidenceEngine.normalizeDigits(line.text).trim().replace(" ", "")
        val number = Regex("^[+-]?\\d{1,3}(?:[.]\\d{1,3})?$").matches(normalized)
        nearSide && number && line.heightPct >= line.widthPct
    }

    private fun dedupe(lines: List<SpatialLine>): List<SpatialLine> = lines.distinctBy { line ->
        val cx = (((line.leftPct + line.rightPct) / 2f) * 2f).toInt()
        val cy = (((line.topPct + line.bottomPct) / 2f) * 2f).toInt()
        "${line.pageIndex}:$cx:$cy:${DimensionEvidenceEngine.normalizeDigits(line.text).trim()}"
    }
}
