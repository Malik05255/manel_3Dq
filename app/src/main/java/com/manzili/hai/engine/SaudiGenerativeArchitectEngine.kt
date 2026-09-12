package com.manzili.hai.engine

import com.manzili.hai.model.*
import kotlin.math.abs

/** Hybrid Saudi residential synthesis. AI output never bypasses Geometry V3 or the Saudi critic. */
object SaudiGenerativeArchitectEngine {
    data class SearchStats(val aiSeedAccepted:Boolean,val candidatesInspected:Int,val validCandidates:Int,val distinctCandidates:Int)
    data class Result(val candidates:List<NewBuildSolver.Candidate>,val stats:SearchStats)

    fun generate(
        program:NewBuildSolver.Program,
        projectType:SaudiProjectTypeEngine.Type,
        brief:SaudiDeepBriefEngine.Brief,
        aiSeed:FloorPlan?=null
    ):Result {
        val pool=mutableListOf<NewBuildSolver.Candidate>()
        val typeSeeds=SaudiProjectTypeSeedEngine.generate(program,projectType)
        val base=if(projectType==SaudiProjectTypeEngine.Type.VILLA_ONE||projectType==SaudiProjectTypeEngine.Type.VILLA_TWO) GlobalLayoutOptimizer.generate(program) else typeSeeds
        pool += typeSeeds.map { enrich(it,projectType,brief,"Seed محلي خاص بالنوع") }
        if(base!==typeSeeds) pool += base.map { enrich(it,projectType,brief,"محرك البحث الهندسي") }

        base.forEachIndexed { index,candidate ->
            validCandidate(mirror(candidate.plan,true),projectType,brief,"انعكاس أفقي مستقل","hybrid-h-$index")?.let(pool::add)
            validCandidate(mirror(candidate.plan,false),projectType,brief,"انعكاس رأسي مستقل","hybrid-v-$index")?.let(pool::add)
            if(index==0) validCandidate(rotate180(candidate.plan),projectType,brief,"دوران 180° لتغيير علاقة المدخل/الخلف","hybrid-r-$index")?.let(pool::add)
        }

        var aiAccepted=false
        if(aiSeed!=null) {
            val prepared=prepareAiSeed(aiSeed,program,projectType,brief)
            val report=GeometryV3Engine.inspect(prepared)
            if(report.valid && report.plan.rooms.size>=3) {
                aiAccepted=true
                validCandidate(report.plan,projectType,brief,"Seed مولّد مباشرة بواسطة HAI ثم تحقق منه Geometry V3","hai-generative")?.let(pool::add)
                report.plan.rooms.sortedByDescending(::roomPriority).take(8).forEach { room ->
                    listOf("MOVE","EXPAND","SHRINK").forEach { action ->
                        GeometrySolver.actionCandidates(report.plan,"room",room.id,action).take(2).forEach { g ->
                            if(g.review.objections.isEmpty()) validCandidate(g.plan,projectType,brief,"تحسين محلي لSeed HAI: $action ${room.name}","hai-${room.id}-$action")?.let(pool::add)
                        }
                    }
                }
            }
        }

        val valid=pool.filter { GeometryV3Engine.inspect(it.plan).valid }
        val distinct=valid.distinctBy { signature(it.plan) }.sortedByDescending { it.overall }
        val selected=mutableListOf<NewBuildSolver.Candidate>()
        distinct.forEach { c->if(selected.size<3&&selected.all{layoutDistance(it.plan,c.plan)>=5.5f})selected+=c }
        if(selected.size<3)distinct.forEach{if(selected.size<3&&it !in selected)selected+=it}
        return Result(
            selected.take(3).mapIndexed { index,c->
                val critic=SaudiArchitectCriticEngine.inspect(c.plan,projectType,brief)
                c.copy(
                    id="saudi-gen-${index+1}",
                    title=when(index){0->"HAI • الحل الأقوى";1->"HAI • بديل مختلف";else->"HAI • بديل ثالث"},
                    metrics=(c.metrics+listOf(projectType.label,if(aiAccepted)"AI seed + Geometry" else "Type-aware local fallback","Saudi audit ${SaudiResidentialEngine.inspect(c.plan).score}/100","Architect critic ${critic.score}/100")).distinct()
                )
            },
            SearchStats(aiAccepted,pool.size,valid.size,distinct.size)
        )
    }

