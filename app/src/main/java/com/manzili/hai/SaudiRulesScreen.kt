package com.manzili.hai

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
                    Text("اشتراطات السعودية", fontSize = 21.sp, fontWeight = FontWeight.Black)
                    Text("إضافة اختيارية للمشروع وليست جزءًا إجباريًا من محرك التصميم", color = Color.Gray, fontSize = 9.5.sp)
                }
            }
            if (plan == null) {
                Text("لا يوجد مشروع مفتوح")
                return@Column
            }

            Card(colors = CardDefaults.cardColors(containerColor = if (plan.saudiRulesEnabled) Color(0xFF27312C) else Color.White), shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (plan.saudiRulesEnabled) Icons.Rounded.Verified else Icons.Rounded.AddTask, null, tint = if (plan.saudiRulesEnabled) Color.White else Color(0xFF9A7447))
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (plan.saudiRulesEnabled) "محرك الاشتراطات مضاف" else "محرك الاشتراطات غير مضاف", color = if (plan.saudiRulesEnabled) Color.White else Color.Black, fontWeight = FontWeight.Black)
                        Text(if (plan.saudiRulesEnabled) "يدخل فقط في شاشة الفحص والتنبيهات؛ ويمكن إلغاؤه الآن." else "التصميم وHAI يعملان بدونه بالكامل. أضفه فقط إذا رغبت بالفحص السعودي/البلدي.", color = if (plan.saudiRulesEnabled) Color.White.copy(alpha=.7f) else Color.Gray, fontSize = 9.5.sp, lineHeight = 14.sp)
                    }
                    Switch(
                        checked = plan.saudiRulesEnabled,
                        onCheckedChange = { enabled -> onUpdate(plan.copy(saudiRulesEnabled = enabled, revision = plan.revision + 1)) }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            if (!plan.saudiRulesEnabled) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFEFA)), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("عند عدم التفعيل", fontWeight = FontWeight.Black, fontSize = 12.sp)
                        Text("• لا يفحص SBC أو الاشتراطات البلدية.\n• لا يغيّر توليد المخطط.\n• لا يمنع سحب أو تعديل أي عنصر.\n• لا يضيف اعتراضات إلى HAI.", fontSize = 10.5.sp, lineHeight = 17.sp, color = Color.Gray)
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("يمكنك إضافة المحرك أو إلغاؤه في أي وقت، ويُحفظ الاختيار داخل المشروع نفسه.", color = Color.Gray, fontSize = 9.5.sp, modifier = Modifier.padding(bottom = 12.dp))
                return@Column
            }

            var city by remember(plan.revision) { mutableStateOf(plan.site.city) }
            var roadName by remember(plan.revision) { mutableStateOf(plan.site.roads.firstOrNull()?.name ?: "الشارع الأمامي") }
            var roadWidth by remember(plan.revision) { mutableStateOf(plan.site.roads.firstOrNull()?.widthM?.toString() ?: "") }
            var north by remember(plan.revision) { mutableStateOf((plan.site.northDeg ?: plan.northDeg ?: 0f).toString()) }
            val report = remember(plan) { SaudiRulesEngine.inspect(plan) }

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFEFA)), shape = RoundedCornerShape(17.dp)) {
                    Column(Modifier.padding(11.dp)) {
                        Text("بيانات الموقع للفحص", fontWeight = FontWeight.Black, fontSize = 12.sp)
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(city, { city = it }, label = { Text("المدينة") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(roadName, { roadName = it }, label = { Text("اسم الشارع") }, modifier = Modifier.weight(1.3f), singleLine = true)
                            OutlinedTextField(roadWidth, { roadWidth = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("عرضه م") }, modifier = Modifier.weight(.7f), singleLine = true)
                        }
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(north, { north = it.filter { c -> c.isDigit() || c == '.' || c == '-' } }, label = { Text("اتجاه الشمال °") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Spacer(Modifier.height(7.dp))
                        Button(onClick = {
                            val boundary = plan.site.plotBoundary.ifEmpty { plan.footprint.ifEmpty { listOf(PlanPoint(0f,0f), PlanPoint(100f,0f), PlanPoint(100f,100f), PlanPoint(0f,100f)) } }
                            val width = roadWidth.toDoubleOrNull()?.takeIf { it > 0 }
                            val roads = if (width != null) listOf(RoadEdge("front-road", roadName.ifBlank { "الشارع الأمامي" }, PlanPoint(0f,0f), PlanPoint(100f,0f), width)) else plan.site.roads
                            val northDeg = north.toFloatOrNull()?.let { ((it % 360f) + 360f) % 360f }
                            onUpdate(plan.copy(site = plan.site.copy(city = city.trim(), plotBoundary = boundary, roads = roads, northDeg = northDeg), northDeg = northDeg ?: plan.northDeg, revision = plan.revision + 1))
                        }, modifier = Modifier.fillMaxWidth()) { Text("حفظ بيانات الموقع وإعادة الفحص") }
                    }
                }

                report.checks.forEach { check ->
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(17.dp)) {
                        Column(Modifier.padding(11.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(ruleIcon(check.status), null, tint = ruleColor(check.status), modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(7.dp))
                                Text(check.title, fontWeight = FontWeight.Black, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                Text(check.status.name, color = ruleColor(check.status), fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                            }
                            Text(check.detail, fontSize = 10.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 5.dp))
                            Text("مرجع: ${check.source}", color = Color.Gray, fontSize = 8.5.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
            Text("يمكن إلغاء المحرك من المفتاح بالأعلى في أي وقت. الإلغاء يوقف الفحص ولا يحذف بيانات أرضك.", color = MaterialTheme.colorScheme.secondary, fontSize = 9.5.sp, modifier = Modifier.padding(vertical = 10.dp))
        }
    }
}

private fun ruleIcon(status: SaudiRulesEngine.Status) = when (status) {
    SaudiRulesEngine.Status.PASS -> Icons.Rounded.CheckCircle
    SaudiRulesEngine.Status.NEEDS_DATA -> Icons.Rounded.Help
    SaudiRulesEngine.Status.REVIEW -> Icons.Rounded.WarningAmber
    SaudiRulesEngine.Status.INFO -> Icons.Rounded.Info
    SaudiRulesEngine.Status.NOT_APPLICABLE -> Icons.Rounded.RemoveCircleOutline
}

private fun ruleColor(status: SaudiRulesEngine.Status) = when (status) {
    SaudiRulesEngine.Status.PASS -> Color(0xFF526D5A)
    SaudiRulesEngine.Status.NEEDS_DATA -> Color(0xFF9A7447)
    SaudiRulesEngine.Status.REVIEW -> Color(0xFFB36A3C)
    SaudiRulesEngine.Status.INFO -> Color.Gray
    SaudiRulesEngine.Status.NOT_APPLICABLE -> Color.Gray
}