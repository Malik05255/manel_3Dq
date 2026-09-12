package com.manzili.hai.engine

import com.manzili.hai.model.*

/** Keeps the legacy editor surface synchronized with one canonical active floor. */
object MultiFloorGeometryEngine {
    fun normalize(input: FloorPlan): FloorPlan {
        val base = GeometryV3Engine.normalize(PolygonGeometryEngine.normalize(input))
        if (base.floors.isEmpty()) {
            val first = FloorLevel(
                id = "floor-0",
                name = "الدور الأرضي",
                index = 0,
                elevationM = 0.0,
                footprint = base.footprint,
                rooms = base.rooms,
                walls = base.walls,
                openings = base.openings,
                elements = base.elements
            )
            val site = if (base.site.plotBoundary.isEmpty()) base.site.copy(plotBoundary = base.footprint, northDeg = base.site.northDeg ?: base.northDeg) else base.site
            return base.copy(floors = listOf(first), activeFloorId = first.id, site = site)
        }
        val activeId = base.activeFloorId?.takeIf { id -> base.floors.any { it.id == id } } ?: base.floors.minByOrNull { it.index }!!.id
        val active = base.floors.first { it.id == activeId }
        return base.copy(
            rooms = active.rooms,
            walls = active.walls,
            openings = active.openings,
            elements = active.elements,
            footprint = active.footprint.ifEmpty { base.footprint },
            activeFloorId = activeId,
            northDeg = base.site.northDeg ?: base.northDeg
        )
    }

    fun persistActive(plan: FloorPlan): FloorPlan {
        val normalized = normalize(plan)
        val id = normalized.activeFloorId ?: return normalized
        val updated = normalized.floors.map { floor ->
            if (floor.id != id) floor else floor.copy(
                footprint = normalized.footprint,
                rooms = normalized.rooms,
                walls = normalized.walls,
                openings = normalized.openings,
                elements = normalized.elements
            )
        }
        return GeometryV3Engine.normalize(normalized.copy(floors = updated))
    }

    fun selectFloor(plan: FloorPlan, floorId: String): FloorPlan {
        val saved = persistActive(plan)
        val target = saved.floors.firstOrNull { it.id == floorId } ?: return saved
        return saved.copy(
            rooms = target.rooms,
            walls = target.walls,
            openings = target.openings,
            elements = target.elements,
            footprint = target.footprint,
            activeFloorId = target.id
        )
    }

    fun activeFloor(plan: FloorPlan): FloorLevel? {
        val normalized = normalize(plan)
        return normalized.floors.firstOrNull { it.id == normalized.activeFloorId }
    }

    fun addFloor(plan: FloorPlan, name: String = ""): FloorPlan {
        val saved = persistActive(plan)
        val nextIndex = (saved.floors.maxOfOrNull { it.index } ?: -1) + 1
        val id = "floor-$nextIndex"
        val created = FloorLevel(
            id = id,
            name = name.ifBlank { if (nextIndex == 1) "الدور الأول" else "الدور $nextIndex" },
            index = nextIndex,
            elevationM = nextIndex * 3.2,
            footprint = saved.footprint
        )
        return selectFloor(saved.copy(floors = saved.floors + created), id)
    }
}
