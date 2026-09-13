package com.manzili.hai

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.manzili.hai.ai.HaiArchitectClient
import com.manzili.hai.engine.ArchitecturalEngine
import com.manzili.hai.engine.GeometrySolver
import com.manzili.hai.engine.PlanVerificationEngine
import com.manzili.hai.engine.ProjectMemoryEngine
import com.manzili.hai.model.*
import kotlinx.coroutines.launch
import kotlin.math.hypot

private val PBg = Color(0xFFF8F6F2)
private val PCard = Color(0xFFFFFEFC)
private val PInk = Color(0xFF181A18)
private val PViolet = Color(0xFF6353D9)
private val POrange = Color(0xFFE28B5A)
private val PGreen = Color(0xFF4C8A78)
private val PLine = Color(0xFF323431)

private enum class ProcessingTab { PLAN, EDIT, HAI }
private data class TouchSelection(val kind: String, val id: String)

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
                        Text("المخطط • تعديل باللمس • رأي HAI", fontSize = 10.sp, color = Color.Gray)
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
                        ProcessNav(Modifier.weight(1f), Icons.Rounded.TouchApp, "تعديل", tab == ProcessingTab.EDIT) { tab = ProcessingTab.EDIT }
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
                ProcessingTab.EDIT -> DirectTouchPlanEditor(working) { working = it }
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
private fun DirectTouchPlanEditor(plan: FloorPlan, onPlanChange: (FloorPlan) -> Unit) {
    var selection by remember(plan.revision) { mutableStateOf<TouchSelection?>(null) }
    var liveCandidate by remember { mutableStateOf<GeometrySolver.Candidate?>(null) }
    var pending by remember { mutableStateOf<PlanProposal?>(null) }
    var note by remember { mutableStateOf("المس أي غرفة أو جدار أو فتحة. اسحب للتحريك.") }
    val renderPlan = liveCandidate?.plan ?: pending?.updatedPlan ?: plan
    val validation = pending?.let { ArchitecturalEngine.validate(plan, it) }

    fun proposeCandidate(candidate: GeometrySolver.Candidate?) {
        liveCandidate = null
        if (candidate == null) {
            note = "لم أجد حركة هندسية آمنة لهذا العنصر."
            return
        }
        val memory = ProjectMemoryEngine.review(plan, candidate.plan)
        if (memory.hasObjection) {
            note = memory.objections.firstOrNull() ?: "قاعدة المشروع تمنع هذا التعديل."
            return
        }
        pending = GeometrySolver.toProposal(plan, candidate)
        note = candidate.reason
    }

    fun bestAction(kind: String, id: String, action: String) {
        val candidate = GeometrySolver.actionCandidates(plan, kind, id, action)
            .firstOrNull { !ProjectMemoryEngine.review(plan, it.plan).hasObjection }
        proposeCandidate(candidate)
    }

    fun toggleLock(sel: TouchSelection) {
        val next = when (sel.kind) {
            "room" -> plan.copy(rooms = plan.rooms.map { if (it.id == sel.id) it.copy(locked = !it.locked) else it })
            "wall" -> plan.copy(walls = plan.walls.map { if (it.id == sel.id) it.copy(locked = !it.locked) else it })
            "opening" -> plan.copy(openings = plan.openings.map { if (it.id == sel.id) it.copy(locked = !it.locked) else it })
            else -> plan
        }
        onPlanChange(next.copy(revision = plan.revision + 1))
    }

    fun proposeOpening(wallId: String, type: String) {
        val wall = plan.walls.firstOrNull { it.id == wallId } ?: return
        if (wall.locked) {
            note = "الجدار مقفل. افتحه أولًا لإضافة فتحة."
            return
        }
        val midX = (wall.start.x + wall.end.x) / 2f
        val midY = (wall.start.y + wall.end.y) / 2f
        val prefix = if (type == "door") "door" else "window"
        val opening = Opening(
            id = "${prefix}_${System.currentTimeMillis()}",
            type = type,
            x = midX,
            y = midY,
            width = if (type == "door") 4.2f else 5.4f,
            wallId = wall.id,
            connectsRoomIds = wall.adjacentRoomIds,
            confidence = 100
        )
        val next = plan.copy(openings = plan.openings + opening, revision = plan.revision + 1)
        val check = GeometrySolver.verify(plan, next)
        if (!check.feasible) {
            note = check.errors.firstOrNull() ?: "تعذرت إضافة الفتحة في هذا الجدار."
            return
        }
        pending = PlanProposal(
            message = if (type == "door") "معاينة باب جديد في منتصف الجدار المحدد." else "معاينة نافذة جديدة في منتصف الجدار المحدد.",
            updatedPlan = next,
            requiresConfirmation = true,
            confidence = 96
        )
        note = pending?.message.orEmpty()
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("تعديل مباشر", fontSize = 22.sp, fontWeight = FontWeight.Black, color = PInk)
                Text("المس عنصرًا ثم اسحبه أو استخدم أدواته", fontSize = 10.sp, color = Color.Gray)
            }
            Surface(color = PViolet.copy(alpha = .10f), shape = RoundedCornerShape(14.dp)) {
                Text("${plan.rooms.size} غرفة", modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp), color = PViolet, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(8.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            Box(Modifier.fillMaxSize().padding(8.dp)) {
                TouchPlanCanvas(
                    plan = renderPlan,
                    selected = selection,
                    enabled = pending == null,
                    onSelect = {
                        selection = it
                        note = it?.let { s -> selectionLabel(plan, s) } ?: "اختر عنصرًا من المخطط."
                    },
                    onPreview = { liveCandidate = it },
                    onCommit = { proposeCandidate(it) }
                )
                if (pending != null || liveCandidate != null) {
                    Surface(
                        color = PInk.copy(alpha = .86f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                    ) {
                        Text("معاينة فقط — لم تُعتمد", color = Color.White, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        selection?.let { sel ->
            SelectionTools(
                plan = plan,
                selection = sel,
                onMove = { bestAction(sel.kind, sel.id, "MOVE") },
                onExpand = { bestAction(sel.kind, sel.id, "EXPAND") },
                onShrink = { bestAction(sel.kind, sel.id, "SHRINK") },
                onDoor = { proposeOpening(sel.id, "door") },
                onWindow = { proposeOpening(sel.id, "window") },
                onLock = { toggleLock(sel) }
            )
            Spacer(Modifier.height(7.dp))
        }

        Surface(color = PCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 11.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.TipsAndUpdates, null, tint = POrange, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
                Text(note, fontSize = 9.5.sp, lineHeight = 13.sp, color = Color.DarkGray, modifier = Modifier.weight(1f))
            }
        }

        pending?.let { proposal ->
            Spacer(Modifier.height(7.dp))
            Card(colors = CardDefaults.cardColors(containerColor = PInk), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(11.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Preview, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("معاينة التعديل", color = Color.White, fontWeight = FontWeight.Black, fontSize = 11.5.sp)
                        Spacer(Modifier.weight(1f))
                        Text("${proposal.confidence}%", color = Color.White.copy(alpha = .7f), fontSize = 9.5.sp)
                    }
                    validation?.errors?.take(1)?.forEach { Text("• $it", color = Color(0xFFFF9D86), fontSize = 9.sp, modifier = Modifier.padding(top = 4.dp)) }
                    validation?.warnings?.take(1)?.forEach { Text("• $it", color = Color(0xFFFFC58F), fontSize = 9.sp, modifier = Modifier.padding(top = 4.dp)) }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { pending = null; liveCandidate = null },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) { Text("إلغاء") }
                        Button(
                            onClick = {
                                proposal.updatedPlan?.let { onPlanChange(it.copy(revision = plan.revision + 1)) }
                                pending = null
                                liveCandidate = null
                            },
                            enabled = validation?.valid != false,
                            modifier = Modifier.weight(1f)
                        ) { Text("تطبيق", fontWeight = FontWeight.Black) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionTools(
    plan: FloorPlan,
    selection: TouchSelection,
    onMove: () -> Unit,
    onExpand: () -> Unit,
    onShrink: () -> Unit,
    onDoor: () -> Unit,
    onWindow: () -> Unit,
    onLock: () -> Unit
) {
    val room = plan.rooms.firstOrNull { selection.kind == "room" && it.id == selection.id }
    val wall = plan.walls.firstOrNull { selection.kind == "wall" && it.id == selection.id }
    val opening = plan.openings.firstOrNull { selection.kind == "opening" && it.id == selection.id }
    val locked = room?.locked ?: wall?.locked ?: opening?.locked ?: false
    val title = room?.name ?: wall?.let { "الجدار المحدد" } ?: opening?.let { if (it.type.lowercase().contains("window")) "النافذة المحددة" else "الباب المحدد" } ?: "العنصر"

    Surface(color = PCard, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).background(PViolet.copy(alpha = .10f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(if (locked) Icons.Rounded.Lock else Icons.Rounded.TouchApp, null, tint = PViolet, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Black, fontSize = 11.5.sp)
                    Text(if (locked) "مقفل — افتحه للتعديل" else "اسحب مباشرة أو اختر أداة", color = Color.Gray, fontSize = 9.sp)
                }
                TextButton(onClick = onLock) { Text(if (locked) "فتح" else "قفل", fontSize = 9.5.sp) }
            }
            if (!locked) {
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToolButton(Modifier.weight(1f), Icons.Rounded.OpenWith, "تحريك", onMove)
                    if (room != null) {
                        ToolButton(Modifier.weight(1f), Icons.Rounded.ZoomOutMap, "تكبير", onExpand)
                        ToolButton(Modifier.weight(1f), Icons.Rounded.ZoomInMap, "تصغير", onShrink)
                    } else if (wall != null) {
                        ToolButton(Modifier.weight(1f), Icons.Rounded.DoorFront, "باب", onDoor)
                        ToolButton(Modifier.weight(1f), Icons.Rounded.Window, "نافذة", onWindow)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolButton(modifier: Modifier, icon: ImageVector, label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = modifier.height(44.dp), shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 5.dp)) {
        Icon(icon, null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TouchPlanCanvas(
    plan: FloorPlan,
    selected: TouchSelection?,
    enabled: Boolean,
    onSelect: (TouchSelection?) -> Unit,
    onPreview: (GeometrySolver.Candidate?) -> Unit,
    onCommit: (GeometrySolver.Candidate?) -> Unit
) {
    var lastCandidate by remember(plan, selected) { mutableStateOf<GeometrySolver.Candidate?>(null) }

    Canvas(
        Modifier.fillMaxSize()
            .pointerInput(plan, enabled) {
                detectTapGestures { tap ->
                    if (!enabled) return@detectTapGestures
                    val px = (tap.x / size.width.coerceAtLeast(1) * 100f).coerceIn(0f, 100f)
                    val py = (tap.y / size.height.coerceAtLeast(1) * 100f).coerceIn(0f, 100f)
                    val opening = plan.openings.minByOrNull { hypot((it.x - px).toDouble(), (it.y - py).toDouble()) }
                    if (opening != null && hypot((opening.x - px).toDouble(), (opening.y - py).toDouble()) <= 5.0) {
                        onSelect(TouchSelection("opening", opening.id)); return@detectTapGestures
                    }
                    val wall = plan.walls.minByOrNull { pointToSegment(px, py, it.start.x, it.start.y, it.end.x, it.end.y) }
                    if (wall != null && pointToSegment(px, py, wall.start.x, wall.start.y, wall.end.x, wall.end.y) <= 2.7) {
                        onSelect(TouchSelection("wall", wall.id)); return@detectTapGestures
                    }
                    val room = plan.rooms.lastOrNull { px >= it.x && px <= it.x + it.width && py >= it.y && py <= it.y + it.height }
                    onSelect(room?.let { TouchSelection("room", it.id) })
                }
            }
            .pointerInput(plan, selected, enabled) {
                if (!enabled || selected == null) return@pointerInput
                var dxTotal = 0f
                var dyTotal = 0f
                detectDragGestures(
                    onDragStart = { dxTotal = 0f; dyTotal = 0f; lastCandidate = null },
                    onDragCancel = { lastCandidate = null; onPreview(null) },
                    onDragEnd = {
                        onPreview(null)
                        onCommit(lastCandidate)
                        lastCandidate = null
                    },
                    onDrag = { _, amount ->
                        dxTotal += amount.x
                        dyTotal += amount.y
                        val dxPct = dxTotal / size.width.coerceAtLeast(1) * 100f
                        val dyPct = dyTotal / size.height.coerceAtLeast(1) * 100f
                        val candidate = GeometrySolver.drag(plan, selected.kind, selected.id, dxPct, dyPct)
                        lastCandidate = candidate
                        onPreview(candidate)
                    }
                )
            }
    ) {
        fun p(x: Float, y: Float) = Offset(size.width * x / 100f, size.height * y / 100f)

        plan.rooms.forEach { room ->
            val isSelected = selected?.kind == "room" && selected.id == room.id
            val left = size.width * room.x / 100f
            val top = size.height * room.y / 100f
            val width = size.width * room.width / 100f
            val height = size.height * room.height / 100f
            drawRect(if (isSelected) PViolet.copy(alpha = .20f) else PViolet.copy(alpha = .07f), topLeft = Offset(left, top), size = Size(width, height))
            drawRect(if (isSelected) PViolet else Color(0xFFB8B3C9), topLeft = Offset(left, top), size = Size(width, height), style = Stroke(if (isSelected) 3.dp.toPx() else 1.dp.toPx()))
        }

        plan.walls.forEach { wall ->
            val isSelected = selected?.kind == "wall" && selected.id == wall.id
            drawLine(if (isSelected) POrange else PLine, p(wall.start.x, wall.start.y), p(wall.end.x, wall.end.y), strokeWidth = (if (isSelected) 4.dp else 2.dp).toPx())
        }

        plan.openings.forEach { opening ->
            val isSelected = selected?.kind == "opening" && selected.id == opening.id
            drawCircle(if (isSelected) POrange else PGreen, radius = (if (isSelected) 6.dp else 4.dp).toPx(), center = p(opening.x, opening.y))
            if (isSelected) drawCircle(Color.White, radius = 2.dp.toPx(), center = p(opening.x, opening.y))
        }
    }
}

private fun selectionLabel(plan: FloorPlan, selection: TouchSelection): String = when (selection.kind) {
    "room" -> plan.rooms.firstOrNull { it.id == selection.id }?.let { "${it.name} • ${"%.1f".format(it.areaM2)}م² • ثقة ${it.confidence}%" } ?: "غرفة"
    "wall" -> plan.walls.firstOrNull { it.id == selection.id }?.let { "جدار • ${it.kind} • ثقة ${it.confidence}%" } ?: "جدار"
    "opening" -> plan.openings.firstOrNull { it.id == selection.id }?.let { "${if (it.type.lowercase().contains("window")) "نافذة" else "باب"} • ثقة ${it.confidence}%" } ?: "فتحة"
    else -> "عنصر"
}

private fun pointToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Double {
    val dx = (bx - ax).toDouble()
    val dy = (by - ay).toDouble()
    if (dx == 0.0 && dy == 0.0) return hypot((px - ax).toDouble(), (py - ay).toDouble())
    val t = (((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
    val x = ax + t * dx
    val y = ay + t * dy
    return hypot(px - x, py - y)
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
