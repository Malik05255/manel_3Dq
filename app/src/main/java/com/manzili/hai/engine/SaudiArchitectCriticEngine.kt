package com.manzili.hai.engine

import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.Room
import kotlin.math.abs

/** Senior-architect critic. Architectural guidance only; never invents official regulations. */
object SaudiArchitectCriticEngine {
    data class Critique(val score:Int,val strengths:List<String>,val issues:List<String>,val hardViolations:List<String>)

    fun inspect(plan:FloorPlan,type:SaudiProjectTypeEngine.Type,brief:SaudiDeepBriefEngine.Brief):Critique {
        val rooms=if(plan.floors.isNotEmpty())plan.floors.flatMap { it.rooms } else plan.rooms
        val issues=mutableListOf<String>();val strengths=mutableListOf<String>();val hard=mutableListOf<String>()
        var score=100
        if(SaudiProjectTypeEngine.detect(plan)!=type){ hard += "نوع المشروع لم يُحفظ كقيد صلب.";score-=35 } else strengths += "نوع المشروع محفوظ كقيد صلب."
        val majlis=find(rooms,"majlis","مجلس");val family=find(rooms,"living","صالة");val kitchen=find(rooms,"kitchen","مطبخ")
        if(brief.base.menMajlis && majlis==null){issues += "المجلس المطلوب غير واضح.";score-=12}
        if(family==null){issues += "منطقة العائلة غير واضحة.";score-=12}else strengths += "منطقة عائلية واضحة."
        if(kitchen==null){issues += "المطبخ غير واضح.";score-=10}else strengths += "المطبخ حاضر في البرنامج."
        if(majlis!=null&&family!=null){
            val separation=abs(majlis.x-family.x)+abs(majlis.y-family.y)
            if(separation<20f){issues += "فصل الضيوف عن العائلة ضعيف.";score-=12}else strengths += "فصل الضيافة عن العائلة مقبول مبدئيًا."
        }
        val floors=if(plan.floors.isEmpty())1 else plan.floors.size
        when(type){
            SaudiProjectTypeEngine.Type.VILLA_ONE -> if(floors!=1){issues += "فيلا الدور الواحد خرجت عن عدد الأدوار المختار.";score-=18}
            SaudiProjectTypeEngine.Type.VILLA_TWO,SaudiProjectTypeEngine.Type.TOWNHOUSE,SaudiProjectTypeEngine.Type.DUPLEX -> if(floors<2){issues += "النوع المختار يحتاج توزيعًا رأسيًا أوضح.";score-=15}
            SaudiProjectTypeEngine.Type.BUILDING_ONE,SaudiProjectTypeEngine.Type.BUILDING_TWO -> {
                val units=rooms.count { it.name.contains("شقة")||it.type.contains("apartment",true) }
                if(units<2){issues += "استقلال الوحدات السكنية غير واضح.";score-=16}else strengths += "هوية الوحدات المتعددة واضحة."
            }
            SaudiProjectTypeEngine.Type.REST_HOUSE -> if(rooms.none { it.type.equals("courtyard",true)||it.name.contains("حوش") }){issues += "الاستراحة تحتاج ارتباطًا أوضح بالحوش/الخارج.";score-=8}
            SaudiProjectTypeEngine.Type.TRADITIONAL -> if(rooms.none { it.type.equals("courtyard",true)||it.name.contains("حوش") }){issues += "البيت التقليدي يحتاج فناء/حوش أوضح.";score-=8}
        }
        if(brief.elderlyGroundSuite){
            val ground=plan.floors.minByOrNull { it.index }?.rooms ?: plan.rooms
            if(ground.none { it.type.lowercase() in setOf("bedroom","master") }){issues += "جناح كبير السن الأرضي المطلوب غير متحقق.";score-=12}
        }
        if(brief.base.serviceEntrance && rooms.none { it.type.lowercase() in setOf("service","laundry","maid") }){issues += "مسار الخدمة يحتاج عناصر خدمة أوضح.";score-=6}
        if(plan.constraints.any { it.hard && !it.active }){issues += "هناك قيود صلبة معطلة تحتاج مراجعة.";score-=5}
        return Critique(score.coerceIn(0,100),strengths.distinct(),issues.distinct(),hard.distinct())
    }

    private fun find(rooms:List<Room>,type:String,name:String)=rooms.firstOrNull { it.type.equals(type,true)||it.name.contains(name) }
}
