package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.ProjectConstraint

/** Execution overlay on top of CPM/BOQ: reporting day, actual progress, resources and earned value. */
object Bim4DExecutionEngineV2 {
    const val PROGRESS_KIND="USER_4D_PROGRESS"
    const val REPORT_DAY_KIND="USER_4D_REPORT_DAY"

    data class ResourceNeed(val category:String,val roles:List<String>,val equipment:List<String>)
    data class PackageStatus(
        val base:Bim4DProductionEngine.WorkPackage,
        val plannedProgressPct:Int,
        val actualProgressPct:Int,
        val variancePct:Int,
        val resources:ResourceNeed,
        val budgetReferenceSar:Double?,
        val earnedValueSar:Double?
    )
    data class ExecutionTimeline(
        val base:Bim4DProductionEngine.Timeline,
        val reportingDay:Int,
        val packages:List<PackageStatus>,
        val overallPlannedProgressPct:Int,
        val overallActualProgressPct:Int,
        val schedulePerformanceIndex:Double?,
        val earnedValueSar:Double?,
        val warnings:List<String>
    )

    fun build(plan:FloorPlan):ExecutionTimeline {
        val base=Bim4DProductionEngine.build(plan)
        val reportDay=plan.constraints.lastOrNull{it.active&&it.kind==REPORT_DAY_KIND}?.value?.toInt()?.coerceIn(0,base.totalPlanningDays.coerceAtLeast(0))?:0
        val progress=plan.constraints.filter{it.active&&it.kind==PROGRESS_KIND&&it.value!=null}.associate{it.id.removePrefix("4d-progress:") to it.value!!.toInt().coerceIn(0,100)}
        val boqByKey=base.boq.associateBy{it.key}
        val statuses=base.packages.map{p->
            val planned=when{
                reportDay<=p.earliestStartDay->0
                reportDay>=p.earliestFinishDay->100
                else->(((reportDay-p.earliestStartDay).toDouble()/p.durationDays.coerceAtLeast(1))*100.0).toInt().coerceIn(0,100)
            }
            val actual=progress[p.id]?:0
            val budget=p.quantityKeys.mapNotNull{boqByKey[it]?.totalSar}.takeIf{it.isNotEmpty()}?.sum()
            PackageStatus(p,planned,actual,actual-planned,resourcesFor(p.id),budget,budget?.times(actual/100.0))
        }
        val totalWeight=statuses.sumOf{it.base.durationDays.coerceAtLeast(1)}.coerceAtLeast(1)
        val planned=(statuses.sumOf{it.plannedProgressPct*it.base.durationDays.coerceAtLeast(1)}/totalWeight).coerceIn(0,100)
        val actual=(statuses.sumOf{it.actualProgressPct*it.base.durationDays.coerceAtLeast(1)}/totalWeight).coerceIn(0,100)
        val spi=if(planned>0)actual.toDouble()/planned.toDouble() else null
        val ev=statuses.mapNotNull{it.earnedValueSar}.takeIf{it.isNotEmpty()}?.sum()
        val warnings=buildList{
            addAll(base.warnings)
            add("نسب الإنجاز Actual يدخلها المستخدم؛ HAI لا يخمّن تقدم الموقع من تلقاء نفسه.")
            add("الموارد المعروضة فئات عمل/معدات مطلوبة للتخطيط وليست أعداد عمال أو تعهد توريد.")
            if(reportDay==0)add("حدد يوم التقرير لإظهار Planned مقابل Actual وSPI.")
            if(ev==null)add("Earned Value يبقى غير متاح حتى تدخل أسعار وحدات تغطي بنود الحزم.")
        }.distinct()
        return ExecutionTimeline(base,reportDay,statuses,planned,actual,spi,ev,warnings)
    }

    fun setProgress(plan:FloorPlan,packageId:String,pct:Int):FloorPlan {
        val id="4d-progress:$packageId";val kept=plan.constraints.filterNot{it.id==id}
        val c=ProjectConstraint(id,PROGRESS_KIND,"نسبة إنجاز فعلية أدخلها المستخدم للحزمة $packageId",value=pct.coerceIn(0,100).toDouble(),hard=false,priority=20,active=true)
        return plan.copy(constraints=kept+c,revision=plan.revision+1)
    }

    fun setReportingDay(plan:FloorPlan,day:Int):FloorPlan {
        val id="4d-report-day";val kept=plan.constraints.filterNot{it.id==id}
        val c=ProjectConstraint(id,REPORT_DAY_KIND,"يوم التقرير 4D أدخله المستخدم",value=day.coerceAtLeast(0).toDouble(),hard=false,priority=20,active=true)
        return plan.copy(constraints=kept+c,revision=plan.revision+1)
    }

    private fun resourcesFor(id:String)=when(id){
        "site"->ResourceNeed("تجهيز",listOf("مساح","مشرف موقع"),listOf("معدات رفع مساحي","معدات سلامة"))
        "earth"->ResourceNeed("أعمال ترابية/أساسات",listOf("مهندس موقع","فني مختبر"),listOf("حفار","دكاك","معدات صب"))
        "structure"->ResourceNeed("إنشائي",listOf("مهندس إنشائي","حداد","نجار مسلح"),listOf("شدات","مضخة خرسانة","رافعة حسب الحاجة"))
        "envelope"->ResourceNeed("غلاف ومباني",listOf("بناء","فني ألمنيوم/فتحات"),listOf("سقالات","معدات قص"))
        "mep_rough","mep_final"->ResourceNeed("MEP",listOf("كهربائي","سباك","فني تكييف"),listOf("معدات اختبار","معدات تمديد"))
        "waterproof"->ResourceNeed("عزل",listOf("فني عزل","مشرف جودة"),listOf("معدات اختبار غمر","معدات تطبيق عزل"))
        "interior"->ResourceNeed("تشطيبات",listOf("مبلط","دهان","نجار","فني جبس"),listOf("معدات تشطيب"))
        "facade"->ResourceNeed("واجهة",listOf("فني واجهات","فني حجر/ألمنيوم"),listOf("سقالات/منصة رفع"))
        "external"->ResourceNeed("أعمال خارجية",listOf("فني رصف","منسق موقع"),listOf("معدات رصف وتسوية"))
        else->ResourceNeed("اختبارات وتسليم",listOf("مهندس تشغيل","QA/QC"),listOf("معدات قياس واختبار"))
    }
}
