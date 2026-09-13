package com.manzili.hai

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.manzili.hai.engine.ArchitecturalEngine
import com.manzili.hai.engine.ContextualHaiResolver
import com.manzili.hai.engine.GeometrySolver
import com.manzili.hai.engine.PlanVerificationEngine
import com.manzili.hai.engine.ProjectMemoryEngine
import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.PlanProposal
import kotlinx.coroutines.launch
import kotlin.math.hypot

private val WBg = Color(0xFFF8F6F2)
private val WCard = Color(0xFFFFFEFC)
private val WInk = Color(0xFF181A18)
private val WViolet = Color(0xFF6353D9)
private val WOrange = Color(0xFFE28B5A)
private val WGreen = Color(0xFF4C8A78)
private val WMuted = Color(0xFF8F8A83)

private enum class WorkspaceTab { PLAN, EDIT }
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
        haiMessage = if (tab == WorkspaceTab.PLAN) "HAI يقرأ المشكلة الحالية..." else "HAI يراجع التعديل الحالي..."
        scope.launch {
            if (tab == WorkspaceTab.PLAN) {
                runCatching { resolver.resolveReview(source, working) }
                    .onSuccess { result ->
                        working = result.plan
                        haiProposal = null
                        haiMessage = result.message
                    }
                    .onFailure { haiMessage = "تعذر استدعاء HAI: ${it.message.orEmpty().take(120)}" }
            } else {
                runCatching { resolver.proposeEdit(working) }
                    .onSuccess { proposal ->
                        haiProposal = proposal
                        haiMessage = proposal.message.lineSequence().firstOrNull().orEmpty().ifBlank { "اكتملت مراجعة HAI." }
                    }
                    .onFailure { haiMessage = "تعذر استدعاء HAI: ${it.message.orEmpty().take(120)}" }
            }
            haiBusy = false
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
                            Text("المخطط • تعديل بصري • استدع HAI عند الحاجة", fontSize = 10.sp, color = WMuted)
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
                    WorkspaceTab.EDIT -> ContextualPlanEditor(
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
            modifier = Modifier.fillMaxSize().padding(top = 84.dp, bottom = 126.dp, start = 8.dp, end = 8.dp)
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

/** A rebuilt editor: current geometry stays neutral; every proposed change is a colored overlay. */
@Composable
private fun ContextualPlanEditor(
    plan: FloorPlan,
    haiProposal: PlanProposal?,
    haiMessage: String?,
    onPlanChange: (FloorPlan) -> Unit,
    onDismissHai: () -> Unit
) {
    var selection by remember(plan.revision) { mutableStateOf<EditorSelection?>(null) }
    var localProposal by remember(plan.revision) { mutableStateOf<PlanProposal?>(null) }
    val proposal = haiProposal ?: localProposal
    val preview = proposal?.updatedPlan
    val validation = proposal?.let { ArchitecturalEngine.validate(plan, it) }
    val score = remember(plan) { ArchitecturalEngine.score(plan) }

    fun buildManualProposal(action: String) {
        val sel = selection ?: return
        val candidate = GeometrySolver.actionCandidates(plan, sel.kind, sel.id, action)
            .firstOrNull { !ProjectMemoryEngine.review(plan, it.plan).hasObjection }
        localProposal = candidate?.let { GeometrySolver.toProposal(plan, it) }
            ?: PlanProposal("لم أجد تعديلًا هندسيًا آمنًا لهذا العنصر.", null, confidence = 100)
        onDismissHai()
    }

    fun toggleLock() {
        val sel = selection ?: return
        val next = when (sel.kind) {
            "room" -> plan.copy(rooms = plan.rooms.map { if (it.id == sel.id) it.copy(locked = !it.locked) else it })
            "wall" -> plan.copy(walls = plan.walls.map { if (it.id == sel.id) it.copy(locked = !it.locked) else it })
            "opening" -> plan.copy(openings = plan.openings.map { if (it.id == sel.id) it.copy(locked = !it.locked) else it })
            else -> plan
        }
        onPlanChange(next.copy(revision = plan.revision + 1))
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("التعديل", fontSize = 24.sp, fontWeight = FontWeight.Black, color = WInk)
                Text("الحالي بالرمادي • اقتراح HAI بالبنفسجي قبل التطبيق", fontSize = 10.sp, color = WMuted)
            }
            Surface(color = WViolet.copy(alpha = .10f), shape = RoundedCornerShape(15.dp)) {
                Column(Modifier.padding(horizontal = 11.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${score.overall}", color = WViolet, fontWeight = FontWeight.Black, fontSize = 16.sp)
                    Text("تقييم", color = WMuted, fontSize = 8.5.sp)
                }
            }
        }

        Spacer(Modifier.height(9.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            Box(Modifier.fillMaxSize().padding(8.dp)) {
                EditorPlanCanvas(
                    base = plan,
                    preview = preview,
                    selected = selection,
                    onSelect = { selection = it }
                )
                if (preview != null) {
                    Surface(
                        color = WViolet,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                    ) {
                        Text(
                            "معاينة HAI — لم تُطبّق",
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 9.5.sp,
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        selection?.let { sel ->
            val title = selectionTitle(plan, sel)
            Surface(color = WCard, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(34.dp).background(WViolet.copy(alpha = .10f), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.TouchApp, null, tint = WViolet, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(title, fontWeight = FontWeight.Black, fontSize = 11.5.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = ::toggleLock) { Text("قفل/فتح", fontSize = 9.5.sp) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        EditorAction(Modifier.weight(1f), Icons.Rounded.OpenWith, "تحريك") { buildManualProposal("MOVE") }
                        if (sel.kind == "room") {
                            EditorAction(Modifier.weight(1f), Icons.Rounded.ZoomOutMap, "تكبير") { buildManualProposal("EXPAND") }
                            EditorAction(Modifier.weight(1f), Icons.Rounded.ZoomInMap, "تصغير") { buildManualProposal("SHRINK") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(7.dp))
        }

        proposal?.let { p ->
            Surface(
                color = if (p.updatedPlan != null) WViolet.copy(alpha = .10f) else WOrange.copy(alpha = .11f),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = WViolet)
                        Spacer(Modifier.width(7.dp))
                        Text("اقتراح HAI", fontWeight = FontWeight.Black, fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        Text("${p.confidence}%", color = WMuted, fontSize = 9.5.sp)
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        p.message.ifBlank { haiMessage ?: "مراجعة مكتملة." },
                        color = WInk,
                        fontSize = 10.5.sp,
                        lineHeight = 15.sp,
                        maxLines = 4
                    )
                    validation?.errors?.firstOrNull()?.let {
                        Text("اعتراض: $it", color = WOrange, fontSize = 9.5.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                localProposal = null
                                onDismissHai()
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("رفض") }
                        Button(
                            onClick = {
                                p.updatedPlan?.let { onPlanChange(it.copy(revision = plan.revision + 1)) }
                                localProposal = null
                                onDismissHai()
                            },
                            enabled = p.updatedPlan != null && validation?.valid != false,
                            modifier = Modifier.weight(1f)
                        ) { Text("تطبيق التعديل", fontWeight = FontWeight.Black) }
                    }
                }
            }
        } ?: run {
            Surface(color = WCard, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.TipsAndUpdates, null, tint = WOrange, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("اختر عنصرًا للتعديل، أو اضغط «استدع HAI» ليظهر اعتراضه واقتراحه على المخطط مباشرة.", color = WMuted, fontSize = 9.8.sp, lineHeight = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun EditorAction(modifier: Modifier, icon: ImageVector, text: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.height(42.dp),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 5.dp)
    ) {
        Icon(icon, null, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, fontWeight = FontWeight.Bold, fontSize = 9.sp)
    }
}

@Composable
private fun EditorPlanCanvas(
    base: FloorPlan,
    preview: FloorPlan?,
    selected: EditorSelection?,
    onSelect: (EditorSelection?) -> Unit
) {
    Canvas(
        Modifier.fillMaxSize().pointerInput(base, preview) {
            detectTapGestures { tap ->
                val px = tap.x / size.width.coerceAtLeast(1) * 100f
                val py = tap.y / size.height.coerceAtLeast(1) * 100f
                val source = preview ?: base
                val opening = source.openings.minByOrNull { hypot((it.x - px).toDouble(), (it.y - py).toDouble()) }
                if (opening != null && hypot((opening.x - px).toDouble(), (opening.y - py).toDouble()) <= 5.0) {
                    onSelect(EditorSelection("opening", opening.id)); return@detectTapGestures
                }
                val wall = source.walls.minByOrNull { pointToSegment(px, py, it.start.x, it.start.y, it.end.x, it.end.y) }
                if (wall != null && pointToSegment(px, py, wall.start.x, wall.start.y, wall.end.x, wall.end.y) <= 2.7) {
                    onSelect(EditorSelection("wall", wall.id)); return@detectTapGestures
                }
                val room = source.rooms.lastOrNull { px >= it.x && px <= it.x + it.width && py >= it.y && py <= it.y + it.height }
                onSelect(room?.let { EditorSelection("room", it.id) })
            }
        }
    ) {
        drawPlanLayer(base, selected, Color(0xFF55575A), Color(0xFFB7B2C8), alpha = if (preview == null) 1f else .38f)
        preview?.let { drawPlanLayer(it, selected, WViolet, Color(0xFF8D7CF0), alpha = .92f) }
    }
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
        drawRect(roomColor.copy(alpha = if (isSelected) .28f * alpha else .10f * alpha), topLeft = topLeft, size = roomSize)
        drawRect(
            if (isSelected) WOrange.copy(alpha = alpha) else lineColor.copy(alpha = .42f * alpha),
            topLeft = topLeft,
            size = roomSize,
            style = Stroke(if (isSelected) 3.dp.toPx() else 1.dp.toPx())
        )
    }

    plan.walls.forEach { wall ->
        val isSelected = selected?.kind == "wall" && selected.id == wall.id
        drawLine(
            if (isSelected) WOrange.copy(alpha = alpha) else lineColor.copy(alpha = alpha),
            p(wall.start.x, wall.start.y),
            p(wall.end.x, wall.end.y),
            strokeWidth = (if (isSelected) 4.dp else 2.2.dp).toPx()
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
    "wall" -> plan.walls.firstOrNull { it.id == selection.id }?.let { "جدار • ${it.kind}" } ?: "جدار"
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
