package com.manzili.hai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.manzili.hai.engine.ArchitecturalEngine
import com.manzili.hai.engine.ContextualHaiResolver
import com.manzili.hai.engine.PlanVerificationEngine
import com.manzili.hai.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot

private val PXBg = Color(0xFFF7F5F1)
private val PXCard = Color(0xFFFFFEFC)
private val PXInk = Color(0xFF171816)
private val PXMuted = Color(0xFF817C75)
private val PXViolet = Color(0xFF5E4BDD)
private val PXOrange = Color(0xFFE18A58)
private val PXGreen = Color(0xFF4C8A78)
private val PXLine = Color(0xFF34363A)

private enum class ProcessingStage { REVIEW, EDIT }
private enum class EditorTool { SELECT, ADD_ROOM, ADD_WALL, ADD_DOOR, ADD_WINDOW }
private data class EditSelection(val kind: String, val id: String)

@Composable
fun ImportedProjectWorkspaceScreen(
    nav: NavHostController,
    source: Uri?,
    plan: FloorPlan?,
    onConfirm: (FloorPlan) -> Unit
) {
    if (plan == null) {
        Box(Modifier.fillMaxSize().background(PXBg), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = PXViolet)
        }
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val resolver = remember(context) { ContextualHaiResolver(context) }

    var working by remember(plan) { mutableStateOf(PlanVerificationEngine.inspect(plan).plan) }
    var stage by remember { mutableStateOf(ProcessingStage.REVIEW) }
    var haiBusy by remember { mutableStateOf(false) }
    var haiMessage by remember { mutableStateOf<String?>(null) }
    var haiProposal by remember { mutableStateOf<PlanProposal?>(null) }

    val report = remember(working) { PlanVerificationEngine.inspect(working) }

    fun invokeHai() {
        if (haiBusy) return
        haiBusy = true
        haiMessage = if (stage == ProcessingStage.REVIEW) {
            "HAI يقرأ المشكلة الحالية ويحل ما يستطيع إثباته من المخطط..."
        } else {
            "HAI يراجع التصميم الحالي ويجهز رأيًا أو تعديلًا مقترحًا..."
        }

        scope.launch {
            try {
                if (stage == ProcessingStage.REVIEW) {
                    val result = resolver.resolveReview(source, working)
                    working = result.plan
                    haiProposal = null
                    haiMessage = result.message
                } else {
                    var base = working
                    val baseReport = PlanVerificationEngine.inspect(base)
                    if (baseReport.blocking || (base.rooms.isEmpty() && base.walls.size < 3)) {
                        val repaired = resolver.resolveReview(source, base)
                        base = repaired.plan
                        working = base
                        if (base.rooms.isEmpty() && base.walls.size < 3) {
                            haiProposal = null
                            haiMessage = repaired.message
                            return@launch
                        }
                    }

                    val proposal = resolver.proposeEdit(base)
                    haiProposal = proposal
                    haiMessage = proposal.message.lineSequence().firstOrNull().orEmpty()
                        .ifBlank { "راجعت المخطط ولم أجد اعتراضًا قويًا." }
                }
            } catch (error: Throwable) {
                haiMessage = "تعذر استدعاء HAI: ${error.message.orEmpty().take(140)}"
            } finally {
                haiBusy = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(PXBg)) {
        Scaffold(
            containerColor = PXBg,
            topBar = {
                ProcessingTopBar(
                    stage = stage,
                    blocking = report.blocking,
                    onBack = { nav.popBackStack() }
                )
            },
            bottomBar = {
                ProcessingBottomBar(
                    stage = stage,
                    canConfirm = !report.blocking,
                    onStage = {
                        stage = it
                        haiMessage = null
                        if (it == ProcessingStage.REVIEW) haiProposal = null
                    },
                    onConfirm = { onConfirm(PlanVerificationEngine.inspect(working).plan) }
                )
            }
        ) { padding ->
            when (stage) {
                ProcessingStage.REVIEW -> ReviewStage(
                    source = source,
                    plan = working,
                    report = report,
                    modifier = Modifier.fillMaxSize().padding(padding),
                    onPlanChange = {
                        working = PlanVerificationEngine.inspect(it).plan
                        haiMessage = null
                    },
                    onOpenEditor = { stage = ProcessingStage.EDIT }
                )

                ProcessingStage.EDIT -> EditorStage(
                    source = source,
                    plan = working,
                    haiProposal = haiProposal,
                    modifier = Modifier.fillMaxSize().padding(padding),
                    onPlanChange = {
                        working = it
                        haiProposal = null
                        haiMessage = null
                    },
                    onRejectHai = {
                        haiProposal = null
                        haiMessage = null
                    }
                )
            }
        }

        haiMessage?.let { text ->
            Surface(
                color = PXInk.copy(alpha = .94f),
                shape = RoundedCornerShape(18.dp),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 66.dp, start = 22.dp, end = 22.dp)
            ) {
                Row(
                    Modifier.padding(start = 12.dp, end = 6.dp, top = 9.dp, bottom = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(text, color = Color.White, fontSize = 10.5.sp, lineHeight = 15.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { haiMessage = null }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Rounded.Close, "إغلاق", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        FloatingHaiButton(
            busy = haiBusy,
            onClick = ::invokeHai,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun ProcessingTopBar(stage: ProcessingStage, blocking: Boolean, onBack: () -> Unit) {
    Surface(color = PXBg) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowForward, "رجوع") }
            Column(Modifier.weight(1f)) {
                Text(
                    if (stage == ProcessingStage.REVIEW) "مراجعة المخطط" else "محرر المخطط",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    color = PXInk
                )
                Text(
                    if (stage == ProcessingStage.REVIEW)
                        "افهم ما قرأه النظام، صحح عند الحاجة، أو استدع HAI"
                    else
                        "عدّل العناصر فعليًا ثم راجع اقتراح HAI قبل تطبيقه",
                    color = PXMuted,
                    fontSize = 10.sp
                )
            }
            Surface(
                color = if (blocking) PXOrange.copy(alpha = .12f) else PXGreen.copy(alpha = .12f),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(
                    if (blocking) "يحتاج مراجعة" else "جاهز",
                    color = if (blocking) PXOrange else PXGreen,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                )
            }
        }
    }
}

@Composable
private fun ProcessingBottomBar(
    stage: ProcessingStage,
    canConfirm: Boolean,
    onStage: (ProcessingStage) -> Unit,
    onConfirm: () -> Unit
) {
    Surface(color = PXCard, shadowElevation = 10.dp) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                StageButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.FactCheck,
                    label = "مراجعة",
                    selected = stage == ProcessingStage.REVIEW
                ) { onStage(ProcessingStage.REVIEW) }
                StageButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.Edit,
                    label = "تعديل",
                    selected = stage == ProcessingStage.EDIT
                ) { onStage(ProcessingStage.EDIT) }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = canConfirm,
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = PXViolet),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Icon(Icons.Rounded.Verified, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("اعتماد المشروع", fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun StageButton(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) PXViolet.copy(alpha = .11f) else Color.Transparent,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.height(45.dp).clickable(onClick = onClick)
    ) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (selected) PXViolet else PXMuted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(5.dp))
            Text(label, color = if (selected) PXViolet else PXMuted, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ReviewStage(
    source: Uri?,
    plan: FloorPlan,
    report: PlanVerificationEngine.Report,
    modifier: Modifier,
    onPlanChange: (FloorPlan) -> Unit,
    onOpenEditor: () -> Unit
) {
    var widthText by remember { mutableStateOf(plan.widthM?.let { "%.2f".format(it) } ?: "") }
    var heightText by remember { mutableStateOf(plan.heightM?.let { "%.2f".format(it) } ?: "") }

    LaunchedEffect(plan.widthM, plan.heightM) {
        widthText = plan.widthM?.let { "%.2f".format(it) } ?: ""
        heightText = plan.heightM?.let { "%.2f".format(it) } ?: ""
    }

    Column(
        modifier.padding(horizontal = 12.dp).verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(4.dp))
        SourcePlanCard(source = source, base = plan, preview = null, height = 330.dp)

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            ReviewMetric(Modifier.weight(1f), "ثقة القراءة", "${report.readingConfidence}%")
            ReviewMetric(Modifier.weight(1f), "الغرف", "${plan.rooms.size}")
            ReviewMetric(Modifier.weight(1f), "الجدران", "${plan.walls.size}")
        }

        Spacer(Modifier.height(10.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = PXCard),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Straighten, null, tint = PXViolet)
                    Spacer(Modifier.width(7.dp))
                    Text("أبعاد المبنى", fontWeight = FontWeight.Black, fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                    Text("يمكنك إدخالها يدويًا أو استدع HAI", color = PXMuted, fontSize = 8.8.sp)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = widthText,
                        onValueChange = { widthText = it },
                        label = { Text("العرض") },
                        suffix = { Text("م") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = heightText,
                        onValueChange = { heightText = it },
                        label = { Text("الطول") },
                        suffix = { Text("م") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(7.dp))
                OutlinedButton(
                    onClick = {
                        val w = widthText.replace(',', '.').toDoubleOrNull()
                        val h = heightText.replace(',', '.').toDoubleOrNull()
                        if (w != null && h != null && w > .5 && h > .5) {
                            onPlanChange(PlanVerificationEngine.confirmScale(plan, w, h))
                        }
                    },
                    shape = RoundedCornerShape(15.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("تأكيد الأبعاد يدويًا")
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        if (report.issues.isEmpty()) {
            Surface(
                color = PXGreen.copy(alpha = .10f),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Verified, null, tint = PXGreen)
                    Spacer(Modifier.width(8.dp))
                    Text("لا توجد مشكلة حاجزة. يمكنك التعديل أو اعتماد المشروع.", color = PXGreen, fontWeight = FontWeight.Bold, fontSize = 10.5.sp)
                }
            }
        } else {
            Text("ما يحتاج انتباه", fontWeight = FontWeight.Black, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            report.issues.take(8).forEach { issue ->
                Surface(
                    color = PXCard,
                    shape = RoundedCornerShape(17.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                ) {
                    Row(Modifier.padding(11.dp), verticalAlignment = Alignment.Top) {
                        Icon(
                            if (issue.level == "error") Icons.Rounded.ErrorOutline else Icons.Rounded.Info,
                            null,
                            tint = if (issue.level == "error") PXOrange else PXViolet,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(issue.title, fontWeight = FontWeight.Bold, fontSize = 10.5.sp)
                            if (issue.detail.isNotBlank()) Text(issue.detail, color = PXMuted, fontSize = 9.sp, lineHeight = 13.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        FilledTonalButton(
            onClick = onOpenEditor,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Icon(Icons.Rounded.Edit, null, Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text("فتح المحرر", fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun ReviewMetric(modifier: Modifier, label: String, value: String) {
    Surface(color = PXCard, shape = RoundedCornerShape(16.dp), modifier = modifier) {
        Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = PXViolet, fontWeight = FontWeight.Black, fontSize = 15.sp)
            Text(label, color = PXMuted, fontSize = 8.5.sp)
        }
    }
}

@Composable
private fun EditorStage(
    source: Uri?,
    plan: FloorPlan,
    haiProposal: PlanProposal?,
    modifier: Modifier,
    onPlanChange: (FloorPlan) -> Unit,
    onRejectHai: () -> Unit
) {
    var tool by remember { mutableStateOf(EditorTool.SELECT) }
    var selection by remember { mutableStateOf<EditSelection?>(null) }
    var firstWallPoint by remember { mutableStateOf<PlanPoint?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    val undo = remember { mutableStateListOf<FloorPlan>() }
    val redo = remember { mutableStateListOf<FloorPlan>() }

    val preview = haiProposal?.updatedPlan
    val validation = haiProposal?.let { ArchitecturalEngine.validate(plan, it) }

    fun commit(next: FloorPlan) {
        undo += plan
        if (undo.size > 40) undo.removeAt(0)
        redo.clear()
        onPlanChange(next.copy(revision = maxOf(next.revision, plan.revision + 1)))
    }

    fun undoOnce() {
        val previous = undo.removeLastOrNull() ?: return
        redo += plan
        onPlanChange(previous.copy(revision = plan.revision + 1))
        selection = null
    }

    fun redoOnce() {
        val next = redo.removeLastOrNull() ?: return
        undo += plan
        onPlanChange(next.copy(revision = plan.revision + 1))
        selection = null
    }

    fun roomArea(widthPct: Float, heightPct: Float): Double {
        val w = plan.widthM ?: return 0.0
        val h = plan.heightM ?: return 0.0
        return (w * widthPct / 100.0) * (h * heightPct / 100.0)
    }

    fun selectAt(x: Float, y: Float) {
        val opening = plan.openings.minByOrNull { hypot((it.x - x).toDouble(), (it.y - y).toDouble()) }
        if (opening != null && hypot((opening.x - x).toDouble(), (opening.y - y).toDouble()) <= 5.0) {
            selection = EditSelection("opening", opening.id)
            return
        }
        val wall = plan.walls.minByOrNull { pointToSegmentDistance(x, y, it.start.x, it.start.y, it.end.x, it.end.y) }
        if (wall != null && pointToSegmentDistance(x, y, wall.start.x, wall.start.y, wall.end.x, wall.end.y) <= 3.0) {
            selection = EditSelection("wall", wall.id)
            return
        }
        val room = plan.rooms.lastOrNull { x >= it.x && x <= it.x + it.width && y >= it.y && y <= it.y + it.height }
        selection = room?.let { EditSelection("room", it.id) }
    }

    fun addAt(x: Float, y: Float) {
        when (tool) {
            EditorTool.SELECT -> selectAt(x, y)
            EditorTool.ADD_ROOM -> {
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
                    areaM2 = roomArea(rw, rh),
                    confidence = 100
                )
                commit(plan.copy(rooms = plan.rooms + room))
                selection = EditSelection("room", room.id)
                tool = EditorTool.SELECT
            }
            EditorTool.ADD_WALL -> {
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
                    selection = EditSelection("wall", wall.id)
                    firstWallPoint = null
                    tool = EditorTool.SELECT
                }
            }
            EditorTool.ADD_DOOR, EditorTool.ADD_WINDOW -> {
                val nearest = plan.walls.minByOrNull { pointToSegmentDistance(x, y, it.start.x, it.start.y, it.end.x, it.end.y) }
                val opening = Opening(
                    id = "manual-opening-${System.currentTimeMillis()}",
                    type = if (tool == EditorTool.ADD_WINDOW) "window" else "door",
                    x = x.coerceIn(0f, 100f),
                    y = y.coerceIn(0f, 100f),
                    width = if (tool == EditorTool.ADD_WINDOW) 5f else 4f,
                    wallId = nearest?.id,
                    confidence = 100
                )
                commit(plan.copy(openings = plan.openings + opening))
                selection = EditSelection("opening", opening.id)
                tool = EditorTool.SELECT
            }
        }
    }

    fun moveSelected(dx: Float, dy: Float) {
        val sel = selection ?: return
        when (sel.kind) {
            "room" -> commit(plan.copy(rooms = plan.rooms.map { r ->
                if (r.id != sel.id || r.locked) r else r.copy(
                    x = (r.x + dx).coerceIn(0f, 100f - r.width),
                    y = (r.y + dy).coerceIn(0f, 100f - r.height)
                )
            }))
            "wall" -> commit(plan.copy(walls = plan.walls.map { w ->
                if (w.id != sel.id || w.locked) w else w.copy(
                    start = PlanPoint((w.start.x + dx).coerceIn(0f, 100f), (w.start.y + dy).coerceIn(0f, 100f)),
                    end = PlanPoint((w.end.x + dx).coerceIn(0f, 100f), (w.end.y + dy).coerceIn(0f, 100f))
                )
            }))
            "opening" -> commit(plan.copy(openings = plan.openings.map { o ->
                if (o.id != sel.id || o.locked) o else o.copy(
                    x = (o.x + dx).coerceIn(0f, 100f),
                    y = (o.y + dy).coerceIn(0f, 100f)
                )
            }))
        }
    }

    fun resizeSelected(factor: Float) {
        val sel = selection ?: return
        when (sel.kind) {
            "room" -> commit(plan.copy(rooms = plan.rooms.map { r ->
                if (r.id != sel.id || r.locked) r else {
                    val nw = (r.width * factor).coerceIn(4f, 70f)
                    val nh = (r.height * factor).coerceIn(4f, 70f)
                    r.copy(
                        width = nw,
                        height = nh,
                        x = r.x.coerceIn(0f, 100f - nw),
                        y = r.y.coerceIn(0f, 100f - nh),
                        areaM2 = roomArea(nw, nh)
                    )
                }
            }))
            "opening" -> commit(plan.copy(openings = plan.openings.map { o ->
                if (o.id != sel.id || o.locked) o else o.copy(width = (o.width * factor).coerceIn(1.5f, 20f))
            }))
            "wall" -> commit(plan.copy(walls = plan.walls.map { w ->
                if (w.id != sel.id || w.locked) w else w.copy(thicknessCm = ((w.thicknessCm ?: 15.0) * factor).coerceIn(7.0, 45.0))
            }))
        }
    }

    fun rotateSelected() {
        val sel = selection ?: return
        if (sel.kind != "opening") return
        commit(plan.copy(openings = plan.openings.map { o ->
            if (o.id != sel.id || o.locked) o else o.copy(rotationDeg = (o.rotationDeg + 15f) % 360f)
        }))
    }

    fun toggleLock() {
        val sel = selection ?: return
        val next = when (sel.kind) {
            "room" -> plan.copy(rooms = plan.rooms.map { if (it.id == sel.id) it.copy(locked = !it.locked) else it })
            "wall" -> plan.copy(walls = plan.walls.map { if (it.id == sel.id) it.copy(locked = !it.locked) else it })
            "opening" -> plan.copy(openings = plan.openings.map { if (it.id == sel.id) it.copy(locked = !it.locked) else it })
            else -> plan
        }
        commit(next)
    }

    fun deleteSelected() {
        val sel = selection ?: return
        val next = when (sel.kind) {
            "room" -> plan.copy(rooms = plan.rooms.filterNot { it.id == sel.id })
            "wall" -> plan.copy(
                walls = plan.walls.filterNot { it.id == sel.id },
                openings = plan.openings.filterNot { it.wallId == sel.id }
            )
            "opening" -> plan.copy(openings = plan.openings.filterNot { it.id == sel.id })
            else -> plan
        }
        commit(next)
        selection = null
    }

    Column(modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("تحرير مباشر", fontSize = 17.sp, fontWeight = FontWeight.Black, color = PXInk)
                Text("المخطط الأصلي خلفية مرجعية • كل أدوات التعديل تعمل على الطبقة الهندسية", color = PXMuted, fontSize = 9.sp)
            }
            IconButton(onClick = { zoom = (zoom - .15f).coerceAtLeast(.7f) }) { Icon(Icons.Rounded.ZoomOut, "تصغير العرض") }
            Text("${(zoom * 100).toInt()}%", fontSize = 9.sp, color = PXMuted)
            IconButton(onClick = { zoom = (zoom + .15f).coerceAtMost(2.2f) }) { Icon(Icons.Rounded.ZoomIn, "تكبير العرض") }
        }

        EditorToolBar(
            tool = tool,
            canUndo = undo.isNotEmpty(),
            canRedo = redo.isNotEmpty(),
            onTool = {
                tool = it
                firstWallPoint = null
            },
            onUndo = ::undoOnce,
            onRedo = ::redoOnce
        )

        if (tool == EditorTool.ADD_WALL && firstWallPoint != null) {
            Text("اضغط النقطة الثانية لإنشاء الجدار", color = PXViolet, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
        }

        Spacer(Modifier.height(5.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            Box(Modifier.fillMaxSize().graphicsLayer(scaleX = zoom, scaleY = zoom)) {
                SourceBackdrop(source)
                InteractivePlanCanvas(
                    base = plan,
                    preview = preview,
                    selection = selection,
                    onTap = ::addAt,
                    modifier = Modifier.fillMaxSize()
                )
                if (preview != null) {
                    Surface(
                        color = PXViolet,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
                    ) {
                        Text("اقتراح HAI بالبنفسجي — لم يُطبق", color = Color.White, fontWeight = FontWeight.Black, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(7.dp))
        selection?.let { sel ->
            SelectionInspector(
                plan = plan,
                selection = sel,
                onMove = ::moveSelected,
                onResize = ::resizeSelected,
                onRotate = ::rotateSelected,
                onToggleLock = ::toggleLock,
                onDelete = ::deleteSelected,
                onRenameRoom = { newName ->
                    commit(plan.copy(rooms = plan.rooms.map { r -> if (r.id == sel.id) r.copy(name = newName) else r }))
                }
            )
            Spacer(Modifier.height(7.dp))
        }

        haiProposal?.let { proposal ->
            HaiProposalCard(
                proposal = proposal,
                validation = validation,
                onReject = onRejectHai,
                onApply = {
                    val next = proposal.updatedPlan ?: return@HaiProposalCard
                    commit(next)
                    onRejectHai()
                }
            )
        } ?: run {
            Surface(color = PXCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = PXViolet, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("استدع HAI في أي لحظة ليعطي رأيه أو اعتراضه أو تعديلًا يمكن معاينته قبل التطبيق.", color = PXMuted, fontSize = 9.5.sp, lineHeight = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun EditorToolBar(
    tool: EditorTool,
    canUndo: Boolean,
    canRedo: Boolean,
    onTool: (EditorTool) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ToolChip(Icons.Rounded.TouchApp, "تحديد", tool == EditorTool.SELECT) { onTool(EditorTool.SELECT) }
        ToolChip(Icons.Rounded.AddHome, "غرفة", tool == EditorTool.ADD_ROOM) { onTool(EditorTool.ADD_ROOM) }
        ToolChip(Icons.Rounded.HorizontalRule, "جدار", tool == EditorTool.ADD_WALL) { onTool(EditorTool.ADD_WALL) }
        ToolChip(Icons.Rounded.DoorFront, "باب", tool == EditorTool.ADD_DOOR) { onTool(EditorTool.ADD_DOOR) }
        ToolChip(Icons.Rounded.Window, "نافذة", tool == EditorTool.ADD_WINDOW) { onTool(EditorTool.ADD_WINDOW) }
        AssistChip(onClick = onUndo, enabled = canUndo, label = { Text("تراجع", fontSize = 9.sp) }, leadingIcon = { Icon(Icons.Rounded.Undo, null, Modifier.size(15.dp)) })
        AssistChip(onClick = onRedo, enabled = canRedo, label = { Text("إعادة", fontSize = 9.sp) }, leadingIcon = { Icon(Icons.Rounded.Redo, null, Modifier.size(15.dp)) })
    }
}

@Composable
private fun ToolChip(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 9.sp) },
        leadingIcon = { Icon(icon, null, Modifier.size(15.dp)) }
    )
}

@Composable
private fun SelectionInspector(
    plan: FloorPlan,
    selection: EditSelection,
    onMove: (Float, Float) -> Unit,
    onResize: (Float) -> Unit,
    onRotate: () -> Unit,
    onToggleLock: () -> Unit,
    onDelete: () -> Unit,
    onRenameRoom: (String) -> Unit
) {
    val room = if (selection.kind == "room") plan.rooms.firstOrNull { it.id == selection.id } else null
    val wall = if (selection.kind == "wall") plan.walls.firstOrNull { it.id == selection.id } else null
    val opening = if (selection.kind == "opening") plan.openings.firstOrNull { it.id == selection.id } else null
    var roomName by remember(room?.id, room?.name) { mutableStateOf(room?.name.orEmpty()) }

    Surface(color = PXCard, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Tune, null, tint = PXViolet, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text(
                    room?.let { "غرفة • ${it.name}" }
                        ?: wall?.let { "جدار • ${it.thicknessCm?.toInt() ?: 15} سم" }
                        ?: opening?.let { if (it.type.contains("window", true)) "نافذة" else "باب" }
                        ?: "عنصر",
                    fontWeight = FontWeight.Black,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onToggleLock) { Text("قفل/فتح", fontSize = 9.sp) }
                IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.Delete, "حذف", tint = PXOrange, modifier = Modifier.size(18.dp)) }
            }

            if (room != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = roomName,
                        onValueChange = { roomName = it },
                        label = { Text("اسم الغرفة") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(6.dp))
                    FilledTonalButton(onClick = { if (roomName.isNotBlank()) onRenameRoom(roomName.trim()) }) { Text("حفظ") }
                }
                Spacer(Modifier.height(5.dp))
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                MiniAction(Modifier.weight(1f), Icons.Rounded.ArrowForward, "يمين") { onMove(1.5f, 0f) }
                MiniAction(Modifier.weight(1f), Icons.Rounded.ArrowBack, "يسار") { onMove(-1.5f, 0f) }
                MiniAction(Modifier.weight(1f), Icons.Rounded.ArrowUpward, "أعلى") { onMove(0f, -1.5f) }
                MiniAction(Modifier.weight(1f), Icons.Rounded.ArrowDownward, "أسفل") { onMove(0f, 1.5f) }
            }
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                MiniAction(Modifier.weight(1f), Icons.Rounded.Add, if (wall != null) "سماكة +" else "تكبير") { onResize(1.10f) }
                MiniAction(Modifier.weight(1f), Icons.Rounded.Remove, if (wall != null) "سماكة -" else "تصغير") { onResize(.90f) }
                if (opening != null) {
                    MiniAction(Modifier.weight(1f), Icons.Rounded.RotateRight, "تدوير") { onRotate() }
                }
            }
        }
    }
}

@Composable
private fun MiniAction(modifier: Modifier, icon: ImageVector, label: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.height(38.dp),
        contentPadding = PaddingValues(horizontal = 3.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Icon(icon, null, Modifier.size(14.dp))
        Spacer(Modifier.width(2.dp))
        Text(label, fontSize = 8.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HaiProposalCard(
    proposal: PlanProposal,
    validation: ValidationReport?,
    onReject: () -> Unit,
    onApply: () -> Unit
) {
    Surface(color = PXViolet.copy(alpha = .09f), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = PXViolet)
                Spacer(Modifier.width(6.dp))
                Text("رأي HAI", fontWeight = FontWeight.Black, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text("${proposal.confidence}%", color = PXMuted, fontSize = 9.sp)
            }
            Spacer(Modifier.height(5.dp))
            Text(proposal.message, color = PXInk, fontSize = 10.sp, lineHeight = 14.sp, maxLines = 6)
            validation?.errors?.firstOrNull()?.let {
                Text("اعتراض هندسي: $it", color = PXOrange, fontSize = 9.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(7.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedButton(onClick = onReject, modifier = Modifier.weight(1f)) { Text("رفض") }
                Button(
                    onClick = onApply,
                    enabled = proposal.updatedPlan != null && validation?.valid != false,
                    modifier = Modifier.weight(1f)
                ) { Text("تطبيق", fontWeight = FontWeight.Black) }
            }
        }
    }
}

@Composable
private fun SourcePlanCard(source: Uri?, base: FloorPlan, preview: FloorPlan?, height: androidx.compose.ui.unit.Dp) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth().height(height)
    ) {
        Box(Modifier.fillMaxSize()) {
            SourceBackdrop(source)
            PlanLayerCanvas(base = base, preview = preview, selection = null, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun SourceBackdrop(source: Uri?) {
    if (source == null) {
        Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
            Text("المخطط الأصلي غير متاح", color = PXMuted, fontSize = 10.sp)
        }
        return
    }

    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, source) {
        value = withContext(Dispatchers.IO) { loadSourceBitmap(context, source) }
    }
    val image = bitmap
    if (image == null) {
        Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = PXViolet, strokeWidth = 2.2.dp)
        }
    } else {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = "المخطط الأصلي",
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun InteractivePlanCanvas(
    base: FloorPlan,
    preview: FloorPlan?,
    selection: EditSelection?,
    onTap: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier.pointerInput(base, preview) {
            detectTapGestures { tap ->
                val x = tap.x / size.width.coerceAtLeast(1) * 100f
                val y = tap.y / size.height.coerceAtLeast(1) * 100f
                onTap(x, y)
            }
        }
    ) {
        drawFloorPlan(base, selection, PXLine, Color(0xFF6D6878), if (preview == null) .90f else .32f)
        preview?.let { drawFloorPlan(it, selection, PXViolet, Color(0xFF8A7AF0), .95f) }
    }
}

@Composable
private fun PlanLayerCanvas(base: FloorPlan, preview: FloorPlan?, selection: EditSelection?, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawFloorPlan(base, selection, PXViolet, PXViolet, if (preview == null) .72f else .25f)
        preview?.let { drawFloorPlan(it, selection, PXViolet, Color(0xFF8A7AF0), .95f) }
    }
}

private fun DrawScope.drawFloorPlan(
    plan: FloorPlan,
    selection: EditSelection?,
    lineColor: Color,
    roomColor: Color,
    alpha: Float
) {
    fun p(x: Float, y: Float) = Offset(size.width * x / 100f, size.height * y / 100f)

    plan.rooms.forEach { room ->
        val selected = selection?.kind == "room" && selection.id == room.id
        val topLeft = p(room.x, room.y)
        val roomSize = Size(size.width * room.width / 100f, size.height * room.height / 100f)
        drawRect(roomColor.copy(alpha = if (selected) .22f else .07f), topLeft = topLeft, size = roomSize)
        drawRect(
            if (selected) PXOrange else lineColor.copy(alpha = alpha),
            topLeft = topLeft,
            size = roomSize,
            style = Stroke((if (selected) 3.2.dp else 1.5.dp).toPx())
        )
    }

    plan.walls.forEach { wall ->
        val selected = selection?.kind == "wall" && selection.id == wall.id
        drawLine(
            if (selected) PXOrange else lineColor.copy(alpha = alpha),
            p(wall.start.x, wall.start.y),
            p(wall.end.x, wall.end.y),
            strokeWidth = (if (selected) 4.2.dp else 2.4.dp).toPx()
        )
    }

    plan.openings.forEach { opening ->
        val selected = selection?.kind == "opening" && selection.id == opening.id
        drawCircle(
            if (selected) PXOrange else PXGreen.copy(alpha = alpha),
            radius = (if (selected) 6.dp else 4.dp).toPx(),
            center = p(opening.x, opening.y)
        )
    }
}

private fun loadSourceBitmap(context: Context, source: Uri): Bitmap? {
    val type = context.contentResolver.getType(source).orEmpty()
    return runCatching {
        if (type == "application/pdf") {
            val pfd = context.contentResolver.openFileDescriptor(source, "r") ?: return@runCatching null
            PdfRenderer(pfd).use { renderer ->
                if (renderer.pageCount <= 0) return@use null
                renderer.openPage(0).use { page ->
                    val target = 1800f
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

private fun pointToSegmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Double {
    val dx = (bx - ax).toDouble()
    val dy = (by - ay).toDouble()
    if (dx == 0.0 && dy == 0.0) return hypot((px - ax).toDouble(), (py - ay).toDouble())
    val t = (((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
    val x = ax + t * dx
    val y = ay + t * dy
    return hypot(px - x, py - y)
}
