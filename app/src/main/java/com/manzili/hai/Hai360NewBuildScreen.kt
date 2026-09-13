package com.manzili.hai

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manzili.hai.engine.GlobalLayoutOptimizer
import com.manzili.hai.engine.NewBuildSolver
import com.manzili.hai.engine.SaudiProjectTypeEngine
import com.manzili.hai.model.FloorPlan

@Composable
internal fun Hai360NewBuildScreen(
    onBack: () -> Unit,
    onChoose: (FloorPlan, SaudiProjectTypeEngine.Type) -> Unit
) {
    var type by remember { mutableStateOf(SaudiProjectTypeEngine.Type.VILLA_TWO) }
    var city by remember { mutableStateOf("الرياض") }
    var width by remember { mutableStateOf("20") }
    var depth by remember { mutableStateOf("25") }
    var floors by remember { mutableIntStateOf(type.defaultFloors) }
    var bedrooms by remember { mutableIntStateOf(type.defaultBedrooms) }
    var guestIndependent by remember { mutableStateOf(true) }
    var privacy by remember { mutableFloatStateOf(type.defaultPrivacy.toFloat()) }
    var circulation by remember { mutableFloatStateOf(86f) }
    var daylight by remember { mutableFloatStateOf(80f) }
    var notes by remember { mutableStateOf("الضيوف لا يمرون على منطقة العائلة") }
    var candidates by remember { mutableStateOf<List<NewBuildSolver.Candidate>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(type) {
        floors = type.defaultFloors
        bedrooms = type.defaultBedrooms
        privacy = type.defaultPrivacy.toFloat()
        candidates = emptyList()
    }

    Surface(Modifier.fillMaxSize(), color = H360Ivory) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                H360IconButton(Icons.Rounded.ArrowForward, "رجوع", onClick = onBack)
                Spacer(Modifier.width(12.dp))
                H360SectionLabel(if (candidates.isEmpty()) "BRIEF" else "OPTIONS", if (candidates.isEmpty()) "برنامج البيت" else "ثلاثة اتجاهات")
            }

            if (candidates.isEmpty()) {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)
                ) {
                    Spacer(Modifier.height(8.dp))
                    Text("اختر النوع", color = H360Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SaudiProjectTypeEngine.Type.entries.forEach { item ->
                            val selected = item == type
                            Surface(
                                color = if (selected) H360Ink else H360Paper,
                                contentColor = if (selected) Color.White else H360Ink,
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier.clickable { type = item }
                            ) {
                                Column(Modifier.width(128.dp).padding(12.dp)) {
                                    Text(item.label, fontWeight = FontWeight.Black, fontSize = 11.sp)
                                    Spacer(Modifier.height(3.dp))
                                    Text(item.subtitle, color = LocalContentColor.current.copy(alpha = .55f), fontSize = 8.5.sp, lineHeight = 12.sp)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                    Surface(color = H360Paper, shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text("الأرض", fontSize = 13.sp, fontWeight = FontWeight.Black)
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(city, { city = it }, label = { Text("المدينة") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(width, { width = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("العرض م") }, singleLine = true, modifier = Modifier.weight(1f))
                                OutlinedTextField(depth, { depth = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("الطول م") }, singleLine = true, modifier = Modifier.weight(1f))
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Surface(color = H360Ink, contentColor = Color.White, shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("التكوين", fontSize = 13.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                                Text("$floors دور • $bedrooms غرف", color = H360Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(13.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                (1..3).forEach { n -> DarkChoiceChip("$n دور", floors == n) { floors = n } }
                            }
                            Spacer(Modifier.height(9.dp))
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                (2..7).forEach { n -> DarkChoiceChip("$n غرف", bedrooms == n) { bedrooms = n } }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("مدخل ضيوف مستقل", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Switch(guestIndependent, { guestIndependent = it })
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Surface(color = H360Paper, shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(15.dp)) {
                            Text("الأولويات", fontSize = 13.sp, fontWeight = FontWeight.Black)
                            PriorityLine("الخصوصية", privacy) { privacy = it }
                            PriorityLine("الحركة", circulation) { circulation = it }
                            PriorityLine("الإضاءة", daylight) { daylight = it }
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(notes, { notes = it }, label = { Text("ملاحظة مهمة") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = H360Danger, fontSize = 10.5.sp)
                    }
                    Spacer(Modifier.height(16.dp))
                    H360PrimaryButton(
                        text = "ولّد أفضل 3 اتجاهات",
                        icon = Icons.Rounded.AutoAwesome,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val w = width.toDoubleOrNull()
                        val d = depth.toDoubleOrNull()
                        if (w == null || d == null || w <= 5 || d <= 5) {
                            error = "أدخل أبعاد أرض صحيحة."
                        } else {
                            runCatching {
                                GlobalLayoutOptimizer.generate(
                                    NewBuildSolver.Program(
                                        city = city.trim(),
                                        plotWidthM = w,
                                        plotDepthM = d,
                                        floorCount = floors,
                                        bedrooms = bedrooms,
                                        guestEntranceIndependent = guestIndependent,
                                        privacyPriority = privacy.toInt(),
                                        circulationPriority = circulation.toInt(),
                                        daylightPriority = daylight.toInt(),
                                        notes = notes
                                    )
                                )
                            }.onSuccess {
                                candidates = it
                                error = null
                            }.onFailure { error = it.message ?: "تعذر توليد البدائل" }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                }
            } else {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    Text("لا نعرض عشرات الخيارات؛ فقط أكثر ثلاثة اتجاهات اختلافًا بعد البحث.", color = H360Muted, fontSize = 10.5.sp, lineHeight = 15.sp)
                    Spacer(Modifier.height(14.dp))
                    candidates.take(3).forEachIndexed { index, candidate ->
                        Candidate360Card(index, candidate) { onChoose(candidate.plan, type) }
                        Spacer(Modifier.height(12.dp))
                    }
                    TextButton(onClick = { candidates = emptyList() }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Edit, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("عدّل البرنامج وأعد البحث", fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(18.dp))
                }
            }
        }
    }
}

@Composable
private fun DarkChoiceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) H360Cyan else Color.White.copy(alpha = .07f),
        contentColor = if (selected) H360Ink else Color.White,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(text, fontSize = 9.5.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
    }
}

@Composable
private fun PriorityLine(label: String, value: Float, onValue: (Float) -> Unit) {
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(value.toInt().toString(), color = H360CyanDeep, fontSize = 10.sp, fontWeight = FontWeight.Black)
    }
    Slider(value = value, onValueChange = onValue, valueRange = 50f..100f)
}

@Composable
private fun Candidate360Card(index: Int, candidate: NewBuildSolver.Candidate, onChoose: () -> Unit) {
    Surface(
        color = if (index == 0) H360Ink else H360Paper,
        contentColor = if (index == 0) Color.White else H360Ink,
        shape = RoundedCornerShape(30.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = if (index == 0) H360Cyan else H360Ivory, shape = CircleShape, modifier = Modifier.size(39.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text((index + 1).toString().padStart(2, '0'), color = H360Ink, fontWeight = FontWeight.Black, fontSize = 10.sp)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(candidate.title, fontWeight = FontWeight.Black, fontSize = 14.sp)
                    Text(candidate.rationale, color = LocalContentColor.current.copy(alpha = .52f), fontSize = 9.sp, maxLines = 2, lineHeight = 13.sp)
                }
                Text("${candidate.overall}", color = if (index == 0) H360Cyan else H360CyanDeep, fontSize = 24.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(10.dp))
            CandidatePlanPreview(candidate.plan, Modifier.fillMaxWidth().height(190.dp))
            Spacer(Modifier.height(10.dp))
            Text(candidate.metrics.joinToString(" • "), color = LocalContentColor.current.copy(alpha = .52f), fontSize = 8.5.sp)
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onChoose,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (index == 0) H360Cyan else H360Ink,
                    contentColor = if (index == 0) H360Ink else Color.White
                ),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text(if (index == 0) "ابدأ بهذا الاتجاه" else "اختيار هذا الاتجاه", fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun CandidatePlanPreview(plan: FloorPlan, modifier: Modifier) {
    Surface(color = H360InkSoft, shape = RoundedCornerShape(22.dp), modifier = modifier) {
        Canvas(Modifier.fillMaxSize().padding(12.dp)) {
            fun p(x: Float, y: Float) = Offset(size.width * x / 100f, size.height * y / 100f)
            plan.rooms.forEach { room ->
                drawRect(Color.White.copy(alpha = .05f), topLeft = p(room.x, room.y), size = androidx.compose.ui.geometry.Size(size.width * room.width / 100f, size.height * room.height / 100f))
            }
            plan.walls.forEach { wall ->
                drawLine(H360Cyan.copy(alpha = .9f), p(wall.start.x, wall.start.y), p(wall.end.x, wall.end.y), 2.2f)
            }
        }
    }
}
