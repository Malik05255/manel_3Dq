package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import kotlin.math.abs

/** Independent deterministic decision layer for Saudi residential planning. No official-rule guessing. */
object SaudiArchitectDecisionEngineV3 {
    data class Evaluation(
        val score:Int,
        val categoryScores:Map<String,Int>,
        val hardViolations:List<String>,
        val strengths:List<String>,
        val issues:List<String>
    )

    fun inspect(plan:FloorPlan,type:SaudiProjectTypeEngine.Type,brief:SaudiDeepBriefEngine.Brief):Evaluation {
        val rooms=if(plan.floors.isNotEmpty())plan.floors.flatMap{it.rooms}else plan.rooms
        val openings=if(plan.floors.isNotEmpty())plan.floors.flatMap{it.openings}else plan.openings
        val elements=if(plan.floors.isNotEmpty())plan.floors.flatMap{it.elements}else plan.elements
        val categories=linkedMapOf<String,Int>()
        val hard=mutableListOf<String>();val good=mutableListOf<String>();val issues=mutableListOf<String>()

        val geometry=GeometryV3Engine.inspect(plan)
        var validity=100
        if(!geometry.valid){hard+="Geometry V3 غير صالح.";validity=0}
        if(SaudiProjectTypeEngine.detect(plan)!=type){hard+="نوع المشروع الصلب لا يطابق المطلوب.";validity=minOf(validity,25)}
        categories["القيود الصلبة"]=validity

        val expectedBedrooms=brief.base.bedrooms.coerceAtLeast(0)
        val actualBedrooms=rooms.count{val t="${it.type} ${it.name}".lowercase();t.contains("bedroom")||t.contains("master")||t.contains("نوم")}
        var program=100
        if(expectedBedrooms>0&&actualBedrooms<expectedBedrooms){program-=(expectedBedrooms-actualBedrooms)*12;issues+="عدد غرف النوم أقل من البرنامج المطلوب."}
        if(brief.base.menMajlis&&rooms.none{it.type.equals("majlis",true)||it.name.contains("مجلس")}){program-=18;issues+="المجلس المطلوب غير موجود."}
        if(rooms.none{it.type.equals("living",true)||it.type.equals("family",true)||it.name.contains("صالة")}){program-=16;issues+="منطقة العائلة غير واضحة."}
        categories["البرنامج"]=program.coerceIn(0,100)

        val adjacency=mutableMapOf<String,MutableSet<String>>()
        openings.forEach{o->val ids=o.connectsRoomIds.distinct();if(ids.size>=2)ids.forEach{a->ids.filter{it!=a}.forEach{b->adjacency.getOrPut(a){mutableSetOf()}+=b}}}
        var circulation=100
        if(rooms.size>=4){
            val connected=adjacency.keys+adjacency.values.flatten()
            val isolated=rooms.count{it.id !in connected}
            if(isolated>rooms.size/3){circulation-=30;issues+="شبكة الحركة تترك غرفًا كثيرة دون اتصال موثق."}else good+="شبكة الحركة تغطي غالبية الغرف."
        }
        val entry=rooms.firstOrNull{val t="${it.type} ${it.name}".lowercase();t.contains("entry")||t.contains("foyer")||t.contains("مدخل")||t.contains("بهو")}
        if(entry==null)circulation-=8
        categories["شبكة الحركة"]=circulation.coerceIn(0,100)

        val majlis=rooms.firstOrNull{it.type.equals("majlis",true)||it.name.contains("مجلس")}
        val family=rooms.firstOrNull{it.type.equals("living",true)||it.type.equals("family",true)||it.name.contains("صالة")}
        var privacy=100
        if(majlis!=null&&family!=null){
            if(family.id in adjacency[majlis.id].orEmpty()){privacy-=32;issues+="المجلس متصل مباشرة بالعائلة."}
            val distance=abs(majlis.x-family.x)+abs(majlis.y-family.y);if(distance<18f)privacy-=14
        }
        categories["الخصوصية"]=privacy.coerceIn(0,100)

        val kitchen=rooms.firstOrNull{it.type.equals("kitchen",true)||it.name.contains("مطبخ")}
        val services=rooms.filter{it.type.lowercase() in setOf("service","laundry","maid","storage","bath","wc")||it.name.contains("غسيل")||it.name.contains("مخزن")}
        var serviceScore=100
        if(kitchen!=null&&services.isNotEmpty()&&services.none{abs(it.x-kitchen.x)+abs(it.y-kitchen.y)<30f||it.id in adjacency[kitchen.id].orEmpty()}){serviceScore-=20;issues+="الخدمات مشتتة بعيدًا عن المطبخ."}else if(kitchen!=null)good+="نواة الخدمة متماسكة مبدئيًا."
        categories["تشغيل الخدمات"]=serviceScore.coerceIn(0,100)

        var daylight=100
        val windows=openings.filter{OpeningVerticalProfileEngine.isWindow(it.type)}
        val windowRooms=windows.flatMap{it.connectsRoomIds}.toSet()
        val habitable=rooms.filterNot{it.type.lowercase() in setOf("bath","wc","storage","service","laundry","maid")}
        if(habitable.isNotEmpty()&&windowRooms.isNotEmpty()){
            val covered=habitable.count{it.id in windowRooms};val ratio=covered.toDouble()/habitable.size
            if(ratio<.5){daylight-=30;issues+="تغطية النوافذ الموثقة للفراغات المعيشية ضعيفة."}
        }else daylight-=12
        categories["الضوء والواجهة"]=daylight.coerceIn(0,100)

        val floors=if(plan.floors.isEmpty())1 else plan.floors.size
        val verticalElements=elements.filter{val t=it.type.lowercase();t.contains("stair")||t.contains("سلم")||t.contains("درج")||t.contains("elevator")||t.contains("مصعد")}
        var vertical=100
        if(floors>1&&verticalElements.isEmpty()){vertical-=45;issues+="مشروع متعدد الأدوار بلا نواة حركة رأسية موثقة."}
        if(floors>1&&verticalElements.none{it.connectsFloorIds.distinct().size>=2}){vertical-=20;issues+="الحركة الرأسية غير مربوطة بين الأدوار في Geometry V3."}
        categories["الربط الرأسي"]=vertical.coerceIn(0,100)

        var buildability=100
        rooms.forEach{r->val short=minOf(r.width,r.height);val long=maxOf(r.width,r.height);if(short>0f&&long/short>4.0f)buildability-=5}
        if(plan.constraints.any{it.hard&&!it.active})buildability-=20
        categories["قابلية البناء"]=buildability.coerceIn(0,100)

        var future=100
        if(brief.futureExpansion&&brief.futureFloors>0&&floors>1&&vertical<80){future-=28;issues+="التوسع المستقبلي غير محمي بنواة رأسية موثقة."}
        if(brief.futureExpansion&&plan.preferences.futureFlexibilityPriority>=70)good+="التوسع المستقبلي محفوظ كأولوية."
        categories["المرونة"]=future.coerceIn(0,100)

        val weights=mapOf("القيود الصلبة" to .16,"البرنامج" to .17,"شبكة الحركة" to .16,"الخصوصية" to .16,"تشغيل الخدمات" to .10,"الضوء والواجهة" to .08,"الربط الرأسي" to .08,"قابلية البناء" to .06,"المرونة" to .03)
        val score=categories.entries.sumOf{(k,v)->v*(weights[k]?:0.0)}.toInt().coerceIn(0,100)
        return Evaluation(score,categories,hard.distinct(),good.distinct(),issues.distinct())
    }
}
