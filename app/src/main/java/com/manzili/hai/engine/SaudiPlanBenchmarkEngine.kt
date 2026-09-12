package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import kotlin.math.abs
import kotlin.math.hypot

object SaudiPlanBenchmarkEngine {
    data class Sample(val id:String,val city:String,val reference:FloorPlan,val result:FloorPlan)
    data class Metrics(val rooms:Double,val walls:Double,val openings:Double,val dimensions:Double,val geometryValid:Boolean,val overall:Double)
    data class Evaluation(val id:String,val city:String,val metrics:Metrics)
    data class Report(val evaluations:List<Evaluation>) {
        val count:Int get()=evaluations.size
        val meanOverall:Double get()=avg(evaluations.map { it.metrics.overall })
        val geometryPassRate:Double get()=if(evaluations.isEmpty())0.0 else evaluations.count { it.metrics.geometryValid }.toDouble()/evaluations.size
    }

    fun evaluate(sample:Sample):Evaluation {
        val roomScore=roomScore(sample.reference,sample.result)
        val wallScore=wallScore(sample.reference,sample.result)
        val openingScore=openingScore(sample.reference,sample.result)
        val dimensionScore=dimensionScore(sample.reference,sample.result)
        val geometry=GeometryV3Engine.inspect(sample.result).valid
        val overall=(roomScore*.30+wallScore*.30+openingScore*.22+dimensionScore*.13+(if(geometry)1.0 else 0.0)*.05).coerceIn(0.0,1.0)
        return Evaluation(sample.id,sample.city,Metrics(roomScore,wallScore,openingScore,dimensionScore,geometry,overall))
    }

    fun evaluateAll(samples:List<Sample>)=Report(samples.map(::evaluate))

    private fun roomScore(a:FloorPlan,b:FloorPlan):Double {
        val x=rooms(a).map { normalize(it.type,it.name) };val y=rooms(b).map { normalize(it.type,it.name) }
        val xa=x.groupingBy { it }.eachCount();val ya=y.groupingBy { it }.eachCount()
        val hit=xa.keys.union(ya.keys).sumOf { minOf(xa[it]?:0,ya[it]?:0) }
        return f1(hit,y.size-hit,x.size-hit)
    }

    private fun wallScore(a:FloorPlan,b:FloorPlan):Double {
        val ref=walls(a);val found=walls(b).toMutableList();var hit=0
        ref.forEach { w->val best=found.withIndex().minByOrNull { wallDistance(w.start.x,w.start.y,w.end.x,w.end.y,it.value.start.x,it.value.start.y,it.value.end.x,it.value.end.y) };if(best!=null&&wallDistance(w.start.x,w.start.y,w.end.x,w.end.y,best.value.start.x,best.value.start.y,best.value.end.x,best.value.end.y)<=7.0){hit++;found.removeAt(best.index)} }
        return f1(hit,walls(b).size-hit,ref.size-hit)
    }

    private fun openingScore(a:FloorPlan,b:FloorPlan):Double {
        val ref=openings(a);val found=openings(b).toMutableList();var hit=0
        ref.forEach { o->val best=found.withIndex().filter { it.value.type.equals(o.type,true) }.minByOrNull { hypot((it.value.x-o.x).toDouble(),(it.value.y-o.y).toDouble()) };if(best!=null&&hypot((best.value.x-o.x).toDouble(),(best.value.y-o.y).toDouble())<=6.5){hit++;found.removeAt(best.index)} }
        return f1(hit,openings(b).size-hit,ref.size-hit)
    }

    private fun dimensionScore(a:FloorPlan,b:FloorPlan):Double {
        if(a.dimensions.isEmpty())return if(b.dimensions.isEmpty())1.0 else .5
        val found=b.dimensions.toMutableList();var hit=0
        a.dimensions.forEach { d->val best=found.withIndex().minByOrNull { abs(it.value.valueM-d.valueM) };if(best!=null&&abs(best.value.valueM-d.valueM)<=maxOf(.12,d.valueM*.05)){hit++;found.removeAt(best.index)} }
        return hit.toDouble()/a.dimensions.size
    }

    private fun rooms(p:FloorPlan)=if(p.floors.isNotEmpty())p.floors.flatMap { it.rooms } else p.rooms
    private fun walls(p:FloorPlan)=if(p.floors.isNotEmpty())p.floors.flatMap { it.walls } else p.walls
    private fun openings(p:FloorPlan)=if(p.floors.isNotEmpty())p.floors.flatMap { it.openings } else p.openings
    private fun normalize(type:String,name:String):String { val s=(type+" "+name).lowercase();return when { "مجلس" in s||"majlis" in s->"majlis";"صالة" in s||"living" in s||"family" in s->"living";"مطبخ" in s||"kitchen" in s->"kitchen";"نوم" in s||"bedroom" in s||"master" in s->"bedroom";"حمام" in s||"bath" in s||"wc" in s->"bath";else->type.lowercase().ifBlank { "room" } } }
    private fun wallDistance(ax:Float,ay:Float,bx:Float,by:Float,cx:Float,cy:Float,dx:Float,dy:Float):Double { fun d(x1:Float,y1:Float,x2:Float,y2:Float)=hypot((x1-x2).toDouble(),(y1-y2).toDouble());return minOf(d(ax,ay,cx,cy)+d(bx,by,dx,dy),d(ax,ay,dx,dy)+d(bx,by,cx,cy))/2.0 }
    private fun f1(tp:Int,fp:Int,fn:Int):Double { if(tp==0)return if(fp==0&&fn==0)1.0 else 0.0;val p=tp.toDouble()/(tp+fp);val r=tp.toDouble()/(tp+fn);return 2*p*r/(p+r) }
    private fun avg(v:List<Double>)=if(v.isEmpty())0.0 else v.average()
}
