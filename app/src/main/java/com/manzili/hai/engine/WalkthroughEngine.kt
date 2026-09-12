package com.manzili.hai.engine

import com.manzili.hai.model.FloorLevel
import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.Opening
import com.manzili.hai.model.PlanPoint
import com.manzili.hai.model.Room
import java.util.ArrayDeque

/**
 * Interior route derived from the canonical room/opening graph.
 * Rooms connected by a verified door/opening are traversed as one graph. Disconnected rooms are
 * never bridged by an invented door; they start a new route segment and are reported explicitly.
 */
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
        val elevationM:Double,
        val worldX:Float = xPct,
        val worldY:Float = yPct,
        val eyeZ:Float = 1.62f,
        val viaOpeningId:String? = null,
        val connectedFromPrevious:Boolean = false
    )
    data class Route(
        val points:List<Waypoint>,
        val warnings:List<String>,
        val metricReady:Boolean = false,
        val disconnectedSegments:Int = 0,
        val verifiedTransitions:Int = 0
    )

    fun build(plan:FloorPlan):Route {
        val normalized=MultiFloorGeometryEngine.normalize(plan)
        val metricReady=(normalized.widthM?:0.0)>0.0&&(normalized.heightM?:0.0)>0.0&&normalized.scaleConfidence>=50
        val sx=if(metricReady)normalized.widthM!!/100.0 else 1.0
        val sy=if(metricReady)normalized.heightM!!/100.0 else 1.0
        val floors=normalized.floors.ifEmpty {
            listOf(FloorLevel(
                id="floor-0",name="الدور الأرضي",index=0,rooms=normalized.rooms,
                openings=normalized.openings,footprint=normalized.footprint
            ))
        }.sortedBy { it.index }
        val points=mutableListOf<Waypoint>()
        var disconnectedSegments=0
        var verifiedTransitions=0

        floors.forEach { floor ->
            val rooms=floor.rooms.filterNot { it.type.equals("courtyard",true) }
            if(rooms.isEmpty())return@forEach
            val roomById=rooms.associateBy { it.id }
            val edges=openingEdges(floor.openings,roomById.keys)
            val adjacency=mutableMapOf<String,MutableList<Pair<String,String>>>()
            edges.forEach { (a,b,openingId) ->
                adjacency.getOrPut(a){mutableListOf()} += b to openingId
                adjacency.getOrPut(b){mutableListOf()} += a to openingId
            }
            val visited=mutableSetOf<String>()
            val ordered=mutableListOf<Triple<Room,String?,Boolean>>()
            val preferred=rooms.sortedWith(compareBy<Room>{priority(it)}.thenBy{it.y}.thenBy{it.x})
            val firstSeed=preferred.firstOrNull { priority(it)==0 } ?: preferred.first()
            val seeds=listOf(firstSeed)+preferred.filter { it.id!=firstSeed.id }

            seeds.forEach { seed ->
                if(seed.id in visited)return@forEach
                if(visited.isNotEmpty())disconnectedSegments++
                val queue=ArrayDeque<Triple<String,String?,Boolean>>()
                queue.add(Triple(seed.id,null,false))
                while(queue.isNotEmpty()) {
                    val (roomId,via,connected)=queue.removeFirst()
                    if(!visited.add(roomId))continue
                    val room=roomById[roomId]?:continue
                    ordered += Triple(room,via,connected)
                    adjacency[roomId].orEmpty()
                        .filter { it.first !in visited }
                        .sortedBy { next -> priority(roomById[next.first] ?: room) }
                        .forEach { (next,openingId) -> queue.add(Triple(next,openingId,true)) }
                }
            }

            val sceneElevation=if(metricReady)floor.elevationM else floor.index*12.0
            val eye=if(metricReady)1.62 else 5.4
            ordered.forEach { (room,via,connected) ->
                val c=centroid(room)
                if(connected)verifiedTransitions++
                points += Waypoint(
                    id="${floor.id}:${room.id}",floorId=floor.id,floorName=floor.name,
                    roomId=room.id,roomName=room.name.ifBlank{room.type},roomType=room.type,
                    xPct=c.x.coerceIn(0f,100f),yPct=c.y.coerceIn(0f,100f),elevationM=sceneElevation,
                    worldX=(c.x*sx).toFloat(),worldY=(c.y*sy).toFloat(),eyeZ=(sceneElevation+eye).toFloat(),
                    viaOpeningId=via,connectedFromPrevious=connected
                )
            }
        }
        val warnings=buildList {
            if(points.isEmpty())add("لا توجد غرف كافية لبناء جولة داخلية.")
            if(!metricReady)add("الجولة 3D تستخدم مقياسًا نسبيًا حتى تأكيد الأبعاد؛ لا تعرض مسافات مترية.")
            if(disconnectedSegments>0)add("هناك $disconnectedSegments انتقال غير متصل بفتحة موثقة؛ يبدأ كقطاع جولة جديد بدل اختراع باب.")
            add("الانتقالات الموثقة عبر أبواب/فتحات Geometry V3: $verifiedTransitions.")
        }
        return Route(points,warnings,metricReady,disconnectedSegments,verifiedTransitions)
    }

    private data class Edge(val a:String,val b:String,val openingId:String)

    private fun openingEdges(openings:List<Opening>,validRoomIds:Set<String>):List<Edge> = openings.mapNotNull { opening ->
        val ids=opening.connectsRoomIds.filter { it in validRoomIds }.distinct()
        if(ids.size<2)null else Edge(ids[0],ids[1],opening.id)
    }

    private fun priority(room:Room)=when(room.type.lowercase()) {
        "entry","foyer","entrance" -> 0
        "majlis","guest" -> 1
        "living","family" -> 2
        "kitchen" -> 3
        "bedroom","master" -> 4
        "service","laundry","maid" -> 5
        else -> 6
    }

    private fun centroid(room:Room):PlanPoint {
        val poly=room.polygon
        if(poly.isNotEmpty())return PlanPoint(poly.map{it.x}.average().toFloat(),poly.map{it.y}.average().toFloat())
        return PlanPoint(room.x+room.width/2f,room.y+room.height/2f)
    }
}
