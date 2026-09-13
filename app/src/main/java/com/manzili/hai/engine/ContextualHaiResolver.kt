package com.manzili.hai.engine

import android.content.Context
import android.net.Uri
import com.manzili.hai.ai.HaiArchitectClient
import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.PlanDimension
import com.manzili.hai.model.PlanProposal
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Context-aware HAI entry point used by the floating assistant button.
 * It always tries local evidence first so the interaction feels immediate,
 * then uses the original source and remote HAI only when something is still unresolved.
 */
class ContextualHaiResolver(context: Context) {
    data class Resolution(
        val plan: FloorPlan,
        val message: String,
        val solvedSomething: Boolean
    )

    private val ocr = PlanTextOcrEngine(context)
    private val raster = RasterFloorplanParserEngine(context)
    private val remote = RemoteFloorplanEvidenceClient(context)
    private val visualHai = HaiArchitectClient(context)

    suspend fun resolveReview(source: Uri?, input: FloorPlan): Resolution {
        val before = PlanVerificationEngine.inspect(input)
        var candidate = inferScaleFromEvidence(HaiReviewResolutionEngine.localResolve(input))
        var channels = 0
        val failures = mutableListOf<String>()

        if (source != null && needsReviewHelp(candidate)) {
            val ocrAttempt = runCatching { ocr.readSpatial(source, maxPdfPages = 8) }
            val rasterAttempt = runCatching { raster.analyze(source, maxPdfPages = 6) }
            val remoteAttempt = if (remote.available) runCatching { remote.analyze(source, maxPdfPages = 8) } else null

            val ocrResult = ocrAttempt.getOrNull()
            val rasterResult = rasterAttempt.getOrNull()
            val remoteResult = remoteAttempt?.getOrNull()

            ocrAttempt.exceptionOrNull()?.message?.let { failures += "OCR: ${it.take(80)}" }
            rasterAttempt.exceptionOrNull()?.message?.let { failures += "Raster: ${it.take(80)}" }
            remoteAttempt?.exceptionOrNull()?.message?.let { failures += "Deep Parser: ${it.take(80)}" }

            val lines = ocrResult?.lines.orEmpty() + remoteResult?.ocrLines.orEmpty()
            val recovered = DimensionEvidenceEngine.extractSpatial(lines)
            if (recovered.isNotEmpty()) {
                candidate = candidate.copy(
                    dimensions = (candidate.dimensions + recovered).distinctBy {
                        "${it.pageIndex}:${"%.3f".format(it.valueM)}:${it.sourceText.trim()}"
                    }
                )
                channels++
            }

            if (remoteResult != null) {
                candidate = MultiPageEvidenceFusionEngine.apply(candidate, remoteResult.pages)
                channels++
            }

            if (rasterResult != null) {
                candidate = FloorplanParserEngine.refine(candidate, rasterResult.primaryWalls).plan
                channels++
            }

            candidate = inferScaleFromEvidence(HaiReviewResolutionEngine.localResolve(candidate))

            if (needsReviewHelp(candidate)) {
                runCatching { visualHai.analyzePlan(source) }
                    .onSuccess { visual ->
                        candidate = HaiReviewResolutionEngine.mergeVisualEvidence(candidate, visual)
                        candidate = inferScaleFromEvidence(HaiReviewResolutionEngine.localResolve(candidate))
                        channels++
                    }
                    .onFailure { failures += "HAI Vision: ${it.message.orEmpty().take(80)}" }
            }
        }

        val after = PlanVerificationEngine.inspect(candidate)
        val solvedCount = (before.issues.size - after.issues.size).coerceAtLeast(0)
        val widthSolved = input.widthM == null && after.plan.widthM != null
        val heightSolved = input.heightM == null && after.plan.heightM != null
        val solved = solvedCount > 0 || widthSolved || heightSolved || after.plan.revision > input.revision

        val message = when {
            widthSolved && heightSolved && !after.blocking -> "استدع HAI عبّأ العرض والطول وأكمل مشاكل المراجعة."
            widthSolved || heightSolved -> "استدع HAI عبّأ ${listOfNotNull(if (widthSolved) "العرض" else null, if (heightSolved) "الطول" else null).joinToString(" و")} من المخطط."
            !after.blocking && before.blocking -> "استدع HAI أكمل الهندسة وأصبحت جاهزة للاعتماد."
            solvedCount > 0 -> "استدع HAI حل $solvedCount من المشاكل الحالية."
            source == null -> "المخطط الأصلي غير متاح الآن؛ لا أستطيع استخراج قيم جديدة بأمان."
            channels > 0 -> "راجعت المخطط من عدة قنوات، لكن لم أجد دليلاً موثوقًا كافيًا لتعبئة المتبقي دون تخمين."
            failures.isNotEmpty() -> "تعذر إكمال الاستدعاء: ${failures.first()}"
            else -> "لا يوجد حل آمن تلقائي لهذه المشكلة الآن؛ أكملها يدويًا."
        }

        return Resolution(after.plan, message, solved)
    }

