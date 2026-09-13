package com.manzili.hai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manzili.hai.engine.ContextualHaiResolver
import com.manzili.hai.engine.PlanVerificationEngine
import com.manzili.hai.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot

private enum class StudioMode { REVIEW, EDIT }
private enum class StudioTool { SELECT, ROOM, WALL, DOOR, WINDOW }
private enum class StudioPanel { NONE, DIMENSIONS, ISSUES, SELECTION }
private data class StudioSelection(val kind: String, val id: String)

@Composable
internal fun Hai360StudioScreen(
    source: Uri?,
    initialPlan: FloorPlan,
    onBack: () -> Unit,
    onConfirm: (FloorPlan) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val resolver = remember(context) { ContextualHaiResolver(context) }

    var plan by remember(initialPlan) { mutableStateOf(PlanVerificationEngine.inspect(initialPlan).plan) }
    var mode by remember { mutableStateOf(StudioMode.REVIEW) }
    var panel by remember { mutableStateOf(StudioPanel.NONE) }
    var tool by remember { mutableStateOf(StudioTool.SELECT) }
    var selected by remember { mutableStateOf<StudioSelection?>(null) }
    var firstWallPoint by remember { mutableStateOf<PlanPoint?>(null) }
    var haiBusy by remember { mutableStateOf(false) }
    var haiMessage by remember { mutableStateOf<String?>(null) }
    var haiProposal by remember { mutableStateOf<PlanProposal?>(null) }
    val undo = remember { mutableStateListOf<FloorPlan>() }

    val report = remember(plan) { PlanVerificationEngine.inspect(plan) }

    fun commit(next: FloorPlan) {
        undo += plan
        if (undo.size > 30) undo.removeAt(0)
        plan = PlanVerificationEngine.inspect(next.copy(revision = maxOf(next.revision, plan.revision + 1))).plan
        haiProposal = null
        haiMessage = null
    }

    fun invokeHai() {
        if (haiBusy) return
        haiBusy = true
        haiMessage = null
        scope.launch {
            runCatching {
                if (mode == StudioMode.REVIEW) {
                    val result = resolver.resolveReview(source, plan)
                    plan = PlanVerificationEngine.inspect(result.plan).plan
                    haiProposal = null
                    result.message
                } else {
                    val proposal = resolver.proposeEdit(plan)
                    haiProposal = proposal
                    proposal.message.lineSequence().firstOrNull().orEmpty().ifBlank { "تمت مراجعة المخطط الحالي." }
                }
            }.onSuccess { haiMessage = it }
                .onFailure { haiMessage = "تعذر تشغيل HAI: ${it.message.orEmpty().take(120)}" }
            haiBusy = false
        }
    }

    Box(Modifier.fillMaxSize().background(H360Ivory)) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.statusBarsPadding())
            StudioHeader(
                mode = mode,
                blocking = report.blocking,
                onBack = onBack,
                onMode = {
                    mode = it
                    panel = StudioPanel.NONE
                    selected = null
                    tool = StudioTool.SELECT
                    firstWallPoint = null
                }
            )

            Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                StudioCanvas(
                    source = source,
                    plan = haiProposal?.updatedPlan ?: plan,
                    previewProposal = haiProposal?.updatedPlan != null,
                    mode = mode,
                    tool = tool,
                    selection = selected,
                    firstWallPoint = firstWallPoint,
                    onTap = { x, y ->
                        if (mode != StudioMode.EDIT) return@StudioCanvas
                        when (tool) {
                            StudioTool.SELECT -> {
                                selected = selectStudioElement(plan, x, y)
                                panel = if (selected != null) StudioPanel.SELECTION else StudioPanel.NONE
                            }
                            StudioTool.ROOM -> {
                                val rw = 18f
                                val rh = 14f
                                val room = Room(
                                    id = "manual-room-${System.currentTimeMillis()}",
                                    name = "غرفة ${plan.rooms.size + 1}",
                                    type = "generic",
                                    x = (x - rw / 2f).coerceIn(0f, 100f - rw),
                                    y = (y - rh / 2f).coerceIn(0f, 100f - rh),
                                    width = rw,
                                    height = rh,
                                    areaM2 = roomArea(plan, rw, rh),
                                    confidence = 100
                                )
                                commit(plan.copy(rooms = plan.rooms + room))
                                selected = StudioSelection("room", room.id)
                                tool = StudioTool.SELECT
                                panel = StudioPanel.SELECTION
                            }
                            StudioTool.WALL -> {
                                val start = firstWallPoint
                                if (start == null) {
                                    firstWallPoint = PlanPoint(x, y)
                                } else {
                                    val wall = Wall(
                                        id = "manual-wall-${System.currentTimeMillis()}",
                                        start = start,
                                        end = PlanPoint(x, y),
                                        thicknessCm = 15.0,
                                        kind = "manual",
                                        confidence = 100
                                    )
                                    commit(plan.copy(walls = plan.walls + wall))
                                    firstWallPoint = null
                                    selected = StudioSelection("wall", wall.id)
                                    tool = StudioTool.SELECT
                                    panel = StudioPanel.SELECTION
                                }
                            }
                            StudioTool.DOOR, StudioTool.WINDOW -> {
                                val nearest = plan.walls.minByOrNull {
                                    pointToSegmentDistance(x, y, it.start.x, it.start.y, it.end.x, it.end.y)
                                }
                                val opening = Opening(
                                    id = "manual-opening-${System.currentTimeMillis()}",
                                    type = if (tool == StudioTool.WINDOW) "window" else "door",
                                    x = x.coerceIn(0f, 100f),
                                    y = y.coerceIn(0f, 100f),
                                    width = if (tool == StudioTool.WINDOW) 5f else 4f,
                                    wallId = nearest?.id,
                                    confidence = 100
                                )
                                commit(plan.copy(openings = plan.openings + opening))
                                selected = StudioSelection("opening", opening.id)
                                tool = StudioTool.SELECT
                                panel = StudioPanel.SELECTION
                            }
                        }
                    }
                )

                Column(
                    Modifier.align(Alignment.TopStart).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    H360Metric("ثقة", "${report.readingConfidence}%", highlighted = report.readingConfidence >= 80)
                    H360Metric("غرف", plan.rooms.size.toString())
                    H360Metric("جدران", plan.walls.size.toString())
                }

                if (haiProposal?.updatedPlan != null) {
                    Surface(
                        color = H360Cyan,
                        contentColor = H360Ink,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
                    ) {
                        Text("معاينة اقتراح HAI", fontWeight = FontWeight.Black, fontSize = 9.5.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
                    }
                }
            }

            report.issues.firstOrNull()?.let { issue ->
                Surface(
                    color = if (issue.level == "error") H360Danger.copy(alpha = .11f) else H360Amber.copy(alpha = .13f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.clickable { panel = StudioPanel.ISSUES }.padding(horizontal = 16.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (issue.level == "error") Icons.Rounded.ErrorOutline else Icons.Rounded.Info,
                            null,
                            tint = if (issue.level == "error") H360Danger else H360CyanDeep,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(issue.title, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("عرض الكل", color = H360CyanDeep, fontSize = 9.5.sp, fontWeight = FontWeight.Black)
                    }
                }
            }

            StudioDock(
                mode = mode,
                tool = tool,
                canUndo = undo.isNotEmpty(),
                haiBusy = haiBusy,
                canConfirm = !report.blocking,
                onTool = {
                    tool = it
                    firstWallPoint = null
                    if (it != StudioTool.SELECT) {
                        selected = null
                        panel = StudioPanel.NONE
                    }
                },
                onUndo = {
                    val previous = undo.removeLastOrNull()
                    if (previous != null) {
                        plan = previous.copy(revision = plan.revision + 1)
                        selected = null
                        panel = StudioPanel.NONE
                    }
                },
                onDimensions = { panel = StudioPanel.DIMENSIONS },
                onIssues = { panel = StudioPanel.ISSUES },
                onHai = ::invokeHai,
                onConfirm = { onConfirm(PlanVerificationEngine.inspect(plan).plan) }
            )
        }

        haiMessage?.let { message ->
            Surface(
                color = H360InkSoft,
                contentColor = Color.White,
                shape = RoundedCornerShape(22.dp),
                shadowElevation = 12.dp,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 70.dp, start = 18.dp, end = 18.dp)
            ) {
                Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = H360Cyan, shape = CircleShape, modifier = Modifier.size(30.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("H", color = H360Ink, fontWeight = FontWeight.Black)
                        }
                    }
                    Spacer(Modifier.width(9.dp))
                    Text(message, fontSize = 10.5.sp, lineHeight = 15.sp, modifier = Modifier.weight(1f), maxLines = 4, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { haiMessage = null }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Rounded.Close, "إغلاق", tint = Color.White, modifier = Modifier.size(17.dp))
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = panel != StudioPanel.NONE,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            StudioInspectorPanel(
                panel = panel,
                plan = plan,
                report = report,
                selection = selected,
                onClose = { panel = StudioPanel.NONE },
                onPlan = { commit(it) },
                onDeleteSelection = {
                    val s = selected ?: return@StudioInspectorPanel
                    val next = when (s.kind) {
                        "room" -> plan.copy(rooms = plan.rooms.filterNot { it.id == s.id })
                        "wall" -> plan.copy(walls = plan.walls.filterNot { it.id == s.id }, openings = plan.openings.filterNot { it.wallId == s.id })
                        "opening" -> plan.copy(openings = plan.openings.filterNot { it.id == s.id })
                        else -> plan
                    }
                    commit(next)
                    selected = null
                    panel = StudioPanel.NONE
                }
            )
        }

        haiProposal?.updatedPlan?.let { proposed ->
            Row(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 18.dp, end = 18.dp, bottom = 154.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { haiProposal = null; haiMessage = null },
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) { Text("رفض", fontWeight = FontWeight.Bold) }
                Button(
                    onClick = { commit(proposed); haiProposal = null; haiMessage = "تم تطبيق اقتراح HAI كنسخة قابلة للتراجع." },
                    colors = ButtonDefaults.buttonColors(containerColor = H360Cyan, contentColor = H360Ink),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) { Text("تطبيق الاقتراح", fontWeight = FontWeight.Black) }
            }
        }
    }
}

