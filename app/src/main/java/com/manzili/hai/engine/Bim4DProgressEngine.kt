package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.ProjectConstraint

object Bim4DProgressEngine {
    data class Control(val packageId:String,val progressPct:Int,val crewSize:Int)
    data class Dashboard(val controls:List<Control>,val weightedProgressPct:Int,val activeCrew:Int,val peakCrew:Int)

    fun build(plan:FloorPlan):Dashboard {
        val timeline=Bim4DProductionEngine.build(plan)
        val controls=timeline.packages.map{p->Control(p.id,value(plan,"USER_4D_PROGRESS",p.id).toInt().coerceIn(0,100),value(plan,"USER_4D_CREW",p.id).toInt().coerceAtLeast(0))}
        val weight=timeline.packages.sumOf{it.durationDays}.coerceAtLeast(1)
        val progress=timeline.packages.sumOf{p->p.durationDays*(controls.first{it.packageId==p.id}.progressPct)}/weight
        val active=controls.filter{it.progressPct in 1..99}.sumOf{it.crewSize}
        val peak=if(timeline.totalPlanningDays<=0)0 else (0..timeline.totalPlanningDays).maxOf{day->timeline.packages.filter{day>=it.earliestStartDay&&day<it.earliestFinishDay}.sumOf{p->controls.firstOrNull{it.packageId==p.id}?.crewSize?:0}}
        return Dashboard(controls,progress,active,peak)
    }

    fun setProgress(plan:FloorPlan,id:String,pct:Int)=set(plan,"USER_4D_PROGRESS",id,pct.coerceIn(0,100).toDouble())
    fun setCrew(plan:FloorPlan,id:String,count:Int)=set(plan,"USER_4D_CREW",id,count.coerceAtLeast(0).toDouble())

    private fun value(plan:FloorPlan,kind:String,id:String)=plan.constraints.firstOrNull{it.active&&it.kind==kind&&it.targetIds.contains(id)}?.value?:0.0
    private fun set(plan:FloorPlan,kind:String,id:String,value:Double):FloorPlan{
        val key="4d:$kind:$id";val kept=plan.constraints.filterNot{it.id==key}
        val c=ProjectConstraint(key,kind,"4D user input",targetIds=listOf(id),value=value,hard=false,priority=15,active=true)
        return plan.copy(constraints=kept+c,revision=plan.revision+1)
    }
}
