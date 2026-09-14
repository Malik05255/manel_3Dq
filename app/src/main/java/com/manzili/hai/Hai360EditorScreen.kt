package com.manzili.hai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manzili.hai.engine.HaiAutoRepairEngine
import com.manzili.hai.engine.PlanVerificationEngine
import com.manzili.hai.engine.RemoteFloorplanEvidenceClient
import com.manzili.hai.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot

private enum class HaiEditorTool { SELECT, ROOM, WALL, DOOR, WINDOW }
private enum class HaiStudioPanel { ORIGINAL, READ }
private data class HaiEditorSelection(val kind: String, val id: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Hai360StudioScreen(
    source: Uri?,
    initialPlan: FloorPlan,
    onBack: () -> Unit,
    onEdit: (FloorPlan) -> Unit,
    onConfirm: (FloorPlan) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val remote = remember { RemoteFloorplanEvidenceClient(context) }
    val snackbar = remember { SnackbarHostState() }

    var plan by remember(initialPlan) { mutableStateOf(PlanVerificationEngine.inspect(initialPlan).plan) }
    val report = remember(plan) { PlanVerificationEngine.inspect(plan) }
    var expanded by remember { mutableStateOf<HaiStudioPanel?>(null) }
    var haiBusy by remember { mutableStateOf(false) }
    var haiStatus by remember { mutableStateOf("") }

    BackHandler(enabled = expanded != null) { expanded = null }

    fun runHaiRepair() {
        if (haiBusy) return
        val uri = source
        if (uri == null) {
            scope.launch { snackbar.showSnackbar("HAI يحتاج المخطط الأصلي حتى يقارن القراءة به.") }
            return
        }
        scope.launch {
            haiBusy = true
            haiStatus = "HAI يفحص الأصل ويقارن الهندسة..."
            try {
                require(remote.available) { "خدمة القراءة السحابية غير مهيأة" }
                val fresh = remote.analyze(uri, maxPdfPages = 8) { update ->
                    haiStatus = when {
                        update.percent < 18 -> "HAI يتصل بمحرك القراءة..."
                        update.percent < 90 -> "HAI يعيد قراءة الأصل بدقة..."
                        else -> "HAI يقارن الجدران والغرف والفتحات..."
                    }
                }.toFloorPlan(plan.title)

                val repaired = HaiAutoRepairEngine.repair(plan, fresh)
                plan = repaired.plan
                haiStatus = ""
                val message = if (repaired.changed) {
                    "${repaired.summary} • ${repaired.beforeConfidence}% ← ${repaired.afterConfidence}%"
                } else {
                    "أعاد HAI فحص الأصل ولم يعتمد تغييرًا غير موثوق • ${repaired.afterConfidence}%"
                }
                snackbar.showSnackbar(message)
            } catch (failure: Throwable) {
                haiStatus = ""
                snackbar.showSnackbar(failure.message ?: "تعذر على HAI إعادة فحص المخطط")
            } finally {
                haiBusy = false
            }
        }
    }

    Scaffold(
        containerColor = H360Ivory,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (expanded == null) {
                TopAppBar(
                    title = {
                        Column {
                            Text("المخطط", color = H360Ink, fontSize = 22.sp, fontWeight = FontWeight.Black)
                            Text(
                                "${plan.rooms.size} غرف  •  ${plan.walls.size} جدار  •  ${report.readingConfidence}%",
                                color = H360Muted,
                                fontSize = 9.sp
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Rounded.ArrowForward, "رجوع", tint = H360Ink)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = H360Paper)
                )
            }
        },
        bottomBar = {
            if (expanded == null) {
                Surface(color = H360Paper, shadowElevation = 10.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onEdit(plan) },
                            border = BorderStroke(1.dp, H360CyanDeep.copy(alpha = .35f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = H360CyanDeep),
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.weight(1f).height(52.dp)
                        ) {
                            Icon(Icons.Rounded.Edit, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("تعديل", fontWeight = FontWeight.Black)
                        }
                        Button(
                            onClick = { onConfirm(report.plan) },
                            enabled = !report.blocking,
                            colors = ButtonDefaults.buttonColors(containerColor = H360CyanDeep, contentColor = Color.White),
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.weight(1.25f).height(52.dp)
                        ) {
                            Icon(Icons.Rounded.Check, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("اعتماد", fontWeight = FontWeight.Black)
                        }
                    }
                }
            } else {
                Surface(color = H360Paper, shadowElevation = 8.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 7.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        OutlinedButton(
                            onClick = { expanded = null },
                            shape = RoundedCornerShape(15.dp),
                            border = BorderStroke(1.dp, H360Line),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 7.dp),
                            modifier = Modifier.height(40.dp)
                        ) {
                            Icon(Icons.Rounded.KeyboardReturn, null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("رجوع للمقارنة", fontSize = 10.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (expanded) {
                null -> PlanComparisonSplit(
                    source = source,
                    plan = plan,
                    report = report,
                    onExpandRead = { expanded = HaiStudioPanel.READ },
                    onExpandOriginal = { expanded = HaiStudioPanel.ORIGINAL }
                )
                HaiStudioPanel.ORIGINAL -> ZoomableFrame {
                    OriginalPlanPreview(source = source, modifier = Modifier.fillMaxSize(), showHint = false)
                }
                HaiStudioPanel.READ -> ZoomableFrame {
                    ReadPlanPreview(plan = plan, modifier = Modifier.fillMaxSize(), showMetrics = true)
                }
            }

            FloatingHaiButton(
                busy = haiBusy,
                onClick = ::runHaiRepair,
                modifier = Modifier.fillMaxSize()
            )

            if (haiBusy && haiStatus.isNotBlank()) {
                Surface(
                    color = H360Paper.copy(alpha = .97f),
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, H360Line),
                    shadowElevation = 4.dp,
                    modifier = Modifier.align(Alignment.TopCenter).padding(10.dp)
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = H360CyanDeep)
                        Spacer(Modifier.width(7.dp))
                        Text(haiStatus, color = H360Ink, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanComparisonSplit(
    source: Uri?,
    plan: FloorPlan,
    report: PlanVerificationEngine.Report,
    onExpandRead: () -> Unit,
    onExpandOriginal: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 9.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ReviewBadge("${report.readingConfidence}%", if (report.readingConfidence >= 90) H360Mint else H360Peach)
            ReviewBadge("${plan.rooms.size} غرف", H360Sky)
            ReviewBadge("${plan.walls.size} جدار", H360Lilac)
        }
        Spacer(Modifier.height(8.dp))

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ComparisonCard(
                    title = "المخطط المقروء",
                    subtitle = "اضغط للتكبير",
                    color = H360Lilac,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = onExpandRead
                ) {
                    ReadPlanPreview(plan = plan, modifier = Modifier.fillMaxSize(), showMetrics = false)
                }
                ComparisonCard(
                    title = "المخطط الأصلي",
                    subtitle = "اضغط للتكبير",
                    color = H360Sky,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = onExpandOriginal
                ) {
                    OriginalPlanPreview(source = source, modifier = Modifier.fillMaxSize(), showHint = false)
                }
            }
        }
    }
}

@Composable
private fun ComparisonCard(
    title: String,
    subtitle: String,
    color: Color,
    modifier: Modifier,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Surface(
        color = H360Paper,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, H360Line),
        shadowElevation = 2.dp,
        modifier = modifier.clickable(onClick = onClick)
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().background(color.copy(alpha = .72f)).padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(title, color = H360Ink, fontSize = 12.sp, fontWeight = FontWeight.Black, maxLines = 1)
                        Text(subtitle, color = H360Muted, fontSize = 8.sp, maxLines = 1)
                    }
                    Icon(Icons.Rounded.OpenInFull, null, tint = H360Ink, modifier = Modifier.size(16.dp))
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            }
        }
    }
}

@Composable
private fun OriginalPlanPreview(source: Uri?, modifier: Modifier = Modifier, showHint: Boolean = true) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, source) {
        value = source?.let { withContext(Dispatchers.IO) { loadEditorBitmap(context, it) } }
    }
    Box(modifier.background(Color.White), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "المخطط الأصلي",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(4.dp)
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.InsertDriveFile, null, tint = H360Muted, modifier = Modifier.size(26.dp))
                Spacer(Modifier.height(6.dp))
                Text("لا يوجد مخطط أصلي", color = H360Muted, fontSize = 9.sp)
            }
        }
        if (showHint && bitmap != null) {
            Text(
                "الأصل",
                color = H360Ink,
                fontSize = 8.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).background(H360Paper.copy(alpha = .85f), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun ReadPlanPreview(plan: FloorPlan, modifier: Modifier = Modifier, showMetrics: Boolean) {
    Box(modifier.background(Color(0xFFF8FAFD))) {
        BlueprintGrid(Modifier.matchParentSize(), dark = false, step = 24f)
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            fun p(x: Float, y: Float) = Offset(size.width * x / 100f, size.height * y / 100f)

            plan.rooms.forEach { room ->
                if (room.polygon.size >= 3) {
                    val path = Path().apply {
                        val first = p(room.polygon.first().x, room.polygon.first().y)
                        moveTo(first.x, first.y)
                        room.polygon.drop(1).forEach { point ->
                            val q = p(point.x, point.y)
                            lineTo(q.x, q.y)
                        }
                        close()
                    }
                    drawPath(path, H360CyanDeep.copy(alpha = .055f))
                    drawPath(path, H360CyanDeep.copy(alpha = .25f), style = Stroke(width = 1.1f))
                } else {
                    drawRect(
                        color = H360CyanDeep.copy(alpha = .055f),
                        topLeft = p(room.x, room.y),
                        size = Size(size.width * room.width / 100f, size.height * room.height / 100f)
                    )
                }
            }

            plan.walls.forEach { wall ->
                drawLine(
                    color = if (wall.kind.contains("hai", true)) H360CyanDeep else H360Ink.copy(alpha = .88f),
                    start = p(wall.start.x, wall.start.y),
                    end = p(wall.end.x, wall.end.y),
                    strokeWidth = if (wall.kind.contains("hai", true)) 4.2f else 3.1f
                )
            }

            plan.openings.forEach { opening ->
                drawCircle(
                    color = if (opening.type.contains("window", true)) Color(0xFF4A8ED8) else H360Amber,
                    radius = 4.5f,
                    center = p(opening.x, opening.y)
                )
            }

            plan.elements.forEach { element ->
                if (element.footprint.size >= 3) {
                    val path = Path().apply {
                        val first = p(element.footprint.first().x, element.footprint.first().y)
                        moveTo(first.x, first.y)
                        element.footprint.drop(1).forEach { point ->
                            val q = p(point.x, point.y)
                            lineTo(q.x, q.y)
                        }
                        close()
                    }
                    drawPath(path, H360Lilac.copy(alpha = .75f))
                    drawPath(path, H360CyanDeep.copy(alpha = .72f), style = Stroke(width = 1.6f))
                }
            }
        }

        if (showMetrics) {
            Row(
                Modifier.align(Alignment.TopStart).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ReviewBadge("${plan.rooms.size} غرف", H360Sky)
                ReviewBadge("${plan.walls.size} جدار", H360Lilac)
                ReviewBadge("${plan.openings.size} فتحة", H360Peach)
            }
        }
    }
}

@Composable
private fun ZoomableFrame(content: @Composable () -> Unit) {
    var measured by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        Modifier.fillMaxSize()
            .background(Color(0xFFF2F5F9))
            .onSizeChanged { measured = it }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 7f)
                    val maxX = measured.width * (scale - 1f) / 2f + measured.width * .25f
                    val maxY = measured.height * (scale - 1f) / 2f + measured.height * .25f
                    offset = Offset(
                        (offset.x + pan.x).coerceIn(-maxX, maxX),
                        (offset.y + pan.y).coerceIn(-maxY, maxY)
                    )
                }
            }
            .pointerInput(scale) {
                detectTapGestures(onDoubleTap = {
                    scale = 1f
                    offset = Offset.Zero
                })
            }
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
        ) { content() }

        Surface(
            color = H360Paper.copy(alpha = .94f),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, H360Line),
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
        ) {
            Text(
                "${(scale * 100).toInt()}% • نقرتان للملاءمة",
                color = H360Muted,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun ReviewBadge(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = .96f),
        shape = RoundedCornerShape(50.dp),
        border = BorderStroke(1.dp, H360Line),
        shadowElevation = 1.dp
    ) {
        Text(
            text,
            color = H360Ink,
            fontSize = 9.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Hai360EditorScreen(
    source: Uri?,
    initialPlan: FloorPlan,
    onCancel: () -> Unit,
    onDone: (FloorPlan) -> Unit
) {
    var plan by remember(initialPlan) { mutableStateOf(PlanVerificationEngine.inspect(initialPlan).plan) }
    var tool by remember { mutableStateOf(HaiEditorTool.SELECT) }
    var selected by remember { mutableStateOf<HaiEditorSelection?>(null) }
    var firstWallPoint by remember { mutableStateOf<PlanPoint?>(null) }
    val undo = remember { mutableStateListOf<FloorPlan>() }
    val report = remember(plan) { PlanVerificationEngine.inspect(plan) }

    fun commit(next: FloorPlan) {
        undo += plan
        if (undo.size > 40) undo.removeAt(0)
        plan = PlanVerificationEngine.inspect(next.copy(revision = maxOf(plan.revision + 1, next.revision))).plan
    }

    Scaffold(
        containerColor = H360Ivory,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("التعديل", color = H360Ink, fontSize = 22.sp, fontWeight = FontWeight.Black)
                        Text(
                            "${plan.rooms.size} غرف  •  ${plan.walls.size} جدار  •  ${report.readingConfidence}%",
                            color = H360Muted,
                            fontSize = 9.sp
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, "إلغاء", tint = H360Ink) } },
                actions = {
                    IconButton(
                        onClick = {
                            val previous = undo.removeLastOrNull()
                            if (previous != null) {
                                plan = previous
                                selected = null
                                firstWallPoint = null
                            }
                        },
                        enabled = undo.isNotEmpty()
                    ) { Icon(Icons.Rounded.Undo, "تراجع") }
                    TextButton(onClick = { onDone(PlanVerificationEngine.inspect(plan).plan) }) {
                        Text("تم", color = H360CyanDeep, fontWeight = FontWeight.Black, fontSize = 15.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = H360Paper)
            )
        },
        bottomBar = {
            EditorToolbar(
                tool = tool,
                selection = selected,
                onTool = {
                    tool = it
                    firstWallPoint = null
                    if (it != HaiEditorTool.SELECT) selected = null
                },
                onDelete = {
                    val s = selected
                    if (s != null) {
                        val next = when (s.kind) {
                            "room" -> plan.copy(rooms = plan.rooms.filterNot { it.id == s.id })
                            "wall" -> plan.copy(
                                walls = plan.walls.filterNot { it.id == s.id },
                                openings = plan.openings.filterNot { it.wallId == s.id }
                            )
                            "opening" -> plan.copy(openings = plan.openings.filterNot { it.id == s.id })
                            else -> plan
                        }
                        commit(next)
                        selected = null
                    }
                }
            )
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            EditorCanvas(
                source = source,
                plan = plan,
                tool = tool,
                selection = selected,
                firstWallPoint = firstWallPoint,
                onTap = { x, y ->
                    when (tool) {
                        HaiEditorTool.SELECT -> selected = selectEditorElement(plan, x, y)
                        HaiEditorTool.ROOM -> {
                            val rw = 16f
                            val rh = 12f
                            val room = Room(
                                id = "manual-room-${System.nanoTime()}",
                                name = "غرفة ${plan.rooms.size + 1}",
                                type = "generic",
                                x = (x - rw / 2f).coerceIn(0f, 100f - rw),
                                y = (y - rh / 2f).coerceIn(0f, 100f - rh),
                                width = rw,
                                height = rh,
                                areaM2 = editorRoomArea(plan, rw, rh),
                                confidence = 100
                            )
                            commit(plan.copy(rooms = plan.rooms + room))
                            selected = HaiEditorSelection("room", room.id)
                            tool = HaiEditorTool.SELECT
                        }
                        HaiEditorTool.WALL -> {
                            val start = firstWallPoint
                            if (start == null) {
                                firstWallPoint = PlanPoint(x, y)
                            } else {
                                val wall = Wall(
                                    id = "manual-wall-${System.nanoTime()}",
                                    start = start,
                                    end = PlanPoint(x, y),
                                    thicknessCm = 15.0,
                                    kind = "manual",
                                    confidence = 100
                                )
                                commit(plan.copy(walls = plan.walls + wall))
                                selected = HaiEditorSelection("wall", wall.id)
                                firstWallPoint = null
                                tool = HaiEditorTool.SELECT
                            }
                        }
                        HaiEditorTool.DOOR, HaiEditorTool.WINDOW -> {
                            val wall = plan.walls.minByOrNull {
                                editorSegmentDistance(x, y, it.start.x, it.start.y, it.end.x, it.end.y)
                            }
                            val opening = Opening(
                                id = "manual-opening-${System.nanoTime()}",
                                type = if (tool == HaiEditorTool.WINDOW) "window" else "door",
                                x = x,
                                y = y,
                                width = if (tool == HaiEditorTool.WINDOW) 5f else 4f,
                                wallId = wall?.id,
                                confidence = 100
                            )
                            commit(plan.copy(openings = plan.openings + opening))
                            selected = HaiEditorSelection("opening", opening.id)
                            tool = HaiEditorTool.SELECT
                        }
                    }
                }
            )

            Surface(
                color = H360Paper.copy(alpha = .96f),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, H360Line),
                shadowElevation = 2.dp,
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
            ) {
                Row(Modifier.padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(if (report.blocking) H360Amber else H360Success, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(if (report.blocking) "مراجعة" else "جاهز", color = H360Ink, fontSize = 9.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

@Composable
private fun EditorToolbar(
    tool: HaiEditorTool,
    selection: HaiEditorSelection?,
    onTool: (HaiEditorTool) -> Unit,
    onDelete: () -> Unit
) {
    Surface(color = H360Paper, shadowElevation = 10.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EditorToolButton("تحديد", Icons.Rounded.NearMe, tool == HaiEditorTool.SELECT) { onTool(HaiEditorTool.SELECT) }
            EditorToolButton("غرفة", Icons.Rounded.CropSquare, tool == HaiEditorTool.ROOM) { onTool(HaiEditorTool.ROOM) }
            EditorToolButton("جدار", Icons.Rounded.HorizontalRule, tool == HaiEditorTool.WALL) { onTool(HaiEditorTool.WALL) }
            EditorToolButton("باب", Icons.Rounded.DoorFront, tool == HaiEditorTool.DOOR) { onTool(HaiEditorTool.DOOR) }
            EditorToolButton("نافذة", Icons.Rounded.Window, tool == HaiEditorTool.WINDOW) { onTool(HaiEditorTool.WINDOW) }
            if (selection != null) {
                FilledTonalButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFFFFE9E7), contentColor = H360Danger),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 13.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Rounded.DeleteOutline, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("حذف", fontWeight = FontWeight.Black, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun EditorToolButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (active) H360CyanDeep else H360Sky,
            contentColor = if (active) Color.White else H360Ink
        ),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 8.dp)
    ) {
        Icon(icon, null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, fontWeight = FontWeight.Black, fontSize = 10.sp)
    }
}

@Composable
private fun EditorCanvas(
    source: Uri?,
    plan: FloorPlan,
    tool: HaiEditorTool,
    selection: HaiEditorSelection?,
    firstWallPoint: PlanPoint?,
    onTap: (Float, Float) -> Unit
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, source) {
        value = source?.let { withContext(Dispatchers.IO) { loadEditorBitmap(context, it) } }
    }
    var measured by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    fun fittedRect(width: Float, height: Float): FloatArray {
        val bmp = bitmap
        if (bmp == null || bmp.width <= 0 || bmp.height <= 0 || width <= 0f || height <= 0f) {
            return floatArrayOf(0f, 0f, width, height)
        }
        val imageRatio = bmp.width.toFloat() / bmp.height
        val boxRatio = width / height
        return if (imageRatio > boxRatio) {
            val h = width / imageRatio
            floatArrayOf(0f, (height - h) / 2f, width, h)
        } else {
            val w = height * imageRatio
            floatArrayOf((width - w) / 2f, 0f, w, height)
        }
    }

    Box(
        Modifier.fillMaxSize()
            .background(Color(0xFFF3F6FA))
            .onSizeChanged { measured = it }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.7f, 6f)
                    val maxX = measured.width * (scale - 1f) / 2f + measured.width * .45f
                    val maxY = measured.height * (scale - 1f) / 2f + measured.height * .45f
                    offset = Offset(
                        (offset.x + pan.x).coerceIn(-maxX, maxX),
                        (offset.y + pan.y).coerceIn(-maxY, maxY)
                    )
                }
            }
            .pointerInput(tool, scale, offset, bitmap) {
                detectTapGestures(
                    onDoubleTap = {
                        scale = 1f
                        offset = Offset.Zero
                    },
                    onTap = { pos ->
                        val w = measured.width.toFloat().coerceAtLeast(1f)
                        val h = measured.height.toFloat().coerceAtLeast(1f)
                        val center = Offset(w / 2f, h / 2f)
                        val local = (pos - center - offset) / scale + center
                        val rect = fittedRect(w, h)
                        val insideX = local.x >= rect[0] && local.x <= rect[0] + rect[2]
                        val insideY = local.y >= rect[1] && local.y <= rect[1] + rect[3]
                        if (insideX && insideY) {
                            val x = ((local.x - rect[0]) / rect[2] * 100f).coerceIn(0f, 100f)
                            val y = ((local.y - rect[1]) / rect[3] * 100f).coerceIn(0f, 100f)
                            onTap(x, y)
                        }
                    }
                )
            }
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
        ) {
            BlueprintGrid(Modifier.matchParentSize(), dark = false, step = 28f)
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    alpha = .62f,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Canvas(Modifier.fillMaxSize()) {
                val rect = fittedRect(size.width, size.height)
                fun p(x: Float, y: Float) = Offset(rect[0] + rect[2] * x / 100f, rect[1] + rect[3] * y / 100f)
                fun sz(w: Float, h: Float) = Size(rect[2] * w / 100f, rect[3] * h / 100f)

                plan.rooms.forEach { room ->
                    val active = selection?.kind == "room" && selection.id == room.id
                    drawRect(
                        color = if (active) H360CyanDeep.copy(alpha = .20f) else H360CyanDeep.copy(alpha = .055f),
                        topLeft = p(room.x, room.y),
                        size = sz(room.width, room.height)
                    )
                    drawRect(
                        color = if (active) H360CyanDeep else H360CyanDeep.copy(alpha = .28f),
                        topLeft = p(room.x, room.y),
                        size = sz(room.width, room.height),
                        style = Stroke(width = if (active) 3.2f else 1.2f)
                    )
                }
                plan.walls.forEach { wall ->
                    val active = selection?.kind == "wall" && selection.id == wall.id
                    drawLine(
                        color = if (active) H360CyanDeep else H360Ink.copy(alpha = .86f),
                        start = p(wall.start.x, wall.start.y),
                        end = p(wall.end.x, wall.end.y),
                        strokeWidth = if (active) 6.5f else 3.4f
                    )
                }
                plan.openings.forEach { opening ->
                    val active = selection?.kind == "opening" && selection.id == opening.id
                    drawCircle(
                        color = if (active) H360CyanDeep else if (opening.type.contains("window", true)) Color(0xFF4A8ED8) else H360Amber,
                        radius = if (active) 7f else 4.8f,
                        center = p(opening.x, opening.y)
                    )
                }
                firstWallPoint?.let {
                    drawCircle(H360CyanDeep, 8f, p(it.x, it.y), style = Stroke(width = 3f))
                }
            }
        }

        Surface(
            color = H360Paper.copy(alpha = .94f),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, H360Line),
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { scale = (scale / 1.35f).coerceAtLeast(.7f) }) { Icon(Icons.Rounded.Remove, "تصغير") }
                Text("${(scale * 100).toInt()}%", fontSize = 9.sp, fontWeight = FontWeight.Black, color = H360Ink)
                IconButton(onClick = { scale = (scale * 1.35f).coerceAtMost(6f) }) { Icon(Icons.Rounded.Add, "تكبير") }
                IconButton(onClick = { scale = 1f; offset = Offset.Zero }) { Icon(Icons.Rounded.CenterFocusStrong, "ملاءمة") }
            }
        }
    }
}