    fun requirements(program:NewBuildSolver.Program,projectType:SaudiProjectTypeEngine.Type,brief:SaudiDeepBriefEngine.Brief,adaptiveAnswers:String=""):String {
        val b=brief.base;val profile=SaudiProjectTypeEngine.profile(projectType)
        return buildString {
            appendLine("صمم مشروعًا سكنيًا سعوديًا حقيقيًا وليس رسماً زخرفياً.")
            appendLine("نوع المشروع الملزم: ${projectType.label}. ${projectType.subtitle}")
            appendLine("أولويات هذا النوع: ${profile.priorities.joinToString("، ")}.")
            appendLine("المدينة: ${b.city}")
            appendLine("الأرض: ${program.plotWidthM}م × ${program.plotDepthM}م، جهة الشارع ${b.streetSide}${b.streetWidthM?.let { " وعرضه ${it}م" }.orEmpty()}.")
            appendLine("الشمال: ${b.northDeg}°، الأدوار الحالية: ${program.floorCount}، غرف النوم المطلوبة: ${program.bedrooms}، الأسرة: ${b.familySize} أفراد.")
            appendLine("الضيافة: مجلس رجال=${b.menMajlis}، استقبال نساء=${b.womenReception}، جناح ضيف=${brief.guestSuite}.")
            appendLine("الحركة: مدخل عائلة مستقل=${b.familyEntranceSeparate}، مدخل خدمة=${b.serviceEntrance}، مصعد=${b.elevator}، كبار سن بالدور الأرضي=${brief.elderlyGroundSuite}.")
            appendLine("الخدمات: عاملة منزلية=${b.maidRoom}، سائق=${b.driverRoom}، مخزن=${brief.storageRoom}، بانتري=${brief.pantry}، غسيل=${brief.laundryRoom}.")
            appendLine("الخارج: مواقف=${b.parkingCars}، حوش=${b.courtyard}، ملحق=${b.annex}، سطح خدمات=${b.rooftopService}، مدخل سيارات=${brief.carEntranceSide}.")
            appendLine("المستقبل: توسع=${brief.futureExpansion}، أدوار مستقبلية=${brief.futureFloors}.")
            appendLine("الجيران/الانكشاف: ${brief.neighborExposure}. قطعة زاوية=${brief.cornerPlot}.")
            appendLine("ارتدادات أدخلها المستخدم فقط إن وجدت: أمامي=${brief.frontSetbackM}، خلفي=${brief.rearSetbackM}، جانبي=${brief.sideSetbackM}.")
            appendLine("الهوية: ${b.architectureStyle}. أولوية الخصوصية=${program.privacyPriority}/100، الحركة=${program.circulationPriority}/100، الضوء=${program.daylightPriority}/100.")
            appendLine("إجابات خاصة بنوع المشروع: $adaptiveAnswers")
            appendLine("تعليمات إضافية: ${program.notes}")
            appendLine("أنشئ حلولاً مختلفة فعليًا في علاقة المدخل والضيافة والعائلة والخدمات، وليس نسخًا شكلية.")
            appendLine("لا تحول عمارة إلى فيلا أو تاون هاوس إلى فيلا. احترم نوع المشروع كقيد صلب.")
            appendLine("لا تفترض ارتدادات أو نسب بناء نظامية غير معطاة. لا تخترع أبعاداً رسمية. أعد مخططاً قابلاً للتحقق هندسياً.")
        }
    }

    private fun prepareAiSeed(seed:FloorPlan,program:NewBuildSolver.Program,projectType:SaudiProjectTypeEngine.Type,brief:SaudiDeepBriefEngine.Brief):FloorPlan {
        val b=brief.base
        val normalized=seed.copy(
            title=program.title,widthM=program.plotWidthM,heightM=program.plotDepthM,scaleConfidence=100,
            preferences=seed.preferences.copy(privacyPriority=program.privacyPriority,circulationPriority=program.circulationPriority,daylightPriority=program.daylightPriority),
            site=seed.site.copy(countryCode="SA",city=b.city,northDeg=b.northDeg),northDeg=b.northDeg,
            observations=(seed.observations+"Seed توليدي من HAI؛ جميع عناصره خاضعة لـ Geometry V3 والمراجعة السعودية.").distinct()
        )
        return MultiFloorGeometryEngine.normalize(SaudiProjectTypeEngine.apply(SaudiDeepBriefEngine.apply(normalized,brief),projectType))
    }

    private fun enrich(candidate:NewBuildSolver.Candidate,projectType:SaudiProjectTypeEngine.Type,brief:SaudiDeepBriefEngine.Brief,source:String):NewBuildSolver.Candidate {
        val plan=SaudiProjectTypeEngine.apply(SaudiDeepBriefEngine.apply(candidate.plan,brief),projectType)
        val review=SaudiResidentialEngine.inspect(plan);val critic=SaudiArchitectCriticEngine.inspect(plan,projectType,brief)
        val adjusted=(candidate.overall*.42+review.score*.28+critic.score*.30).toInt().coerceIn(0,100)
        val note=critic.issues.firstOrNull()?:critic.strengths.firstOrNull().orEmpty()
        return candidate.copy(plan=plan,overall=adjusted,rationale="${candidate.rationale} • $source • $note",metrics=(candidate.metrics+listOf(projectType.label,"Saudi ${review.score}/100","Critic ${critic.score}/100")).distinct())
    }

