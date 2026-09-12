package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.Opening
import com.manzili.hai.model.Room
import kotlin.math.abs

/** Senior Saudi residential architect critic. Guidance only; it never invents official rules. */
object SaudiArchitectCriticEngine {
    data class Critique(
        val score:Int,
        val strengths:List<String>,
        val issues:List<String>,
        val hardViolations:List<String>,
        val categoryScores:Map<String,Int> = emptyMap(),
        val recommendations:List<String> = emptyList()
    )

    fun inspect(plan:FloorPlan,type:SaudiProjectTypeEngine.Type,brief:SaudiDeepBriefEngine.Brief):Critique {
        val rooms=allRooms(plan)
        val openings=allOpenings(plan)
        val issues=mutableListOf<String>()
        val strengths=mutableListOf<String>()
        val hard=mutableListOf<String>()
        val recommendations=mutableListOf<String>()
        val categories=linkedMapOf<String,Int>()

        var typeScore=100
        if(SaudiProjectTypeEngine.detect(plan)!=type){hard+="نوع المشروع لم يُحفظ كقيد صلب.";typeScore=25}else strengths+="نوع المشروع محفوظ كقيد صلب."
        if(!GeometryV3Engine.inspect(plan).valid){hard+="Geometry V3 غير صالح.";typeScore=minOf(typeScore,35)}
        val floors=if(plan.floors.isEmpty())1 else plan.floors.size
        when(type){
            SaudiProjectTypeEngine.Type.VILLA_ONE -> if(floors!=1){issues+="فيلا الدور الواحد خرجت عن عدد الأدوار المختار.";typeScore-=25}
            SaudiProjectTypeEngine.Type.VILLA_TWO -> if(floors<2){issues+="فيلا الدورين تحتاج توزيعًا رأسيًا فعليًا.";typeScore-=22}
            SaudiProjectTypeEngine.Type.TOWNHOUSE,SaudiProjectTypeEngine.Type.DUPLEX -> if(floors<2){issues+="النوع المختار يحتاج تنظيمًا رأسيًا أوضح.";typeScore-=18}
            SaudiProjectTypeEngine.Type.BUILDING_ONE,SaudiProjectTypeEngine.Type.BUILDING_TWO -> {
                val units=rooms.count{it.name.contains("شقة")||it.type.contains("apartment",true)}
                if(units<2){issues+="استقلال الوحدات السكنية غير واضح.";typeScore-=20}else strengths+="هوية الوحدات المتعددة واضحة."
            }
            SaudiProjectTypeEngine.Type.REST_HOUSE,SaudiProjectTypeEngine.Type.TRADITIONAL -> if(rooms.none{isCourtyard(it)}){issues+="النوع المختار يحتاج ارتباطًا أوضح بالحوش/الفناء.";typeScore-=10}
        }
        categories["نوع المشروع"]=typeScore.coerceIn(0,100)

        val majlis=find(rooms,"majlis","مجلس")
        val family=find(rooms,"living","صالة") ?: find(rooms,"family","عائل")
        val kitchen=find(rooms,"kitchen","مطبخ")
        val bedrooms=rooms.filter{isBedroom(it)}
        val service=rooms.filter{isService(it)}
        var program=100
        if(brief.base.menMajlis&&majlis==null){issues+="المجلس المطلوب غير موجود بوضوح.";recommendations+="أعد توزيع البرنامج لإظهار مجلس ضيوف حقيقي.";program-=20}
        if(family==null){issues+="منطقة العائلة غير واضحة.";recommendations+="ثبّت صالة عائلية مركزية مرتبطة بالغرف الخاصة.";program-=18}else strengths+="منطقة عائلية واضحة."
        if(kitchen==null){issues+="المطبخ غير واضح.";recommendations+="أضف/ثبت المطبخ ضمن مسار الخدمة.";program-=16}else strengths+="المطبخ حاضر في البرنامج."
        if(brief.guestSuite&&rooms.none{it.type.equals("guest",true)||it.name.contains("ضيف")}){issues+="جناح الضيف المطلوب غير متحقق.";program-=10}
        if(brief.storageRoom&&rooms.none{it.type.equals("storage",true)||it.name.contains("مخزن")}){issues+="المخزن المطلوب غير واضح.";program-=6}
        if(brief.laundryRoom&&rooms.none{it.type.equals("laundry",true)||it.name.contains("غسيل")}){issues+="غرفة الغسيل المطلوبة غير واضحة.";program-=6}
        if(brief.elderlyGroundSuite){
            val ground=plan.floors.minByOrNull{it.index}?.rooms?:plan.rooms
            if(ground.none{isBedroom(it)}){issues+="جناح كبير السن الأرضي المطلوب غير متحقق.";program-=14}
        }
        if(bedrooms.isEmpty()){issues+="لا توجد غرف نوم واضحة.";program-=12}
        categories["البرنامج"]=program.coerceIn(0,100)

        val adjacency=adjacency(openings)
        var privacy=100
        if(majlis!=null&&family!=null){
            val separation=abs(majlis.x-family.x)+abs(majlis.y-family.y)
            if(separation<20f){issues+="الفصل المكاني بين الضيوف والعائلة ضعيف.";privacy-=18}else strengths+="فصل الضيافة عن العائلة مقبول مبدئيًا."
            if(areAdjacent(majlis.id,family.id,adjacency)){issues+="المجلس متصل مباشرة بمنطقة العائلة؛ هذا يضعف الخصوصية.";recommendations+="افصل الضيوف عبر بهو/موزع أو مسار مستقل دون تغيير البرنامج المطلوب.";privacy-=24}
        }
        if(majlis!=null&&bedrooms.any{areAdjacent(majlis.id,it.id,adjacency)}){issues+="هناك اتصال مباشر بين الضيافة وغرفة نوم.";recommendations+="اكسر الاتصال المباشر بين المجلس والنطاق الخاص.";privacy-=28}
        if(brief.base.privacyPriority>=85&&majlis==null)privacy-=8
        categories["الخصوصية"]=privacy.coerceIn(0,100)

        var circulation=100
        val graphRoomIds=rooms.map{it.id}.toSet()
        val connectedIds=adjacency.keys+adjacency.values.flatten()
        val isolated=graphRoomIds.filter{it !in connectedIds}
        if(openings.any{it.connectsRoomIds.size>=2}){
            if(isolated.size>rooms.size/3){issues+="عدد كبير من الغرف غير مرتبط بشبكة حركة موثقة.";recommendations+="راجع الأبواب والموزعات واربط الغرف دون اختراع مسارات غير قابلة للبناء.";circulation-=24}
            else strengths+="شبكة الحركة موثقة عبر فتحات بين الغرف."
        } else {
            issues+="علاقات الأبواب بين الغرف غير موثقة بما يكفي لتحليل الحركة كاملًا.";circulation-=14
        }
        val entry=rooms.firstOrNull{val t="${it.type} ${it.name}".lowercase();t.contains("entry")||t.contains("foyer")||t.contains("مدخل")||t.contains("بهو")}
        if(entry==null){recommendations+="عرّف نقطة دخول/بهو واضحة لتحسين تحليل الحركة.";circulation-=8}
        categories["الحركة"]=circulation.coerceIn(0,100)

        var services=100
        if(brief.base.serviceEntrance&&service.isEmpty()){issues+="مسار الخدمة المطلوب بلا عناصر خدمة واضحة.";services-=18}
        if(kitchen!=null&&service.isNotEmpty()){
            val near=service.any{abs(it.x-kitchen.x)+abs(it.y-kitchen.y)<28f||areAdjacent(it.id,kitchen.id,adjacency)}
            if(near)strengths+="تجميع الخدمة قريب من المطبخ." else {issues+="الخدمات متباعدة عن المطبخ.";recommendations+="قرّب الغسيل/الخدمة/المخزن من المطبخ لتقليل مسار التشغيل.";services-=15}
        }
        categories["الخدمات"]=services.coerceIn(0,100)

        var vertical=100
        val elements=if(plan.floors.isNotEmpty())plan.floors.flatMap{it.elements}else plan.elements
        val hasStair=elements.any{val t=it.type.lowercase();t.contains("stair")||t.contains("درج")||t.contains("سلم")}
        val hasElevator=elements.any{val t=it.type.lowercase();t.contains("elevator")||t.contains("مصعد")}
        if(floors>1&&!hasStair){issues+="المشروع متعدد الأدوار دون درج إنشائي واضح.";recommendations+="ثبّت نواة درج قابلة للتنفيذ وتحقق من تطابقها رأسيًا.";vertical-=35}
        if(brief.base.elevator&&!hasElevator){issues+="المصعد المطلوب غير ممثل كنواة إنشائية.";recommendations+="احجز نواة مصعد مرتبطة بالدرج قبل تحسين بقية الغرف.";vertical-=22}
        if(floors>1&&hasStair)strengths+="الحركة الرأسية ممثلة بعنصر درج."
        categories["الحركة الرأسية"]=vertical.coerceIn(0,100)

        var daylight=100
        val windowRoomIds=openings.filter{OpeningVerticalProfileEngine.isWindow(it.type)}.flatMap{it.connectsRoomIds}.toSet()
        if(windowRoomIds.isNotEmpty()){
            val habitable=rooms.filter{!isService(it)}
            val without=habitable.count{it.id !in windowRoomIds}
            if(habitable.isNotEmpty()&&without*2>habitable.size){issues+="أكثر من نصف الفراغات المعيشية لا تملك ارتباط نافذة موثقًا.";recommendations+="حسّن وصول الضوء من الواجهات الفعلية مع الحفاظ على الخصوصية.";daylight-=22}
            else strengths+="توزيع النوافذ يخدم معظم الفراغات المعيشية الموثقة."
        } else {daylight-=12;recommendations+="اربط النوافذ بالغرف في Geometry V3 لقياس الإضاءة والواجهة بدقة."}
        categories["الضوء والواجهة"]=daylight.coerceIn(0,100)

        var future=100
        if(brief.futureExpansion&&brief.futureFloors>0&&floors>1&&!hasStair&&!hasElevator){future-=30;issues+="التوسع المستقبلي مطلوب لكن النواة الرأسية غير محمية."}
        if(brief.futureExpansion&&plan.preferences.futureFlexibilityPriority>=80)strengths+="مرونة المستقبل محفوظة كأولوية مشروع."
        categories["المرونة المستقبلية"]=future.coerceIn(0,100)

        if(plan.constraints.any{it.hard&&!it.active}){issues+="هناك قيود صلبة معطلة تحتاج مراجعة.";recommendations+="أعد تفعيل أو حل القيود الصلبة قبل اعتماد البديل."}
        val weights=mapOf("نوع المشروع" to .16,"البرنامج" to .18,"الخصوصية" to .18,"الحركة" to .15,"الخدمات" to .10,"الحركة الرأسية" to .10,"الضوء والواجهة" to .08,"المرونة المستقبلية" to .05)
        val score=categories.entries.sumOf{(k,v)->v*(weights[k]?:0.0)}.toInt().coerceIn(0,100)
        return Critique(score,strengths.distinct(),issues.distinct(),hard.distinct(),categories,recommendations.distinct())
    }

