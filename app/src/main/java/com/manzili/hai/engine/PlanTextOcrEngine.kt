package com.manzili.hai.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** On-device OCR pass used as independent dimension evidence beside the vision model. */
class PlanTextOcrEngine(private val context: Context) {
    suspend fun read(uri: Uri): List<String> = withContext(Dispatchers.IO) {
        recognize(inputImage(uri))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(160)
    }

    private fun inputImage(uri: Uri): InputImage {
        val type = context.contentResolver.getType(uri).orEmpty()
        if (type != "application/pdf") return InputImage.fromFilePath(context, uri)
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("تعذر فتح PDF للـOCR")
        val bitmap = PdfRenderer(pfd).use { renderer ->
            require(renderer.pageCount > 0) { "PDF بلا صفحات" }
            renderer.openPage(0).use { page ->
                val targetWidth = 2600
                val scale = targetWidth.toFloat() / page.width.coerceAtLeast(1)
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createBitmap(targetWidth, height, Bitmap.Config.ARGB_8888).also {
                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
        return InputImage.fromBitmap(bitmap, 0)
    }

    private suspend fun recognize(image: InputImage): List<String> = suspendCancellableCoroutine { continuation ->
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val lines = result.textBlocks.flatMap { block -> block.lines.map { it.text } }
                if (continuation.isActive) continuation.resume(lines)
            }
            .addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
            .addOnCompleteListener { recognizer.close() }
    }
}
