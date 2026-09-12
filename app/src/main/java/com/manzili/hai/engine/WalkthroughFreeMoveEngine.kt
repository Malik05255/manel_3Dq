package com.manzili.hai.engine

import com.manzili.hai.model.*
import kotlin.math.*

object WalkthroughFreeMoveEngine {
    data class State(val floorId:String,val roomId:String,val x:Double,val y:Double,val yaw:Float,val eyeZ:Double)
    data class Result(val state:State,val blocked:Boolean,val crossedOpeningId:String?=null)

    fun start(plan:FloorPlan):State? {
        val floor=floors(plan).firstOrNull()?:return null
        val room=floor.rooms.firstOrNull()?:return null
        val p=center(room,plan)
        return State(floor.id,room.id,p.first,p.second,0f,floor.elevationM+eye(plan))
    }

    fun turn(state:State,delta:Float)=state.copy(yaw=((state.yaw+delta)%360f+360f)%360f)

    fun move(plan:FloorPlan,state:State,forward:Double,right:Double):Result {
        val metric=metric(plan);val step=if(metric)0.35 else 1.8
        val r=Math.toRadians(state.yaw.toDouble())
        val dx=(cos(r)*forward-sin(r)*right)*step;val dy=(sin(r)*forward+cos(r)*right)*step
        val nx=state.x+dx;val ny=state.y+dy
        val floor=floors(plan).firstOrNull{it.id==state.floorId}?:return Result(state,true)
        val current=floor.rooms.firstOrNull{it.id==state.roomId}?:return Result(state,true)
        if(inside(current,nx,ny,plan))return Result(state.copy(x=nx,y=ny),false)
        val sx=scaleX(plan);val sy=scaleY(plan);val reach=if(metric)0.95 else 4.0
        val door=floor.openings.filter{!OpeningVerticalProfileEngine.isWindow(it.type)&&state.roomId in it.connectsRoomIds&&it.connectsRoomIds.size>=2}
            .minByOrNull{hypot(nx-it.x*sx,ny-it.y*sy)}
        if(door!=null&&hypot(nx-door.x*sx,ny-door.y*sy)<=reach){
            val nextId=door.connectsRoomIds.firstOrNull{it!=state.roomId}
            val next=floor.rooms.firstOrNull{it.id==nextId}
            if(next!=null){val c=center(next,plan);return Result(state.copy(roomId=next.id,x=c.first,y=c.second),false,door.id)}
        }
        return Result(state,true)
    }

    fun verticalTargets(plan:FloorPlan,state:State):List<String>{
        val floor=floors(plan).firstOrNull{it.id==state.floorId}?:return emptyList();val sx=scaleX(plan);val sy=scaleY(plan);val reach=if(metric(plan))1.8 else 6.0
        return floor.elements.filter{it.connectsFloorIds.size>=2}.filter{e->val c=elementCenter(e,sx,sy);hypot(state.x-c.first,state.y-c.second)<=reach}.flatMap{it.connectsFloorIds}.filter{it!=state.floorId}.distinct()
    }

    fun changeFloor(plan:FloorPlan,state:State,target:String):Result{
        val floors=floors(plan);val from=floors.firstOrNull{it.id==state.floorId}?:return Result(state,true);val to=floors.firstOrNull{it.id==target}?:return Result(state,true)
        val sx=scaleX(plan);val sy=scaleY(plan);val reach=if(metric(plan))1.8 else 6.0
        val link=from.elements.filter{state.floorId in it.connectsFloorIds&&target in it.connectsFloorIds}.minByOrNull{e->val c=elementCenter(e,sx,sy);hypot(state.x-c.first,state.y-c.second)}?:return Result(state,true)
        val lc=elementCenter(link,sx,sy);if(hypot(state.x-lc.first,state.y-lc.second)>reach)return Result(state,true)
        val room=to.rooms.firstOrNull{inside(it,lc.first,lc.second,plan)}?:to.rooms.minByOrNull{r->val c=center(r,plan);hypot(c.first-lc.first,c.second-lc.second)}?:return Result(state,true)
        val c=center(room,plan);return Result(state.copy(floorId=to.id,roomId=room.id,x=c.first,y=c.second,eyeZ=to.elevationM+eye(plan)),false,link.id)
    }

    fun roomName(plan:FloorPlan,state:State)=floors(plan).firstOrNull{it.id==state.floorId}?.rooms?.firstOrNull{it.id==state.roomId}?.name.orEmpty()
    private fun floors(p:FloorPlan)=if(p.floors.isNotEmpty())p.floors.sortedBy{it.index}else listOf(FloorLevel(p.activeFloorId?:"floor-0","الدور الأرضي",0,0.0,null,p.footprint,p.rooms,p.walls,p.openings,p.elements))
    private fun metric(p:FloorPlan)=(p.widthM?:0.0)>0&&(p.heightM?:0.0)>0&&p.scaleConfidence>=50
    private fun scaleX(p:FloorPlan)=if(metric(p))p.widthM!!/100.0 else 1.0
    private fun scaleY(p:FloorPlan)=if(metric(p))p.heightM!!/100.0 else 1.0
    private fun eye(p:FloorPlan)=if(metric(p))1.62 else 5.4
    private fun center(r:Room,p:FloorPlan)=(r.x+r.width/2)*scaleX(p) to (r.y+r.height/2)*scaleY(p)
    private fun inside(r:Room,x:Double,y:Double,p:FloorPlan):Boolean{val sx=scaleX(p);val sy=scaleY(p);return x>=r.x*sx&&x<=(r.x+r.width)*sx&&y>=r.y*sy&&y<=(r.y+r.height)*sy}
    private fun elementCenter(e:StructuralElement,sx:Double,sy:Double):Pair<Double,Double>{if(e.footprint.isEmpty())return 0.0 to 0.0;return e.footprint.map{it.x*sx}.average() to e.footprint.map{it.y*sy}.average()}
}
