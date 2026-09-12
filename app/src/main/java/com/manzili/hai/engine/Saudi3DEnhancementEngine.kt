package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.PlanPoint
import kotlin.math.hypot

object Saudi3DEnhancementEngine {
    fun build(plan: FloorPlan): Semantic3DEngine.Scene {
        val base = Architectural3DEnhancementEngine.build(plan)
        if (!plan.site.countryCode.equals("SA", true)) return base
        val footprint = topFootprint(plan)
        if (footprint.size < 3) return base
        val metric = base.metricReady
        val sx = if (metric) (plan.widthM ?: 100.0) / 100.0 else 1.0
        val sy = if (metric) (plan.heightM ?: 100.0) / 100.0 else 1.0
        val floor = plan.floors.maxByOrNull { it.index }
        val floorId = floor?.id ?: (plan.activeFloorId ?: "ground")
        val z0 = if (metric) (floor?.elevationM ?: 0.0) + (floor?.clearHeightM ?: 2.8) + 0.12 else (floor?.index ?: 0) * 12.0 + 10.45
        val h = if (metric) 1.05 else 3.7
        val t = if (metric) 0.15 else 0.55
        val pts = footprint.map { it.x * sx to it.y * sy }
        val extra = pts.indices.mapNotNull { i -> edgeMesh("saudi-parapet-$i", floorId, pts[i], pts[(i+1)%pts.size], z0, z0+h, t) }
        val warnings = base.warnings + listOf(
            "هوية 3D السعودية: ${SaudiResidentialEngine.styleLabel(plan)} • ${SaudiResidentialEngine.climateLabel(plan)}.",
            "دروة السطح عنصر عرض معماري افتراضي وليست قياسًا تنفيذيًا أو اشتراطًا رسميًا."
        )
        return base.copy(meshes = base.meshes + extra, warnings = warnings.distinct())
    }

    private fun topFootprint(plan: FloorPlan): List<PlanPoint> = plan.floors.maxByOrNull { it.index }?.footprint?.takeIf { it.size >= 3 }
        ?: plan.footprint.takeIf { it.size >= 3 }
        ?: plan.site.plotBoundary

    private fun edgeMesh(id:String,floorId:String,a:Pair<Double,Double>,b:Pair<Double,Double>,z0:Double,z1:Double,t:Double): Semantic3DEngine.Mesh? {
        val dx=b.first-a.first; val dy=b.second-a.second; val len=hypot(dx,dy); if(len<1e-6) return null
        val px=-dy/len*t/2.0; val py=dx/len*t/2.0
        val p=listOf(a.first+px to a.second+py,b.first+px to b.second+py,b.first-px to b.second-py,a.first-px to a.second-py)
        val v=p.map { Semantic3DEngine.Vec3(it.first,it.second,z0) }+p.map { Semantic3DEngine.Vec3(it.first,it.second,z1) }
        val f=listOf(
            Semantic3DEngine.Face(listOf(0,3,2,1)),Semantic3DEngine.Face(listOf(4,5,6,7)),
            Semantic3DEngine.Face(listOf(0,1,5,4)),Semantic3DEngine.Face(listOf(1,2,6,5)),
            Semantic3DEngine.Face(listOf(2,3,7,6)),Semantic3DEngine.Face(listOf(3,0,4,7))
        )
        return Semantic3DEngine.Mesh(id,"saudi-parapet","دروة سطح",floorId,"saudi-profile",v,f)
    }
}
