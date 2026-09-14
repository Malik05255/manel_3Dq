package com.manzili.hai.engine

import android.content.Context
import android.net.Uri

/** Compatibility shell only. Production OCR is cloud-only. */
class AdaptivePlanOcrEngine(@Suppress("UNUSED_PARAMETER") context: Context) {
    suspend fun readSpatial(
        @Suppress("UNUSED_PARAMETER") uri: Uri,
        @Suppress("UNUSED_PARAMETER") maxPdfPages: Int = 8
    ): PlanTextOcrEngine.Result =
        error("On-device OCR is disabled; use the cloud floor-plan reader")
}
