package com.manzili.hai

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

private val WBg = Color(0xFFF8F6F2)
private val WCard = Color(0xFFFFFEFC)
private val WInk = Color(0xFF181A18)
private val WViolet = Color(0xFF6353D9)
private val WOrange = Color(0xFFE28B5A)
private val WGreen = Color(0xFF4C8A78)
private val WMuted = Color(0xFF8F8A83)

private enum class WorkspaceTab { PLAN, EDIT }
private enum class EditMode { SELECT, ADD_ROOM, ADD_WALL, ADD_DOOR, ADD_WINDOW }
private data class EditorSelection(val kind: String, val id: String)

@Composable
fun ImportedProjectWorkspaceScreen(
    nav: NavHostController,
    source: Uri?,
    plan: FloorPlan?,
    onConfirm: (FloorPlan) -> Unit
) {
    if (plan == null) {
        Box(Modifier.fillMaxSize().background(WBg), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = WViolet)
        }
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val resolver = remember(context) { ContextualHaiResolver(context) }
    var working by remember(plan) { mutableStateOf(plan) }
    var tab by remember { mutableStateOf(WorkspaceTab.PLAN) }
    var haiBusy by remember { mutableStateOf(false) }
    var haiMessage by remember { mutableStateOf<String?>(null) }
    var haiProposal by remember { mutableStateOf<PlanProposal?>(null) }
    val report = remember(working) { PlanVerificationEngine.inspect(working) }

    fun invokeHai() {
        if (haiBusy) return
        haiBusy = true
        haiMessage = if (tab == WorkspaceTab.PLAN) "HAI يحل المشكلة الظاهرة في المراجعة..." else "HAI يراجع المخطط ويبحث عن اعتراض أو تحسين..."
        scope.launch {
            try {
                if (tab == WorkspaceTab.PLAN) {
                    val result = resolver.resolveReview(source, working)
                    working = result.plan
                    haiProposal = null
                    haiMessage = result.message
                } else {
                    var base = working
                    if (base.rooms.isEmpty() && base.walls.size < 3) {
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
                    haiMessage = proposal.message.lineSequence().firstOrNull().orEmpty().ifBlank { "اكتملت مراجعة HAI." }
                }
            } catch (error: Throwable) {
                haiMessage = "تعذر استدعاء HAI: ${error.message.orEmpty().take(120)}"
            } finally {
                haiBusy = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(WBg)) {
        Scaffold(
            containerColor = WBg,
            topBar = {
                Surface(color = WBg) {
                    Row(
                        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Rounded.ArrowForward, "رجوع") }
                        Column(Modifier.weight(1f)) {
                            Text("معالجة المشروع", fontSize = 20.sp, fontWeight = FontWeight.Black, color = WInk)
                            Text("المخطط • محرر كامل • استدع HAI عند الحاجة", fontSize = 10.sp, color = WMuted)
                        }
                        Surface(
                            color = if (report.blocking) WOrange.copy(alpha = .12f) else WGreen.copy(alpha = .12f),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Text(
                                if (report.blocking) "راجع" else "جاهز",
                                color = if (report.blocking) WOrange else WGreen,
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
                    Surface(color = WCard, shadowElevation = 8.dp) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            WorkspaceNav(Modifier.weight(1f), Icons.Rounded.Map, "المخطط", tab == WorkspaceTab.PLAN) {
                                tab = WorkspaceTab.PLAN
                                haiProposal = null
                            }
                            WorkspaceNav(Modifier.weight(1f), Icons.Rounded.TouchApp, "تعديل", tab == WorkspaceTab.EDIT) {
                                tab = WorkspaceTab.EDIT
                            }
                        }
                    }
                    Surface(color = WBg) {
                        Button(
                            enabled = !report.blocking,
                            onClick = { onConfirm(report.plan) },
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = WViolet),
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
                    WorkspaceTab.PLAN -> PlanVerificationScreen(
                        nav = nav,
                        source = source,
                        plan = working,
                        onPlanChange = {
                            working = it
                            haiMessage = null
                        }
                    ) {
                        working = it
                        tab = WorkspaceTab.EDIT
                    }
                    WorkspaceTab.EDIT -> FullPlanEditor(
                        source = source,
                        plan = working,
                        haiProposal = haiProposal,
                        haiMessage = haiMessage,
                        onPlanChange = {
                            working = it
                            haiProposal = null
                            haiMessage = null
                        },
                        onDismissHai = {
                            haiProposal = null
                            haiMessage = null
                        }
                    )
                }
            }
        }

        haiMessage?.let { message ->
            Surface(
                color = WInk.copy(alpha = .92f),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 68.dp, start = 26.dp, end = 26.dp)
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(message, color = Color.White, fontSize = 10.5.sp, lineHeight = 14.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { haiMessage = null }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Rounded.Close, "إغلاق", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        FloatingHaiButton(
            busy = haiBusy,
            onClick = ::invokeHai,
            modifier = Modifier.fillMaxSize().padding(top = 78.dp, bottom = 120.dp, start = 6.dp, end = 6.dp)
        )
    }
}

@Composable
private fun WorkspaceNav(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.height(54.dp).clickable(onClick = onClick),
        color = if (selected) WViolet.copy(alpha = .10f) else Color.Transparent,
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, null, tint = if (selected) WViolet else WMuted, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(2.dp))
            Text(label, fontSize = 10.sp, fontWeight = if (selected) FontWeight.Black else FontWeight.Medium, color = if (selected) WViolet else WMuted)
        }
    }
}

@Composable
private fun FullPlanEditor(
    source: Uri?,
    plan: FloorPlan,
    haiProposal: PlanProposal?,
    haiMessage: String?,
    onPlanChange: (FloorPlan) -> Unit,
    onDismissHai: () -> Unit
) {
    var mode by remember { mutableStateOf(EditMode.SELECT) }
    var selection by remember(plan.revision) { mutableStateOf<EditorSelection?>(null) }
    var wallStart by remember { mutableStateOf<PlanPoint?>(null) }
    val undo = remember { mutableStateListOf<FloorPlan>() }
    val redo = remember { mutableStateListOf<FloorPlan>() }
    val proposal = haiProposal
    val preview = proposal?.updatedPlan
    val validation = proposal?.let { ArchitecturalEngine.validate(plan, it) }
    val score = remember(plan) { ArchitecturalEngine.score(plan) }

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
    }

    fun redoOnce() {
        val next = redo.removeLastOrNull() ?: return
        undo += plan
        onPlanChange(next.copy(revision = plan.revision + 1))
    }

    fun areaFor(xPct: Float, yPct: Float): Double {
        val w = plan.widthM ?: return 0.0
        val h = plan.heightM ?: return 0.0
        return (w * xPct / 100.0) * (h * yPct / 100.0)
    }

    fun addAt(x: Float, y: Float) {
        when (mode) {
            EditMode.ADD_ROOM -> {
                val width = 18f
                val height = 13f
                val room = Room(
                    id = "manual-room-${System.currentTimeMillis()}",
                    name = "غرفة ${plan.rooms.size + 1}",
                    type = "generic",
                    x = (x - width / 2f).coerceIn(0f, 100f - width),
                    y = (y - height / 2f).coerceIn(0f, 100f - height),
                    width = width,
                    height = height,
                    areaM2 = areaFor(width, height),
                    confidence = 100
                )
                commit(plan.copy(rooms = plan.rooms + room))
                selection = EditorSelection("room", room.id)
                mode = EditMode.SELECT
            }
            EditMode.ADD_WALL -> {
                val first = wallStart
                if (first == null) {
                    wallStart = PlanPoint(x, y)
                } else {
                    val wall = Wall(
                        id = "manual-wall-${System.currentTimeMillis()}",
                        start = first,
                        end = PlanPoint(x, y),
                        thicknessCm = 15.0,
                        kind = "manual",
                        confidence = 100
                    )
                    commit(plan.copy(walls = plan.walls + wall))
                    selection = EditorSelection("wall", wall.id)
                    wallStart = null
                    mode = EditMode.SELECT
                }
            }
            EditMode.ADD_DOOR, EditMode.ADD_WINDOW -> {
                val nearest = plan.walls.minByOrNull { pointToSegment(x, y, it.start.x, it.start.y, it.end.x, it.end.y) }
                val opening = Opening(
                    id = "manual-opening-${System.currentTimeMillis()}",
                    type = if (mode == EditMode.ADD_WINDOW) "window" else "door",
                    x = x.coerceIn(0f, 100f),
                    y = y.coerceIn(0f, 100f),
                    width = if (mode == EditMode.ADD_WINDOW) 5f else 4f,
                    wallId = nearest?.id,
                    confidence = 100
                )
                commit(plan.copy(openings = plan.openings + opening))
                selection = EditorSelection("opening", opening.id)
                mode = EditMode.SELECT
            }
            else -> Unit
        }
    }

    fun selectAt(x: Float, y: Float) {
        val opening = plan.openings.minByOrNull { hypot((it.x - x).toDouble(), (it.y - y).toDouble()) }
        if (opening != null && hypot((opening.x - x).toDouble(), (opening.y - y).toDouble()) <= 5.0) {
            selection = EditorSelection("opening", opening.id); return
        }
        val wall = plan.walls.minByOrNull { pointToSegment(x, y, it.start.x, it.start.y, it.end.x, it.end.y) }
        if (wall != null && pointToSegment(x, y, wall.start.x, wall.start.y, wall.end.x, wall.end.y) <= 3.0) {
            selection = EditorSelection("wall", wall.id); return
        }
        val room = plan.rooms.lastOrNull { x >= it.x && x <= it.x + it.width && y >= it.y && y <= it.y + it.height }
        selection = room?.let { EditorSelection("room", it.id) }
    }

    fun moveSelected(dx: Float, dy: Float) {
        val sel = selection ?: return
        when (sel.kind) {
            "room" -> commit(plan.copy(rooms = plan.rooms.map { room ->
                if (room.id != sel.id || room.locked) room else room.copy(
                    x = (room.x + dx).coerceIn(0f, 100f - room.width),
                    y = (room.y + dy).coerceIn(0f, 100f - room.height)
                )
            }))
            "wall" -> commit(plan.copy(walls = plan.walls.map { wall ->
                if (wall.id != sel.id || wall.locked) wall else wall.copy(
                    start = PlanPoint((wall.start.x + dx).coerceIn(0f, 100f), (wall.start.y + dy).coerceIn(0f, 100f)),
                    end = PlanPoint((wall.end.x + dx).coerceIn(0f, 100f), (wall.end.y + dy).coerceIn(0f, 100f))
                )
            }))
            "opening" -> commit(plan.copy(openings = plan.openings.map { opening ->
                if (opening.id != sel.id || opening.locked) opening else opening.copy(
                    x = (opening.x + dx).coerceIn(0f, 100f),
                    y = (opening.y + dy).coerceIn(0f, 100f)
                )
            }))
        }
    }

    fun resizeSelected(factor: Float) {
        val sel = selection ?: return
        when (sel.kind) {
            "room" -> commit(plan.copy(rooms = plan.rooms.map { room ->
                if (room.id != sel.id || room.locked) room else {
                    val nw = (room.width * factor).coerceIn(4f, 60f)
                    val nh = (room.height * factor).coerceIn(4f, 60f)
                    room.copy(
                        width = nw,
                        height = nh,
                        x = room.x.coerceIn(0f, 100f - nw),
                        y = room.y.coerceIn(0f, 100f - nh),
                        areaM2 = areaFor(nw, nh)
                    )
                }
            }))
            "opening" -> commit(plan.copy(openings = plan.openings.map { opening ->
                if (opening.id != sel.id || opening.locked) opening else opening.copy(width = (opening.width * factor).coerceIn(1.5f, 18f))
            }))
            "wall" -> commit(plan.copy(walls = plan.walls.map { wall ->
                if (wall.id != sel.id || wall.locked) wall else wall.copy(thicknessCm = ((wall.thicknessCm ?: 15.0) * factor).coerceIn(7.0, 45.0))
            }))
        }
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

    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("التعديل", fontSize = 25.sp, fontWeight = FontWeight.Black, color = WInk)
                Text("حرّر الجدران والغرف والفتحات مباشرة — الأصل يبقى مرجعًا في الخلفية", fontSize = 9.5.sp, color = WMuted)
            }
            Surface(color = WViolet.copy(alpha = .10f), shape = RoundedCornerShape(15.dp)) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${score.overall}", color = WViolet, fontWeight = FontWeight.Black, fontSize = 16.sp)
                    Text("تقييم", color = WMuted, fontSize = 8.sp)
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ToolChip("تحديد", Icons.Rounded.TouchApp, mode == EditMode.SELECT) { mode = EditMode.SELECT; wallStart = null }
            ToolChip("غرفة +", Icons.Rounded.Add, mode == EditMode.ADD_ROOM) { mode = EditMode.ADD_ROOM; wallStart = null }
            ToolChip("جدار +", Icons.Rounded.Add, mode == EditMode.ADD_WALL) { mode = EditMode.ADD_WALL; wallStart = null }
            ToolChip("باب +", Icons.Rounded.Add, mode == EditMode.ADD_DOOR) { mode = EditMode.ADD_DOOR; wallStart = null }
            ToolChip("نافذة +", Icons.Rounded.Add, mode == EditMode.ADD_WINDOW) { mode = EditMode.ADD_WINDOW; wallStart = null }
            ToolChip("تراجع", Icons.Rounded.Undo, false, enabled = undo.isNotEmpty()) { undoOnce() }
            ToolChip("إعادة", Icons.Rounded.Redo, false, enabled = redo.isNotEmpty()) { redoOnce() }
        }

        if (mode == EditMode.ADD_WALL && wallStart != null) {
            Text("اختر النقطة الثانية للجدار", color = WViolet, fontWeight = FontWeight.Bold, fontSize = 9.5.sp, modifier = Modifier.padding(vertical = 4.dp))
        } else {
            Spacer(Modifier.height(4.dp))
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            Box(Modifier.fillMaxSize()) {
                EditorSourceBackdrop(source)
                Canvas(
                    Modifier.fillMaxSize().pointerInput(plan, preview, mode, wallStart) {
                        detectTapGestures { tap ->
                            val x = tap.x / size.width.coerceAtLeast(1) * 100f
                            val y = tap.y / size.height.coerceAtLeast(1) * 100f
                            if (mode == EditMode.SELECT) selectAt(x, y) else addAt(x, y)
                        }
                    }
                ) {
                    drawPlanLayer(plan, selection, Color(0xFF35373A), Color(0xFF756F86), alpha = if (preview == null) .94f else .34f)
                    preview?.let { drawPlanLayer(it, selection, WViolet, Color(0xFF8D7CF0), alpha = .96f) }
                    wallStart?.let { start ->
                        drawCircle(WOrange, 6.dp.toPx(), Offset(size.width * start.x / 100f, size.height * start.y / 100f))
                    }
                }

                Surface(
                    color = WInk.copy(alpha = .76f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                ) {
                    Text(
                        when (mode) {
                            EditMode.SELECT -> "المس أي غرفة أو جدار أو فتحة لتعديلها"
                            EditMode.ADD_ROOM -> "المس مكان الغرفة الجديدة"
                            EditMode.ADD_WALL -> if (wallStart == null) "المس بداية الجدار" else "المس نهاية الجدار"
                            EditMode.ADD_DOOR -> "المس مكان الباب"
                            EditMode.ADD_WINDOW -> "المس مكان النافذة"
                        },
                        color = Color.White,
                        fontSize = 9.2.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }

                if (preview != null) {
                    Surface(
                        color = WViolet,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
                    ) {
                        Text("البنفسجي = اقتراح HAI قبل التطبيق", color = Color.White, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        selection?.let { sel ->
            Surface(color = WCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(selectionTitle(plan, sel), fontWeight = FontWeight.Black, fontSize = 10.5.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = ::toggleLock) { Text("قفل/فتح", fontSize = 9.sp) }
                        TextButton(onClick = ::deleteSelected) { Text("حذف", color = WOrange, fontSize = 9.sp) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        MiniAction(Modifier.weight(1f), "←") { moveSelected(-1.5f, 0f) }
                        MiniAction(Modifier.weight(1f), "→") { moveSelected(1.5f, 0f) }
                        MiniAction(Modifier.weight(1f), "↑") { moveSelected(0f, -1.5f) }
                        MiniAction(Modifier.weight(1f), "↓") { moveSelected(0f, 1.5f) }
                        MiniAction(Modifier.weight(1f), "+") { resizeSelected(1.10f) }
                        MiniAction(Modifier.weight(1f), "−") { resizeSelected(.90f) }
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
        }

        proposal?.let { p ->
            Surface(
                color = if (p.updatedPlan != null) WViolet.copy(alpha = .10f) else WOrange.copy(alpha = .11f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = WViolet, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("رأي HAI", fontWeight = FontWeight.Black, fontSize = 11.sp)
                        Spacer(Modifier.weight(1f))
                        Text("${p.confidence}%", color = WMuted, fontSize = 9.sp)
                    }
                    Text(p.message.ifBlank { haiMessage ?: "مراجعة مكتملة." }, fontSize = 9.6.sp, lineHeight = 13.sp, maxLines = 4)
                    validation?.errors?.firstOrNull()?.let { Text("اعتراض: $it", color = WOrange, fontSize = 9.sp) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 5.dp)) {
                        OutlinedButton(onClick = onDismissHai, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("رفض", fontSize = 9.sp) }
                        Button(
                            onClick = {
                                p.updatedPlan?.let { commit(it) }
                                onDismissHai()
                            },
                            enabled = p.updatedPlan != null && validation?.valid != false,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(4.dp)
                        ) { Text("تطبيق", fontSize = 9.sp, fontWeight = FontWeight.Black) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolChip(text: String, icon: ImageVector, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = if (selected) WViolet.copy(alpha = .18f) else WCard),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp),
        modifier = Modifier.height(38.dp)
    ) {
        Icon(icon, null, modifier = Modifier.size(14.dp), tint = if (selected) WViolet else WMuted)
        Spacer(Modifier.width(3.dp))
        Text(text, fontSize = 8.8.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MiniAction(modifier: Modifier, text: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = modifier.height(34.dp), contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(10.dp)) {
        Text(text, fontWeight = FontWeight.Black, fontSize = 13.sp)
    }
}

@Composable
private fun EditorSourceBackdrop(source: Uri?) {
    if (source == null) {
        Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
            Text("المصدر الأصلي غير متاح", color = WMuted, fontSize = 11.sp)
        }
        return
    }
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, source) {
        value = withContext(Dispatchers.IO) { loadEditorBitmap(context, source) }
    }
    val image = bitmap
    if (image == null) {
        Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = WViolet, strokeWidth = 2.2.dp)
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

private fun loadEditorBitmap(context: android.content.Context, source: Uri): Bitmap? {
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

private fun DrawScope.drawPlanLayer(
    plan: FloorPlan,
    selected: EditorSelection?,
    lineColor: Color,
    roomColor: Color,
    alpha: Float
) {
    fun p(x: Float, y: Float) = Offset(size.width * x / 100f, size.height * y / 100f)
    plan.rooms.forEach { room ->
        val isSelected = selected?.kind == "room" && selected.id == room.id
        val topLeft = p(room.x, room.y)
        val roomSize = Size(size.width * room.width / 100f, size.height * room.height / 100f)
        drawRect(roomColor.copy(alpha = if (isSelected) .25f * alpha else .08f * alpha), topLeft = topLeft, size = roomSize)
        drawRect(
            if (isSelected) WOrange.copy(alpha = alpha) else lineColor.copy(alpha = .72f * alpha),
            topLeft = topLeft,
            size = roomSize,
            style = Stroke(if (isSelected) 3.dp.toPx() else 1.3.dp.toPx())
        )
    }
    plan.walls.forEach { wall ->
        val isSelected = selected?.kind == "wall" && selected.id == wall.id
        drawLine(
            if (isSelected) WOrange.copy(alpha = alpha) else lineColor.copy(alpha = alpha),
            p(wall.start.x, wall.start.y),
            p(wall.end.x, wall.end.y),
            strokeWidth = (if (isSelected) 4.dp else 2.4.dp).toPx()
        )
    }
    plan.openings.forEach { opening ->
        val isSelected = selected?.kind == "opening" && selected.id == opening.id
        drawCircle(
            if (isSelected) WOrange.copy(alpha = alpha) else WGreen.copy(alpha = alpha),
            radius = (if (isSelected) 6.dp else 4.dp).toPx(),
            center = p(opening.x, opening.y)
        )
    }
}

private fun selectionTitle(plan: FloorPlan, selection: EditorSelection): String = when (selection.kind) {
    "room" -> plan.rooms.firstOrNull { it.id == selection.id }?.let { "${it.name} • ${"%.1f".format(it.areaM2)}م²" } ?: "غرفة"
    "wall" -> plan.walls.firstOrNull { it.id == selection.id }?.let { "جدار • ${it.thicknessCm?.let { t -> "${t.toInt()}سم" } ?: it.kind}" } ?: "جدار"
    "opening" -> plan.openings.firstOrNull { it.id == selection.id }?.let { if (it.type.contains("window", true)) "نافذة" else "باب" } ?: "فتحة"
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
