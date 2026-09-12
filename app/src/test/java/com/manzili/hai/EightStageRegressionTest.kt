package com.manzili.hai

import com.manzili.hai.engine.*
import com.manzili.hai.export.TexturedPbrGlbExporter
import com.manzili.hai.model.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EightStageRegressionTest {
    private fun room(id:String,name:String,type:String,x:Float,y:Float,w:Float,h:Float)=Room(id,name,type,x,y,w,h,areaM2=12.0,polygon=listOf(PlanPoint(x,y),PlanPoint(x+w,y),PlanPoint(x+w,y+h),PlanPoint(x,y+h)))
    private fun boundary()=listOf(PlanPoint(0f,0f),PlanPoint(100f,0f),PlanPoint(100f,100f),PlanPoint(0f,100f))

    private fun connectedPlan(withVertical:Boolean=false):FloorPlan {
        val a=room("entry","مدخل","entry",0f,0f,45f,45f);val b=room("living","صالة","living",50f,0f,50f,45f)
        val wall=Wall("w",PlanPoint(48f,0f),PlanPoint(48f,45f),confidence=100,adjacentRoomIds=listOf(a.id,b.id))
        val door=Opening("door","door",48f,22f,4f,wallId="w",connectsRoomIds=listOf(a.id,b.id),confidence=100)
        val f0=FloorLevel("f0","الأرضي",0,0.0,2.8,boundary(),listOf(a,b),listOf(wall),listOf(door),if(withVertical)listOf(StructuralElement("stair","stair",listOf(PlanPoint(40f,50f),PlanPoint(50f,50f),PlanPoint(50f,60f),PlanPoint(40f,60f)),connectsFloorIds=listOf("f0","f1")))else emptyList())
        val upperRoom=room("upper","صالة علوية","living",0f,0f,100f,60f)
        val f1=FloorLevel("f1","الأول",1,3.0,2.8,boundary(),listOf(upperRoom),elements=if(withVertical)f0.elements else emptyList())
        return FloorPlan(title="0.60 regression",widthM=20.0,heightM=25.0,scaleConfidence=100,rooms=f0.rooms,walls=f0.walls,openings=f0.openings,footprint=boundary(),floors=listOf(f0,f1),activeFloorId="f0",site=SiteContext(countryCode="SA",city="الرياض",plotBoundary=boundary()))
    }

    @Test fun texturedRuntimeGlbHasValidContainerAndTextureMetadata(){
        val bytes=TexturedPbrGlbExporter.render(connectedPlan())
        val h=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);assertEquals(0x46546C67,h.int);assertEquals(2,h.int);assertEquals(bytes.size,h.int)
        val raw=String(bytes,Charsets.ISO_8859_1);assertTrue(raw.contains("TEXCOORD_0"));assertTrue(raw.contains("embeddedTextures"));assertTrue(raw.contains("Manzili HAI 0.60 textured PBR"))
    }

    @Test fun walkthroughOnlyCrossesVerifiedDoor(){
        val plan=connectedPlan();val n=WalkthroughNavigationEngineV2.build(plan);val s=WalkthroughNavigationEngineV2.initial(n)!!
        assertTrue(WalkthroughNavigationEngineV2.exits(n,s).any{it.roomId=="living"})
        val moved=WalkthroughNavigationEngineV2.enter(n,s,"living");assertEquals("living",moved.roomId)
        val rejected=WalkthroughNavigationEngineV2.enter(n,s,"missing");assertEquals(s.roomId,rejected.roomId);assertTrue(rejected.note.contains("مرفوض"))
    }

    @Test fun walkthroughRequiresRealVerticalConnector(){
        val no=connectedPlan(false);val n1=WalkthroughNavigationEngineV2.build(no);val s1=WalkthroughNavigationEngineV2.initial(n1)!!;assertTrue(WalkthroughNavigationEngineV2.floorExits(n1,s1).isEmpty())
        val yes=connectedPlan(true);val n2=WalkthroughNavigationEngineV2.build(yes);val s2=WalkthroughNavigationEngineV2.initial(n2)!!;assertTrue(WalkthroughNavigationEngineV2.floorExits(n2,s2).any{it.floorId=="f1"});assertEquals("f1",WalkthroughNavigationEngineV2.changeFloor(n2,s2,"f1").floorId)
    }

    @Test fun fourDExecutionUsesUserProgressAndReportingDay(){
        var plan=connectedPlan(true);plan=Bim4DExecutionEngineV2.setReportingDay(plan,15);plan=Bim4DExecutionEngineV2.setProgress(plan,"site",100);plan=Bim4DExecutionEngineV2.setProgress(plan,"earth",40)
        val e=Bim4DExecutionEngineV2.build(plan);assertEquals(15,e.reportingDay);assertEquals(100,e.packages.first{it.base.id=="site"}.actualProgressPct);assertEquals(40,e.packages.first{it.base.id=="earth"}.actualProgressPct);assertTrue(e.overallActualProgressPct>0)
    }

    @Test fun architectDecisionV3RejectsLostProjectType(){
        val brief=SaudiDeepBriefEngine.Brief(SaudiResidentialEngine.Brief(city="الرياض",menMajlis=false,serviceEntrance=false,courtyard=false,maidRoom=false))
        val raw=connectedPlan(true);val bad=SaudiArchitectDecisionEngineV3.inspect(raw,SaudiProjectTypeEngine.Type.VILLA_TWO,brief);assertTrue(bad.hardViolations.isNotEmpty())
        val typed=SaudiProjectTypeEngine.apply(raw,SaudiProjectTypeEngine.Type.VILLA_TWO);val good=SaudiArchitectDecisionEngineV3.inspect(typed,SaudiProjectTypeEngine.Type.VILLA_TWO,brief);assertTrue(good.score>bad.score)
    }
}