    private fun allRooms(plan:FloorPlan)=if(plan.floors.isNotEmpty())plan.floors.flatMap{it.rooms}else plan.rooms
    private fun allOpenings(plan:FloorPlan)=if(plan.floors.isNotEmpty())plan.floors.flatMap{it.openings}else plan.openings
    private fun adjacency(openings:List<Opening>):Map<String,Set<String>>{
        val map=mutableMapOf<String,MutableSet<String>>()
        openings.forEach{o->val ids=o.connectsRoomIds.distinct();if(ids.size>=2){ids.forEach{a->ids.filter{it!=a}.forEach{b->map.getOrPut(a){mutableSetOf()}+=b}}}}
        return map.mapValues{it.value.toSet()}
    }
    private fun areAdjacent(a:String,b:String,graph:Map<String,Set<String>>)=b in graph[a].orEmpty()
    private fun find(rooms:List<Room>,type:String,name:String)=rooms.firstOrNull{it.type.equals(type,true)||it.name.contains(name)}
    private fun isBedroom(room:Room)=room.type.lowercase() in setOf("bedroom","master")||room.name.contains("نوم")
    private fun isService(room:Room)=room.type.lowercase() in setOf("kitchen","service","laundry","maid","storage","bath","wc")||room.name.contains("خدمة")||room.name.contains("غسيل")||room.name.contains("مخزن")
    private fun isCourtyard(room:Room)=room.type.equals("courtyard",true)||room.name.contains("حوش")||room.name.contains("فناء")
}