    fun proposeEdit(plan: FloorPlan): PlanProposal {
        val suggestions = ArchitecturalEngine.proactiveSuggestions(plan)
        val top = suggestions.firstOrNull()
            ?: return PlanProposal(
                message = "لا أرى اعتراضًا معماريًا قويًا يستحق تعديل المخطط الآن.",
                updatedPlan = null,
                confidence = 92
            )

        val targetId = top.targetIds.firstOrNull()
        val targetKind = when {
            targetId == null -> null
            plan.rooms.any { it.id == targetId } -> "room"
            plan.walls.any { it.id == targetId } -> "wall"
            plan.openings.any { it.id == targetId } -> "opening"
            else -> null
        }
        val action = when (top.actionKind) {
            "EXPAND_ROOM" -> "EXPAND"
            "SHRINK_ROOM" -> "SHRINK"
            "MOVE_OPENING" -> "MOVE"
            else -> null
        }

        val candidate = if (targetId != null && targetKind != null && action != null) {
            GeometrySolver.actionCandidates(plan, targetKind, targetId, action)
                .firstOrNull { !ProjectMemoryEngine.review(plan, it.plan).hasObjection }
        } else null

        if (candidate == null) {
            return PlanProposal(
                message = "${top.title}. ${top.reason}",
                updatedPlan = null,
                requiresConfirmation = true,
                confidence = top.priority.coerceIn(60, 96)
            )
        }

        val proposal = GeometrySolver.toProposal(plan, candidate)
        return proposal.copy(
            message = "${top.title}\n${top.reason}\nسبب تعديلي: ${candidate.reason}",
            confidence = maxOf(proposal.confidence, top.priority.coerceIn(65, 96))
        )
    }

    private fun needsReviewHelp(plan: FloorPlan): Boolean {
        val report = PlanVerificationEngine.inspect(plan)
        return report.blocking || plan.widthM == null || plan.heightM == null || report.issues.isNotEmpty()
    }

    /**
     * Exterior dimension chains are common in Saudi residential drawings.
     * If there is no explicit overall width/height, sum a coherent edge chain instead of
     * forcing the user to manually calculate every segment.
     */
    private fun inferScaleFromEvidence(input: FloorPlan): FloorPlan {
        var width = input.widthM
        var height = input.heightM
        if (width == null) width = chainTotal(input.dimensions, horizontal = true)
        if (height == null) height = chainTotal(input.dimensions, horizontal = false)
        if (width == input.widthM && height == input.heightM) return input
        return input.copy(
            widthM = width,
            heightM = height,
            scaleConfidence = maxOf(input.scaleConfidence, 78),
            revision = input.revision + 1,
            observations = (input.observations + "استدع HAI حسب البعد الكلي من سلسلة الأبعاد الخارجية المقروءة.").distinct()
        )
    }

    private fun chainTotal(dimensions: List<PlanDimension>, horizontal: Boolean): Double? {
        data class Item(val d: PlanDimension, val lane: Int, val nearEdge: Boolean)

        val items = dimensions.mapNotNull { d ->
            if (d.confidence < 62 || d.valueM !in 0.25..30.0) return@mapNotNull null
            val start = d.start ?: return@mapNotNull null
            val end = d.end ?: return@mapNotNull null
            val dx = abs(end.x - start.x)
            val dy = abs(end.y - start.y)
            val orientationMatches = if (horizontal) dx >= dy else dy > dx
            if (!orientationMatches) return@mapNotNull null
            val cross = if (horizontal) (start.y + end.y) / 2f else (start.x + end.x) / 2f
            val nearEdge = cross <= 18f || cross >= 82f
            Item(d, (cross / 4f).roundToInt(), nearEdge)
        }

        val grouped = items.filter { it.nearEdge }
            .groupBy { "${it.d.pageIndex}:${it.lane}" }
            .values
            .map { lane -> lane.distinctBy { "%.2f".format(it.d.valueM) + ":" + it.d.sourceText.trim() } }
            .filter { it.size >= 3 }

        return grouped.map { lane -> lane.sumOf { it.d.valueM } }
            .filter { it in 3.0..100.0 }
            .maxOrNull()
    }
}
