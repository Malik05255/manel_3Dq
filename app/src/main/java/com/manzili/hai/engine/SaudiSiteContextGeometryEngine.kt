package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import kotlin.math.abs

/** Visual-only site context. It never mutates canonical Geometry V3. */
object SaudiSiteContextGeometryEngine {
    data class Result(val meshes:List<Semantic3DEngine.Mesh>,val warnings:List<String>)
    fun build(plan:FloorPlan,scene:Semantic3DEngine.Scene):Result {
        val source=plan.site.plotBoundary.ifEmpty { plan.footprint }
        if(source.size<3)return Result(emptyList(),listOf("حدود الموقع غير كافية لإضافة سياق خارجي."))
        val metric=scene.metricReady
        val sx=if(metric)(plan.widthM?:100.0)/100.0 else 1.0
        val sy=if(metric)(plan.heightM?:100.0)/100.0 else 1.0
        val plot=source.map { it.x*sx to it.y*sy }
        val meshes=mutableListOf<Semantic3DEngine.Mesh>()
        val z=if(metric)-0.02 else -0.08
        meshes+=prism("site-ground","site-ground","أرض الموقع",plot,z-(if(metric)0.08 else 0.28),z)
        val p=bounds(plot)
        val building=plan.footprint.ifEmpty{source}.map { it.x*sx to it.y*sy }
        val b=bounds(building)
        val freeX=((p.maxX-p.minX)-(b.maxX-b.minX)).coerceAtLeast(0.0)
        val freeY=((p.maxY-p.minY)-(b.maxY-b.minY)).coerceAtLeast(0.0)
        val margin=freeX>(if(metric)1.5 else 5.0)||freeY>(if(metric)1.5 else 5.0)
        if(margin){
            val depth=if(metric)2.4 else 8.0
            val width=((b.maxX-b.minX)*0.34).coerceAtLeast(if(metric)2.7 else 9.0).coerceAtMost((p.maxX-p.minX)*0.48)
            val cx=(p.minX+p.maxX)/2.0
            rect(cx-width/2,p.minY,cx+width/2,(p.minY+depth).coerceAtMost(p.maxY)).takeIf{area(it)>0.1}?.let{meshes+=prism("site-parking","site-parking","موقف/مدخل سيارات",it,z,z+(if(metric)0.025 else 0.09))}
            val walkW=if(metric)1.2 else 4.0
            val walkX=(b.minX+b.maxX)/2.0
            rect((walkX-walkW/2).coerceAtLeast(p.minX),p.minY,(walkX+walkW/2).coerceAtMost(p.maxX),b.minY.coerceAtLeast(p.minY+0.2)).takeIf{area(it)>0.1}?.let{meshes+=prism("site-walkway","site-paving","ممر دخول",it,z,z+(if(metric)0.03 else 0.10))}
            val plantW=if(metric)0.65 else 2.2
            if(p.maxX-b.maxX>plantW*1.2)rect(b.maxX+plantW*.25,b.minY,(b.maxX+plantW).coerceAtMost(p.maxX),b.maxY).takeIf{area(it)>0.1}?.let{meshes+=prism("site-planting-r","site-planting","شريط زراعة",it,z,z+(if(metric)0.10 else 0.32))}
            if(b.minX-p.minX>plantW*1.2)rect((b.minX-plantW).coerceAtLeast(p.minX),b.minY,b.minX-plantW*.25,b.maxY).takeIf{area(it)>0.1}?.let{meshes+=prism("site-planting-l","site-planting","شريط زراعة",it,z,z+(if(metric)0.10 else 0.32))}
        }
        val warnings=buildList{
            add("سياق الموقع طبقة عرض فقط ولا يغير Geometry V3.")
            if(!metric)add("السياق الخارجي نسبي حتى تأكيد المقياس.")
            if(!margin)add("لم أضف عناصر خارجية إضافية لعدم وجود فراغ موقع مؤكد حول المبنى.")
        }
        return Result(meshes,warnings)
    }
    private data class Bounds(val minX:Double,val minY:Double,val maxX:Double,val maxY:Double)
    private fun bounds(p:List<Pair<Double,Double>>)=Bounds(p.minOf{it.first},p.minOf{it.second},p.maxOf{it.first},p.maxOf{it.second})
    private fun rect(x1:Double,y1:Double,x2:Double,y2:Double)=if(x2>x1&&y2>y1)listOf(x1 to y1,x2 to y1,x2 to y2,x1 to y2)else emptyList()
    private fun area(p:List<Pair<Double,Double>>):Double{if(p.size<3)return 0.0;var s=0.0;p.indices.forEach{i->val a=p[i];val b=p[(i+1)%p.size];s+=a.first*b.second-b.first*a.second};return abs(s)/2}
    private fun prism(id:String,kind:String,name:String,p:List<Pair<Double,Double>>,z0:Double,z1:Double):Semantic3DEngine.Mesh{
        val n=p.size;val v=p.map{Semantic3DEngine.Vec3(it.first,it.second,z0)}+p.map{Semantic3DEngine.Vec3(it.first,it.second,z1)}
        val f=mutableListOf<Semantic3DEngine.Face>();f+=Semantic3DEngine.Face((0 until n).reversed().toList());f+=Semantic3DEngine.Face((n until 2*n).toList());repeat(n){i->val j=(i+1)%n;f+=Semantic3DEngine.Face(listOf(i,j,n+j,n+i))}
        return Semantic3DEngine.Mesh(id,kind,name,"site",id,v,f)
    }
}
