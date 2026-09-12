package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.Opening
import com.manzili.hai.model.PlanPoint
import com.manzili.hai.model.Wall
import kotlin.math.hypot

/** Adds architectural meshes on top of the canonical Semantic3D scene. */
object Architectural3DEnhancementEngine {
    fun build(plan: FloorPlan): Semantic3DEngine.Scene {
        val base = Semantic3DEngine.build(plan)
        val extra = mutableListOf<Semantic3DEngine.Mesh>()
        val warnings = base.warnings.toMutableList()
        val metricReady = base.metricReady
        val sx = if (metricReady) (plan.widthM ?: 100.0) / 100.0 else 1.0
        val sy = if (metricReady) (plan.heightM ?: 100.0) / 100.0 else 1.0

        floorSources(plan).forEachIndexed { index, floor ->
            val elevation = if (metricReady) floor.elevationM else index * 12.0
            val clearHeight = if (metricReady) (floor.clearHeightM ?: 2.80).coerceIn(2.0, 6.0) else 10.0
            val wallById = floor.walls.associateBy { it.id }

            floor.openings.forEach { opening ->
                val wall = opening.wallId?.let(wallById::get) ?: return@forEach
                val node = base.openings.firstOrNull { it.id == opening.id && it.floorId == floor.id } ?: return@forEach
                val kind = if (OpeningVerticalProfileEngine.isWindow(opening.type)) "window" else "door"
                thinPanel(
                    id = "$kind-${floor.id}-${opening.id}", kind = kind, name = opening.id,
                    floorId = floor.id, sourceId = opening.id, wall = wall,
                    centerX = node.center.x, centerY = node.center.y,
                    zMin = elevation + node.sillHeight, zMax = elevation + node.sillHeight + node.height,
                    width = node.width, thickness = if (metricReady) 0.035 else 0.18, sx = sx, sy = sy
                )?.let(extra::add)
            }

            val footprint = floor.footprint.ifEmpty { deriveFootprint(floor.walls) }
            if (footprint.size >= 3) {
                val roofThickness = if (metricReady) 0.12 else 0.45
                val z = elevation + clearHeight
                extra += prism(
                    id = "roof-${floor.id}", kind = "roof", name = "${floor.name} roof",
                    floorId = floor.id, sourceId = floor.id,
                    polygon = footprint.map { it.x * sx to it.y * sy }, zMin = z, zMax = z + roofThickness
                )
                if (plan.site.countryCode.equals("SA", true) && index == floorSources(plan).lastIndex) {
                    val parapetHeight = if (metricReady) 1.05 else 3.7
                    val parapetThickness = if (metricReady) 0.15 else 0.55
                    val points = footprint.map { it.x * sx to it.y * sy }
                    points.indices.forEach { i ->
                        edgePrism(
                            id = "saudi-parapet-$i", floorId = floor.id,
                            a = points[i], b = points[(i + 1) % points.size],
                            zMin = z + roofThickness, zMax = z + roofThickness + parapetHeight,
                            thickness = parapetThickness
                        )?.let(extra::add)
                    }
                }
            } else warnings += "${floor.name}: لم أضف سقف 3D لأن حدود الدور غير كافية."
        }

        val preFacade = base.copy(meshes = base.meshes + extra)
        val facade = SaudiFacadeGeometryEngine.build(plan, preFacade)
        warnings += facade.warnings
        if (plan.site.countryCode.equals("SA", true)) {
            warnings += "هوية 3D السعودية: ${SaudiResidentialEngine.styleLabel(plan)} • ${SaudiResidentialEngine.climateLabel(plan)}."
            warnings += "دروة السطح وعناصر الواجهة طبقة تصميم هندسية مشتقة من المخطط وليست قياسات تنفيذية أو اشتراطات رسمية."
        }
        return base.copy(meshes = base.meshes + extra + facade.meshes, warnings = warnings.distinct())
    }

    private data class FloorSource(
        val id: String, val name: String, val elevationM: Double, val clearHeightM: Double?,
        val footprint: List<PlanPoint>, val walls: List<Wall>, val openings: List<Opening>
    )

    private fun floorSources(plan: FloorPlan): List<FloorSource> = if (plan.floors.isNotEmpty()) {
        plan.floors.sortedBy { it.index }.map { FloorSource(it.id, it.name, it.elevationM, it.clearHeightM, it.footprint, it.walls, it.openings) }
    } else listOf(FloorSource(plan.activeFloorId ?: "ground", "الدور الأرضي", 0.0, null, plan.footprint, plan.walls, plan.openings))

