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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.manzili.hai.engine.SaudiConstruction4DEngine
import com.manzili.hai.model.FloorPlan

@Composable
fun Saudi4DScreen(nav:NavHostController,plan:FloorPlan?) {
    Surface(Modifier.fillMaxSize(),color=Color(0xFFF7F4EE)){
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal=18.dp)){
            Row{IconButton(onClick={nav.popBackStack()}){Icon(Icons.Rounded.ArrowForward,"رجوع")};Column{Text("4D التنفيذ",fontSize=22.sp,fontWeight=FontWeight.Black);Text("Dependencies • Gantt نسبي • كميات آمنة",color=Color.Gray,fontSize=10.sp)}}
            if(plan==null){Text("افتح مشروعًا أولًا");return@Column}
            val timeline=SaudiConstruction4DEngine.build(plan)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                AssistChip(onClick={},label={Text(timeline.climate,fontSize=9.sp)},leadingIcon={Icon(Icons.Rounded.Schedule,null,Modifier.size(15.dp))})
                AssistChip(onClick={},label={Text("${timeline.totalPlanningDays} يوم تخطيط",fontSize=9.sp)})
                AssistChip(onClick={},label={Text(if(timeline.metricQuantitiesReady)"الكميات مفعلة" else "الكميات متوقفة",fontSize=9.sp)})
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top=7.dp)){
                timeline.phases.forEach{p->
                    Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth().padding(bottom=8.dp)){
                        Column(Modifier.padding(12.dp)){
                            Row{Text("${p.startPct}–${p.endPct}%",fontWeight=FontWeight.Black,color=Color(0xFF9A7447),modifier=Modifier.width(68.dp));Text(p.title,fontWeight=FontWeight.Black,modifier=Modifier.weight(1f));Text("${p.planningDays}ي",fontSize=9.sp,color=Color.Gray)}
                            LinearProgressIndicator(progress={p.endPct/100f},modifier=Modifier.fillMaxWidth().padding(vertical=7.dp))
                            if(p.dependencies.isNotEmpty())Text("يعتمد على: ${p.dependencies.joinToString(" • ")}",fontSize=8.8.sp,color=Color(0xFF706B62))
                            Text(p.scope,fontSize=10.5.sp,lineHeight=16.sp,modifier=Modifier.padding(top=3.dp))
                            Text(p.saudiNote,color=Color.Gray,fontSize=9.3.sp,lineHeight=14.sp,modifier=Modifier.padding(top=4.dp))
                            p.quantities.forEach{q->Surface(color=Color(0xFFECE7DC),shape=RoundedCornerShape(10.dp),modifier=Modifier.fillMaxWidth().padding(top=5.dp)){Text("${q.label}: ${"%.1f".format(q.value)} ${q.unit} • ${q.note}",fontSize=8.8.sp,lineHeight=13.sp,modifier=Modifier.padding(7.dp))}}
                        }
                    }
                }
                timeline.warnings.forEach{Surface(color=Color(0xFFFFF1D6),shape=RoundedCornerShape(13.dp),modifier=Modifier.fillMaxWidth().padding(bottom=6.dp)){Text(it,fontSize=9.5.sp,modifier=Modifier.padding(10.dp))}}
                Spacer(Modifier.height(80.dp))
            }
        }
    }
}