@Composable
private fun StudioHeader(
    mode: StudioMode,
    blocking: Boolean,
    onBack: () -> Unit,
    onMode: (StudioMode) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        H360IconButton(Icons.Rounded.ArrowForward, "رجوع", onClick = onBack)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("الاستوديو", color = H360Ink, fontSize = 22.sp, fontWeight = FontWeight.Black)
            Text(if (blocking) "أكمل الناقص ثم اعتمد" else "المخطط جاهز للعمل", color = H360Muted, fontSize = 9.5.sp)
        }
        Surface(color = H360Line.copy(alpha = .65f), shape = RoundedCornerShape(18.dp)) {
            Row(Modifier.padding(3.dp)) {
                ModeChip("راجع", mode == StudioMode.REVIEW) { onMode(StudioMode.REVIEW) }
                ModeChip("حرّر", mode == StudioMode.EDIT) { onMode(StudioMode.EDIT) }
            }
        }
    }
}

@Composable
private fun ModeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) H360Ink else Color.Transparent,
        contentColor = if (selected) Color.White else H360Muted,
        shape = RoundedCornerShape(15.dp),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(text, fontSize = 10.5.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

@Composable
private fun StudioDock(
    mode: StudioMode,
    tool: StudioTool,
    canUndo: Boolean,
    haiBusy: Boolean,
    canConfirm: Boolean,
    onTool: (StudioTool) -> Unit,
    onUndo: () -> Unit,
    onDimensions: () -> Unit,
    onIssues: () -> Unit,
    onHai: () -> Unit,
    onConfirm: () -> Unit
) {
    Surface(color = H360Paper, shadowElevation = 16.dp) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                if (mode == StudioMode.REVIEW) {
                    DockAction(Icons.Rounded.Straighten, "الأبعاد", false, onDimensions)
                    DockAction(Icons.Rounded.Rule, "الملاحظات", false, onIssues)
                    HaiOrb(haiBusy, onHai)
                    DockAction(Icons.Rounded.ZoomInMap, "ملاءمة", false) { }
                    DockAction(Icons.Rounded.Layers, "طبقات", false) { }
                } else {
                    DockAction(Icons.Rounded.TouchApp, "تحديد", tool == StudioTool.SELECT) { onTool(StudioTool.SELECT) }
                    DockAction(Icons.Rounded.MeetingRoom, "غرفة", tool == StudioTool.ROOM) { onTool(StudioTool.ROOM) }
                    HaiOrb(haiBusy, onHai)
                    DockAction(Icons.Rounded.HorizontalRule, "جدار", tool == StudioTool.WALL) { onTool(StudioTool.WALL) }
                    DockAction(Icons.Rounded.DoorFront, "باب", tool == StudioTool.DOOR) { onTool(StudioTool.DOOR) }
                }
            }
            if (mode == StudioMode.EDIT) {
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = onUndo, enabled = canUndo) {
                        Icon(Icons.Rounded.Undo, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("تراجع", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { onTool(StudioTool.WINDOW) }) {
                        Icon(Icons.Rounded.Window, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("نافذة", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(7.dp))
            H360PrimaryButton(
                text = if (canConfirm) "اعتماد المشروع" else "أكمل المراجعة أولًا",
                enabled = canConfirm,
                icon = Icons.Rounded.ArrowBack,
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun DockAction(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 3.dp)) {
        Surface(color = if (selected) H360Ink else Color.Transparent, shape = CircleShape, modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, label, tint = if (selected) Color.White else H360Ink, modifier = Modifier.size(19.dp))
            }
        }
        Text(label, color = if (selected) H360Ink else H360Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HaiOrb(busy: Boolean, onClick: () -> Unit) {
    Surface(
        color = H360Cyan,
        shape = CircleShape,
        shadowElevation = 10.dp,
        modifier = Modifier.size(58.dp).clickable(enabled = !busy, onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(color = H360Ink, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            else Text("HAI", color = H360Ink, fontSize = 13.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun StudioInspectorPanel(
    panel: StudioPanel,
    plan: FloorPlan,
    report: PlanVerificationEngine.Report,
    selection: StudioSelection?,
    onClose: () -> Unit,
    onPlan: (FloorPlan) -> Unit,
    onDeleteSelection: () -> Unit
) {
    var width by remember(plan.widthM) { mutableStateOf(plan.widthM?.let { "%.2f".format(it) } ?: "") }
    var height by remember(plan.heightM) { mutableStateOf(plan.heightM?.let { "%.2f".format(it) } ?: "") }

    Surface(
        color = H360Paper,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        shadowElevation = 24.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.navigationBarsPadding().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(42.dp).height(4.dp).background(H360Line, RoundedCornerShape(50.dp)))
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Rounded.Close, "إغلاق")
                }
            }
            when (panel) {
                StudioPanel.DIMENSIONS -> {
                    Text("المقياس الحقيقي", fontSize = 21.sp, fontWeight = FontWeight.Black)
                    Text("أدخل البعدين الكليين فقط. بقية القياسات تُشتق من النموذج.", color = H360Muted, fontSize = 10.sp)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(width, { width = it }, label = { Text("العرض") }, suffix = { Text("م") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(height, { height = it }, label = { Text("الطول") }, suffix = { Text("م") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    H360PrimaryButton("تثبيت المقياس", Modifier.fillMaxWidth()) {
                        val w = width.replace(',', '.').toDoubleOrNull()
                        val h = height.replace(',', '.').toDoubleOrNull()
                        if (w != null && h != null && w > .5 && h > .5) {
                            onPlan(PlanVerificationEngine.confirmScale(plan, w, h))
                            onClose()
                        }
                    }
                }
                StudioPanel.ISSUES -> {
                    Text("ما يحتاج قرارك", fontSize = 21.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(10.dp))
                    Column(Modifier.heightIn(max = 310.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        if (report.issues.isEmpty()) {
                            Text("لا توجد ملاحظات حاجزة.", color = H360Success, fontWeight = FontWeight.Bold)
                        } else {
                            report.issues.take(12).forEach { issue ->
                                Surface(color = H360Ivory, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                                    Row(Modifier.padding(11.dp), verticalAlignment = Alignment.Top) {
                                        Icon(
                                            if (issue.level == "error") Icons.Rounded.ErrorOutline else Icons.Rounded.Info,
                                            null,
                                            tint = if (issue.level == "error") H360Danger else H360CyanDeep,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Column {
                                            Text(issue.title, fontWeight = FontWeight.Black, fontSize = 11.sp)
                                            if (issue.detail.isNotBlank()) Text(issue.detail, color = H360Muted, fontSize = 9.5.sp, lineHeight = 14.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                StudioPanel.SELECTION -> {
                    val label = selectionLabel(plan, selection)
                    Text(label.first, fontSize = 21.sp, fontWeight = FontWeight.Black)
                    Text(label.second, color = H360Muted, fontSize = 10.sp)
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = onDeleteSelection,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = H360Danger),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("حذف العنصر", fontWeight = FontWeight.Black)
                    }
                }
                StudioPanel.NONE -> Unit
            }
        }
    }
}

@Composable
private fun StudioCanvas(
    source: Uri?,
    plan: FloorPlan,
    previewProposal: Boolean,
    mode: StudioMode,
    tool: StudioTool,
    selection: StudioSelection?,
    firstWallPoint: PlanPoint?,
    onTap: (Float, Float) -> Unit
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, source) {
        value = source?.let { withContext(Dispatchers.IO) { loadStudioBitmap(context, it) } }
    }
    var measured by remember { mutableStateOf(IntSize.Zero) }

    Surface(
        color = H360Ink,
        shape = RoundedCornerShape(30.dp),
        shadowElevation = 7.dp,
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            Modifier.fillMaxSize()
                .onSizeChanged { measured = it }
                .pointerInput(mode, tool, plan) {
                    detectTapGestures { p ->
                        if (measured.width <= 0 || measured.height <= 0) return@detectTapGestures
                        onTap(
                            (p.x / measured.width * 100f).coerceIn(0f, 100f),
                            (p.y / measured.height * 100f).coerceIn(0f, 100f)
                        )
                    }
                }
        ) {
            BlueprintGrid(Modifier.matchParentSize(), dark = true, step = 30f)
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "المخطط الأصلي",
                    contentScale = ContentScale.Fit,
                    alpha = if (mode == StudioMode.EDIT) .55f else .70f,
                    modifier = Modifier.fillMaxSize().padding(8.dp)
                )
            }
            Canvas(Modifier.fillMaxSize()) {
                fun p(x: Float, y: Float) = Offset(size.width * x / 100f, size.height * y / 100f)
                val geometry = if (previewProposal) H360Cyan else Color.White

                plan.rooms.forEach { room ->
                    val isSelected = selection?.kind == "room" && selection.id == room.id
                    drawRect(
                        if (isSelected) H360Cyan.copy(alpha = .30f) else geometry.copy(alpha = .08f),
                        topLeft = p(room.x, room.y),
                        size = Size(size.width * room.width / 100f, size.height * room.height / 100f)
                    )
                    drawRect(
                        if (isSelected) H360Cyan else geometry.copy(alpha = .28f),
                        topLeft = p(room.x, room.y),
                        size = Size(size.width * room.width / 100f, size.height * room.height / 100f),
                        style = Stroke(width = if (isSelected) 2.8f else 1.2f)
                    )
                }

                plan.walls.forEach { wall ->
                    val isSelected = selection?.kind == "wall" && selection.id == wall.id
                    drawLine(
                        color = if (isSelected) H360Cyan else geometry.copy(alpha = .92f),
                        start = p(wall.start.x, wall.start.y),
                        end = p(wall.end.x, wall.end.y),
                        strokeWidth = if (isSelected) 6f else 3.2f
                    )
                }

                plan.openings.forEach { opening ->
                    val isSelected = selection?.kind == "opening" && selection.id == opening.id
                    drawCircle(
                        color = if (isSelected) H360Cyan else if (opening.type.contains("window", true)) Color(0xFF8ABFFF) else H360Amber,
                        radius = if (isSelected) 7f else 4.5f,
                        center = p(opening.x, opening.y)
                    )
                }

                firstWallPoint?.let {
                    drawCircle(H360Cyan, radius = 8f, center = p(it.x, it.y), style = Stroke(width = 3f))
                }
            }

            if (bitmap == null && plan.rooms.isEmpty() && plan.walls.isEmpty()) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Architecture, null, tint = Color.White.copy(alpha = .32f), modifier = Modifier.size(42.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("لا توجد هندسة مرئية بعد", color = Color.White.copy(alpha = .55f), fontWeight = FontWeight.Bold)
                }
            }

            if (mode == StudioMode.EDIT && tool != StudioTool.SELECT) {
                Surface(
                    color = H360Cyan,
                    contentColor = H360Ink,
                    shape = RoundedCornerShape(50.dp),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
                ) {
                    Text(
                        when (tool) {
                            StudioTool.ROOM -> "اضغط مكان الغرفة"
                            StudioTool.WALL -> if (firstWallPoint == null) "حدد بداية الجدار" else "حدد نهاية الجدار"
                            StudioTool.DOOR -> "اضغط مكان الباب"
                            StudioTool.WINDOW -> "اضغط مكان النافذة"
                            else -> ""
                        },
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

private fun selectStudioElement(plan: FloorPlan, x: Float, y: Float): StudioSelection? {
    val opening = plan.openings.minByOrNull { hypot((it.x - x).toDouble(), (it.y - y).toDouble()) }
    if (opening != null && hypot((opening.x - x).toDouble(), (opening.y - y).toDouble()) <= 5.5) {
        return StudioSelection("opening", opening.id)
    }
    val wall = plan.walls.minByOrNull { pointToSegmentDistance(x, y, it.start.x, it.start.y, it.end.x, it.end.y) }
    if (wall != null && pointToSegmentDistance(x, y, wall.start.x, wall.start.y, wall.end.x, wall.end.y) <= 3.2) {
        return StudioSelection("wall", wall.id)
    }
    val room = plan.rooms.lastOrNull { x >= it.x && x <= it.x + it.width && y >= it.y && y <= it.y + it.height }
    return room?.let { StudioSelection("room", it.id) }
}

private fun selectionLabel(plan: FloorPlan, selection: StudioSelection?): Pair<String, String> {
    selection ?: return "لا يوجد تحديد" to "اضغط عنصرًا في المخطط."
    return when (selection.kind) {
        "room" -> plan.rooms.firstOrNull { it.id == selection.id }?.let { it.name to "غرفة • ثقة ${it.confidence}%${if (it.locked) " • مقفلة" else ""}" }
        "wall" -> plan.walls.firstOrNull { it.id == selection.id }?.let { "جدار" to "${it.kind} • ثقة ${it.confidence}%${if (it.locked) " • مقفل" else ""}" }
        "opening" -> plan.openings.firstOrNull { it.id == selection.id }?.let { (if (it.type.contains("window", true)) "نافذة" else "باب") to "ثقة ${it.confidence}%${if (it.locked) " • مقفل" else ""}" }
        else -> null
    } ?: ("عنصر" to selection.id)
}

private fun roomArea(plan: FloorPlan, widthPct: Float, heightPct: Float): Double {
    val w = plan.widthM ?: return 0.0
    val h = plan.heightM ?: return 0.0
    return (w * widthPct / 100.0) * (h * heightPct / 100.0)
}

private fun pointToSegmentDistance(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Double {
    val dx = (x2 - x1).toDouble()
    val dy = (y2 - y1).toDouble()
    if (dx == 0.0 && dy == 0.0) return hypot((px - x1).toDouble(), (py - y1).toDouble())
    val t = (((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
    return hypot(px - (x1 + t * dx), py - (y1 + t * dy))
}

private fun loadStudioBitmap(context: Context, source: Uri): Bitmap? {
    val type = context.contentResolver.getType(source).orEmpty()
    return runCatching {
        if (type == "application/pdf") {
            val pfd = context.contentResolver.openFileDescriptor(source, "r") ?: return@runCatching null
            PdfRenderer(pfd).use { renderer ->
                if (renderer.pageCount <= 0) return@use null
                renderer.openPage(0).use { page ->
                    val target = 1600f
                    val scale = target / page.width.coerceAtLeast(1)
                    val w = target.toInt()
                    val h = (page.height * scale).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        } else {
            context.contentResolver.openInputStream(source).use { BitmapFactory.decodeStream(it) }
        }
    }.getOrNull()
}
