package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.Room
import kotlin.math.abs

/** Bounded beam-search repair loop guided by the senior critic + independent Decision V3. */
object SaudiArchitectSearchV2Engine {
    data class Refined(val plan:FloorPlan,val score:Int,val depth:Int,val rationale:String)

    fun refine(seed:FloorPlan,type:SaudiProjectTypeEngine.Type,brief:SaudiDeepBriefEngine.Brief,beamWidth:Int=5,depth:Int=2):List<Refined> {
        var beam=listOf(score(seed,type,brief,0,"الحل الأصلي"))
        repeat(depth.coerceIn(1,3)){level->
            val expanded=mutableListOf<Refined>();expanded+=beam
            beam.forEach{state->
                val critic=SaudiArchitectCriticEngine.inspect(state.plan,type,brief)
                val decision=SaudiArchitectDecisionEngineV3.inspect(state.plan,type,brief)
                targetRooms(state.plan,critic,decision).take(6).forEach{room->
                    actionsFor(room,critic,decision).forEach{action->
                        GeometrySolver.actionCandidates(state.plan,"room",room.id,action).take(2).forEach{candidate->
                            if(candidate.review.objections.isNotEmpty())return@forEach
                            val inspected=GeometryV3Engine.inspect(candidate.plan);if(!inspected.valid)return@forEach
                            val d=SaudiArchitectDecisionEngineV3.inspect(inspected.plan,type,brief);if(d.hardViolations.isNotEmpty())return@forEach
                            val c=SaudiArchitectCriticEngine.inspect(inspected.plan,type,brief);if(c.hardViolations.isNotEmpty())return@forEach
                            expanded+=score(inspected.plan,type,brief,level+1,"$action ${room.name.ifBlank{room.type}}")
                        }
                    }
                }
            }
            beam=expanded.distinctBy{signature(it.plan)}.sortedByDescending{it.score}.take(beamWidth.coerceIn(2,8))
        }
        return beam.sortedByDescending{it.score}
    }

    private fun score(plan:FloorPlan,type:SaudiProjectTypeEngine.Type,brief:SaudiDeepBriefEngine.Brief,depth:Int,rationale:String):Refined {
        val decision=SaudiArchitectDecisionEngineV3.inspect(plan,type,brief);val critic=SaudiArchitectCriticEngine.inspect(plan,type,brief);val architectural=ArchitecturalEngine.score(plan);val saudi=SaudiResidentialEngine.inspect(plan)
        val value=(decision.score*.38+critic.score*.30+architectural.overall*.20+saudi.score*.12).toInt().coerceIn(0,100)
        return Refined(plan,value,depth,"$rationale • Decision V3 ${decision.score}/100")
    }

    private fun targetRooms(plan:FloorPlan,critic:SaudiArchitectCriticEngine.Critique,decision:SaudiArchitectDecisionEngineV3.Evaluation):List<Room> {
        val rooms=if(plan.floors.isNotEmpty())plan.floors.flatMap{it.rooms}else plan.rooms
        val low=(critic.categoryScores.entries.sortedBy{it.value}.take(3).map{it.key}+decision.categoryScores.entries.sortedBy{it.value}.take(3).map{it.key}).toSet()
        fun priority(room:Room):Int {val t=room.type.lowercase();var p=0;if(low.any{it.contains("خصوص") }&&t in setOf("majlis","guest","living","family","bedroom","master"))p+=100;if(low.any{it.contains("خدم") }&&t in setOf("kitchen","service","laundry","maid","storage"))p+=90;if(low.any{it.contains("حركة") }&&t in setOf("entry","foyer","living","family"))p+=80;if(low.any{it.contains("ضوء") }&&t in setOf("living","family","bedroom","master"))p+=55;if(t in setOf("majlis","living","family","kitchen"))p+=25;return p}
        return rooms.sortedWith(compareByDescending<Room>{priority(it)}.thenByDescending{it.areaM2})
    }

    private fun actionsFor(room:Room,critic:SaudiArchitectCriticEngine.Critique,decision:SaudiArchitectDecisionEngineV3.Evaluation):List<String> {
        val low=(critic.categoryScores.filterValues{it<75}.keys+decision.categoryScores.filterValues{it<75}.keys)
        val actions=mutableListOf("MOVE");if(low.any{it.contains("برنامج")||it.contains("حركة")||it.contains("خدم")})actions+="EXPAND";if(room.areaM2>28.0)actions+="SHRINK";if(actions.size<3)actions+="EXPAND";return actions.distinct()
    }

    private fun signature(plan:FloorPlan):String {val rooms=if(plan.floors.isNotEmpty())plan.floors.flatMap{it.rooms}else plan.rooms;return rooms.sortedBy{it.id}.joinToString("|"){r->"${r.type}:${(r.x*2).toInt()}:${(r.y*2).toInt()}:${(r.width*2).toInt()}:${(r.height*2).toInt()}"}}
    fun layoutDistance(a:FloorPlan,b:FloorPlan):Float {val ar=if(a.floors.isNotEmpty())a.floors.flatMap{it.rooms}else a.rooms;val br=if(b.floors.isNotEmpty())b.floors.flatMap{it.rooms}else b.rooms;val byType=br.groupBy{it.type.lowercase()};val distances=mutableListOf<Float>();ar.forEach{room->val best=byType[room.type.lowercase()].orEmpty().minByOrNull{x->abs(room.x-x.x)+abs(room.y-x.y)}?:return@forEach;distances+=abs(room.x-best.x)+abs(room.y-best.y)+.5f*abs(room.width-best.width)+.5f*abs(room.height-best.height)};return if(distances.isEmpty())100f else distances.average().toFloat()}
}
