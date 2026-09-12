package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.Room
import kotlin.math.abs

object SaudiArchitectExperienceEngine {
    data class Review(val score:Int,val categories:Map<String,Int>,val notes:List<String>)

    fun inspect(plan:FloorPlan,brief:SaudiDeepBriefEngine.Brief):Review {
        val rooms=if(plan.floors.isNotEmpty())plan.floors.flatMap{it.rooms}else plan.rooms
        val notes=mutableListOf<String>();val scores=linkedMapOf<String,Int>()
        val majlis=find(rooms,"majlis","مجلس");val family=find(rooms,"living","صالة");val kitchen=find(rooms,"kitchen","مطبخ")
        val bedrooms=rooms.filter{bed(it)};val services=rooms.filter{service(it)};val wet=rooms.filter{wet(it)}

        var zoning=100
        if(majlis!=null&&family!=null){val d=dist(majlis,family);if(d<22f){zoning-=28;notes+="منطقة الضيوف والعائلة متقاربة أكثر من اللازم."}}
        if(majlis!=null&&bedrooms.any{dist(majlis,it)<18f}){zoning-=30;notes+="غرفة نوم قريبة جدًا من نطاق الضيافة."}
        scores["zoning"]=zoning.coerceIn(0,100)

        var serviceCompact=100
        if(kitchen!=null&&services.isNotEmpty()){
            val mean=services.map{dist(kitchen,it)}.average();if(mean>42){serviceCompact-=28;notes+="مسار الخدمة متشتت بعيدًا عن المطبخ."}else if(mean>28)serviceCompact-=12
        } else if(brief.base.serviceEntrance||brief.base.maidRoom){serviceCompact-=20}
        scores["service_compactness"]=serviceCompact.coerceIn(0,100)

        var wetCore=100
        if(wet.size>=2){
            val cx=wet.map{it.x+it.width/2}.average();val cy=wet.map{it.y+it.height/2}.average()
            val spread=wet.map{abs((it.x+it.width/2)-cx)+abs((it.y+it.height/2)-cy)}.average()
            if(spread>45){wetCore-=25;notes+="المناطق الرطبة متباعدة؛ راجع تجميع السباكة."}else if(spread>30)wetCore-=10
        }
        scores["wet_core"]=wetCore.coerceIn(0,100)

        var vertical=100
        val floorCount=if(plan.floors.isEmpty())1 else plan.floors.size
        val elements=if(plan.floors.isNotEmpty())plan.floors.flatMap{it.elements}else plan.elements
        val linked=elements.filter{it.connectsFloorIds.size>=2}
        if(floorCount>1&&linked.isEmpty()){vertical-=45;notes+="المشروع متعدد الأدوار بلا نواة رأسية موثقة."}
        if(brief.base.elevator&&linked.none{it.type.contains("elevator",true)||it.type.contains("مصعد")})vertical-=20
        scores["vertical_core"]=vertical.coerceIn(0,100)

        var exposure=100
        val openings=if(plan.floors.isNotEmpty())plan.floors.flatMap{it.openings}else plan.openings
        val windowRooms=openings.filter{OpeningVerticalProfileEngine.isWindow(it.type)}.flatMap{it.connectsRoomIds}.toSet()
        val habitable=rooms.filterNot{service(it)}
        if(habitable.isNotEmpty()){
            val coverage=habitable.count{it.id in windowRooms}*100/habitable.size
            if(coverage<50){exposure-=30;notes+="تغطية النوافذ الموثقة للفراغات المعيشية ضعيفة."}else if(coverage<75)exposure-=12
        }
        scores["external_exposure"]=exposure.coerceIn(0,100)

        var flexibility=100
        if(brief.futureExpansion&&floorCount>1&&linked.isEmpty())flexibility-=35
        if(brief.futureExpansion&&plan.preferences.futureFlexibilityPriority>=80)flexibility+=5
        scores["future_flexibility"]=flexibility.coerceIn(0,100)

        val weights=mapOf("zoning" to .27,"service_compactness" to .19,"wet_core" to .14,"vertical_core" to .18,"external_exposure" to .13,"future_flexibility" to .09)
        val total=scores.entries.sumOf{entry:Map.Entry<String,Int>->entry.value*(weights[entry.key]?:0.0)}.toInt().coerceIn(0,100)
        return Review(total,scores,notes.distinct())
    }

    private fun find(rs:List<Room>,type:String,name:String)=rs.firstOrNull{it.type.equals(type,true)||it.name.contains(name)}
    private fun bed(r:Room)=r.type.lowercase() in setOf("bedroom","master")||r.name.contains("نوم")
    private fun service(r:Room)=r.type.lowercase() in setOf("kitchen","service","laundry","maid","storage","bath","wc")
    private fun wet(r:Room)=r.type.lowercase() in setOf("kitchen","laundry","bath","wc")||r.name.contains("مطبخ")||r.name.contains("حمام")||r.name.contains("غسيل")
    private fun dist(a:Room,b:Room)=abs((a.x+a.width/2)-(b.x+b.width/2))+abs((a.y+a.height/2)-(b.y+b.height/2))
}
