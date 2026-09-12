package com.manzili.hai

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
fun Saudi4DScreen(nav:NavHostController, plan:FloorPlan?) {
    Surface(Modifier.fillMaxSize(),color=Color(0xFFF7F4EE)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal=18.dp)) {
            Row { IconButton(onClick={nav.popBackStack()}){Icon(Icons.Rounded.ArrowForward,"رجوع")};Column { Text("4D تنفيذ الفيلا السعودية",fontSize=22.sp,fontWeight=FontWeight.Black);Text("المخطط + المجسم + تسلسل التنفيذ",color=Color.Gray,fontSize=10.sp) } }
            if(plan==null){Text("افتح مشروعًا أولًا");return@Column}
            val timeline=SaudiConstruction4DEngine.build(plan)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                AssistChip(onClick={},label={Text(timeline.climate,fontSize=9.sp)},leadingIcon={Icon(Icons.Rounded.Schedule,null,Modifier.size(15.dp))})
                timeline.phases.forEach { p->
                    Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth().padding(bottom=8.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Row { Text("${p.startPct}–${p.endPct}%",fontWeight=FontWeight.Black,color=Color(0xFF9A7447),modifier=Modifier.width(68.dp));Text(p.title,fontWeight=FontWeight.Black) }
                            LinearProgressIndicator(progress={p.endPct/100f},modifier=Modifier.fillMaxWidth().padding(vertical=7.dp))
                            Text(p.scope,fontSize=10.5.sp,lineHeight=16.sp)
                            Text(p.saudiNote,color=Color.Gray,fontSize=9.5.sp,lineHeight=14.sp,modifier=Modifier.padding(top=4.dp))
                        }
                    }
                }
                timeline.warnings.forEach { Surface(color=Color(0xFFFFF1D6),shape=RoundedCornerShape(13.dp),modifier=Modifier.fillMaxWidth().padding(bottom=6.dp)){Text(it,fontSize=9.5.sp,modifier=Modifier.padding(10.dp))} }
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}
