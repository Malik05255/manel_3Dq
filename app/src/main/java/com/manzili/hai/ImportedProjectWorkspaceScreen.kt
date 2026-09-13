package com.manzili.hai

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.manzili.hai.ai.HaiArchitectClient
import com.manzili.hai.engine.ArchitecturalEngine
import com.manzili.hai.engine.PlanVerificationEngine
import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.PlanProposal
import kotlinx.coroutines.launch

private val PBg = Color(0xFFF8F6F2)
private val PCard = Color(0xFFFFFEFC)
private val PInk = Color(0xFF181A18)
private val PViolet = Color(0xFF6353D9)
private val POrange = Color(0xFFE28B5A)
private val PGreen = Color(0xFF4C8A78)

private enum class ProcessingTab { PLAN, EDIT, HAI }

@Composable
fun ImportedProjectWorkspaceScreen(
    nav: NavHostController,
    source: Uri?,
    plan: FloorPlan?,
    onConfirm: (FloorPlan) -> Unit
) {
    if (plan == null) {
        Box(Modifier.fillMaxSize().background(PBg), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = PViolet)
        }
        return
    }

    var working by remember(plan) { mutableStateOf(plan) }
    var tab by remember { mutableStateOf(ProcessingTab.PLAN) }
    val report = remember(working) { PlanVerificationEngine.inspect(working) }

    Scaffold(
        containerColor = PBg,
        topBar = {
            Surface(color = PBg) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Rounded.ArrowForward, "رجوع") }
                    Column(Modifier.weight(1f)) {
                        Text("معالجة المشروع", fontSize = 19.sp, fontWeight = FontWeight.Black, color = PInk)
                        Text("راجع • عدّل • اسأل HAI", fontSize = 10.sp, color = Color.Gray)
                    }
                    Surface(
                        color = if (report.blocking) POrange.copy(alpha = .12f) else PGreen.copy(alpha = .12f),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text(
                            if (report.blocking) "راجع" else "جاهز",
                            color = if (report.blocking) POrange else PGreen,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                        )
                    }
                }
            }
        },
        bottomBar = {
            Column {
                Surface(color = PCard, shadowElevation = 8.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ProcessNav(Modifier.weight(1f), Icons.Rounded.Map, "المخطط", tab == ProcessingTab.PLAN) { tab = ProcessingTab.PLAN }
                        ProcessNav(Modifier.weight(1f), Icons.Rounded.Edit, "تعديل", tab == ProcessingTab.EDIT) { tab = ProcessingTab.EDIT }
                        ProcessNav(Modifier.weight(1f), Icons.Rounded.AutoAwesome, "HAI", tab == ProcessingTab.HAI) { tab = ProcessingTab.HAI }
                    }
                }
                Surface(color = PBg) {
                    Button(
                        enabled = !report.blocking,
                        onClick = { onConfirm(report.plan) },
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PViolet),
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp).height(52.dp)
                    ) {
                        Icon(Icons.Rounded.Verified, null)
                        Spacer(Modifier.width(6.dp))
                        Text("اعتماد المشروع", fontWeight = FontWeight.Black)
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                ProcessingTab.PLAN -> PlanVerificationScreen(nav, source, working) {
                    working = it
                    tab = ProcessingTab.EDIT
                }
                ProcessingTab.EDIT -> EnhancedEditor(nav, working) { working = it }
                ProcessingTab.HAI -> ProcessingHaiTab(working) { working = it }
            }
        }
    }
}

@Composable
private fun ProcessNav(modifier: Modifier, icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = modifier.height(56.dp).clickable(onClick = onClick),
        color = if (selected) PViolet.copy(alpha = .10f) else Color.Transparent,
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, null, tint = if (selected) PViolet else Color.Gray, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(2.dp))
            Text(label, fontSize = 9.5.sp, fontWeight = if (selected) FontWeight.Black else FontWeight.Medium, color = if (selected) PViolet else Color.Gray)
        }
    }
}

