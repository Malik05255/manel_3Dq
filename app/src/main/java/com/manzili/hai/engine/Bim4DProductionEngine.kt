package com.manzili.hai.engine

import com.manzili.hai.model.FloorLevel
import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.PlanPoint
import com.manzili.hai.model.ProjectConstraint
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Production 4D/BIM planning layer.
 *
 * Quantities are derived only when metric Geometry V3 is ready. Cost rates are never guessed:
 * every SAR/unit rate must come from USER_COST_RATE constraints entered by the user.
 */
object Bim4DProductionEngine {
    data class QuantityRef(
        val key:String,
        val label:String,
        val value:Double,
        val unit:String,
        val sourceIds:List<String>,
        val verified:Boolean,
        val note:String,
        val unitRateSar:Double?=null,
        val totalSar:Double?=null
    )

    data class WorkPackage(
        val id:String,
        val title:String,
        val durationDays:Int,
        val dependencies:List<String>,
        val elementIds:List<String>,
        val quantityKeys:List<String>,
        val earliestStartDay:Int=0,
        val earliestFinishDay:Int=0,
        val critical:Boolean=false,
        val saudiNote:String=""
    )

    data class Timeline(
        val packages:List<WorkPackage>,
        val totalPlanningDays:Int,
        val criticalPath:List<String>,
        val boq:List<QuantityRef>,
        val totalCostSar:Double?,
        val costCoveragePct:Int,
        val metricReady:Boolean,
        val warnings:List<String>
    )

    private data class Spec(
        val id:String,val title:String,val days:Int,val deps:List<String>,val elements:List<String>,
        val quantityKeys:List<String>,val note:String
    )

    fun build(plan:FloorPlan):Timeline {
        val normalized=MultiFloorGeometryEngine.normalize(plan)
        val floors=floors(normalized)
        val metric=(normalized.widthM?:0.0)>0.0&&(normalized.heightM?:0.0)>0.0&&normalized.scaleConfidence>=70
        val quantities=buildQuantities(normalized,floors,metric)
        val rateMap=normalized.constraints.filter{it.active&&it.kind=="USER_COST_RATE"&&it.value!=null&&it.value>0.0}
            .associate{it.id.removePrefix("cost-rate:") to it.value!!}
        val boq=quantities.map { q->
            val rate=rateMap[q.key]
            q.copy(unitRateSar=rate,totalSar=rate?.let{it*q.value})
        }
        val allIds=allSourceIds(floors)
        val wallIds=floors.flatMap{it.walls}.map{it.id}
        val openingIds=floors.flatMap{it.openings}.map{it.id}
        val structuralIds=floors.flatMap{it.elements}.map{it.id}
        val roomIds=floors.flatMap{it.rooms}.map{it.id}
        val wetIds=floors.flatMap{it.rooms}.filter{r->val t="${r.type} ${r.name}".lowercase();t.contains("bath")||t.contains("حمام")||t.contains("دورة")||t.contains("kitchen")||t.contains("مطبخ")}.map{it.id}
        val serviceIds=floors.flatMap{it.rooms}.filter{r->val t=r.type.lowercase();t in setOf("kitchen","service","laundry","maid","bath","wc")}.map{it.id}
        val floorIds=floors.map{it.id}
        val climate=SaudiResidentialEngine.climateLabel(normalized)
        val floorCount=floors.size.coerceAtLeast(1)

        val specs=listOf(
            Spec("site","تجهيز الموقع",7,emptyList(),floorIds,listOf("gross_floor_area"),"رفع الموقع والوصول ونقاط الخدمات قبل التنفيذ."),
            Spec("earth","الحفر والأساسات",18,listOf("site"),structuralIds+floorIds,listOf("gross_floor_area"),"الأساسات النهائية تتبع تقرير التربة والتصميم الإنشائي المعتمد."),
            Spec("structure","الهيكل الإنشائي",25+(floorCount-1)*12,listOf("earth"),structuralIds+floorIds,listOf("gross_floor_area","structural_count"),"ربط عناصر الهيكل بالأدوار والعناصر الإنشائية في النموذج."),
            Spec("envelope","الجدران والغلاف",20,listOf("structure"),wallIds+openingIds,listOf("wall_length","wall_surface","doors","windows"),"مواقع الفتحات مأخوذة من Geometry V3 ولا تتغير لأجل الجدول."),
            Spec("mep_rough","MEP أولي",24,listOf("structure"),serviceIds,listOf("gross_floor_area"),"مسارات MEP هنا نطاق تخطيط؛ المخططات التنفيذية التخصصية منفصلة."),
            Spec("waterproof","العزل المائي والحراري",12,listOf("envelope","mep_rough"),wetIds+floorIds.takeLast(1),listOf("roof_area","wall_surface"),climate),
            Spec("interior","التشطيبات الداخلية",35,listOf("waterproof"),roomIds,listOf("gross_floor_area","wall_surface"),"الاستلام مقسم إلى ضيافة وعائلة وخدمات."),
            Spec("facade","الواجهة السعودية",24,listOf("envelope"),wallIds+openingIds,listOf("wall_surface","windows","doors"),"الواجهة مرتبطة بالفتحات الحقيقية وبالهوية ${SaudiResidentialEngine.styleLabel(normalized)}."),
            Spec("mep_final","تركيبات MEP النهائية",14,listOf("interior","mep_rough"),serviceIds,listOf("gross_floor_area"),"اختبار الأنظمة قبل الإقفال النهائي."),
            Spec("external","الحوش والمواقف والخارج",18,listOf("facade"),floorIds,listOf("gross_floor_area"),"الأعمال الخارجية لا تفترض حدودًا أو ارتدادات غير معطاة."),
            Spec("commission","الاختبارات والتسليم",10,listOf("mep_final","external"),allIds,emptyList(),"اختبارات، ملاحظات، تشغيل وAs-built قبل التسليم.")
        )
        val scheduled=schedule(specs)
        val terminal=scheduled.maxByOrNull{it.earliestFinishDay}
        val criticalIds=criticalPath(scheduled,terminal?.id)
        val packages=scheduled.map{it.copy(critical=it.id in criticalIds)}
        val costed=boq.filter{it.totalSar!=null}
        val eligible=boq.filter{it.verified&&it.value>0.0}
        val totalCost=if(costed.isEmpty())null else costed.sumOf{it.totalSar?:0.0}
        val coverage=if(eligible.isEmpty())0 else (eligible.count{it.totalSar!=null}*100/eligible.size)
        val warnings=buildList {
            add("4D يستخدم CPM فعليًا للمدد التخطيطية، لكنه ليس برنامج مقاول أو مدة عقدية.")
            if(!metric)add("المقياس غير مؤكد؛ أوقفت الكميات المترية بدل تخمينها.")
            else add("الكميات مشتقة من Geometry V3؛ يلزم تدقيق حصر كميات تنفيذي قبل الشراء أو التعاقد.")
            if(totalCost==null)add("لا توجد تكلفة إجمالية لأن HAI لا يخمن أسعار السوق؛ أدخل سعر SAR/وحدة لكل بند تريد تسعيره.")
            else add("التكلفة تحسب فقط من أسعار الوحدات التي أدخلها المستخدم؛ تغطية التسعير $coverage%.")
            add("المسار الحرج: ${criticalIds.joinToString(" ← ").ifBlank{"غير متاح"}}.")
        }
        return Timeline(packages,terminal?.earliestFinishDay?:0,criticalIds,boq,totalCost,coverage,metric,warnings)
    }

