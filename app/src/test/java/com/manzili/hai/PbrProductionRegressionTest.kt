package com.manzili.hai

import com.manzili.hai.engine.Architectural3DEnhancementEngine
import com.manzili.hai.engine.PbrSceneFramingEngine
import com.manzili.hai.export.GltfPlanExporter
import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.PlanPoint
import com.manzili.hai.model.SiteContext
import com.manzili.hai.model.Wall
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PbrProductionRegressionTest {
    private fun plan() = FloorPlan(
        title = "PBR regression",
        widthM = 20.0,
        heightM = 25.0,
        scaleConfidence = 100,
        footprint = listOf(PlanPoint(0f,0f),PlanPoint(100f,0f),PlanPoint(100f,100f),PlanPoint(0f,100f)),
        walls = listOf(
            Wall("north", PlanPoint(0f,0f), PlanPoint(100f,0f), confidence = 100),
            Wall("east", PlanPoint(100f,0f), PlanPoint(100f,100f), confidence = 100)
        ),
        site = SiteContext(countryCode = "SA", city = "الرياض")
    )

    @Test fun gltfCarriesNormalsAndPbrMetadata() {
        val json = GltfPlanExporter.renderGltf(plan())
        assertTrue(json.contains("\"NORMAL\""))
        assertTrue(json.contains("pbrMetallicRoughness"))
        assertTrue(json.contains("\"pbrReady\": true") || json.contains("\"pbrReady\":true"))
        assertTrue(json.contains("Manzili HAI 0.50"))
    }

    @Test fun glbHasValidV2ContainerHeader() {
        val bytes = GltfPlanExporter.renderGlb(plan())
        assertTrue(bytes.size > 20)
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x46546C67, header.int)
        assertEquals(2, header.int)
        assertEquals(bytes.size, header.int)
    }

    @Test fun pbrCameraFrameContainsWholeSemanticScene() {
        val scene = Architectural3DEnhancementEngine.build(plan())
        val frame = PbrSceneFramingEngine.frame(scene)
        assertTrue(frame.span > 0f)
        assertTrue(frame.cameraY > frame.targetY)
        val cameraDistanceSquared =
            (frame.cameraX-frame.targetX)*(frame.cameraX-frame.targetX) +
            (frame.cameraY-frame.targetY)*(frame.cameraY-frame.targetY) +
            (frame.cameraZ-frame.targetZ)*(frame.cameraZ-frame.targetZ)
        assertTrue(cameraDistanceSquared > frame.span * frame.span)
    }
}
