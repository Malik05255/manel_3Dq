package com.manzili.hai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.HomeWork
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.manzili.hai.engine.SaudiResidentialEngine
import com.manzili.hai.model.FloorPlan

@Composable
fun SaudiPlanAuditScreen(nav: NavHostController, plan: FloorPlan?) {
    Surface(Modifier.fillMaxSize(), color=Color(0xFFF7F4EE)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal=18.dp)) {
            Row(Modifier.fillMaxWidth()) {
                IconButton(onClick={nav.popBackStack()}) { Icon(Icons.Rounded.ArrowForward,"رجوع") }
                Column { Text("مراجعة الفيلا السعودية",fontSize=22.sp,fontWeight=FontWeight.Black);Text("خصوصية • ضيافة • عائلة • خدمات • مناخ",color=Color.Gray,fontSize=10.sp) }
            }
            if(plan==null){Text("افتح مشروعًا أولًا");return@Column}
            val report=SaudiResidentialEngine.inspect(plan)
            val ctx=SaudiResidentialEngine.context(plan.site.city)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF27312C)),shape=RoundedCornerShape(22.dp),modifier=Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("${report.score}/100",color=Color.White,fontSize=34.sp,fontWeight=FontWeight.Black)
                        Text("ملاءمة تخطيطية للسكن السعودي",color=Color.White,fontWeight=FontWeight.Bold)
                        Text("${ctx.regionLabel} • ${SaudiResidentialEngine.styleLabel(plan)}",color=Color(0xFFD9D5CB),fontSize=10.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                report.notes.forEachIndexed { i,n->
                    Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth().padding(bottom=7.dp)) {
                        Row(Modifier.padding(12.dp)) { Icon(Icons.Rounded.HomeWork,null,tint=Color(0xFF9A7447));Spacer(Modifier.width(8.dp));Column { Text("ملاحظة ${i+1}",fontWeight=FontWeight.Bold,fontSize=10.sp);Text(n,fontSize=11.sp,lineHeight=17.sp) } }
                    }
                }
                Surface(color=Color(0xFFFFF1D6),shape=RoundedCornerShape(15.dp),modifier=Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp)) { Icon(Icons.Rounded.Rule,null);Spacer(Modifier.width(7.dp));Text("هذه مراجعة معمارية سعودية للسلوك والتوزيع والمناخ. المطابقة النظامية الرقمية تبقى في شاشة الاشتراطات السعودية.",fontSize=10.sp,lineHeight=15.sp) }
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick={nav.navigate("editor")},modifier=Modifier.fillMaxWidth().height(52.dp)){Text("افتح المحرر وعالج الملاحظات")}
                OutlinedButton(onClick={nav.navigate("saudi-rules")},modifier=Modifier.fillMaxWidth().padding(top=6.dp)){Text("افتح التحقق من الاشتراطات")}
            }
        }
    }
}