    fun setCostRate(plan:FloorPlan,key:String,rateSar:Double?):FloorPlan {
        val id="cost-rate:$key"
        val kept=plan.constraints.filterNot{it.id==id}
        if(rateSar==null||rateSar<=0.0)return plan.copy(constraints=kept,revision=plan.revision+1)
        val constraint=ProjectConstraint(
            id=id,kind="USER_COST_RATE",text="سعر وحدة أدخله المستخدم: $key",value=rateSar,
            hard=false,priority=20,active=true
        )
        return plan.copy(constraints=kept+constraint,revision=plan.revision+1)
    }

    private fun schedule(specs:List<Spec>):List<WorkPackage> {
        val finish=mutableMapOf<String,Int>()
        val pending=specs.toMutableList()
        val out=mutableListOf<WorkPackage>()
        var guard=0
        while(pending.isNotEmpty()&&guard++<100){
            val ready=pending.filter{s->s.deps.all{it in finish}}
            if(ready.isEmpty()){
                pending.forEach{s->out+=WorkPackage(s.id,s.title,s.days,s.deps,s.elements.distinct(),s.quantityKeys,0,s.days,false,s.note);finish[s.id]=s.days}
                break
            }
            ready.forEach{s->
                val start=s.deps.maxOfOrNull{finish[it]?:0}?:0
                val end=start+s.days
                out+=WorkPackage(s.id,s.title,s.days,s.deps,s.elements.distinct(),s.quantityKeys,start,end,false,s.note)
                finish[s.id]=end
                pending.remove(s)
            }
        }
        return out
    }

