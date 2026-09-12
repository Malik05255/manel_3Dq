package com.manzili.hai

import com.manzili.hai.engine.*
import com.manzili.hai.model.*
import org.junit.Assert.*
import org.junit.Test

class SevenStageRegressionTest {
    private fun room(id:String,name:String,type:String,x:Float,y:Float,w:Float,h:Float)=Room(
        id=id,name=name,type=type,x=x,y=y,width=w,height=h,areaM2=(w*h/10000.0)*500.0,
        polygon=listOf(PlanPoint(x,y),PlanPoint(x+w,y),PlanPoint(x+w,y+h),PlanPoint(x,y+h))
    )
    private fun boundary()=listOf(PlanPoint(0f,0f),PlanPoint(100f,0f),PlanPoint(100f,100f),PlanPoint(0f,100f))
    private fun basePlan(metric:Boolean=true):FloorPlan {
        val rooms=listOf(
            room("majlis","مجلس","majlis",0f,0f,45f,35f),
            room("living","صالة عائلية","living",50f,0f,50f,35f),
            room("kitchen","مطبخ","kitchen",0f,40f,40f,30f),
            room("bed","غرفة نوم","bedroom",45f,40f,55f,30f)
        )
        val floor=FloorLevel("floor-0","الدور الأرضي",0,footprint=boundary(),rooms=rooms)
        return FloorPlan(
            title="اختبار سبع مراحل",
            widthM=if(metric)20.0 else null,
            heightM=if(metric)25.0 else null,
            scaleConfidence=if(metric)100 else 0,
            rooms=rooms,footprint=boundary(),floors=listOf(floor),activeFloorId="floor-0",
            site=SiteContext(countryCode="SA",city="الرياض",plotBoundary=boundary(),northDeg=0f)
        )
    }

    @Test fun visualRenderProfileDoesNotMutateCanonicalGeometry() {
        val input=SaudiProjectTypeEngine.apply(basePlan(),SaudiProjectTypeEngine.Type.VILLA_ONE)
        val before=input.copy()
        val profile=SaudiVisualRenderEngine.build(input,SaudiVisualRenderEngine.Quality.HIGH)
        assertEquals(before,input)
        assertTrue(profile.materials.containsKey("window"))
        assertTrue(profile.facade.features.isNotEmpty())
        assertTrue(profile.warnings.any { it.contains("Geometry V3") })
    }

    @Test fun walkthroughUsesOnlyExistingRoomIds() {
        val plan=basePlan()
        val route=WalkthroughEngine.build(plan)
        val ids=plan.floors.flatMap { it.rooms }.map { it.id }.toSet()
        assertTrue(route.points.isNotEmpty())
        assertTrue(route.points.all { it.roomId in ids })
        assertTrue(route.disconnectedSegments > 0)
        assertEquals(0,route.verifiedTransitions)
        assertTrue(route.warnings.any { it.contains("اختراع باب") })
    }

    @Test fun fourDDependenciesAndQuantitiesRespectMetricGate() {
        val metric=SaudiConstruction4DEngine.build(basePlan(metric=true))
        assertTrue(metric.metricQuantitiesReady)
        assertTrue(metric.totalPlanningDays>0)
        assertTrue(metric.phases.drop(1).any { it.dependencies.isNotEmpty() })
        assertTrue(metric.phases.any { phase->phase.quantities.any { it.verified && it.value>0 } })

        val relative=SaudiConstruction4DEngine.build(basePlan(metric=false))
        assertFalse(relative.metricQuantitiesReady)
        assertTrue(relative.phases.all { it.quantities.isEmpty() })
        assertTrue(relative.warnings.any { it.contains("أوقفت الكميات") })
    }

    @Test fun officialRulesStayFullyOptional() {
        val disabled=basePlan().copy(saudiRulesEnabled=false)
        assertTrue(SaudiRulesEngine.inspect(disabled).checks.isEmpty())
        val enabled=disabled.copy(saudiRulesEnabled=true)
        assertTrue(SaudiRulesEngine.inspect(enabled).checks.isNotEmpty())
        assertEquals(disabled.rooms,enabled.rooms)
        assertEquals(disabled.footprint,enabled.footprint)
    }

    @Test fun architectCriticRejectsLostProjectTypeAndRewardsPreservedType() {
        val brief=SaudiDeepBriefEngine.Brief(SaudiResidentialEngine.Brief(city="الرياض",menMajlis=true,serviceEntrance=false,courtyard=false,maidRoom=false))
        val untyped=basePlan()
        val bad=SaudiArchitectCriticEngine.inspect(untyped,SaudiProjectTypeEngine.Type.VILLA_ONE,brief)
        assertTrue(bad.hardViolations.isNotEmpty())

        val typed=SaudiProjectTypeEngine.apply(untyped,SaudiProjectTypeEngine.Type.VILLA_ONE)
        val good=SaudiArchitectCriticEngine.inspect(typed,SaudiProjectTypeEngine.Type.VILLA_ONE,brief)
        assertTrue(good.hardViolations.isEmpty())
        assertTrue(good.score>bad.score)
    }
}
