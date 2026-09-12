package com.manzili.hai

import com.manzili.hai.engine.SaudiProjectTypeEngine
import com.manzili.hai.engine.SaudiRulesEngine
import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.FloorLevel
import com.manzili.hai.model.PlanPoint
import com.manzili.hai.model.SiteContext
import org.junit.Assert.*
import org.junit.Test

class SaudiRulesScopeRegressionTest {
    private val boundary=listOf(PlanPoint(0f,0f),PlanPoint(100f,0f),PlanPoint(100f,100f),PlanPoint(0f,100f))

    @Test fun verifiedScopeCanReachPassOnlyWhenExplicitInputsExist() {
        var plan=FloorPlan(
            widthM=20.0,heightM=25.0,scaleConfidence=100,footprint=boundary,
            floors=listOf(FloorLevel("floor-0","الأرضي",0,footprint=boundary)),
            site=SiteContext(countryCode="SA",city="الرياض",plotBoundary=boundary),saudiRulesEnabled=true
        )
        plan=SaudiProjectTypeEngine.apply(plan,SaudiProjectTypeEngine.Type.VILLA_ONE)
        assertFalse(SaudiRulesEngine.inspect(plan).codeScopeReady)
        plan=SaudiRulesEngine.setScopeInputs(plan,basementCount=0,familyCount=1,independentEgress=true,openSides=null)
        val report=SaudiRulesEngine.inspect(plan)
        assertTrue(report.codeScopeReady)
        assertEquals(SaudiRulesEngine.Status.PASS,report.checks.first { it.id=="family-count" }.status)
    }

    @Test fun townhouseNeedsAtLeastTwoOpenSidesForScopePass() {
        var plan=FloorPlan(
            widthM=8.0,heightM=25.0,scaleConfidence=100,footprint=boundary,
            floors=listOf(FloorLevel("floor-0","الأرضي",0,footprint=boundary),FloorLevel("floor-1","الأول",1,footprint=boundary)),
            site=SiteContext(countryCode="SA",city="الرياض",plotBoundary=boundary),saudiRulesEnabled=true
        )
        plan=SaudiProjectTypeEngine.apply(plan,SaudiProjectTypeEngine.Type.TOWNHOUSE)
        plan=SaudiRulesEngine.setScopeInputs(plan,0,1,true,1)
        assertEquals(SaudiRulesEngine.Status.REVIEW,SaudiRulesEngine.inspect(plan).checks.first { it.id=="townhouse-open-sides" }.status)
        plan=SaudiRulesEngine.setScopeInputs(plan,0,1,true,2)
        assertTrue(SaudiRulesEngine.inspect(plan).codeScopeReady)
    }
}
