package com.manzili.hai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.manzili.hai.engine.NewBuildSolver
import com.manzili.hai.model.FloorPlan

@Composable
fun NewBuildSolverScreen(nav: NavHostController, onChoose: (FloorPlan) -> Unit) {
    var city by remember { mutableStateOf("الرياض") }
    var width by remember { mutableStateOf("20") }
    var depth by remember { mutableStateOf("25") }
    var floors by remember { mutableIntStateOf(2) }
    var bedrooms by remember { mutableIntStateOf(4) }
    var guestIndependent by remember { mutableStateOf(true) }
    var saudiRules by remember { mutableStateOf(false) }
    var privacy by remember { mutableFloatStateOf(90f) }
    var circulation by remember { mutableFloatStateOf(85f) }
    var daylight by remember { mutableFloatStateOf(80f) }
    var notes by remember { mutableStateOf("الضيوف لا يمرون على منطقة العائلة") }
    var candidates by remember { mutableStateOf<List<NewBuildSolver.Candidate>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    Surface(Modifier.fillMaxSize(), color = Color(0xFFF7F4EE)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp)) {
            Row(Modifier.fillMaxWidth()) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Rounded.ArrowForward, "رجوع") }
                Column {
                    Text("بناء من جديد", fontSize = 23.sp, fontWeight = FontWeight.Black)
                    Text("3 بدائل هندسية من نفس البرنامج — بدون اختيار عشوائي", color = Color.Gray, fontSize = 10.sp)
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                if (candidates.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(city, { city = it }, label = { Text("المدينة") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(width, { width = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("عرض الأرض م") }, modifier = Modifier.weight(1f), singleLine = true)
                        OutlinedTextField(depth, { depth = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("طول الأرض م") }, modifier = Modifier.weight(1f), singleLine = true)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("عدد الأدوار: $floors", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { (1..3).forEach { n -> FilterChip(selected = floors == n, onClick = { floors = n }, label = { Text("$n") }) } }
                    Spacer(Modifier.height(8.dp))
                    Text("غرف النوم: $bedrooms", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { (2..6).forEach { n -> FilterChip(selected = bedrooms == n, onClick = { bedrooms = n }, label = { Text("$n") }) } }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("مدخل ضيوف مستقل", modifier = Modifier.padding(top = 14.dp))
                        Switch(guestIndependent, { guestIndependent = it })
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text("إضافة فحص الاشتراطات السعودية/البلدية", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                Text("اختياري • الافتراضي غير مفعّل • يمكن إلغاؤه لاحقًا", color = Color.Gray, fontSize = 9.sp)
                            }
                            Switch(saudiRules, { saudiRules = it })
                        }
                    }
                    PrioritySlider("الخصوصية", privacy) { privacy = it }
                    PrioritySlider("سهولة الحركة", circulation) { circulation = it }
                    PrioritySlider("الإضاءة الطبيعية", daylight) { daylight = it }
                    OutlinedTextField(notes, { notes = it }, label = { Text("أولوية/ملاحظة إضافية") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp)) }
                    Spacer(Modifier.height(18.dp))
                    Button(onClick = {
                        val w = width.toDoubleOrNull(); val d = depth.toDoubleOrNull()
                        if (w == null || d == null) { error = "أدخل أبعاد أرض صحيحة."; return@Button }
                        runCatching {
                            NewBuildSolver.generate(NewBuildSolver.Program(
                                city = city.trim(), plotWidthM = w, plotDepthM = d, floorCount = floors, bedrooms = bedrooms,
                                guestEntranceIndependent = guestIndependent, privacyPriority = privacy.toInt(), circulationPriority = circulation.toInt(), daylightPriority = daylight.toInt(), notes = notes
                            )).map { it.copy(plan = it.plan.copy(saudiRulesEnabled = saudiRules)) }
                        }.onSuccess { candidates = it; error = null }.onFailure { error = it.message ?: "تعذر توليد البدائل" }
                    }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(17.dp)) {
                        Icon(Icons.Rounded.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text("ولّد 3 بدائل محسوبة", fontWeight = FontWeight.Bold)
                    }
                } else {
                    Text("ثلاثة اتجاهات مختلفة", fontSize = 19.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(vertical = 10.dp))
                    Text(if (saudiRules) "فحص السعودية مضاف لهذه البدائل ويمكن إلغاؤه لاحقًا." else "فحص السعودية غير مضاف — التصميم يعمل بدونه.", color = Color.Gray, fontSize = 9.5.sp, modifier = Modifier.padding(bottom = 8.dp))
                    candidates.forEachIndexed { index, candidate ->
                        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Row {
                                    Text("${index + 1}. ${candidate.title}", fontWeight = FontWeight.Black, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                    Text("${candidate.overall}/100", fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.secondary)
                                }
                                Text(candidate.rationale, color = Color.Gray, fontSize = 10.5.sp, lineHeight = 15.sp, modifier = Modifier.padding(vertical = 5.dp))
                                Text(candidate.metrics.joinToString(" • "), fontSize = 9.5.sp)
                                Spacer(Modifier.height(7.dp))
                                PlanCanvas(candidate.plan, Modifier.fillMaxWidth().height(205.dp), previewMode = true, onSelect = {})
                                Spacer(Modifier.height(8.dp))
                                Button(onClick = { onChoose(candidate.plan) }, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Rounded.Done, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("اعتمد هذا الاتجاه كمشروع")
                                }
                            }
                        }
                    }
                    OutlinedButton(onClick = { candidates = emptyList() }, modifier = Modifier.fillMaxWidth()) { Text("عدّل البرنامج وأعد التوليد") }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun PrioritySlider(label: String, value: Float, onValue: (Float) -> Unit) {
    Text("$label: ${value.toInt()}/100", fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
    Slider(value = value, onValueChange = onValue, valueRange = 50f..100f)
}