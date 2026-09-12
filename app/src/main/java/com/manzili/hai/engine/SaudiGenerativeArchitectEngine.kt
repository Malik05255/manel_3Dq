package com.manzili.hai.engine

import com.manzili.hai.model.FloorLevel
import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.Opening
import com.manzili.hai.model.PlanPoint
import com.manzili.hai.model.Room
import com.manzili.hai.model.Wall
import kotlin.math.abs

/**
 * Hybrid Saudi residential synthesis.
 *
 * Sources are intentionally mixed:
 * 1) an optional AI-authored architectural seed,
 * 2) deterministic Saudi baseline strategies,
 * 3) GeometrySolver/GlobalLayoutOptimizer search,
 * 4) mirrored/rotated topology alternatives.
 *
 * Every returned plan is normalized and must pass Geometry V3. AI output never bypasses geometry.
 */
object SaudiGenerativeArchitectEngine {
    data class SearchStats(
        val aiSeedAccepted: Boolean,
        val candidatesInspected: Int,
        val validCandidates: Int,
        val distinctCandidates: Int
    )

    data class Result(
        val candidates: List<NewBuildSolver.Candidate>,
        val stats: SearchStats
    )

    fun generate(
        program: NewBuildSolver.Program,
        brief: SaudiResidentialEngine.Brief,
        aiSeed: FloorPlan? = null
    ): Result {
        val pool = mutableListOf<NewBuildSolver.Candidate>()
        val base = GlobalLayoutOptimizer.generate(program)
        pool += base.map { enrich(it, brief, "محرك البحث الهندسي") }

        base.forEachIndexed { index, candidate ->
            val h = mirror(candidate.plan, horizontal = true)
            validCandidate(h, brief, "انعكاس أفقي مستقل", "hybrid-h-$index")?.let(pool::add)
            val v = mirror(candidate.plan, horizontal = false)
            validCandidate(v, brief, "انعكاس رأسي مستقل", "hybrid-v-$index")?.let(pool::add)
            if (index == 0) {
                val r = rotate180(candidate.plan)
                validCandidate(r, brief, "دوران 180° لتغيير علاقة المدخل/الخلف", "hybrid-r-$index")?.let(pool::add)
            }
        }

        var aiAccepted = false
        if (aiSeed != null) {
            val prepared = prepareAiSeed(aiSeed, program, brief)
            val report = GeometryV3Engine.inspect(prepared)
            if (report.valid && report.plan.rooms.size >= 3) {
                aiAccepted = true
                validCandidate(report.plan, brief, "Seed مولّد مباشرة بواسطة HAI ثم تحقق منه Geometry V3", "hai-generative")?.let(pool::add)
                // Let the local solver improve the AI seed instead of trusting it as-is.
                val priorityRooms = report.plan.rooms.sortedByDescending { roomPriority(it) }.take(8)
                priorityRooms.forEach { room ->
                    listOf("MOVE", "EXPAND", "SHRINK").forEach { action ->
                        GeometrySolver.actionCandidates(report.plan, "room", room.id, action).take(2).forEach { g ->
                            if (g.review.objections.isEmpty()) {
                                validCandidate(g.plan, brief, "تحسين محلي لSeed HAI: $action ${room.name}", "hai-${room.id}-$action")?.let(pool::add)
                            }
                        }
                    }
                }
            }
        }

        val valid = pool.filter { GeometryV3Engine.inspect(it.plan).valid }
        val distinct = valid.distinctBy { signature(it.plan) }
            .sortedByDescending { it.overall }

        val selected = mutableListOf<NewBuildSolver.Candidate>()
        distinct.forEach { candidate ->
            if (selected.size < 3 && selected.all { layoutDistance(it.plan, candidate.plan) >= 5.5f }) selected += candidate
        }
        if (selected.size < 3) distinct.forEach { if (selected.size < 3 && it !in selected) selected += it }

        return Result(
            candidates = selected.take(3).mapIndexed { index, c ->
                c.copy(
                    id = "saudi-gen-${index + 1}",
                    title = when (index) {
                        0 -> "HAI • الحل السعودي الأقوى"
                        1 -> "HAI • بديل سعودي مختلف"
                        else -> "HAI • بديل سعودي ثالث"
                    },
                    metrics = (c.metrics + listOf(
                        if (aiAccepted) "AI seed + Geometry" else "Local hybrid search",
                        "Saudi audit ${SaudiResidentialEngine.inspect(c.plan).score}/100"
                    )).distinct()
                )
            },
            stats = SearchStats(aiAccepted, pool.size, valid.size, distinct.size)
        )
    }

