package com.manzili.hai.engine

import com.manzili.hai.model.*

/** Converts independently-read PDF pages into explicit floor/page layers with collision-free IDs. */
object MultiPagePlanFusionEngine {
    fun merge(pages:List<FloorPlan>,totalPdfPages:Int=pages.size):FloorPlan {
        require(pages.isNotEmpty()){"لا توجد صفحات لدمجها"}
        if(pages.size==1) return pages.first()
        val normalized=pages.mapIndexed { index,plan -> prefix(plan,index) }
        val floors=normalized.mapIndexed { index,p ->
            FloorLevel(
                id="pdf-floor-$index",
                name=if(index==0)"صفحة 1 / الدور المرجعي" else "صفحة ${index+1}",
                index=index,
                elevationM=index*3.2,
                clearHeightM=p.floors.firstOrNull()?.clearHeightM,
                footprint=p.footprint.ifEmpty { p.site.plotBoundary },
                rooms=p.rooms,
                walls=p.walls,
                openings=p.openings,
                elements=p.elements
            )
        }
        val first=normalized.first()
        val observations=(normalized.flatMap { it.observations } + listOf(
            "PDF متعدد الصفحات: تم تحليل ${pages.size} صفحة بصريًا وربط كل صفحة بطبقة مستقلة للمراجعة.",
            if(totalPdfPages>pages.size) "الملف يحتوي $totalPdfPages صفحة؛ تم تحليل أول ${pages.size} صفحات فقط بسبب حد الحماية على الجوال." else "تمت تغطية جميع صفحات PDF بصريًا."
        )).distinct()
        val uncertainties=(normalized.flatMap { it.uncertainties } +
            "ترتيب الصفحات لا يثبت تلقائيًا أنها أدوار متتابعة؛ راجع أسماء الأدوار قبل الاعتماد.").distinct()
        return first.copy(
            title=first.title.ifBlank { "مخطط PDF" },
            floors=floors,
            activeFloorId=floors.first().id,
            rooms=floors.first().rooms,
            walls=floors.first().walls,
            openings=floors.first().openings,
            elements=floors.first().elements,
            footprint=floors.first().footprint,
            dimensions=normalized.flatMap { it.dimensions }.distinctBy { "${it.pageIndex}:${it.id}:${it.valueM}" },
            observations=observations,
            uncertainties=uncertainties,
            sourceSummary="HAI حلّل ${pages.size} صفحة PDF كطبقات هندسية مستقلة ثم وحّدها في مشروع واحد."
        )
    }

    private fun prefix(plan:FloorPlan,page:Int):FloorPlan {
        val p="p${page}-"
        val roomMap=plan.rooms.associate { it.id to p+it.id }
        val wallMap=plan.walls.associate { it.id to p+it.id }
        fun room(r:Room)=r.copy(id=roomMap[r.id]?:p+r.id)
        fun wall(w:Wall)=w.copy(id=wallMap[w.id]?:p+w.id,adjacentRoomIds=w.adjacentRoomIds.map { roomMap[it]?:p+it })
        fun opening(o:Opening)=o.copy(
            id=p+o.id,
            wallId=o.wallId?.let { wallMap[it]?:p+it },
            connectsRoomIds=o.connectsRoomIds.map { roomMap[it]?:p+it }
        )
        fun element(e:StructuralElement)=e.copy(id=p+e.id,connectsFloorIds=e.connectsFloorIds.map { "pdf-floor-$page" })
        val rooms=plan.rooms.map(::room);val walls=plan.walls.map(::wall);val openings=plan.openings.map(::opening);val elements=plan.elements.map(::element)
        return plan.copy(
            rooms=rooms,walls=walls,openings=openings,elements=elements,
            dimensions=plan.dimensions.map { it.copy(id=p+it.id,pageIndex=page) },
            floors=emptyList(),activeFloorId=null
        )
    }
}