    private fun criticalPath(packages:List<WorkPackage>,terminalId:String?):List<String> {
        if(terminalId==null)return emptyList()
        val byId=packages.associateBy{it.id}
        val reversed=mutableListOf<String>()
        var current=byId[terminalId]
        while(current!=null){
            reversed+=current.id
            if(current.dependencies.isEmpty())break
            val prev=current.dependencies.mapNotNull(byId::get).maxByOrNull{it.earliestFinishDay}
            current=prev
        }
        return reversed.reversed()
    }

    private fun buildQuantities(plan:FloorPlan,floors:List<FloorLevel>,metric:Boolean):List<QuantityRef> {
        val allWalls=floors.flatMap{it.walls}
        val allOpenings=floors.flatMap{it.openings}
        val allElements=floors.flatMap{it.elements}
        val floorArea=if(metric)floors.sumOf{polygonAreaMeters(it.footprint.ifEmpty{plan.footprint},plan)}else 0.0
        val wallLength=if(metric)floors.sumOf{floor->floor.walls.sumOf{wallLengthMeters(it.start,it.end,plan)}}else 0.0
        val wallSurface=if(metric)floors.sumOf{floor->
            val h=(floor.clearHeightM?:2.80).coerceIn(2.0,6.0)
            floor.walls.sumOf{wallLengthMeters(it.start,it.end,plan)*h}
        }else 0.0
        val top=floors.maxByOrNull{it.index}
        val roofArea=if(metric&&top!=null)polygonAreaMeters(top.footprint.ifEmpty{plan.footprint},plan)else 0.0
        val doors=allOpenings.count{!OpeningVerticalProfileEngine.isWindow(it.type)}.toDouble()
        val windows=allOpenings.count{OpeningVerticalProfileEngine.isWindow(it.type)}.toDouble()
        return listOf(
            QuantityRef("gross_floor_area","إجمالي مساحة الأدوار",floorArea,"م²",floors.map{it.id},metric,"من حدود الأدوار والمقياس المؤكد."),
            QuantityRef("wall_length","طول الجدران",wallLength,"م",allWalls.map{it.id},metric,"مجموع أطوال جدران Geometry V3."),
            QuantityRef("wall_surface","مساحة أسطح جدران مرجعية",wallSurface,"م²",allWalls.map{it.id},metric,"طول الجدار × ارتفاع الدور؛ قبل خصم الفتحات والتفاصيل التنفيذية."),
            QuantityRef("roof_area","مساحة السطح",roofArea,"م²",listOfNotNull(top?.id),metric,"من حدود أعلى دور."),
            QuantityRef("doors","عدد الأبواب",doors,"عدد",allOpenings.filter{!OpeningVerticalProfileEngine.isWindow(it.type)}.map{it.id},true,"عدد عناصر الأبواب في Geometry V3."),
            QuantityRef("windows","عدد النوافذ",windows,"عدد",allOpenings.filter{OpeningVerticalProfileEngine.isWindow(it.type)}.map{it.id},true,"عدد عناصر النوافذ في Geometry V3."),
            QuantityRef("structural_count","عناصر إنشائية معرفة",allElements.size.toDouble(),"عدد",allElements.map{it.id},true,"عدد العناصر الإنشائية المعرّفة؛ ليس حصر حديد أو خرسانة.")
        )
    }

    private fun floors(plan:FloorPlan):List<FloorLevel> = if(plan.floors.isNotEmpty())plan.floors.sortedBy{it.index} else listOf(
        FloorLevel(plan.activeFloorId?:"floor-0","الدور الأرضي",0,0.0,null,plan.footprint,plan.rooms,plan.walls,plan.openings,plan.elements)
    )

    private fun allSourceIds(floors:List<FloorLevel>):List<String> = floors.flatMap{f->listOf(f.id)+f.rooms.map{it.id}+f.walls.map{it.id}+f.openings.map{it.id}+f.elements.map{it.id}}.distinct()

    private fun polygonAreaMeters(poly:List<PlanPoint>,plan:FloorPlan):Double {
        if(poly.size<3)return 0.0
        val sx=plan.widthM!!/100.0;val sy=plan.heightM!!/100.0
        var sum=0.0
        poly.indices.forEach{i->val a=poly[i];val b=poly[(i+1)%poly.size];sum+=(a.x*sx)*(b.y*sy)-(b.x*sx)*(a.y*sy)}
        return abs(sum)/2.0
    }

    private fun wallLengthMeters(a:PlanPoint,b:PlanPoint,plan:FloorPlan):Double {
        val sx=plan.widthM!!/100.0;val sy=plan.heightM!!/100.0
        return hypot((b.x-a.x)*sx,(b.y-a.y)*sy)
    }
}
