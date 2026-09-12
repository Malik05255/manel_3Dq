package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.PlanPoint
import com.manzili.hai.model.Room

/** Deterministic interior route derived only from canonical rooms/floors. */
object WalkthroughEngine {
    data class Waypoint(
        val id:String,
        val floorId:String,
        val floorName:String,
        val roomId:String,
        val roomName:String,
        val roomType:String,
        val xPct:Float,
        val yPct:Float,
        val elevationM:Double
    )
    data class Route(val points:List<Waypoint>,val warnings:List<String>)

    fun build(plan:FloorPlan):Route {
        val normalized=MultiFloorGeometryEngine.normalize(plan)
        val floors=normalized.floors.ifEmpty {
            listOf(com.manzili.hai.model.FloorLevel(
                id="floor-0",name="الدور الأرضي",index=0,rooms=normalized.rooms,footprint=normalized.footprint
            ))
        }.sortedBy { it.index }
        val points=mutableListOf<Waypoint>()
        floors.forEach { floor ->
            floor.rooms.sortedWith(compareBy<Room> { priority(it) }.thenBy { it.y }.thenBy { it.x }).forEach { room ->
                if(room.type.equals("courtyard",true)) return@forEach
                val c=centroid(room)
                points += Waypoint(
                    id="${floor.id}:${room.id}", floorId=floor.id, floorName=floor.name,
                    roomId=room.id, roomName=room.name.ifBlank { room.type }, roomType=room.type,
                    xPct=c.x.coerceIn(0f,100f), yPct=c.y.coerceIn(0f,100f), elevationM=floor.elevationM
                )
            }
        }
        val warnings=buildList {
            if(points.isEmpty()) add("لا توجد غرف كافية لبناء جولة داخلية.")
            if(normalized.scaleConfidence<70) add("الجولة موضعية صحيحة نسبيًا، لكن المقياس المتري غير مؤكد.")
            add("مسار الجولة مشتق من الغرف فقط ولا يخترع ممرات أو أبوابًا غير موجودة.")
        }
        return Route(points,warnings)
    }

    private fun priority(room:Room)=when(room.type.lowercase()) {
        "entry","foyer" -> 0
        "majlis","guest" -> 1
        "living","family" -> 2
        "kitchen" -> 3
        "bedroom","master" -> 4
        "service","laundry","maid" -> 5
        else -> 6
    }

    private fun centroid(room:Room):PlanPoint {
        val poly=room.polygon
        if(poly.isNotEmpty()) return PlanPoint(poly.map { it.x }.average().toFloat(),poly.map { it.y }.average().toFloat())
        return PlanPoint(room.x+room.width/2f,room.y+room.height/2f)
    }
}
