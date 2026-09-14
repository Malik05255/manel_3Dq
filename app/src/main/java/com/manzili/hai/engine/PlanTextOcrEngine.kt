package com.manzili.hai.engine

import android.content.Context
import android.net.Uri

/**
 * Cloud reader text payload types.
 *
 * Production floor-plan inference is cloud-only. This class intentionally contains no
 * on-device OCR implementation; it remains only as the shared data contract used by
 * the remote parser response model and legacy call sites.
 */
class PlanTextOcrEngine(@Suppress("UNUSED_PARAMETER") context: Context) {
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

    suspend fun read(@Suppress("UNUSED_PARAMETER") uri: Uri): List<String> =
        error("On-device OCR is disabled; use the cloud floor-plan reader")

    suspend fun readSpatial(
        @Suppress("UNUSED_PARAMETER") uri: Uri,
        @Suppress("UNUSED_PARAMETER") maxPdfPages: Int = 5
    ): Result = error("On-device OCR is disabled; use the cloud floor-plan reader")
}
