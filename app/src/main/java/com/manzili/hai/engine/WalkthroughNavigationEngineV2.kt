package com.manzili.hai.engine

import com.manzili.hai.model.FloorLevel
import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.PlanPoint
import com.manzili.hai.model.Room
import kotlin.math.max
import kotlin.math.min

/** Verified first-person navigation graph. It never invents a door or inter-floor connector. */
object WalkthroughNavigationEngineV2 {
    data class Portal(val id:String,val floorId:String,val fromRoomId:String,val toRoomId:String,val openingId:String)
    data class FloorConnector(val id:String,val type:String,val fromFloorId:String,val toFloorId:String,val sourceElementId:String)
    data class Network(
        val floors:List<FloorLevel>,
        val portals:List<Portal>,
        val connectors:List<FloorConnector>,
        val metricReady:Boolean,
        val warnings:List<String>
    )
    data class CameraState(
        val floorId:String,
        val roomId:String,
        val xPct:Float,
        val yPct:Float,
        val yawDeg:Float=0f,
        val eyeHeightM:Float=1.62f,
        val note:String=""
    )
    data class Exit(val roomId:String,val roomName:String,val viaOpeningId:String)
    data class FloorExit(val floorId:String,val floorName:String,val viaElementId:String,val type:String)

    fun build(plan:FloorPlan):Network {
        val p=MultiFloorGeometryEngine.normalize(plan)
        val floors=if(p.floors.isNotEmpty())p.floors.sortedBy{it.index}else listOf(FloorLevel(p.activeFloorId?:"floor-0","الدور الأرضي",0,rooms=p.rooms,walls=p.walls,openings=p.openings,elements=p.elements,footprint=p.footprint))
        val portals=mutableListOf<Portal>()
        floors.forEach{floor->
            val ids=floor.rooms.map{it.id}.toSet()
            floor.openings.forEach{o->
                val connected=o.connectsRoomIds.filter{it in ids}.distinct()
                if(connected.size>=2){
                    val a=connected[0];val b=connected[1]
                    portals+=Portal("portal:${floor.id}:${o.id}:a",floor.id,a,b,o.id)
                    portals+=Portal("portal:${floor.id}:${o.id}:b",floor.id,b,a,o.id)
                }
            }
        }
        val connectors=mutableListOf<FloorConnector>()
        val elements=(floors.flatMap{it.elements}+p.elements).distinctBy{it.id}
        elements.forEach{e->
            val t=e.type.lowercase();if(!(t.contains("stair")||t.contains("سلم")||t.contains("elevator")||t.contains("مصعد")))return@forEach
            val fs=e.connectsFloorIds.distinct().filter{id->floors.any{it.id==id}}
            fs.forEach{a->fs.filter{it!=a}.forEach{b->connectors+=FloorConnector("connector:${e.id}:$a:$b",e.type,a,b,e.id)}}
        }
        val metric=(p.widthM?:0.0)>0.0&&(p.heightM?:0.0)>0.0&&p.scaleConfidence>=50
        val warnings=buildList{
            if(portals.isEmpty())add("لا توجد انتقالات غرف موثقة عبر connectsRoomIds؛ لن يخترع HAI أبوابًا للجولة.")
            if(floors.size>1&&connectors.isEmpty())add("المشروع متعدد الأدوار لكن لا يوجد سلم/مصعد موثق يربط الأدوار؛ انتقال الأدوار متوقف.")
            if(!metric)add("المقياس غير مؤكد؛ الحركة داخل الغرفة نسبية حتى تثبيت الأبعاد.")
        }
        return Network(floors,portals.distinctBy{it.id},connectors.distinctBy{it.id},metric,warnings)
    }

    fun initial(network:Network):CameraState? {
        val floor=network.floors.firstOrNull()?:return null
        val room=floor.rooms.sortedWith(compareBy<Room>{priority(it)}.thenBy{it.y}.thenBy{it.x}).firstOrNull()?:return null
        val c=center(room);return CameraState(floor.id,room.id,c.x,c.y,0f,note="بداية الجولة")
    }

    fun room(network:Network,state:CameraState):Room?=network.floors.firstOrNull{it.id==state.floorId}?.rooms?.firstOrNull{it.id==state.roomId}

