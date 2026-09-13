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
 * It always tries deterministic/local evidence first, then the original source and remote HAI.
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
        candidate = RoomTopologyEngine.recover(candidate).plan
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
            val numericLabels = PlanNumberEvidenceEngine.extract(lines)
            if (recovered.isNotEmpty() || numericLabels.isNotEmpty()) {
                candidate = candidate.copy(
                    dimensions = (candidate.dimensions + recovered + numericLabels).distinctBy { d ->
                        val sx = d.start?.x?.times(2f)?.roundToInt() ?: -1
                        val sy = d.start?.y?.times(2f)?.roundToInt() ?: -1
                        val ex = d.end?.x?.times(2f)?.roundToInt() ?: -1
                        val ey = d.end?.y?.times(2f)?.roundToInt() ?: -1
                        "${d.pageIndex}:${"%.3f".format(d.valueM)}:${d.axis}:$sx:$sy:$ex:$ey"
                    },
                    observations = (candidate.observations + numericLabels.takeIf { it.isNotEmpty() }
                        ?.let { "HAI حفظ ${it.size} رقمًا ظاهرًا من OCR كأدلة مكانية." }).filterNotNull().distinct()
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

            candidate = RoomTopologyEngine.recover(candidate).plan
            candidate = inferScaleFromEvidence(HaiReviewResolutionEngine.localResolve(candidate))

            if (needsReviewHelp(candidate)) {
                runCatching { visualHai.analyzePlan(source) }
                    .onSuccess { visual ->
                        candidate = HaiReviewResolutionEngine.mergeVisualEvidence(candidate, visual)
                        candidate = RoomTopologyEngine.recover(candidate).plan
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
        val roomsSolved = after.plan.rooms.size > input.rooms.size
        val numbersBefore = PlanNumberEvidenceEngine.numericLabels(input.dimensions).size
        val numbersAfter = PlanNumberEvidenceEngine.numericLabels(after.plan.dimensions).size
        val numbersSolved = numbersAfter > numbersBefore
        val solved = solvedCount > 0 || widthSolved || heightSolved || roomsSolved || numbersSolved || after.plan.revision > input.revision

        val message = when {
            roomsSolved && numbersSolved -> "استعاد HAI ${after.plan.rooms.size} مساحة وحفظ $numbersAfter رقمًا ظاهرًا من المخطط للمراجعة."
            roomsSolved -> "استعاد HAI المساحات المغلقة من شبكة الجدران؛ العدد الحالي ${after.plan.rooms.size}."
            numbersSolved -> "قرأ HAI $numbersAfter رقمًا ظاهرًا وحفظ مواقعها ومعانيها المحتملة للمراجعة."
            widthSolved && heightSolved && !after.blocking -> "تمت قراءة سلسلة الأبعاد الخارجية. عبّأ HAI العرض والطول وأكمل المراجعة."
            widthSolved && heightSolved -> "تمت تعبئة العرض والطول تلقائيًا من أبعاد المخطط."
            widthSolved || heightSolved -> "عبّأ HAI ${listOfNotNull(if (widthSolved) "العرض" else null, if (heightSolved) "الطول" else null).joinToString(" و")} تلقائيًا من المخطط."
            !after.blocking && before.blocking -> "أكمل HAI الهندسة وأصبحت جاهزة للاعتماد."
            solvedCount > 0 -> "حل HAI $solvedCount من المشاكل الحالية."
            source == null -> "المخطط الأصلي غير متاح الآن؛ لا أستطيع استخراج قيم جديدة بأمان."
            failures.isNotEmpty() && channels == 0 -> "تعذر إكمال الاستدعاء: ${failures.first()}"
            channels > 0 -> "قرأت المخطط من عدة قنوات، لكن بعض الأدلة ما زالت تحتاج تأكيدًا بصريًا قبل اعتبار القراءة كاملة."
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
     * Resolve overall scale from exterior dimension chains. Repeated segment values are preserved;
     * only duplicate OCR hits at the same location are collapsed. The lane with the strongest
     * spatial coverage/count wins, rather than simply taking the largest number.
     */
    private fun inferScaleFromEvidence(input: FloorPlan): FloorPlan {
        val scaleEvidence = input.dimensions.filterNot { it.axis.startsWith("label-") || it.id.startsWith("num-") }
        var width = input.widthM ?: explicitAxisValue(scaleEvidence, horizontal = true)
        var height = input.heightM ?: explicitAxisValue(scaleEvidence, horizontal = false)
        if (width == null) width = chainTotal(scaleEvidence, horizontal = true)
        if (height == null) height = chainTotal(scaleEvidence, horizontal = false)
        if (width == input.widthM && height == input.heightM) return input

        val resolvedBoth = width != null && height != null
        return input.copy(
            widthM = width,
            heightM = height,
            scaleConfidence = maxOf(input.scaleConfidence, if (resolvedBoth) 84 else 76),
            revision = input.revision + 1,
            observations = (input.observations + "HAI استنتج الأبعاد الكلية من سلسلة أبعاد خارجية مكانية موثوقة.").distinct()
        )
    }

    private fun explicitAxisValue(dimensions: List<PlanDimension>, horizontal: Boolean): Double? {
        val axis = if (horizontal) "horizontal" else "vertical"
        return dimensions
            .filter { it.axis == axis && it.confidence >= 82 && it.valueM in 2.0..100.0 }
            .filter { d ->
                val text = d.sourceText
                if (horizontal) text.contains("عرض") || d.label.contains("عرض")
                else text.contains("طول") || text.contains("ارتفاع") || d.label.contains("طول") || d.label.contains("ارتفاع")
            }
            .maxByOrNull { it.confidence }
            ?.valueM
    }

    private data class ChainItem(
        val d: PlanDimension,
        val page: Int,
        val side: Int,
        val lane: Int,
        val mainCenter: Float,
        val cross: Float
    )

    private data class ChainCandidate(
        val total: Double,
        val count: Int,
        val coverage: Float,
        val confidence: Double
    )

    private fun chainTotal(dimensions: List<PlanDimension>, horizontal: Boolean): Double? {
        val items = dimensions.mapNotNull { d ->
            if (d.confidence < 60 || d.valueM !in 0.25..40.0) return@mapNotNull null
            val start = d.start ?: return@mapNotNull null
            val end = d.end ?: return@mapNotNull null
            val dx = abs(end.x - start.x)
            val dy = abs(end.y - start.y)
            val orientationMatches = if (horizontal) dx >= dy else dy > dx
            if (!orientationMatches) return@mapNotNull null

            val cross = if (horizontal) (start.y + end.y) / 2f else (start.x + end.x) / 2f
            if (cross > 22f && cross < 78f) return@mapNotNull null
            val side = if (cross < 50f) 0 else 1
            val main = if (horizontal) (start.x + end.x) / 2f else (start.y + end.y) / 2f
            ChainItem(d, d.pageIndex, side, (cross / 2.5f).roundToInt(), main, cross)
        }

        val candidates = items.groupBy { "${it.page}:${it.side}:${it.lane}" }
            .values
            .mapNotNull { lane ->
                val unique = lane
                    .sortedBy { it.mainCenter }
                    .fold(mutableListOf<ChainItem>()) { acc, item ->
                        val duplicate = acc.any { previous ->
                            abs(previous.mainCenter - item.mainCenter) < 1.3f &&
                                abs(previous.d.valueM - item.d.valueM) < .03
                        }
                        if (!duplicate) acc += item
                        acc
                    }
                if (unique.size < 2) return@mapNotNull null
                val coverage = unique.maxOf { it.mainCenter } - unique.minOf { it.mainCenter }
                if (coverage < 20f) return@mapNotNull null
                val total = unique.sumOf { it.d.valueM }
                if (total !in 3.0..100.0) return@mapNotNull null
                ChainCandidate(
                    total = total,
                    count = unique.size,
                    coverage = coverage,
                    confidence = unique.map { it.d.confidence }.average()
                )
            }

        val best = candidates.sortedWith(
            compareByDescending<ChainCandidate> { it.count }
                .thenByDescending { it.coverage }
                .thenByDescending { it.confidence }
        ).firstOrNull()

        if (best != null) return best.total
        return explicitAxisValue(dimensions, horizontal)
    }
}
