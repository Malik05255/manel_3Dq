package com.manzili.hai

import com.manzili.hai.data.PlanStorageCodec
import com.manzili.hai.engine.*
import com.manzili.hai.export.DxfPlanExporter
import com.manzili.hai.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EngineRegressionTest {
    private fun room(id: String, x: Float, y: Float, w: Float, h: Float) = Room(
        id = id, name = id, type = "room", x = x, y = y, width = w, height = h,
        areaM2 = w * h / 10.0,
        polygon = listOf(PlanPoint(x,y), PlanPoint(x+w,y), PlanPoint(x+w,y+h), PlanPoint(x,y+h))
    )

    @Test fun geometryV3AttachesWallTopology() {
        val a = room("a", 0f, 0f, 50f, 100f); val b = room("b", 50f, 0f, 50f, 100f)
        val wall = Wall("shared", PlanPoint(50f,0f), PlanPoint(50f,100f), confidence = 100)
        val result = GeometryV3Engine.inspect(FloorPlan(rooms = listOf(a,b), walls = listOf(wall), footprint = boundary()))
        assertTrue(result.valid); assertEquals(setOf("a","b"), result.plan.walls.first().adjacentRoomIds.toSet())
    }

    @Test fun geometryV3RejectsElementOutsideBoundary() {
        val column = StructuralElement("c1", "column", listOf(PlanPoint(98f,98f), PlanPoint(103f,98f), PlanPoint(103f,103f), PlanPoint(98f,103f)))
        val result = GeometryV3Engine.inspect(FloorPlan(footprint = boundary(), elements = listOf(column)))
        assertFalse(result.valid); assertTrue(result.errors.any { it.contains("خرج") })
    }

    @Test fun dxfContainsVectorLayersAndMeters() {
        val plan = FloorPlan(widthM = 20.0, heightM = 25.0, scaleConfidence = 100, rooms = listOf(room("r1",0f,0f,50f,50f)), walls = listOf(Wall("w1",PlanPoint(0f,0f),PlanPoint(100f,0f),confidence=100)), footprint = boundary())
        val dxf = DxfPlanExporter.render(plan)
        assertTrue(dxf.contains("MANZILI_HAI_METERS")); assertTrue(dxf.contains("F0_WALLS")); assertTrue(dxf.contains("LINE")); assertTrue(dxf.endsWith("EOF\n"))
    }

    @Test fun dimensionParserDoesNotTreatBareNumbersAsMeasurements() {
        val dims = DimensionEvidenceEngine.extract(listOf("غرفة 12", "عرض 350 cm", "٣.٥ × ٤.٠ م"))
        assertFalse(dims.any { it.valueM == 12.0 }); assertTrue(dims.any { kotlin.math.abs(it.valueM - 3.5) < .001 }); assertTrue(dims.any { kotlin.math.abs(it.valueM - 4.0) < .001 })
    }

    @Test fun parserFusionRaisesConfidenceOnMatchingRasterWall() {
        val base = FloorPlan(rooms=listOf(room("r1",0f,0f,100f,100f)),walls=listOf(Wall("w1",PlanPoint(0f,0f),PlanPoint(100f,0f),confidence=55)),footprint=boundary())
        val result = FloorplanParserEngine.refine(base,listOf(Wall("rw",PlanPoint(0f,.4f),PlanPoint(100f,.4f),confidence=70))).plan
        assertTrue(result.walls.first { it.id=="w1" }.confidence>=80)
    }

    @Test fun storageSchemaFiveRoundTripsV3Fields() {
        val element = StructuralElement("stair","stair",listOf(PlanPoint(10f,10f),PlanPoint(20f,10f),PlanPoint(20f,20f),PlanPoint(10f,20f)),connectsFloorIds=listOf("floor-0","floor-1"))
        val input = FloorPlan(title="roundtrip",widthM=20.0,heightM=25.0,scaleConfidence=100,walls=listOf(Wall("w",PlanPoint(0f,0f),PlanPoint(100f,0f),adjacentRoomIds=listOf("r"))),dimensions=listOf(PlanDimension("d","عرض",3.5,start=PlanPoint(1f,2f),end=PlanPoint(5f,2f),pageIndex=2)),elements=listOf(element),saudiRulesEnabled=true)
        val json=PlanStorageCodec.encode(input).toString(); assertEquals(5,JSONObject(json).getInt("schemaVersion")); val output=PlanStorageCodec.decode(JSONObject(json))
        assertEquals(2,output.dimensions.single().pageIndex); assertEquals("stair",output.elements.single().type); assertEquals(listOf("r"),output.walls.single().adjacentRoomIds); assertTrue(output.saudiRulesEnabled)
    }

    @Test fun optimizerReturnsDistinctFeasibleSolutions() {
        val program=NewBuildSolver.Program(city="الرياض",plotWidthM=20.0,plotDepthM=25.0,floorCount=1,bedrooms=3,guestEntranceIndependent=true,privacyPriority=90,circulationPriority=85,daylightPriority=80)
        val candidates=NewBuildOptimizer.generate(program)
        assertEquals(3,candidates.size); assertEquals(3,candidates.map { it.plan.rooms.joinToString("|") { r -> "${r.id}:${r.x}:${r.y}:${r.width}:${r.height}" } }.distinct().size); assertTrue(candidates.all { GeometryV3Engine.inspect(it.plan).valid })
    }

    @Test fun saudiContextIsRegionalAndNonOfficial() {
        val abha=SaudiResidentialEngine.context("أبها")
        assertEquals(SaudiResidentialEngine.Climate.HIGHLAND_MILD,abha.climate)
        val normalized=SaudiResidentialEngine.normalize(FloorPlan(site=SiteContext(countryCode="SA",city="أبها")))
        assertTrue(normalized.constraints.any { it.kind==SaudiResidentialEngine.STYLE_KIND })
        assertTrue(normalized.observations.any { it.contains("لا يساوي اشتراطًا رسميًا") })
    }

    @Test fun saudiBriefPersistsLifestyleAndRoadEvidence() {
        val brief=SaudiResidentialEngine.Brief(city="الرياض",parkingCars=3,courtyard=true,streetSide="شمال",streetWidthM=20.0,architectureStyle="نجدي معاصر")
        val plan=SaudiResidentialEngine.apply(FloorPlan(site=SiteContext(countryCode="SA")),brief)
        assertTrue(plan.saudiRulesEnabled); assertEquals(20.0,plan.site.roads.single().widthM!!,0.001)
        assertTrue(plan.constraints.any { it.kind==SaudiResidentialEngine.PARKING_KIND && it.value==3.0 })
        assertEquals("نجدي معاصر",SaudiResidentialEngine.styleLabel(plan))
    }

    @Test fun saudi3DAddsPresentationParapetWithoutChangingPlan() {
        val plan=FloorPlan(widthM=20.0,heightM=25.0,scaleConfidence=100,footprint=boundary(),site=SiteContext(countryCode="SA",city="الرياض"))
        val scene=Architectural3DEnhancementEngine.build(plan)
        assertTrue(scene.meshes.any { it.kind=="saudi-parapet" })
        assertTrue(scene.warnings.any { it.contains("ليست قياسًا تنفيذيًا") })
    }

    @Test fun saudi4DCoversWholeRelativeTimeline() {
        val timeline=SaudiConstruction4DEngine.build(FloorPlan(site=SiteContext(countryCode="SA",city="جدة")))
        assertEquals(0,timeline.phases.first().startPct); assertEquals(100,timeline.phases.last().endPct)
        assertTrue(timeline.phases.zipWithNext().all { (a,b)->a.endPct==b.startPct })
        assertTrue(timeline.warnings.any { it.contains("ليس جدول مقاول") })
    }

    private fun boundary() = listOf(PlanPoint(0f,0f),PlanPoint(100f,0f),PlanPoint(100f,100f),PlanPoint(0f,100f))
}