    fun exits(network:Network,state:CameraState):List<Exit>{
        val floor=network.floors.firstOrNull{it.id==state.floorId}?:return emptyList();val byId=floor.rooms.associateBy{it.id}
        return network.portals.filter{it.floorId==state.floorId&&it.fromRoomId==state.roomId}.mapNotNull{p->byId[p.toRoomId]?.let{Exit(it.id,it.name.ifBlank{it.type},p.openingId)}}
    }

    fun floorExits(network:Network,state:CameraState):List<FloorExit>{
        val byId=network.floors.associateBy{it.id}
        return network.connectors.filter{it.fromFloorId==state.floorId}.mapNotNull{c->byId[c.toFloorId]?.let{FloorExit(it.id,it.name,c.sourceElementId,c.type)}}.distinctBy{it.floorId to it.viaElementId}
    }

    fun enter(network:Network,state:CameraState,targetRoomId:String):CameraState {
        val portal=network.portals.firstOrNull{it.floorId==state.floorId&&it.fromRoomId==state.roomId&&it.toRoomId==targetRoomId}?:return state.copy(note="مرفوض: لا توجد فتحة موثقة إلى الغرفة المطلوبة.")
        val target=network.floors.first{it.id==state.floorId}.rooms.first{it.id==targetRoomId};val c=center(target)
        return state.copy(roomId=targetRoomId,xPct=c.x,yPct=c.y,note="دخلت عبر الفتحة ${portal.openingId}")
    }

    fun changeFloor(network:Network,state:CameraState,targetFloorId:String):CameraState {
        val connector=network.connectors.firstOrNull{it.fromFloorId==state.floorId&&it.toFloorId==targetFloorId}?:return state.copy(note="مرفوض: لا يوجد سلم/مصعد موثق لهذا الانتقال.")
        val floor=network.floors.firstOrNull{it.id==targetFloorId}?:return state
        val target=floor.rooms.sortedWith(compareBy<Room>{priority(it)}.thenBy{it.y}.thenBy{it.x}).firstOrNull()?:return state.copy(note="الدور الهدف بلا غرف قابلة للجولة.")
        val c=center(target);return CameraState(floor.id,target.id,c.x,c.y,state.yawDeg,state.eyeHeightM,"انتقال موثق عبر ${connector.sourceElementId}")
    }

    fun moveWithinRoom(network:Network,state:CameraState,dxPct:Float,dyPct:Float):CameraState {
        val room=room(network,state)?:return state
        val poly=room.polygon.ifEmpty{listOf(PlanPoint(room.x,room.y),PlanPoint(room.x+room.width,room.y),PlanPoint(room.x+room.width,room.y+room.height),PlanPoint(room.x,room.y+room.height))}
        val margin=max(.35f,min(room.width,room.height)*.06f)
        val minX=poly.minOf{it.x}+margin;val maxX=poly.maxOf{it.x}-margin;val minY=poly.minOf{it.y}+margin;val maxY=poly.maxOf{it.y}-margin
        return state.copy(xPct=(state.xPct+dxPct).coerceIn(minX,maxX),yPct=(state.yPct+dyPct).coerceIn(minY,maxY),yawDeg=when{dxPct>0->0f;dxPct<0->180f;dyPct>0->90f;dyPct<0->270f;else->state.yawDeg},note="حركة داخل حدود ${room.name.ifBlank{room.type}}")
    }

    fun worldPosition(plan:FloorPlan,network:Network,state:CameraState):Triple<Float,Float,Float>{
        val sx=if(network.metricReady)((plan.widthM?:100.0)/100.0).toFloat() else 1f
        val sy=if(network.metricReady)((plan.heightM?:100.0)/100.0).toFloat() else 1f
        val floor=network.floors.firstOrNull{it.id==state.floorId};val elevation=(floor?.elevationM?:0.0).toFloat()
        val eye=if(network.metricReady)state.eyeHeightM else 5.4f
        return Triple(state.xPct*sx,elevation+eye,state.yPct*sy)
    }

    private fun center(r:Room):PlanPoint=if(r.polygon.isNotEmpty())PlanPoint(r.polygon.map{it.x}.average().toFloat(),r.polygon.map{it.y}.average().toFloat())else PlanPoint(r.x+r.width/2f,r.y+r.height/2f)
    private fun priority(r:Room)=when(r.type.lowercase()){ "entry","foyer","entrance"->0;"majlis","guest"->1;"living","family"->2;"kitchen"->3;"bedroom","master"->4;else->5 }
}