@Composable
private fun ProcessingHaiTab(plan: FloorPlan, onPlanChange: (FloorPlan) -> Unit) {
    val context = LocalContext.current
    val client = remember { HaiArchitectClient(context) }
    val scope = rememberCoroutineScope()
    val score = remember(plan) { ArchitecturalEngine.score(plan) }
    val suggestions = remember(plan) { ArchitecturalEngine.proactiveSuggestions(plan) }
    var message by remember(plan) { mutableStateOf(ArchitecturalEngine.initialArchitectMessage(plan)) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PlanProposal?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun ask(text: String) {
        if (text.isBlank() || busy) return
        busy = true
        error = null
        scope.launch {
            runCatching { client.proposeChange(plan, text) }
                .onSuccess {
                    message = it.message
                    pending = it.takeIf { proposal -> proposal.updatedPlan != null }
                }
                .onFailure { error = it.message ?: "تعذر تشغيل HAI" }
            busy = false
        }
    }

    val validation = pending?.let { ArchitecturalEngine.validate(plan, it) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).background(PViolet, RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                Text("H", color = Color.White, fontWeight = FontWeight.Black, fontSize = 21.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text("رأي HAI", fontSize = 23.sp, fontWeight = FontWeight.Black)
                Text("مراجعة واقتراحات للمخطط الحالي", color = Color.Gray, fontSize = 10.sp)
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScoreTile(Modifier.weight(1f), "الكفاءة", score.efficiency)
            ScoreTile(Modifier.weight(1f), "الخصوصية", score.privacy)
            ScoreTile(Modifier.weight(1f), "الدقة", score.readingConfidence)
        }

        Spacer(Modifier.height(12.dp))
        Surface(color = PCard, shape = RoundedCornerShape(22.dp)) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = PViolet, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(9.dp))
                Text(message.ifBlank { "المخطط جاهز للمراجعة." }, fontSize = 11.sp, lineHeight = 17.sp)
            }
        }

        Spacer(Modifier.height(14.dp))
        Text("إضافة سريعة بواسطة HAI", fontWeight = FontWeight.Black, fontSize = 13.sp)
        Spacer(Modifier.height(7.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            QuickHaiAction(Modifier.weight(1f), Icons.Rounded.MeetingRoom, "غرفة") { ask("أضف غرفة مناسبة للمخطط في أفضل موقع، ولا تغيّر العناصر المقفلة") }
            QuickHaiAction(Modifier.weight(1f), Icons.Rounded.DoorFront, "باب") { ask("أضف بابًا في الموضع الأنسب للحركة والخصوصية") }
            QuickHaiAction(Modifier.weight(1f), Icons.Rounded.Window, "نافذة") { ask("أضف نافذة في الموضع الأنسب للإضاءة والخصوصية") }
        }

        if (suggestions.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("اقتراحات HAI", fontWeight = FontWeight.Black, fontSize = 13.sp)
            Spacer(Modifier.height(7.dp))
            suggestions.take(5).forEach { suggestion ->
                Card(colors = CardDefaults.cardColors(containerColor = PCard), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(34.dp).background(PViolet.copy(alpha = .10f), CircleShape), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Lightbulb, null, tint = PViolet, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(suggestion.title, fontWeight = FontWeight.Black, fontSize = 11.5.sp, modifier = Modifier.weight(1f))
                        }
                        Text(suggestion.reason, color = Color.DarkGray, fontSize = 9.5.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 6.dp))
                        TextButton(onClick = { ask("نفّذ أفضل حل لهذا الاقتراح مع الحفاظ على قيود المشروع: ${suggestion.title}. ${suggestion.reason}") }, enabled = !busy, modifier = Modifier.align(Alignment.End)) {
                            Icon(Icons.Rounded.Preview, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("اعرض الحل")
                        }
                    }
                }
            }
        }

        pending?.let { proposal ->
            Spacer(Modifier.height(6.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF242724)), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(13.dp)) {
                    Text("معاينة اقتراح HAI • ${proposal.confidence}%", color = Color.White, fontWeight = FontWeight.Black, fontSize = 12.sp)
                    validation?.warnings?.take(2)?.forEach { Text("• $it", color = Color(0xFFFFC58F), fontSize = 9.sp, modifier = Modifier.padding(top = 4.dp)) }
                    Spacer(Modifier.height(9.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pending = null }, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) { Text("رفض") }
                        Button(onClick = {
                            proposal.updatedPlan?.let(onPlanChange)
                            pending = null
                        }, enabled = validation?.valid == true, modifier = Modifier.weight(1f)) { Text("تطبيق", fontWeight = FontWeight.Black) }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("مثال: كبّر المجلس وحافظ على الخصوصية") },
            minLines = 2,
            maxLines = 4,
            shape = RoundedCornerShape(20.dp),
            trailingIcon = {
                IconButton(onClick = {
                    val q = input.trim()
                    input = ""
                    ask(q)
                }, enabled = input.isNotBlank() && !busy) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.ArrowUpward, "إرسال")
                }
            }
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp)) }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun ScoreTile(modifier: Modifier, label: String, value: Int) {
    Surface(modifier = modifier, color = PCard, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$value", fontSize = 17.sp, fontWeight = FontWeight.Black, color = if (value >= 75) PGreen else if (value >= 55) PViolet else POrange)
            Text(label, fontSize = 9.sp, color = Color.Gray)
        }
    }
}

@Composable
private fun QuickHaiAction(modifier: Modifier, icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(modifier = modifier.height(78.dp).clickable(onClick = onClick), color = PCard, shape = RoundedCornerShape(18.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, null, tint = PViolet, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(5.dp))
            Text(label, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
        }
    }
}