    private fun validCandidate(plan:FloorPlan,projectType:SaudiProjectTypeEngine.Type,brief:SaudiDeepBriefEngine.Brief,source:String,id:String):NewBuildSolver.Candidate? {
        val applied=SaudiProjectTypeEngine.apply(SaudiDeepBriefEngine.apply(MultiFloorGeometryEngine.normalize(plan),brief),projectType)
        val geometry=GeometryV3Engine.inspect(applied);if(!geometry.valid)return null
        val architecture=ArchitecturalEngine.score(geometry.plan);val saudi=SaudiResidentialEngine.inspect(geometry.plan);val critic=SaudiArchitectCriticEngine.inspect(geometry.plan,projectType,brief)
        if(critic.hardViolations.isNotEmpty())return null
        val score=(architecture.overall*.40+saudi.score*.25+critic.score*.35).toInt().coerceIn(0,100)
        return NewBuildSolver.Candidate(id,source,"$source؛ اجتاز Geometry V3 والناقد المعماري السعودي.",geometry.plan,score,listOf(projectType.label,"Geometry V3","Saudi ${saudi.score}/100","Critic ${critic.score}/100"))
    }

    private fun mirror(plan:FloorPlan,horizontal:Boolean)=transform(plan){p->if(horizontal)PlanPoint(100f-p.x,p.y)else PlanPoint(p.x,100f-p.y)}
    private fun rotate180(plan:FloorPlan)=transform(plan){p->PlanPoint(100f-p.x,100f-p.y)}
    private fun transform(plan:FloorPlan,point:(PlanPoint)->PlanPoint):FloorPlan {
        fun room(r:Room):Room{val poly=(r.polygon.ifEmpty{listOf(PlanPoint(r.x,r.y),PlanPoint(r.x+r.width,r.y),PlanPoint(r.x+r.width,r.y+r.height),PlanPoint(r.x,r.y+r.height))}).map(point);val minX=poly.minOf{it.x};val maxX=poly.maxOf{it.x};val minY=poly.minOf{it.y};val maxY=poly.maxOf{it.y};return r.copy(x=minX,y=minY,width=maxX-minX,height=maxY-minY,polygon=poly)}
        fun wall(w:Wall)=w.copy(start=point(w.start),end=point(w.end));fun opening(o:Opening):Opening{val p=point(PlanPoint(o.x,o.y));return o.copy(x=p.x,y=p.y)}
        fun floor(f:FloorLevel)=f.copy(footprint=f.footprint.map(point),rooms=f.rooms.map(::room),walls=f.walls.map(::wall),openings=f.openings.map(::opening),elements=f.elements.map{e->e.copy(footprint=e.footprint.map(point))})
        return plan.copy(rooms=plan.rooms.map(::room),walls=plan.walls.map(::wall),openings=plan.openings.map(::opening),footprint=plan.footprint.map(point),site=plan.site.copy(plotBoundary=plan.site.plotBoundary.map(point),roads=plan.site.roads.map{it.copy(start=point(it.start),end=point(it.end))}),floors=plan.floors.map(::floor),elements=plan.elements.map{it.copy(footprint=it.footprint.map(point))})
    }
    private fun roomPriority(room:Room)=when(room.type.lowercase()){ "majlis","guest"->100;"living","family"->95;"kitchen","service"->85;"bedroom","master"->80;else->60 }
    private fun signature(plan:FloorPlan)=plan.rooms.sortedBy{it.id}.joinToString("|"){"${it.type}:${(it.x*2).toInt()}:${(it.y*2).toInt()}:${(it.width*2).toInt()}:${(it.height*2).toInt()}"}
    private fun layoutDistance(a:FloorPlan,b:FloorPlan):Float{val byType=b.rooms.groupBy{it.type.lowercase()};val ds=mutableListOf<Float>();a.rooms.forEach{ar->val best=byType[ar.type.lowercase()].orEmpty().minByOrNull{br->abs(ar.x-br.x)+abs(ar.y-br.y)}?:return@forEach;ds+=abs(ar.x-best.x)+abs(ar.y-best.y)+.5f*abs(ar.width-best.width)+.5f*abs(ar.height-best.height)};return if(ds.isEmpty())100f else ds.average().toFloat()}
}
