package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan

object MultiPageEvidenceFusionEngine {
    fun apply(plan: FloorPlan, pages: List<RemoteFloorplanEvidenceClient.PageResult>): FloorPlan {
        if (pages.isEmpty()) return plan
        if (plan.floors.isEmpty()) {
            val p = pages.first()
            val seedRooms = if (plan.rooms.isEmpty()) p.rooms else plan.rooms
            val fused = OpeningEvidenceFusion.merge(plan.openings, p.openings, plan.walls + p.walls)
            return FloorplanParserEngine.refine(
                plan.copy(
                    rooms = seedRooms,
                    openings = fused.openings,
                    observations = (plan.observations + if (p.rooms.isNotEmpty()) {
                        "استخرج Deep Parser ${p.rooms.size} مساحة مغلقة أولية للمراجعة فوق المخطط الأصلي."
                    } else {
                        "لم يستخرج Deep Parser غرفًا مغلقة مؤكدة؛ راجع الجدران فوق الأصل قبل الاعتماد."
                    }).distinct()
                ),
                p.walls
            ).plan
        }
        val evidence = pages.associateBy { it.pageIndex }
        val floors = plan.floors.map { floor ->
            val p = evidence[floor.index] ?: return@map floor
            val fused = OpeningEvidenceFusion.merge(floor.openings, p.openings, floor.walls + p.walls)
            val local = FloorPlan(
                title = floor.name,
                widthM = plan.widthM,
                heightM = plan.heightM,
                rooms = if (floor.rooms.isEmpty()) p.rooms else floor.rooms,
                walls = floor.walls,
                openings = fused.openings,
                footprint = floor.footprint,
                scaleConfidence = plan.scaleConfidence,
                site = plan.site,
                elements = floor.elements
            )
            val refined = FloorplanParserEngine.refine(local, p.walls).plan
            floor.copy(rooms = refined.rooms, walls = refined.walls, openings = refined.openings, elements = refined.elements)
        }
        val active = floors.firstOrNull { it.id == plan.activeFloorId } ?: floors.firstOrNull()
        return plan.copy(
            floors = floors,
            activeFloorId = active?.id,
            rooms = active?.rooms ?: plan.rooms,
            walls = active?.walls ?: plan.walls,
            openings = active?.openings ?: plan.openings,
            elements = active?.elements ?: plan.elements,
            footprint = active?.footprint?.takeIf { it.isNotEmpty() } ?: plan.footprint,
            observations = (plan.observations + "تم ربط أدلة التحليل العميق بكل صفحة PDF على حدة.").distinct()
        )
    }
}
