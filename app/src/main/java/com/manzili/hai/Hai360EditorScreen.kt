package com.manzili.hai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manzili.hai.engine.PlanVerificationEngine
import com.manzili.hai.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.hypot

private enum class EditorTool { SELECT, ROOM, WALL, DOOR, WINDOW }
private data class EditorSelection(val kind: String, val id: String)

@Composable
internal fun Hai360StudioScreen(
    source: Uri?,
    initialPlan: FloorPlan,
    onBack: () -> Unit,
    onEdit: (FloorPlan) -> Unit,
    onConfirm: (FloorPlan) -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        Hai360StudioScreen(source, initialPlan, onBack, onConfirm)
        ExtendedFloatingActionButton(
            onClick = { onEdit(PlanVerificationEngine.inspect(initialPlan).plan) },
            containerColor = H360CyanDeep,
            contentColor = Color.White,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 18.dp, bottom = 88.dp)
        ) {
            Icon(Icons.Rounded.Edit, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text("تعديل", fontWeight = FontWeight.Black)
        }
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
    val context = LocalContext.current
    var plan by remember(initialPlan) { mutableStateOf(PlanVerificationEngine.inspect(initialPlan).plan) }
    var tool by remember { mutableStateOf(EditorTool.SELECT) }
    var selected by remember { mutableStateOf<EditorSelection?>(null) }
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
                        Text("${plan.rooms.size} غرف  •  ${plan.walls.size} جدار  •  ${report.readingConfidence}%", color = H360Muted, fontSize = 9.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, "إلغاء", tint = H360Ink) }
                },
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
                    if (it != EditorTool.SELECT) selected = null
                },
                onDelete = {
                    val s = selected ?: return@EditorToolbar
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
                        EditorTool.SELECT -> selected = selectEditorElement(plan, x, y)
                        EditorTool.ROOM -> {
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
                            selected = EditorSelection("room", room.id)
                            tool = EditorTool.SELECT
                        }
                        EditorTool.WALL -> {
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
                                selected = EditorSelection("wall", wall.id)
                                firstWallPoint = null
                                tool = EditorTool.SELECT
                            }
                        }
                        EditorTool.DOOR, EditorTool.WINDOW -> {
                            val wall = plan.walls.minByOrNull {
                                editorSegmentDistance(x, y, it.start.x, it.start.y, it.end.x, it.end.y)
                            }
                            val opening = Opening(
                                id = "manual-opening-${System.nanoTime()}",
                                type = if (tool == EditorTool.WINDOW) "window" else "door",
                                x = x,
                                y = y,
                                width = if (tool == EditorTool.WINDOW) 5f else 4f,
                                wallId = wall?.id,
                                confidence = 100
                            )
                            commit(plan.copy(openings = plan.openings + opening))
                            selected = EditorSelection("opening", opening.id)
                            tool = EditorTool.SELECT
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
    tool: EditorTool,
    selection: EditorSelection?,
    onTool: (EditorTool) -> Unit,
    onDelete: () -> Unit
) {
    Surface(color = H360Paper, shadowElevation = 10.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EditorToolButton("تحديد", Icons.Rounded.NearMe, tool == EditorTool.SELECT) { onTool(EditorTool.SELECT) }
            EditorToolButton("غرفة", Icons.Rounded.CropSquare, tool == EditorTool.ROOM) { onTool(EditorTool.ROOM) }
            EditorToolButton("جدار", Icons.Rounded.HorizontalRule, tool == EditorTool.WALL) { onTool(EditorTool.WALL) }
            EditorToolButton("باب", Icons.Rounded.DoorFront, tool == EditorTool.DOOR) { onTool(EditorTool.DOOR) }
            EditorToolButton("نافذة", Icons.Rounded.Window, tool == EditorTool.WINDOW) { onTool(EditorTool.WINDOW) }
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
private fun EditorToolButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, active: Boolean, onClick: () -> Unit) {
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
    tool: EditorTool,
    selection: EditorSelection?,
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
        if (bmp == null || bmp.width <= 0 || bmp.height <= 0 || width <= 0f || height <= 0f) return floatArrayOf(0f, 0f, width, height)
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
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF3F6FA))
            .onSizeChanged { measured = it }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.7f, 6f)
                    offset += pan
                    val maxX = measured.width * (scale - 1f) / 2f + measured.width * .45f
                    val maxY = measured.height * (scale - 1f) / 2f + measured.height * .45f
                    offset = Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
                }
            }
            .pointerInput(tool, scale, offset, bitmap) {
                detectTapGestures(onDoubleTap = {
                    scale = 1f
                    offset = Offset.Zero
                }) { pos ->
                    val w = measured.width.toFloat().coerceAtLeast(1f)
                    val h = measured.height.toFloat().coerceAtLeast(1f)
                    val center = Offset(w / 2f, h / 2f)
                    val local = (pos - center - offset) / scale + center
                    val rect = fittedRect(w, h)
                    val x = ((local.x - rect[0]) / rect[2] * 100f).coerceIn(0f, 100f)
                    val y = ((local.y - rect[1]) / rect[3] * 100f).coerceIn(0f, 100f)
                    if (local.x in rect[0]..(rect[0] + rect[2]) && local.y in rect[1]..(rect[1] + rect[3])) onTap(x, y)
                }
            }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
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
                    alpha = .68f,
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
                firstWallPoint?.let { drawCircle(H360CyanDeep, 8f, p(it.x, it.y), style = Stroke(width = 3f)) }
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

private fun selectEditorElement(plan: FloorPlan, x: Float, y: Float): EditorSelection? {
    val opening = plan.openings.minByOrNull { hypot((it.x - x).toDouble(), (it.y - y).toDouble()) }
    if (opening != null && hypot((opening.x - x).toDouble(), (opening.y - y).toDouble()) <= 4.5) return EditorSelection("opening", opening.id)
    val wall = plan.walls.minByOrNull { editorSegmentDistance(x, y, it.start.x, it.start.y, it.end.x, it.end.y) }
    if (wall != null && editorSegmentDistance(x, y, wall.start.x, wall.start.y, wall.end.x, wall.end.y) <= 2.8) return EditorSelection("wall", wall.id)
    return plan.rooms.lastOrNull { x in it.x..(it.x + it.width) && y in it.y..(it.y + it.height) }?.let { EditorSelection("room", it.id) }
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
                val bitmap = Bitmap.createBitmap(target.toInt(), (page.height * ratio).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }
    } else {
        context.contentResolver.openInputStream(source).use(BitmapFactory::decodeStream)
    }
}.getOrNull()
