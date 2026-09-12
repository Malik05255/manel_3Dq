package com.manzili.hai

import com.manzili.hai.engine.*
import com.manzili.hai.export.GltfPlanExporter
import com.manzili.hai.model.*
import org.junit.Assert.*
import org.junit.Test

class SevenProductionUpgradeRegressionTest {
    private fun boundary()=listOf(PlanPoint(0f,0f),PlanPoint(100f,0f),PlanPoint(100f,100f),PlanPoint(0f,100f))
    private fun room(id:String,x:Float,y:Float,w:Float,h:Float,type:String,name:String)=Room(id,name,type,x,y,w,h,w*h/25.0,polygon=listOf(PlanPoint(x,y),PlanPoint(x+w,y),PlanPoint(x+w,y+h),PlanPoint(x,y+h)))

    private fun connectedSaudiPlan():FloorPlan {
        val majlis=room("majlis",0f,0f,50f,50f,"majlis","مجلس")
        val living=room("living",50f,0f,50f,50f,"living","صالة عائلية")
        val kitchen=room("kitchen",50f,50f,50f,50f,"kitchen","مطبخ")
        val bedroom=room("bed",0f,50f,50f,50f,"bedroom","غرفة نوم")
        val walls=listOf(
            Wall("top",PlanPoint(0f,0f),PlanPoint(100f,0f),confidence=100),
            Wall("right",PlanPoint(100f,0f),PlanPoint(100f,100f),confidence=100),
            Wall("bottom",PlanPoint(100f,100f),PlanPoint(0f,100f),confidence=100),
            Wall("left",PlanPoint(0f,100f),PlanPoint(0f,0f),confidence=100),
            Wall("mid-v-top",PlanPoint(50f,0f),PlanPoint(50f,50f),confidence=100),
            Wall("mid-v-bottom",PlanPoint(50f,50f),PlanPoint(50f,100f),confidence=100),
            Wall("mid-h-left",PlanPoint(0f,50f),PlanPoint(50f,50f),confidence=100),
            Wall("mid-h-right",PlanPoint(50f,50f),PlanPoint(100f,50f),confidence=100)
        )
        val openings=listOf(
            Opening("guest-family-door","door",50f,25f,8f,wallId="mid-v-top",connectsRoomIds=listOf("majlis","living"),confidence=100),
            Opening("family-kitchen-door","door",75f,50f,8f,wallId="mid-h-right",connectsRoomIds=listOf("living","kitchen"),confidence=100),
            Opening("bed-kitchen-door","door",50f,75f,8f,wallId="mid-v-bottom",connectsRoomIds=listOf("bed","kitchen"),confidence=100),
            Opening("living-window","window",75f,0f,10f,wallId="top",connectsRoomIds=listOf("living"),confidence=100)
        )
        val base=FloorPlan(widthM=20.0,heightM=20.0,scaleConfidence=100,rooms=listOf(majlis,living,kitchen,bedroom),walls=walls,openings=openings,footprint=boundary(),site=SiteContext(countryCode="SA",city="الرياض"))
        val styled=SaudiResidentialEngine.apply(base,SaudiResidentialEngine.Brief(city="الرياض",architectureStyle="نجدي معاصر",serviceEntrance=false,maidRoom=false))
        return SaudiProjectTypeEngine.apply(styled,SaudiProjectTypeEngine.Type.VILLA_ONE)
    }

    @Test fun saudiFacadeIsGeneratedFromExistingOpenings() {
        val plan=connectedSaudiPlan()
        assertTrue(GeometryV3Engine.inspect(plan).valid)
        val scene=Architectural3DEnhancementEngine.build(plan)
        val facade=scene.meshes.filter{it.kind.startsWith("facade-")}
        assertTrue(facade.isNotEmpty())
        assertTrue(facade.any{it.sourceId=="living-window"})
        assertTrue(scene.warnings.any{it.contains("Geometry V3")})
        val gltf=GltfPlanExporter.renderGltf(plan)
        assertTrue(gltf.contains("Saudi Limestone"))
        assertTrue(gltf.contains("facadeGeometry"))
    }

    @Test fun walkthroughUsesVerifiedDoorGraphAndMetricEyePosition() {
        val route=WalkthroughEngine.build(connectedSaudiPlan())
        assertTrue(route.metricReady)
        assertTrue(route.verifiedTransitions>=3)
        assertTrue(route.points.any{it.connectedFromPrevious&&it.viaOpeningId!=null})
        assertTrue(route.points.all{it.eyeZ>1.5f})
        assertEquals(0,route.disconnectedSegments)
    }

    @Test fun bim4dBuildsCriticalPathAndNeverGuessesCost() {
        val plan=connectedSaudiPlan()
        val timeline=Bim4DProductionEngine.build(plan)
        assertTrue(timeline.metricReady)
        assertTrue(timeline.totalPlanningDays>0)
        assertTrue(timeline.criticalPath.isNotEmpty())
        assertTrue(timeline.packages.any{it.critical})
        assertNull(timeline.totalCostSar)
        assertTrue(timeline.boq.first{it.key=="gross_floor_area"}.value>0.0)
        val priced=Bim4DProductionEngine.setCostRate(plan,"gross_floor_area",100.0)
        val pricedTimeline=Bim4DProductionEngine.build(priced)
        assertNotNull(pricedTimeline.totalCostSar)
        assertEquals(100.0,pricedTimeline.boq.first{it.key=="gross_floor_area"}.unitRateSar!!,0.001)
    }

    @Test fun seniorCriticScoresMultipleArchitecturalCategories() {
        val plan=connectedSaudiPlan()
        val brief=SaudiDeepBriefEngine.Brief(SaudiResidentialEngine.Brief(city="الرياض",serviceEntrance=false,maidRoom=false))
        val critique=SaudiArchitectCriticEngine.inspect(plan,SaudiProjectTypeEngine.Type.VILLA_ONE,brief)
        assertTrue(critique.categoryScores.size>=8)
        assertTrue(critique.categoryScores.containsKey("الخصوصية"))
        assertTrue(critique.issues.any{it.contains("المجلس")||it.contains("الضيافة")})
        assertTrue(critique.score in 0..100)
    }

    @Test fun seniorSearchNeverReturnsGeometryInvalidPlan() {
        val plan=connectedSaudiPlan()
        val brief=SaudiDeepBriefEngine.Brief(SaudiResidentialEngine.Brief(city="الرياض",serviceEntrance=false,maidRoom=false))
        val refined=SaudiArchitectSearchV2Engine.refine(plan,SaudiProjectTypeEngine.Type.VILLA_ONE,brief,beamWidth=3,depth=1)
        assertTrue(refined.isNotEmpty())
        assertTrue(refined.all{GeometryV3Engine.inspect(it.plan).valid})
        assertTrue(refined.all{SaudiProjectTypeEngine.detect(it.plan)==SaudiProjectTypeEngine.Type.VILLA_ONE})
    }
}
