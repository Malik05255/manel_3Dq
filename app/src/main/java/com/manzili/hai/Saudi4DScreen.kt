package com.manzili.hai

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.manzili.hai.engine.Bim4DProductionEngine
import com.manzili.hai.model.FloorPlan

@Composable
fun Saudi4DScreen(nav:NavHostController,plan:FloorPlan?,onPlanChange:(FloorPlan)->Unit={}) {
    Surface(Modifier.fillMaxSize(),color=Color(0xFFF7F4EE)){
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal=16.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){IconButton(onClick={nav.popBackStack()}){Icon(Icons.Rounded.ArrowForward,"رجوع")};Column{Text("4D / BIM",fontSize=22.sp,fontWeight=FontWeight.Black);Text("CPM • Gantt • BOQ • Model links • User costs",color=Color.Gray,fontSize=9.5.sp)}}
            if(plan==null){Text("افتح مشروعًا أولًا");return@Column}
            val timeline=remember(plan){Bim4DProductionEngine.build(plan)}
            var rates by remember(plan.revision){mutableStateOf(timeline.boq.associate{q->q.key to (q.unitRateSar?.toString().orEmpty())})}
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                AssistChip(onClick={},label={Text("CPM ${timeline.totalPlanningDays} يوم",fontSize=9.sp)},leadingIcon={Icon(Icons.Rounded.Schedule,null,Modifier.size(15.dp))})
                AssistChip(onClick={},label={Text("المسار الحرج ${timeline.criticalPath.size} حزم",fontSize=9.sp)})
                AssistChip(onClick={},label={Text(if(timeline.metricReady)"BOQ متري" else "BOQ المتري متوقف",fontSize=9.sp)})
                AssistChip(onClick={},label={Text("تغطية التكلفة ${timeline.costCoveragePct}%",fontSize=9.sp)})
            }
            timeline.totalCostSar?.let{Text("التكلفة من أسعارك فقط: ${"%,.0f".format(it)} ر.س",fontWeight=FontWeight.Black,color=Color(0xFF79572F),fontSize=13.sp,modifier=Modifier.padding(vertical=4.dp))}
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top=6.dp)){
                Text("Gantt / المسار الحرج",fontWeight=FontWeight.Black,fontSize=15.sp,modifier=Modifier.padding(bottom=6.dp))
                timeline.packages.forEach{p->
                    Card(colors=CardDefaults.cardColors(containerColor=if(p.critical)Color(0xFFFFF2DC) else Color.White),shape=RoundedCornerShape(17.dp),modifier=Modifier.fillMaxWidth().padding(bottom=7.dp)){
                        Column(Modifier.padding(11.dp)){
                            Row(verticalAlignment=Alignment.CenterVertically){Text(p.title,fontWeight=FontWeight.Black,modifier=Modifier.weight(1f));if(p.critical)AssistChip(onClick={},label={Text("حرج",fontSize=8.sp)});Spacer(Modifier.width(5.dp));Text("يوم ${p.earliestStartDay}–${p.earliestFinishDay}",fontSize=8.7.sp,color=Color.Gray)}
                            GanttBar(p.earliestStartDay,p.durationDays,timeline.totalPlanningDays,p.critical)
                            if(p.dependencies.isNotEmpty())Text("يعتمد على: ${p.dependencies.joinToString(" • ")}",fontSize=8.5.sp,color=Color(0xFF706B62))
                            Text(p.saudiNote,fontSize=9.2.sp,lineHeight=13.sp,color=Color.Gray,modifier=Modifier.padding(top=3.dp))
                            Text("مرتبط بـ ${p.elementIds.size} عنصر/مصدر في النموذج",fontSize=8.4.sp,color=Color(0xFF8A6A43),modifier=Modifier.padding(top=3.dp))
                        }
                    }
                }

                Spacer(Modifier.height(5.dp));Text("BOQ هندسي + أسعارك",fontWeight=FontWeight.Black,fontSize=15.sp);Text("HAI لا يضع سعر سوق افتراضي. أدخل SAR/وحدة فقط للبنود التي تريد تسعيرها.",fontSize=8.8.sp,color=Color.Gray,modifier=Modifier.padding(bottom=6.dp))
                timeline.boq.forEach{q->
                    Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(15.dp),modifier=Modifier.fillMaxWidth().padding(bottom=6.dp)){
                        Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically){
                            Column(Modifier.weight(1f)){Text(q.label,fontWeight=FontWeight.Bold,fontSize=10.5.sp);Text("${"%.2f".format(q.value)} ${q.unit} • ${q.sourceIds.size} مصدر",fontSize=8.6.sp,color=Color.Gray);Text(q.note,fontSize=7.8.sp,color=Color.Gray,lineHeight=11.sp)}
                            Spacer(Modifier.width(8.dp))
                            OutlinedTextField(value=rates[q.key].orEmpty(),onValueChange={v->rates=rates+(q.key to v.filter{it.isDigit()||it=='.'}.take(12))},label={Text("ر.س/${q.unit}",fontSize=8.sp)},singleLine=true,modifier=Modifier.width(118.dp))
                        }
                    }
                }
                Button(onClick={
                    var updated=plan
                    timeline.boq.forEach{q->updated=Bim4DProductionEngine.setCostRate(updated,q.key,rates[q.key]?.toDoubleOrNull())}
                    onPlanChange(updated)
                },modifier=Modifier.fillMaxWidth()){Text("حفظ أسعار الوحدات وإعادة حساب التكلفة")}
                Spacer(Modifier.height(8.dp))
                timeline.warnings.forEach{Surface(color=Color(0xFFFFF1D6),shape=RoundedCornerShape(13.dp),modifier=Modifier.fillMaxWidth().padding(bottom=6.dp)){Text(it,fontSize=9.2.sp,lineHeight=14.sp,modifier=Modifier.padding(9.dp))}}
                Spacer(Modifier.height(70.dp))
            }
        }
    }
}

@Composable
private fun GanttBar(start:Int,duration:Int,total:Int,critical:Boolean){
    val safeTotal=total.coerceAtLeast(1).toFloat()
    Row(Modifier.fillMaxWidth().height(11.dp).padding(vertical=2.dp)){
        if(start>0)Spacer(Modifier.weight(start/safeTotal))
        Surface(color=if(critical)Color(0xFFB35D3B) else Color(0xFF6F806F),shape=RoundedCornerShape(6.dp),modifier=Modifier.weight((duration/safeTotal).coerceAtLeast(.01f)).fillMaxHeight()){}
        val rest=(total-start-duration).coerceAtLeast(0)
        if(rest>0)Spacer(Modifier.weight(rest/safeTotal))
    }
}