    private fun thinPanel(
        id:String, kind:String, name:String, floorId:String, sourceId:String, wall:Wall,
        centerX:Double, centerY:Double, zMin:Double, zMax:Double, width:Double, thickness:Double,
        sx:Double, sy:Double
    ): Semantic3DEngine.Mesh? {
        val ax=wall.start.x*sx; val ay=wall.start.y*sy; val bx=wall.end.x*sx; val by=wall.end.y*sy
        val dx=bx-ax; val dy=by-ay; val len=hypot(dx,dy); if(len<1e-6||width<=0.0||zMax<=zMin)return null
        val ux=dx/len; val uy=dy/len; val px=-uy; val py=ux; val halfW=width/2.0; val halfT=thickness/2.0
        val a=centerX-ux*halfW to centerY-uy*halfW; val b=centerX+ux*halfW to centerY+uy*halfW
        val v=listOf(
            Semantic3DEngine.Vec3(a.first+px*halfT,a.second+py*halfT,zMin),Semantic3DEngine.Vec3(b.first+px*halfT,b.second+py*halfT,zMin),
            Semantic3DEngine.Vec3(b.first-px*halfT,b.second-py*halfT,zMin),Semantic3DEngine.Vec3(a.first-px*halfT,a.second-py*halfT,zMin),
            Semantic3DEngine.Vec3(a.first+px*halfT,a.second+py*halfT,zMax),Semantic3DEngine.Vec3(b.first+px*halfT,b.second+py*halfT,zMax),
            Semantic3DEngine.Vec3(b.first-px*halfT,b.second-py*halfT,zMax),Semantic3DEngine.Vec3(a.first-px*halfT,a.second-py*halfT,zMax)
        )
        return Semantic3DEngine.Mesh(id,kind,name,floorId,sourceId,v,boxFaces())
    }

    private fun edgePrism(id:String,floorId:String,a:Pair<Double,Double>,b:Pair<Double,Double>,zMin:Double,zMax:Double,thickness:Double):Semantic3DEngine.Mesh? {
        val dx=b.first-a.first; val dy=b.second-a.second; val len=hypot(dx,dy); if(len<1e-6)return null
        val px=-dy/len*thickness/2.0; val py=dx/len*thickness/2.0
        val p=listOf(a.first+px to a.second+py,b.first+px to b.second+py,b.first-px to b.second-py,a.first-px to a.second-py)
        val v=p.map { Semantic3DEngine.Vec3(it.first,it.second,zMin) }+p.map { Semantic3DEngine.Vec3(it.first,it.second,zMax) }
        return Semantic3DEngine.Mesh(id,"saudi-parapet","دروة سطح",floorId,"saudi-profile",v,boxFaces())
    }

    private fun boxFaces() = listOf(
        Semantic3DEngine.Face(listOf(0,3,2,1)), Semantic3DEngine.Face(listOf(4,5,6,7)),
        Semantic3DEngine.Face(listOf(0,1,5,4)), Semantic3DEngine.Face(listOf(1,2,6,5)),
        Semantic3DEngine.Face(listOf(2,3,7,6)), Semantic3DEngine.Face(listOf(3,0,4,7))
    )

    private fun prism(id:String,kind:String,name:String,floorId:String,sourceId:String,polygon:List<Pair<Double,Double>>,zMin:Double,zMax:Double):Semantic3DEngine.Mesh {
        val n=polygon.size
        val vertices=polygon.map { Semantic3DEngine.Vec3(it.first,it.second,zMin) }+polygon.map { Semantic3DEngine.Vec3(it.first,it.second,zMax) }
        val faces=mutableListOf<Semantic3DEngine.Face>(); faces+=Semantic3DEngine.Face((0 until n).reversed().toList()); faces+=Semantic3DEngine.Face((n until 2*n).toList())
        repeat(n){i->val j=(i+1)%n;faces+=Semantic3DEngine.Face(listOf(i,j,n+j,n+i))}
        return Semantic3DEngine.Mesh(id,kind,name,floorId,sourceId,vertices,faces)
    }

    private fun deriveFootprint(walls:List<Wall>):List<PlanPoint> {
        val pts=walls.flatMap { listOf(it.start,it.end) }; if(pts.size<3)return emptyList()
        val minX=pts.minOf { it.x };val maxX=pts.maxOf { it.x };val minY=pts.minOf { it.y };val maxY=pts.maxOf { it.y }
        if(maxX-minX<0.01f||maxY-minY<0.01f)return emptyList()
        return listOf(PlanPoint(minX,minY),PlanPoint(maxX,minY),PlanPoint(maxX,maxY),PlanPoint(minX,maxY))
    }
}