    fun requirements(program: NewBuildSolver.Program, brief: SaudiResidentialEngine.Brief): String = buildString {
        appendLine("صمم مخطط فيلا سعودية حقيقي وليس رسماً زخرفياً.")
        appendLine("المدينة: ${brief.city}")
        appendLine("الأرض: ${program.plotWidthM}م × ${program.plotDepthM}م، جهة الشارع ${brief.streetSide}${brief.streetWidthM?.let { " وعرضه ${it}م" }.orEmpty()}.")
        appendLine("الشمال: ${brief.northDeg}°، الأدوار الحالية: ${program.floorCount}، غرف النوم: ${program.bedrooms}، الأسرة: ${brief.familySize} أفراد.")
        appendLine("الضيافة: مجلس رجال=${brief.menMajlis}، استقبال نساء=${brief.womenReception}، جناح ضيف=${brief.guestSuite}.")
        appendLine("الحركة: مدخل عائلة مستقل=${brief.familyEntranceSeparate}، مدخل خدمة=${brief.serviceEntrance}، مصعد=${brief.elevator}، كبار سن بالدور الأرضي=${brief.elderlyGroundSuite}.")
        appendLine("الخدمات: عاملة منزلية=${brief.maidRoom}، سائق=${brief.driverRoom}، مخزن=${brief.storageRoom}، بانتري=${brief.pantry}، غسيل=${brief.laundryRoom}.")
        appendLine("الخارج: مواقف=${brief.parkingCars}، حوش=${brief.courtyard}، ملحق=${brief.annex}، سطح خدمات=${brief.rooftopService}.")
        appendLine("المستقبل: توسع=${brief.futureExpansion}، أدوار مستقبلية=${brief.futureFloors}.")
        appendLine("الجيران/الانكشاف: ${brief.neighborExposure}. قطعة زاوية=${brief.cornerPlot}.")
        appendLine("الهوية: ${brief.architectureStyle}. أولوية الخصوصية=${program.privacyPriority}/100، الحركة=${program.circulationPriority}/100، الضوء=${program.daylightPriority}/100.")
        appendLine("تعليمات إضافية: ${program.notes}")
        appendLine("لا تفترض ارتدادات أو نسب بناء نظامية غير معطاة. لا تخترع أبعاداً رسمية. أعد مخططاً قابلاً للتحقق هندسياً.")
    }

    private fun prepareAiSeed(seed: FloorPlan, program: NewBuildSolver.Program, brief: SaudiResidentialEngine.Brief): FloorPlan {
        val normalizedScale = seed.copy(
            title = program.title,
            widthM = program.plotWidthM,
            heightM = program.plotDepthM,
            scaleConfidence = 100,
            preferences = seed.preferences.copy(
                privacyPriority = program.privacyPriority,
                circulationPriority = program.circulationPriority,
                daylightPriority = program.daylightPriority
            ),
            site = seed.site.copy(countryCode = "SA", city = brief.city, northDeg = brief.northDeg),
            northDeg = brief.northDeg,
            observations = (seed.observations + "Seed توليدي من HAI؛ جميع عناصره خاضعة لـ Geometry V3 والمراجعة السعودية.").distinct()
        )
        return MultiFloorGeometryEngine.normalize(SaudiResidentialEngine.apply(normalizedScale, brief))
    }

    private fun enrich(candidate: NewBuildSolver.Candidate, brief: SaudiResidentialEngine.Brief, source: String): NewBuildSolver.Candidate {
        val plan = SaudiResidentialEngine.apply(candidate.plan, brief)
        val review = SaudiResidentialEngine.inspect(plan)
        val adjusted = (candidate.overall * .58 + review.score * .42).toInt().coerceIn(0, 100)
        return candidate.copy(
            plan = plan,
            overall = adjusted,
            rationale = "${candidate.rationale} • $source • ${review.notes.firstOrNull().orEmpty()}",
            metrics = (candidate.metrics + "Saudi ${review.score}/100").distinct()
        )
    }

