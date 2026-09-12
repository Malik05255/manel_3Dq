package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan

object ProductionSceneEngine {
    fun build(plan:FloorPlan):Semantic3DEngine.Scene {
        val base=Architectural3DEnhancementEngine.build(plan)
        val site=SaudiSiteContextGeometryEngine.build(plan,base)
        return base.copy(meshes=base.meshes+site.meshes,warnings=(base.warnings+site.warnings).distinct())
    }
}
