package com.manzili.hai

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.manzili.hai.engine.SaudiProjectTypeEngine
import com.manzili.hai.engine.SaudiRulesEngine
import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.PlanPoint
import com.manzili.hai.model.RoadEdge

@Composable
fun SaudiRulesScreen(nav: NavHostController, plan: FloorPlan?, onUpdate: (FloorPlan) -> Unit) {
    Surface(Modifier.fillMaxSize(), color = Color(0xFFF7F4EE)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Rounded.ArrowForward, "رجوع") }
                Column {
                    Text("الاشتراطات السعودية", fontSize = 21.sp, fontWeight = FontWeight.Black)
                    Text("اختيارية في أي مرحلة • SBC 2024 • لا تغيّر التصميم تلقائيًا", color = Color.Gray, fontSize = 9.5.sp)
                }
            }
            if (plan == null) { Text("لا يوجد مشروع مفتوح"); return@Column }

            Card(colors = CardDefaults.cardColors(containerColor = if (plan.saudiRulesEnabled) Color(0xFF27312C) else Color.White), shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (plan.saudiRulesEnabled) Icons.Rounded.Verified else Icons.Rounded.AddTask, null, tint = if (plan.saudiRulesEnabled) Color.White else Color(0xFF9A7447))
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (plan.saudiRulesEnabled) "الفحص الرسمي الاختياري مفعّل" else "الفحص الرسمي غير مفعّل", color = if (plan.saudiRulesEnabled) Color.White else Color.Black, fontWeight = FontWeight.Black)
                        Text(if (plan.saudiRulesEnabled) "يمكن إلغاؤه فورًا؛ إلغاؤه لا يحذف المخطط ولا بيانات الموقع." else "HAI والتعديل و3D و4D تعمل بدونه بالكامل.", color = if (plan.saudiRulesEnabled) Color.White.copy(alpha=.72f) else Color.Gray, fontSize = 9.5.sp)
                    }
                    Switch(checked=plan.saudiRulesEnabled,onCheckedChange={enabled->onUpdate(plan.copy(saudiRulesEnabled=enabled,revision=plan.revision+1))})
                }
            }

            Spacer(Modifier.height(9.dp))
            if (!plan.saudiRulesEnabled) {
                Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(16.dp)){
                    Column(Modifier.padding(13.dp)){
                        Text("بدون الاشتراطات",fontWeight=FontWeight.Black)
                        Text("• لا توجد اعتراضات SBC.\n• لا تتغير الحلول المعمارية.\n• لا يتوقف الاستيراد أو 3D أو 4D.\n• تستطيع التفعيل لاحقًا من أي شاشة.",fontSize=10.5.sp,lineHeight=17.sp,color=Color.Gray)
                    }
                }
                Spacer(Modifier.weight(1f));Text("هذا الفصل مقصود: السياق السعودي المعماري يعمل دائمًا، أما الفحص الرسمي فهو قرارك.",fontSize=9.5.sp,color=Color.Gray,modifier=Modifier.padding(bottom=12.dp));return@Column
            }

            fun stored(kind:String)=plan.constraints.firstOrNull { it.kind==kind && it.active }?.value
            var city by remember(plan.revision){mutableStateOf(plan.site.city)}
            var roadName by remember(plan.revision){mutableStateOf(plan.site.roads.firstOrNull()?.name?:"الشارع الأمامي")}
            var roadWidth by remember(plan.revision){mutableStateOf(plan.site.roads.firstOrNull()?.widthM?.toString()?:"")}
            var north by remember(plan.revision){mutableStateOf((plan.site.northDeg?:plan.northDeg?:0f).toString())}
            var basements by remember(plan.revision){mutableStateOf(stored(SaudiRulesEngine.SCOPE_BASEMENT_COUNT)?.toInt()?.toString()?:"")}
            var families by remember(plan.revision){mutableStateOf(stored(SaudiRulesEngine.SCOPE_FAMILY_COUNT)?.toInt()?.toString()?:"")}
            var openSides by remember(plan.revision){mutableStateOf(stored(SaudiRulesEngine.SCOPE_OPEN_SIDES)?.toInt()?.toString()?:"")}
            var egress by remember(plan.revision){mutableStateOf(stored(SaudiRulesEngine.SCOPE_INDEPENDENT_EGRESS)?.let{it>=.5})}
            val type=SaudiProjectTypeEngine.infer(plan)
            val report=remember(plan){SaudiRulesEngine.inspect(plan)}

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Surface(color=Color(0xFFECE7DC),shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth()){
                    Text("مرجع النطاق المنفذ: SBC 1101 Section 101.2 • إصدار 2024 مطبق من 30/06/2025. PASS داخل التطبيق ليس رخصة أو اعتمادًا رسميًا.",fontSize=9.2.sp,lineHeight=14.sp,modifier=Modifier.padding(10.dp))
                }

                Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(17.dp)){
                    Column(Modifier.padding(12.dp)){
                        Text("بيانات نطاق SBC 1101",fontWeight=FontWeight.Black,fontSize=12.sp)
                        Text("لا أخمّن هذه البيانات من الرسم؛ أدخلها أنت ثم أقارنها بشروط النطاق الرسمية.",fontSize=9.sp,color=Color.Gray)
                        Spacer(Modifier.height(7.dp))
                        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                            OutlinedTextField(basements,{basements=it.filter(Char::isDigit)},label={Text("أدوار تحت الأرض")},modifier=Modifier.weight(1f),singleLine=true)
                            OutlinedTextField(families,{families=it.filter(Char::isDigit)},label={Text("عدد الأسر")},modifier=Modifier.weight(1f),singleLine=true)
                        }
                        Spacer(Modifier.height(7.dp));Text("خروج مستقل لكل عائلة",fontSize=10.sp,fontWeight=FontWeight.Bold)
                        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                            FilterChip(selected=egress==true,onClick={egress=true},label={Text("نعم")})
                            FilterChip(selected=egress==false,onClick={egress=false},label={Text("لا")})
                            FilterChip(selected=egress==null,onClick={egress=null},label={Text("غير محدد")})
                        }
                        if(type==SaudiProjectTypeEngine.Type.TOWNHOUSE){Spacer(Modifier.height(6.dp));OutlinedTextField(openSides,{openSides=it.filter(Char::isDigit)},label={Text("عدد الجهات المفتوحة حول التاون هاوس")},modifier=Modifier.fillMaxWidth(),singleLine=true)}
                    }
                }

                Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(17.dp)){
                    Column(Modifier.padding(12.dp)){
                        Text("بيانات الموقع",fontWeight=FontWeight.Black,fontSize=12.sp)
                        Spacer(Modifier.height(6.dp));OutlinedTextField(city,{city=it},label={Text("المدينة")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                        Spacer(Modifier.height(6.dp));Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                            OutlinedTextField(roadName,{roadName=it},label={Text("اسم الشارع")},modifier=Modifier.weight(1.3f),singleLine=true)
                            OutlinedTextField(roadWidth,{roadWidth=it.filter{c->c.isDigit()||c=='.'}},label={Text("عرضه م")},modifier=Modifier.weight(.7f),singleLine=true)
                        }
                        Spacer(Modifier.height(6.dp));OutlinedTextField(north,{north=it.filter{c->c.isDigit()||c=='.'||c=='-'}},label={Text("اتجاه الشمال °")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                        Spacer(Modifier.height(8.dp));Button(onClick={
                            val boundary=plan.site.plotBoundary.ifEmpty{plan.footprint.ifEmpty{listOf(PlanPoint(0f,0f),PlanPoint(100f,0f),PlanPoint(100f,100f),PlanPoint(0f,100f))}}
                            val width=roadWidth.toDoubleOrNull()?.takeIf{it>0};val roads=if(width!=null)listOf(RoadEdge("front-road",roadName.ifBlank{"الشارع الأمامي"},PlanPoint(0f,0f),PlanPoint(100f,0f),width))else plan.site.roads
                            val northDeg=north.toFloatOrNull()?.let{((it%360f)+360f)%360f}
                            val scoped=SaudiRulesEngine.setScopeInputs(plan,basements.toIntOrNull(),families.toIntOrNull(),egress,if(type==SaudiProjectTypeEngine.Type.TOWNHOUSE)openSides.toIntOrNull() else null)
                            onUpdate(scoped.copy(site=scoped.site.copy(city=city.trim(),plotBoundary=boundary,roads=roads,northDeg=northDeg),northDeg=northDeg?:scoped.northDeg,revision=scoped.revision+1))
                        },modifier=Modifier.fillMaxWidth()){Text("حفظ وإعادة الفحص")}
                    }
                }

                report.checks.forEach{check->
                    Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(17.dp)){
                        Column(Modifier.padding(11.dp)){
                            Row(verticalAlignment=Alignment.CenterVertically){Icon(ruleIcon(check.status),null,tint=ruleColor(check.status),modifier=Modifier.size(18.dp));Spacer(Modifier.width(7.dp));Text(check.title,fontWeight=FontWeight.Black,fontSize=12.sp,modifier=Modifier.weight(1f));Text(check.status.name,color=ruleColor(check.status),fontSize=8.5.sp,fontWeight=FontWeight.Bold)}
                            Text(check.detail,fontSize=10.sp,lineHeight=15.sp,modifier=Modifier.padding(top=5.dp));Text("مرجع: ${check.source}",color=Color.Gray,fontSize=8.5.sp,modifier=Modifier.padding(top=4.dp))
                        }
                    }
                }
                Spacer(Modifier.height(80.dp))
            }
        }
    }
}

private fun ruleIcon(status: SaudiRulesEngine.Status)=when(status){SaudiRulesEngine.Status.PASS->Icons.Rounded.CheckCircle;SaudiRulesEngine.Status.NEEDS_DATA->Icons.Rounded.Help;SaudiRulesEngine.Status.REVIEW->Icons.Rounded.WarningAmber;SaudiRulesEngine.Status.INFO->Icons.Rounded.Info;SaudiRulesEngine.Status.NOT_APPLICABLE->Icons.Rounded.RemoveCircleOutline}
private fun ruleColor(status: SaudiRulesEngine.Status)=when(status){SaudiRulesEngine.Status.PASS->Color(0xFF526D5A);SaudiRulesEngine.Status.NEEDS_DATA->Color(0xFF9A7447);SaudiRulesEngine.Status.REVIEW->Color(0xFFB36A3C);SaudiRulesEngine.Status.INFO->Color.Gray;SaudiRulesEngine.Status.NOT_APPLICABLE->Color.Gray}
