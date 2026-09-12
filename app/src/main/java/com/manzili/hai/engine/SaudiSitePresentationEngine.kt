package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.PlanPoint
import kotlin.math.abs

/** Presentation-only site geometry for the PBR viewer. */
object SaudiSitePresentationEngine {
    data class Result(val meshes:List<Semantic3DEngine.Mesh>,val warnings:List<String>)

    fun build(plan:FloorPlan,scene:Semantic3DEngine.Scene):Result {
        val metric=scene.metricReady
        val sx=if(metric)(plan.widthM?:100.0)/100.0 else 1.0
        val sy=if(metric)(plan.heightM?:100.0)/100.0 else 1.0
        val boundary=plan.site.plotBoundary.ifEmpty{plan.footprint}
        if(boundary.size<3)return Result(emptyList(),listOf("لم أضف أرضية موقع لأن حدود القطعة/المبنى غير كافية."))
        val polygon=boundary.map{it.x*sx to it.y*sy}
        val meshes=mutableListOf<Semantic3DEngine.Mesh>()
        val z0=if(metric)-0.07 else -0.25
        val z1=if(metric)-0.015 else -0.05
        meshes+=prism("site-ground","site-ground","أرضية الموقع","site","site-boundary",polygon,z0,z1)

        // Small corner planters stay strictly inside the supplied boundary bbox and are render-only.
        val minX=polygon.minOf{it.first};val maxX=polygon.maxOf{it.first};val minY=polygon.minOf{it.second};val maxY=polygon.maxOf{it.second}
        val spanX=maxX-minX;val spanY=maxY-minY
        if(spanX>1.0&&spanY>1.0){
            val w=if(metric)minOf(1.2,spanX*.08) else minOf(4.0,spanX*.08)
            val d=if(metric)minOf(1.2,spanY*.08) else minOf(4.0,spanY*.08)
            val h=if(metric)0.32 else 1.0
            listOf(minX+w*.7 to minY+d*.7,maxX-w*.7 to maxY-d*.7).forEachIndexed{i,c->
                val p=listOf(c.first-w/2 to c.second-d/2,c.first+w/2 to c.second-d/2,c.first+w/2 to c.second+d/2,c.first-w/2 to c.second+d/2)
                meshes+=prism("site-planter-$i","landscape","حوض زراعة","site","site-boundary",p,z1,z1+h)
            }
        }
        return Result(meshes,listOf("أرضية الموقع والزراعة عناصر Presentation فقط؛ لا تعدل المخطط أو الاشتراطات."))
    }

    private fun prism(id:String,kind:String,name:String,floorId:String,sourceId:String,polygon:List<Pair<Double,Double>>,zMin:Double,zMax:Double):Semantic3DEngine.Mesh{
        val n=polygon.size
        val vertices=polygon.map{Semantic3DEngine.Vec3(it.first,it.second,zMin)}+polygon.map{Semantic3DEngine.Vec3(it.first,it.second,zMax)}
        val faces=mutableListOf<Semantic3DEngine.Face>()
        faces+=Semantic3DEngine.Face((0 until n).reversed().toList())
        faces+=Semantic3DEngine.Face((n until 2*n).toList())
        repeat(n){i->val j=(i+1)%n;faces+=Semantic3DEngine.Face(listOf(i,j,n+j,n+i))}
        return Semantic3DEngine.Mesh(id,kind,name,floorId,sourceId,vertices,faces)
    }
}
