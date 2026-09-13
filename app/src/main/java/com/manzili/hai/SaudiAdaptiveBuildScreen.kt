package com.manzili.hai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.manzili.hai.ai.HaiArchitectClient
import com.manzili.hai.engine.NewBuildSolver
import com.manzili.hai.engine.SaudiDeepBriefEngine
import com.manzili.hai.engine.SaudiGenerativeArchitectEngine
import com.manzili.hai.engine.SaudiProjectTypeEngine
import com.manzili.hai.engine.SaudiResidentialEngine
import com.manzili.hai.model.FloorPlan
import kotlinx.coroutines.launch

@Composable
fun SaudiAdaptiveBuildScreen(
    nav: NavHostController,
    projectType: SaudiProjectTypeEngine.Type,
    onChoose: (FloorPlan) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hai = remember { HaiArchitectClient(context) }
    var step by remember { mutableIntStateOf(0) }
    var city by remember { mutableStateOf("الرياض") }
    var width by remember { mutableStateOf(if (projectType == SaudiProjectTypeEngine.Type.TOWNHOUSE) "8" else "20") }
    var depth by remember { mutableStateOf("25") }
    var streetSide by remember { mutableStateOf("شمال") }
    var streetWidth by remember { mutableStateOf("") }
    var north by remember { mutableStateOf("0") }
    var floors by remember { mutableIntStateOf(projectType.defaultFloors) }
    var bedrooms by remember { mutableIntStateOf(projectType.defaultBedrooms) }
    var familySize by remember { mutableIntStateOf(6) }
    var parking by remember { mutableIntStateOf(if (projectType.apartmentMode) 4 else 2) }
    var unitsPerFloor by remember { mutableIntStateOf(if (projectType.apartmentMode) 2 else 1) }
    var elevator by remember { mutableStateOf(projectType == SaudiProjectTypeEngine.Type.BUILDING_TWO) }
    var womenReception by remember { mutableStateOf(false) }
    var maid by remember { mutableStateOf(projectType in setOf(SaudiProjectTypeEngine.Type.VILLA_ONE, SaudiProjectTypeEngine.Type.VILLA_TWO)) }
    var courtyard by remember { mutableStateOf(projectType != SaudiProjectTypeEngine.Type.BUILDING_TWO) }
    var familyEntry by remember { mutableStateOf(true) }
    var serviceEntry by remember { mutableStateOf(true) }
    var elderly by remember { mutableStateOf(false) }
    var guestSuite by remember { mutableStateOf(false) }
    var storage by remember { mutableStateOf(true) }
    var pantry by remember { mutableStateOf(false) }
    var futureExpansion by remember { mutableStateOf(true) }
    var futureFloors by remember { mutableIntStateOf(0) }
    var cornerPlot by remember { mutableStateOf(false) }
    var neighborExposure by remember { mutableStateOf("غير محدد") }
    var specialAnswer by remember { mutableStateOf("") }
    var style by remember { mutableStateOf("سعودي معاصر") }
    var privacy by remember { mutableFloatStateOf(projectType.defaultPrivacy.toFloat()) }
    var circulation by remember { mutableFloatStateOf(86f) }
    var daylight by remember { mutableFloatStateOf(82f) }
    var notes by remember { mutableStateOf("") }
    var candidates by remember { mutableStateOf<List<NewBuildSolver.Candidate>>(emptyList()) }
    var searchInfo by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val profile = SaudiProjectTypeEngine.profile(projectType)

    Surface(Modifier.fillMaxSize(), color = StudioColors.Canvas) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 18.dp)) {
            Row(Modifier.fillMaxWidth()) {
                IconButton(onClick = { if (candidates.isNotEmpty()) candidates = emptyList() else if (step > 0) step-- else nav.popBackStack() }) { Icon(Icons.Outlined.ArrowForward, "رجوع") }
                Column(Modifier.weight(1f)) {
                    Text(projectType.label, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(if (candidates.isEmpty()) "الخطوة ${step + 1} من 4" else "أفضل حلول ${projectType.label}", color = Color.Gray, fontSize = 12.sp)
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                if (candidates.isEmpty()) {
                    LinearProgressIndicator(progress = (step + 1) / 4f, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp))
                    when (step) {
                        0 -> {
                            AdaptiveHeading("الأرض والموقع", "أبعاد الأرض واتجاهها")
                            OutlinedTextField(city, { city = it }, label = { Text("المدينة") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                OutlinedTextField(width, { width = it.numeric2() }, label = { Text("عرض الأرض م") }, modifier = Modifier.weight(1f), singleLine = true)
                                OutlinedTextField(depth, { depth = it.numeric2() }, label = { Text("طول الأرض م") }, modifier = Modifier.weight(1f), singleLine = true)
                            }
                            Text("جهة الشارع", fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("شمال", "شرق", "جنوب", "غرب").forEach { s -> FilterChip(streetSide == s, { streetSide = s }, { Text(s) }) } }
                            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                OutlinedTextField(streetWidth, { streetWidth = it.numeric2() }, label = { Text("عرض الشارع م") }, modifier = Modifier.weight(1f), singleLine = true)
                                OutlinedTextField(north, { north = it.numericSigned2() }, label = { Text("الشمال °") }, modifier = Modifier.weight(.65f), singleLine = true)
                            }
                            AdaptiveToggle("قطعة زاوية", cornerPlot) { cornerPlot = it }
                            Text("انكشاف الجيران", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            listOf("غير محدد", "جانبان", "ثلاث جهات", "خلفي قوي", "واجهة مفتوحة").forEach { v -> FilterChip(neighborExposure == v, { neighborExposure = v }, { Text(v) }, modifier = Modifier.padding(end = 4.dp)) }
                        }
                        1 -> {
                            AdaptiveHeading("مساحات البيت", profile.questions.take(2).joinToString(" • "))
                            AdaptiveCounter("الأدوار", floors, 1, 4) { floors = it }
                            AdaptiveCounter(if (projectType.apartmentMode) "إجمالي غرف النوم التقريبي" else "غرف النوم", bedrooms, 1, 12) { bedrooms = it }
                            AdaptiveCounter("مواقف السيارات", parking, 0, 12) { parking = it }
                            if (projectType.apartmentMode || projectType == SaudiProjectTypeEngine.Type.DUPLEX) AdaptiveCounter("الوحدات في كل دور", unitsPerFloor, 1, 6) { unitsPerFloor = it }
                            if (!projectType.apartmentMode) AdaptiveCounter("أفراد الأسرة", familySize, 1, 20) { familySize = it }
                            AdaptiveToggle("مصعد", elevator) { elevator = it }
                            AdaptiveToggle("حوش/فناء", courtyard) { courtyard = it }
                            AdaptiveToggle("غرفة عاملة منزلية", maid) { maid = it }
                        }
                        2 -> {
                            AdaptiveHeading("أسئلة خاصة بـ ${projectType.label}", profile.questions.drop(2).joinToString(" • "))
                            AdaptiveToggle("جناح كبير سن في الأرضي", elderly) { elderly = it }
                            AdaptiveToggle("جناح ضيف", guestSuite) { guestSuite = it }
                            AdaptiveToggle("مدخل عائلة منفصل", familyEntry) { familyEntry = it }
                            AdaptiveToggle("مدخل خدمة مستقل", serviceEntry) { serviceEntry = it }
                            AdaptiveToggle("مخزن", storage) { storage = it }
                            AdaptiveToggle("بانتري/تحضير", pantry) { pantry = it }
                            AdaptiveToggle("استقبال نساء", womenReception) { womenReception = it }
                            OutlinedTextField(
                                specialAnswer,
                                { specialAnswer = it },
                                label = { Text(specialLabel(projectType)) },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2
                            )
                        }
                        else -> {
                            AdaptiveHeading("الهوية والمستقبل", "HAI سيستخدم هذه الإجابات في التوليد والنقد الهندسي.")
                            AdaptiveToggle("أخطط لتوسع مستقبلي", futureExpansion) { futureExpansion = it }
                            if (futureExpansion) AdaptiveCounter("أدوار مستقبلية محتملة", futureFloors, 0, 3) { futureFloors = it }
                            AdaptivePriority("الخصوصية", privacy) { privacy = it }
                            AdaptivePriority("سهولة الحركة", circulation) { circulation = it }
                            AdaptivePriority("الإضاءة الطبيعية", daylight) { daylight = it }
                            Text("الهوية المعمارية", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            listOf("سعودي معاصر", "نجدي معاصر", "حجازي معاصر", "عسيري معاصر", "نيوكلاسيك سعودي").forEach { s -> FilterChip(style == s, { style = s }, { Text(s) }, modifier = Modifier.padding(end = 4.dp)) }
                            OutlinedTextField(notes, { notes = it }, label = { Text("أي شروط إضافية") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                        }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                    Spacer(Modifier.height(14.dp))
                    Button(
                        enabled = !busy,
                        onClick = {
                            if (step < 3) { step++; return@Button }
                            val w = width.toDoubleOrNull(); val d = depth.toDoubleOrNull()
                            if (w == null || d == null) { error = "أدخل أبعاد الأرض بشكل صحيح."; return@Button }
                            val baseBrief = SaudiResidentialEngine.Brief(
                                city = city, familySize = familySize, womenReception = womenReception,
                                familyEntranceSeparate = familyEntry, serviceEntrance = serviceEntry,
                                parkingCars = parking, maidRoom = maid, elevator = elevator, courtyard = courtyard,
                                architectureStyle = style, streetSide = streetSide, streetWidthM = streetWidth.toDoubleOrNull(),
                                northDeg = north.toFloatOrNull() ?: 0f, privacyPriority = privacy.toInt()
                            )
                            val deep = SaudiDeepBriefEngine.Brief(
                                base = baseBrief, elderlyGroundSuite = elderly, guestSuite = guestSuite,
                                storageRoom = storage, pantry = pantry, laundryRoom = true,
                                futureExpansion = futureExpansion, futureFloors = futureFloors,
                                neighborExposure = neighborExposure, cornerPlot = cornerPlot
                            )
                            val program = NewBuildSolver.Program(
                                title = projectType.label, city = city, plotWidthM = w, plotDepthM = d,
                                floorCount = floors, bedrooms = bedrooms, guestEntranceIndependent = true,
                                privacyPriority = privacy.toInt(), circulationPriority = circulation.toInt(),
                                daylightPriority = daylight.toInt(),
                                notes = "$notes • نوع=${projectType.label} • وحدات/دور=$unitsPerFloor • $specialAnswer"
                            )
                            busy = true; error = null
                            scope.launch {
                                runCatching {
                                    val adaptive = "وحدات/دور=$unitsPerFloor؛ ${specialLabel(projectType)}=$specialAnswer"
                                    val requirements = SaudiGenerativeArchitectEngine.requirements(program, projectType, deep, adaptive)
                                    val aiSeed = runCatching { hai.createNewPlan(requirements) }.getOrNull()
                                    SaudiGenerativeArchitectEngine.generate(program, projectType, deep, aiSeed)
                                }.onSuccess { result ->
                                    candidates = result.candidates
                                    searchInfo = "فُحصت ${result.stats.candidatesInspected} حالة • صحيحة ${result.stats.validCandidates} • مختلفة ${result.stats.distinctCandidates} • AI seed=${if (result.stats.aiSeedAccepted) "مقبول" else "fallback محلي"}"
                                }.onFailure { error = it.message ?: "تعذر توليد المشروع" }
                                busy = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(17.dp)
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.AutoAwesome, null)
                        Spacer(Modifier.width(7.dp)); Text(if (step < 3) "التالي" else if (busy) "HAI يصمم ويفحص…" else "ولّد أفضل 3 حلول", fontWeight = FontWeight.Bold)
                    }
                } else {
                    Text(searchInfo, fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 8.dp))
                    candidates.forEachIndexed { index, c ->
                        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Row { Text("${index + 1}. ${c.title}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text("${c.overall}/100", fontWeight = FontWeight.Bold) }
                                Text(c.rationale, color = Color.Gray, fontSize = 12.sp)
                                Text(c.metrics.joinToString(" • "), fontSize = 12.sp, modifier = Modifier.padding(vertical = 5.dp))
                                PlanCanvas(c.plan, Modifier.fillMaxWidth().height(200.dp), previewMode = true, onSelect = {})
                                Button(onClick = { onChoose(c.plan) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Done, null); Spacer(Modifier.width(5.dp)); Text("اعتمد هذا المشروع") }
                            }
                        }
                    }
                    OutlinedButton(onClick = { candidates = emptyList(); step = 0 }, modifier = Modifier.fillMaxWidth()) { Text("عدّل الإجابات") }
                }
            }
        }
    }
}

private fun specialLabel(type: SaudiProjectTypeEngine.Type): String = when (type) {
    SaudiProjectTypeEngine.Type.BUILDING_ONE, SaudiProjectTypeEngine.Type.BUILDING_TWO -> "مثال: شقتان بكل دور + شقة مالك + درج مشترك"
    SaudiProjectTypeEngine.Type.TOWNHOUSE -> "مثال: وحدة وسطية/طرفية + فناء خلفي"
    SaudiProjectTypeEngine.Type.DUPLEX -> "مثال: الوحدتان متجاورتان أو فوق بعض"
    SaudiProjectTypeEngine.Type.TRADITIONAL -> "مثال: اقتصادي جدًا + حوش كبير + توسع لاحق"
    SaudiProjectTypeEngine.Type.REST_HOUSE -> "مثال: عائلية/ضيوف + جلسات خارجية + مبيت"
    else -> "تفصيل إضافي خاص بهذا النوع"
}

@Composable private fun AdaptiveHeading(t: String, s: String) { Text(t, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp)); Text(s, color = Color.Gray, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(bottom = 12.dp)) }
@Composable private fun AdaptiveToggle(t: String, v: Boolean, on: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(t, fontSize = 12.sp, modifier = Modifier.padding(top = 13.dp)); Switch(v, on) } }
@Composable private fun AdaptiveCounter(t: String, v: Int, min: Int, max: Int, on: (Int) -> Unit) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("$t: $v", fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp)); Row { TextButton(onClick = { on((v - 1).coerceAtLeast(min)) }) { Text("−") }; TextButton(onClick = { on((v + 1).coerceAtMost(max)) }) { Text("+") } } } }
@Composable private fun AdaptivePriority(t: String, v: Float, on: (Float) -> Unit) { Text("$t: ${v.toInt()}/100", fontWeight = FontWeight.Bold, fontSize = 12.sp); Slider(v, on, valueRange = 50f..100f) }
private fun String.numeric2() = filter { it.isDigit() || it == '.' || it == '٫' }.replace('٫', '.')
private fun String.numericSigned2() = filter { it.isDigit() || it == '.' || it == '-' }.trim()
