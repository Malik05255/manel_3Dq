package com.manzili.hai

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.manzili.hai.engine.PolygonGeometryEngine
import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.PlanPoint
import kotlin.math.hypot

@Composable
fun PolygonVertexEditorScreen(nav: NavHostController, plan: FloorPlan?, onApply: (FloorPlan) -> Unit) {
    if (plan == null) {
        Surface(Modifier.fillMaxSize()) { Box(Modifier.padding(20.dp)) { Text("لا يوجد مشروع مفتوح") } }
        return
    }
    val base = remember(plan) { PolygonGeometryEngine.normalize(plan) }
    var selectedId by remember(plan.revision) { mutableStateOf(base.rooms.firstOrNull { !it.locked }?.id ?: base.rooms.firstOrNull()?.id) }
    var preview by remember(plan.revision) { mutableStateOf<FloorPlan?>(null) }
    var status by remember(plan.revision) { mutableStateOf("اسحب إحدى نقاط الغرفة. التغيير يبقى معاينة حتى الاعتماد.") }
    val shown = preview ?: base
    val selectedRoom = shown.rooms.firstOrNull { it.id == selectedId }

    Surface(Modifier.fillMaxSize(), color = StudioColors.Canvas) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Outlined.ArrowForward, "رجوع") }
                Column(Modifier.weight(1f)) {
                    Text("تحرير Polygon", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text("تعديل رؤوس الغرف مباشرة • Snap 0.25%", color = Color.Gray, fontSize = 12.sp)
                }
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                items(base.rooms) { room ->
                    FilterChip(
                        selected = selectedId == room.id,
                        onClick = { selectedId = room.id; preview = null; status = if (room.locked) "الغرفة مقفلة؛ لا يمكن تعديل مضلعها." else "اسحب نقطة من حدود «${room.name}»." },
                        label = { Text(room.name, fontSize = 12.sp) },
                        enabled = !room.locked
                    )
                }
            }

            Card(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth().weight(1f), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Canvas(
                    Modifier.fillMaxSize().padding(10.dp).pointerInput(base, selectedId) {
                        var vertexIndex: Int? = null
                        detectDragGestures(
                            onDragStart = { start ->
                                val room = base.rooms.firstOrNull { it.id == selectedId } ?: return@detectDragGestures
                                if (room.locked) { status = "الغرفة مقفلة."; return@detectDragGestures }
                                val px = start.x / size.width.toFloat().coerceAtLeast(1f) * 100f
                                val py = start.y / size.height.toFloat().coerceAtLeast(1f) * 100f
                                val nearest = room.polygon.indices.minByOrNull { i -> hypot((room.polygon[i].x-px).toDouble(), (room.polygon[i].y-py).toDouble()) }
                                val distance = nearest?.let { i -> hypot((room.polygon[i].x-px).toDouble(), (room.polygon[i].y-py).toDouble()) } ?: 999.0
                                vertexIndex = nearest.takeIf { distance <= 7.0 }
                                if (vertexIndex == null) status = "ابدأ السحب من إحدى نقاط المضلع الظاهرة."
                            },
                            onDragCancel = { vertexIndex = null; preview = null },
                            onDragEnd = { vertexIndex = null },
                            onDrag = { change, _ ->
                                val index = vertexIndex ?: return@detectDragGestures
                                val current = preview ?: base
                                val target = PlanPoint(
                                    (change.position.x / size.width.toFloat().coerceAtLeast(1f) * 100f).coerceIn(0f,100f),
                                    (change.position.y / size.height.toFloat().coerceAtLeast(1f) * 100f).coerceIn(0f,100f)
                                )
                                val report = PolygonGeometryEngine.editVertex(current, selectedId ?: return@detectDragGestures, index, target)
                                if (report.valid) {
                                    preview = report.plan
                                    val room = report.plan.rooms.firstOrNull { it.id == selectedId }
                                    status = room?.areaM2?.takeIf { it > 0 }?.let { "معاينة فقط • المساحة ${"%.2f".format(it)}م²" } ?: "معاينة فقط • المقياس غير مؤكد، لذلك لن أخترع مساحة بالمتر."
                                } else {
                                    status = report.errors.firstOrNull() ?: "الحركة غير صالحة هندسيًا."
                                }
                                change.consume()
                            }
                        )
                    }
                ) {
                    fun path(points: List<PlanPoint>): Path = Path().apply {
                        if (points.isNotEmpty()) {
                            moveTo(points[0].x / 100f * size.width, points[0].y / 100f * size.height)
                            points.drop(1).forEach { lineTo(it.x / 100f * size.width, it.y / 100f * size.height) }
                            close()
                        }
                    }
                    shown.footprint.takeIf { it.size >= 3 }?.let { drawPath(path(it), StudioColors.Primary, style = Stroke(width = 3f)) }
                    shown.rooms.forEach { room ->
                        val selected = room.id == selectedId
                        drawPath(path(room.polygon), if (selected) StudioColors.Ink else Color(0xFF9AA39D), style = Stroke(width = if (selected) 5f else 2f))
                        if (selected) room.polygon.forEachIndexed { index, p ->
                            val center = Offset(p.x / 100f * size.width, p.y / 100f * size.height)
                            drawCircle(Color.White, radius = 10f, center = center)
                            drawCircle(StudioColors.Primary, radius = 8f, center = center)
                        }
                    }
                }
            }

            Text(status, fontSize = 12.sp, lineHeight = 20.sp, color = if (preview != null) StudioColors.Muted else Color.DarkGray, modifier = Modifier.padding(vertical = 9.dp))
            selectedRoom?.let { room ->
                Text("${room.name} • ${room.polygon.size} رؤوس${if (room.locked) " • مقفلة" else ""}", fontSize = 12.sp, color = Color.Gray)
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { preview = null; status = "ألغيت المعاينة." }, enabled = preview != null, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Outlined.RestartAlt, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("إلغاء")
                }
                Button(onClick = { preview?.let { onApply(it.copy(revision = plan.revision + 1)); preview = null; status = "تم اعتماد المضلع كنسخة جديدة." } }, enabled = preview != null, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Outlined.Done, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("اعتماد")
                }
            }
        }
    }
}