    private fun validCandidate(plan: FloorPlan, brief: SaudiResidentialEngine.Brief, source: String, id: String): NewBuildSolver.Candidate? {
        val applied = SaudiResidentialEngine.apply(MultiFloorGeometryEngine.normalize(plan), brief)
        val geometry = GeometryV3Engine.inspect(applied)
        if (!geometry.valid) return null
        val architecture = ArchitecturalEngine.score(geometry.plan)
        val saudi = SaudiResidentialEngine.inspect(geometry.plan)
        val score = (architecture.overall * .56 + saudi.score * .44).toInt().coerceIn(0, 100)
        return NewBuildSolver.Candidate(
            id = id,
            title = source,
            rationale = "$source؛ اجتاز Geometry V3 ثم المراجعة السعودية.",
            plan = geometry.plan,
            overall = score,
            metrics = listOf("Geometry V3", "Saudi ${saudi.score}/100")
        )
    }

    private fun mirror(plan: FloorPlan, horizontal: Boolean): FloorPlan = transform(plan) { p ->
        if (horizontal) PlanPoint(100f - p.x, p.y) else PlanPoint(p.x, 100f - p.y)
    }

    private fun rotate180(plan: FloorPlan): FloorPlan = transform(plan) { p -> PlanPoint(100f - p.x, 100f - p.y) }

    private fun transform(plan: FloorPlan, point: (PlanPoint) -> PlanPoint): FloorPlan {
        fun room(r: Room): Room {
            val poly = (r.polygon.ifEmpty { listOf(
                PlanPoint(r.x,r.y), PlanPoint(r.x+r.width,r.y), PlanPoint(r.x+r.width,r.y+r.height), PlanPoint(r.x,r.y+r.height)
            ) }).map(point)
            val minX = poly.minOf { it.x }; val maxX = poly.maxOf { it.x }
            val minY = poly.minOf { it.y }; val maxY = poly.maxOf { it.y }
            return r.copy(x=minX,y=minY,width=maxX-minX,height=maxY-minY,polygon=poly)
        }
        fun wall(w: Wall) = w.copy(start=point(w.start), end=point(w.end))
        fun opening(o: Opening): Opening {
            val p=point(PlanPoint(o.x,o.y)); return o.copy(x=p.x,y=p.y,rotationDeg=(o.rotationDeg+if(point(PlanPoint(0f,0f)).x>0f)180f else 0f)%360f)
        }
        fun floor(f: FloorLevel)=f.copy(
            footprint=f.footprint.map(point), rooms=f.rooms.map(::room), walls=f.walls.map(::wall), openings=f.openings.map(::opening),
            elements=f.elements.map { e->e.copy(footprint=e.footprint.map(point)) }
        )
        return plan.copy(
            rooms=plan.rooms.map(::room), walls=plan.walls.map(::wall), openings=plan.openings.map(::opening),
            footprint=plan.footprint.map(point), site=plan.site.copy(plotBoundary=plan.site.plotBoundary.map(point), roads=plan.site.roads.map { it.copy(start=point(it.start),end=point(it.end)) }),
            floors=plan.floors.map(::floor), elements=plan.elements.map { it.copy(footprint=it.footprint.map(point)) }
        )
    }

    private fun roomPriority(room: Room): Int = when(room.type.lowercase()) {
        "majlis", "guest" -> 100
        "living", "family" -> 95
        "kitchen", "service" -> 85
        "bedroom", "master" -> 80
        else -> 60
    }

    private fun signature(plan: FloorPlan): String = plan.rooms.sortedBy { it.id }.joinToString("|") {
        "${it.type}:${(it.x*2).toInt()}:${(it.y*2).toInt()}:${(it.width*2).toInt()}:${(it.height*2).toInt()}"
    }

    private fun layoutDistance(a: FloorPlan, b: FloorPlan): Float {
        val byType = b.rooms.groupBy { it.type.lowercase() }.mapValues { it.value.toMutableList() }
        val distances = mutableListOf<Float>()
        a.rooms.forEach { ar ->
            val candidates = byType[ar.type.lowercase()].orEmpty()
            val best = candidates.minByOrNull { br -> abs(ar.x-br.x)+abs(ar.y-br.y) } ?: return@forEach
            distances += abs(ar.x-best.x)+abs(ar.y-best.y)+.5f*abs(ar.width-best.width)+.5f*abs(ar.height-best.height)
        }
        return if(distances.isEmpty())100f else distances.average().toFloat()
    }
}