private fun selectEditorElement(plan: FloorPlan, x: Float, y: Float): HaiEditorSelection? {
    val opening = plan.openings.minByOrNull { hypot((it.x - x).toDouble(), (it.y - y).toDouble()) }
    if (opening != null && hypot((opening.x - x).toDouble(), (opening.y - y).toDouble()) <= 4.5) {
        return HaiEditorSelection("opening", opening.id)
    }
    val wall = plan.walls.minByOrNull { editorSegmentDistance(x, y, it.start.x, it.start.y, it.end.x, it.end.y) }
    if (wall != null && editorSegmentDistance(x, y, wall.start.x, wall.start.y, wall.end.x, wall.end.y) <= 2.8) {
        return HaiEditorSelection("wall", wall.id)
    }
    return plan.rooms.lastOrNull {
        x in it.x..(it.x + it.width) && y in it.y..(it.y + it.height)
    }?.let { HaiEditorSelection("room", it.id) }
}

private fun editorRoomArea(plan: FloorPlan, widthPct: Float, heightPct: Float): Double {
    val w = plan.widthM ?: return 0.0
    val h = plan.heightM ?: return 0.0
    return (w * widthPct / 100.0) * (h * heightPct / 100.0)
}

private fun editorSegmentDistance(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Double {
    val dx = (x2 - x1).toDouble()
    val dy = (y2 - y1).toDouble()
    if (dx == 0.0 && dy == 0.0) return hypot((px - x1).toDouble(), (py - y1).toDouble())
    val t = (((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
    return hypot(px - (x1 + t * dx), py - (y1 + t * dy))
}

private fun loadEditorBitmap(context: Context, source: Uri): Bitmap? = runCatching {
    val type = context.contentResolver.getType(source).orEmpty()
    if (type == "application/pdf") {
        val pfd = context.contentResolver.openFileDescriptor(source, "r") ?: return@runCatching null
        PdfRenderer(pfd).use { renderer ->
            if (renderer.pageCount <= 0) return@use null
            renderer.openPage(0).use { page ->
                val target = 2400f
                val ratio = target / page.width.coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(
                    target.toInt(),
                    (page.height * ratio).toInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }
    } else {
        context.contentResolver.openInputStream(source).use(BitmapFactory::decodeStream)
    }
}.getOrNull()
