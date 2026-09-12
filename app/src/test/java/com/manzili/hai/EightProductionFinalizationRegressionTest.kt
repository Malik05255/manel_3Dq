package com.manzili.hai

import com.manzili.hai.engine.*
import com.manzili.hai.export.GltfPlanExporter
import com.manzili.hai.model.*
import org.junit.Assert.*
import org.junit.Test

class EightProductionFinalizationRegressionTest {
    private fun room(id:String,x:Float,y:Float,w:Float,h:Float,type:String)=Room(id,id,type,x,y,w,h,w*h/25.0,polygon=listOf(PlanPoint(x,y),PlanPoint(x+w,y),PlanPoint(x+w,y+h),PlanPoint(x,y+h)))
    private fun plan():FloorPlan {
        val a=room("living",0f,0f,50f,100f,"living");val b=room("kitchen",50f,0f,50f,100f,"kitchen")
        val floor=FloorLevel("f0","الأرضي",0,0.0,2.8,rooms=listOf(a,b),walls=listOf(Wall("mid",PlanPoint(50f,0f),PlanPoint(50f,100f),confidence=100)),openings=listOf(Opening("door","door",50f,50f,8f,wallId="mid",connectsRoomIds=listOf("living","kitchen"),confidence=100)),footprint=boundary())
        return FloorPlan(widthM=20.0,heightM=20.0,scaleConfidence=100,rooms=floor.rooms,walls=floor.walls,openings=floor.openings,footprint=boundary(),floors=listOf(floor),activeFloorId="f0",site=SiteContext(countryCode="SA",city="الرياض",plotBoundary=listOf(PlanPoint(-10f,-10f),PlanPoint(110f,-10f),PlanPoint(110f,110f),PlanPoint(-10f,110f))))
    }

    @Test fun productionSceneAddsContextWithoutMutatingPlan(){
        val input=plan();val before=input.copy();val scene=ProductionSceneEngine.build(input)
        assertEquals(before,input);assertTrue(scene.meshes.any{it.kind=="site-ground"})
        val gltf=GltfPlanExporter.renderGltf(input);assertTrue(gltf.contains("Manzili HAI 0.62"));assertTrue(gltf.contains("siteContext"))
    }

    @Test fun freeWalkthroughBlocksWallsAndUsesVerifiedDoor(){
        val p=plan();val start=WalkthroughFreeMoveEngine.start(p)!!
        val blocked=WalkthroughFreeMoveEngine.move(p,start,forward=0.0,right=-200.0);assertTrue(blocked.blocked)
        var near=start.copy(x=9.7,y=10.0,yaw=0f)
        val crossed=WalkthroughFreeMoveEngine.move(p,near,forward=2.0,right=0.0)
        assertFalse(crossed.blocked);assertEquals("door",crossed.crossedOpeningId);assertEquals("kitchen",crossed.state.roomId)
    }

    @Test fun verticalMovementRequiresLinkedElement(){
        val base=plan();val state=WalkthroughFreeMoveEngine.start(base)!!
        assertTrue(WalkthroughFreeMoveEngine.verticalTargets(base,state).isEmpty())
        val stair=StructuralElement("stair","stair",listOf(PlanPoint(20f,45f),PlanPoint(30f,45f),PlanPoint(30f,55f),PlanPoint(20f,55f)),connectsFloorIds=listOf("f0","f1"))
        val f0=base.floors.first().copy(elements=listOf(stair));val f1=f0.copy(id="f1",name="الأول",index=1,elevationM=3.2,elements=listOf(stair))
        val multi=base.copy(floors=listOf(f0,f1));val near=state.copy(x=5.0,y=10.0)
        assertTrue("f1" in WalkthroughFreeMoveEngine.verticalTargets(multi,near))
        val moved=WalkthroughFreeMoveEngine.changeFloor(multi,near,"f1");assertFalse(moved.blocked);assertEquals("f1",moved.state.floorId)
    }

    @Test fun progressAndCrewAreUserControlled(){
        var p=plan();p=Bim4DProgressEngine.setProgress(p,"site",50);p=Bim4DProgressEngine.setCrew(p,"site",4)
        val d=Bim4DProgressEngine.build(p);val site=d.controls.first{it.packageId=="site"}
        assertEquals(50,site.progressPct);assertEquals(4,site.crewSize);assertTrue(d.weightedProgressPct>0);assertTrue(d.peakCrew>=4)
    }

    @Test fun experienceEngineScoresOperationalCategories(){
        val brief=SaudiDeepBriefEngine.Brief(SaudiResidentialEngine.Brief(city="الرياض"))
        val r=SaudiArchitectExperienceEngine.inspect(plan(),brief)
        assertTrue(r.categories.keys.containsAll(listOf("zoning","service_compactness","wet_core","vertical_core","external_exposure","future_flexibility")))
        assertTrue(r.score in 0..100)
    }

    private fun boundary()=listOf(PlanPoint(0f,0f),PlanPoint(100f,0f),PlanPoint(100f,100f),PlanPoint(0f,100f))
}
