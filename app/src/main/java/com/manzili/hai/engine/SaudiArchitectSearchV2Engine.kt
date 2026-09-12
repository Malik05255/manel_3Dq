package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.Room
import kotlin.math.abs

object SaudiArchitectSearchV2Engine {
    data class Refined(val plan:FloorPlan,val score:Int,val depth:Int,val rationale:String)

    fun refine(seed:FloorPlan,type:SaudiProjectTypeEngine.Type,brief:SaudiDeepBriefEngine.Brief,beamWidth:Int=5,depth:Int=2):List<Refined> {
        var beam=listOf(score(seed,type,brief,0,"الحل الأصلي"))
        repeat(depth.coerceIn(1,3)){level->
            val expanded=mutableListOf<Refined>();expanded+=beam
            beam.forEach{state->
                val critic=SaudiArchitectCriticEngine.inspect(state.plan,type,brief)
                targetRooms(state.plan,critic).take(5).forEach{room->
                    actionsFor(room,critic).forEach{action->
                        GeometrySolver.actionCandidates(state.plan,"room",room.id,action).take(2).forEach{candidate->
                            if(candidate.review.objections.isNotEmpty())return@forEach
                            val inspected=GeometryV3Engine.inspect(candidate.plan);if(!inspected.valid)return@forEach
                            val scored=score(inspected.plan,type,brief,level+1,"$action ${room.name.ifBlank{room.type}}")
                            if(SaudiArchitectCriticEngine.inspect(scored.plan,type,brief).hardViolations.isEmpty())expanded+=scored
                        }
                    }
                }
            }
            beam=expanded.distinctBy{signature(it.plan)}.sortedByDescending{it.score}.take(beamWidth.coerceIn(2,8))
        }
        return beam.sortedByDescending{it.score}
    }

    private fun score(plan:FloorPlan,type:SaudiProjectTypeEngine.Type,brief:SaudiDeepBriefEngine.Brief,depth:Int,rationale:String):Refined {
        val critic=SaudiArchitectCriticEngine.inspect(plan,type,brief)
        val experience=SaudiArchitectExperienceEngine.inspect(plan,brief)
        val architectural=ArchitecturalEngine.score(plan)
        val saudi=SaudiResidentialEngine.inspect(plan)
        val score=(critic.score*.40+experience.score*.18+architectural.overall*.27+saudi.score*.15).toInt().coerceIn(0,100)
        val weakest=experience.categories.minByOrNull{it.value}
        val reason=if(weakest!=null)"$rationale • خبرة ${experience.score}/100 • أضعف ${weakest.key}=${weakest.value}" else rationale
        return Refined(plan,score,depth,reason)
    }

    private fun targetRooms(plan:FloorPlan,critic:SaudiArchitectCriticEngine.Critique):List<Room> {
        val rooms=if(plan.floors.isNotEmpty())plan.floors.flatMap{it.rooms}else plan.rooms
        val low=critic.categoryScores.entries.sortedBy{it.value}.take(3).map{it.key}.toSet()
        fun priority(room:Room):Int {
            val t=room.type.lowercase();var p=0
            if("الخصوصية" in low&&t in setOf("majlis","guest","living","family","bedroom","master"))p+=100
            if("الخدمات" in low&&t in setOf("kitchen","service","laundry","maid","storage"))p+=90
            if("الحركة" in low&&t in setOf("entry","foyer","living","family"))p+=80
            if("الضوء والواجهة" in low&&t in setOf("living","family","bedroom","master"))p+=55
            if(t in setOf("majlis","living","family","kitchen"))p+=25
            return p
        }
        return rooms.sortedWith(compareByDescending<Room>{priority(it)}.thenByDescending{it.areaM2})
    }

    private fun actionsFor(room:Room,critic:SaudiArchitectCriticEngine.Critique):List<String> {
        val low=critic.categoryScores.filterValues{it<75}.keys
        val actions=mutableListOf("MOVE")
        if("البرنامج" in low||"الحركة" in low)actions+="EXPAND"
        if(room.areaM2>28.0)actions+="SHRINK"
        if(actions.size<3)actions+="EXPAND"
        return actions.distinct()
    }

    private fun signature(plan:FloorPlan):String {
        val rooms=if(plan.floors.isNotEmpty())plan.floors.flatMap{it.rooms}else plan.rooms
        return rooms.sortedBy{it.id}.joinToString("|"){r->"${r.type}:${(r.x*2).toInt()}:${(r.y*2).toInt()}:${(r.width*2).toInt()}:${(r.height*2).toInt()}"}
    }

    fun layoutDistance(a:FloorPlan,b:FloorPlan):Float {
        val ar=if(a.floors.isNotEmpty())a.floors.flatMap{it.rooms}else a.rooms
        val br=if(b.floors.isNotEmpty())b.floors.flatMap{it.rooms}else b.rooms
        val byType=br.groupBy{it.type.lowercase()};val distances=mutableListOf<Float>()
        ar.forEach{room->val best=byType[room.type.lowercase()].orEmpty().minByOrNull{x->abs(room.x-x.x)+abs(room.y-x.y)}?:return@forEach;distances+=abs(room.x-best.x)+abs(room.y-best.y)+.5f*abs(room.width-best.width)+.5f*abs(room.height-best.height)}
        return if(distances.isEmpty())100f else distances.average().toFloat()
    }
}
