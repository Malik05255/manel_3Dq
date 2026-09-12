package com.manzili.hai.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

/**
 * On-device OCR evidence. Unlike the old text-only pass, every line keeps its page and
 * normalized location so dimensions can be attached to where they were actually read.
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

    /** Compatibility path used by older callers. */
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
        val bitmap = context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
        if (bitmap != null) {
            val lines = recognize(recognizer, InputImage.fromBitmap(bitmap, 0), 0, bitmap.width, bitmap.height)
            return Result(lines.take(500), 1, lines.size > 500)
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
                    val targetWidth = 2200
                    val scale = targetWidth.toFloat() / page.width.coerceAtLeast(1)
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(targetWidth, height, Bitmap.Config.ARGB_8888)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    all += recognize(recognizer, InputImage.fromBitmap(bitmap, 0), pageIndex, bitmap.width, bitmap.height)
                    bitmap.recycle()
                }
            }
            Result(all.take(1200), count, renderer.pageCount > count || all.size > 1200)
        }
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
}
