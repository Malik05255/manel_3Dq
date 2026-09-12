package com.manzili.hai

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Cached
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.manzili.hai.engine.Architectural3DEnhancementEngine
import com.manzili.hai.engine.OpeningVerticalProfileEngine
import com.manzili.hai.engine.Semantic3DEngine
import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.Opening
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private data class PreviewPoint(val x: Float, val y: Float, val depth: Double)
private data class PreviewFace(val points: List<PreviewPoint>, val fill: Color, val depth: Double)

@Composable
fun Semantic3DScreen(
    nav: NavHostController,
    plan: FloorPlan?,
    onPlanChanged: (FloorPlan) -> Unit = {}
) {
    val scene = remember(plan) { plan?.let { Architectural3DEnhancementEngine.build(it) } }
    var yaw by remember { mutableFloatStateOf(-34f) }
    var pitch by remember { mutableFloatStateOf(34f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var selectedFloor by remember { mutableStateOf<String?>(null) }
    var editOpeningId by remember { mutableStateOf<String?>(null) }
    val allOpenings = remember(plan) {
        plan?.let { (it.openings + it.floors.flatMap { floor -> floor.openings }).distinctBy { opening -> opening.id } }.orEmpty()
    }

    Surface(Modifier.fillMaxSize(), color = Color(0xFFF7F4EE)) {
        Column(
            Modifier.fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Rounded.ArrowForward, "رجوع") }
                Box(
                    Modifier.size(42.dp).background(Color(0xFF27312C), RoundedCornerShape(13.dp)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.ViewInAr, null, tint = Color.White) }
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text("المجسم المعماري", fontWeight = FontWeight.Black, fontSize = 19.sp)
                    Text("نفس Geometry V3 • أسقف • أبواب • نوافذ", color = Color.Gray, fontSize = 9.5.sp)
                }
                IconButton(onClick = { yaw = -34f; pitch = 34f; zoom = 1f; selectedFloor = null }) {
                    Icon(Icons.Rounded.Cached, "إعادة العرض")
                }
            }

            if (scene == null || plan == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("افتح مشروعًا أولًا") }
                return@Column
            }

            val verifiedVertical = scene.openings.count { it.verticalVerified }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MetricChip("${scene.floorIds.size}", "دور", Modifier.weight(1f))
                MetricChip("${scene.meshes.count { it.kind == "wall" }}", "جدار", Modifier.weight(1f))
                MetricChip("$verifiedVertical/${scene.openings.size}", "ارتفاع", Modifier.weight(1f))
                MetricChip(if (scene.metricReady) "m" else "نسبي", "المقياس", Modifier.weight(1f))
            }
            Spacer(Modifier.height(9.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFEFA)),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                Box(Modifier.fillMaxSize()) {
                    SemanticSceneCanvas(
                        scene = scene,
                        floorId = selectedFloor,
                        yaw = yaw,
                        pitch = pitch,
                        zoom = zoom,
                        onGesture = { dx, dy, scale, rotation ->
                            yaw += rotation + dx * 0.10f
                            pitch = (pitch + dy * 0.08f).coerceIn(10f, 78f)
                            zoom = (zoom * scale).coerceIn(0.45f, 4.5f)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    Surface(
                        color = Color(0xDD27312C),
                        shape = RoundedCornerShape(50.dp),
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
                    ) {
                        Text(
                            "اسحب للدوران • قرّب بإصبعين",
                            color = Color.White,
                            fontSize = 9.sp,
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScrollCompat(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = selectedFloor == null,
                    onClick = { selectedFloor = null },
                    label = { Text("كل الأدوار", fontSize = 9.sp) }
                )
                scene.floorIds.forEachIndexed { index, id ->
                    val name = plan.floors.firstOrNull { it.id == id }?.name ?: if (index == 0) "الأرضي" else "الدور ${index + 1}"
                    FilterChip(
                        selected = selectedFloor == id,
                        onClick = { selectedFloor = id },
                        label = { Text(name, fontSize = 9.sp) }
                    )
                }
            }

            if (allOpenings.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScrollCompat().padding(top = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    allOpenings.forEach { opening ->
                        val profile = OpeningVerticalProfileEngine.profile(plan, opening)
                        AssistChip(
                            onClick = { editOpeningId = opening.id },
                            label = { Text("${opening.id} ${if (profile.verified) "✓" else "ارتفاع؟"}", fontSize = 8.5.sp) },
                            leadingIcon = {
                                Icon(if (profile.verified) Icons.Rounded.Verified else Icons.Rounded.Edit, null, Modifier.size(14.dp))
                            }
                        )
                    }
                }
            }

            val primaryWarning = scene.warnings.firstOrNull()
            Surface(
                color = if (scene.metricReady && verifiedVertical == scene.openings.size) Color(0xFFE9E5DC) else Color(0xFFFFF1D6),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 8.dp)
            ) {
                Text(
                    primaryWarning ?: "المجسم مرتبط بالمخطط نفسه؛ السقف والفتحات يعاد بناؤها تلقائيًا من نفس البيانات الهندسية.",
                    fontSize = 9.5.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
        }
    }

    val editOpening = allOpenings.firstOrNull { it.id == editOpeningId }
    if (editOpening != null && plan != null) {
        OpeningHeightDialog(
            plan = plan,
            opening = editOpening,
            onDismiss = { editOpeningId = null },
            onSaved = { updated ->
                onPlanChanged(updated.copy(revision = plan.revision + 1))
                editOpeningId = null
            }
        )
    }
}

@Composable
private fun OpeningHeightDialog(
    plan: FloorPlan,
    opening: Opening,
    onDismiss: () -> Unit,
    onSaved: (FloorPlan) -> Unit
) {
    val profile = OpeningVerticalProfileEngine.profile(plan, opening)
    val window = OpeningVerticalProfileEngine.isWindow(opening.type)
    var height by remember(opening.id, profile.heightM) { mutableStateOf(profile.heightM?.let { "%.2f".format(it) } ?: "") }
    var sill by remember(opening.id, profile.sillHeightM) { mutableStateOf(if (window) profile.sillHeightM?.let { "%.2f".format(it) } ?: "" else "0") }
    val heightM = parse3dMetric(height)
    val sillM = parse3dMetric(sill)
    val ready = heightM?.let { it in 0.4..6.0 } == true && (!window || sillM?.let { it in 0.0..3.5 } == true)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("قياس ${if (window) "النافذة" else "الباب"} ${opening.id}") },
        text = {
            Column {
                Text("أدخل القياس فقط إذا كان معروفًا أو ظاهرًا في المصدر. بدون تأكيد يستخدم 3D قيمة معاينة فقط، وIFC لا يعتمدها.", fontSize = 10.sp, color = Color.Gray)
                OutlinedTextField(height, { height = it }, label = { Text("الارتفاع بالمتر") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                if (window) OutlinedTextField(sill, { sill = it }, label = { Text("ارتفاع الجلسة بالمتر") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                if (profile.verified) Text("القياس الحالي مؤكد ويمكن تعديله أو مسحه.", color = Color(0xFF56705E), fontSize = 9.sp, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = {
            TextButton(
                enabled = ready,
                onClick = {
                    if (heightM != null) onSaved(OpeningVerticalProfileEngine.confirm(plan, opening.id, heightM, if (window) sillM else null))
                }
            ) { Text("تأكيد") }
        },
        dismissButton = {
            Row {
                if (profile.heightM != null || profile.sillHeightM != null) {
                    TextButton(onClick = { onSaved(OpeningVerticalProfileEngine.clear(plan, opening.id)) }) { Text("مسح القياس") }
                }
                TextButton(onClick = onDismiss) { Text("إلغاء") }
            }
        }
    )
}

private fun parse3dMetric(raw: String): Double? {
    val western = buildString {
        raw.trim().forEach { ch ->
            append(when (ch) {
                '٠' -> '0'; '١' -> '1'; '٢' -> '2'; '٣' -> '3'; '٤' -> '4'; '٥' -> '5'
                '٦' -> '6'; '٧' -> '7'; '٨' -> '8'; '٩' -> '9'; '٫', ',' -> '.'
                else -> ch
            })
        }
    }
    return western.toDoubleOrNull()
}

@Composable
private fun MetricChip(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(color = Color(0xFFE9E5DC), shape = RoundedCornerShape(13.dp), modifier = modifier) {
        Column(Modifier.padding(vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontWeight = FontWeight.Black, fontSize = 12.sp)
            Text(label, color = Color.Gray, fontSize = 7.5.sp)
        }
    }
}

@Composable
private fun SemanticSceneCanvas(
    scene: Semantic3DEngine.Scene,
    floorId: String?,
    yaw: Float,
    pitch: Float,
    zoom: Float,
    onGesture: (dx: Float, dy: Float, scale: Float, rotation: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val meshes = remember(scene, floorId) { scene.meshes.filter { floorId == null || it.floorId == floorId } }
    Canvas(
        modifier.pointerInput(scene, floorId) {
            detectTransformGestures { _, pan, scale, rotation -> onGesture(pan.x, pan.y, scale, rotation) }
        }
    ) {
        val vertices = meshes.flatMap { it.vertices }
        if (vertices.isEmpty()) return@Canvas
        val cx = (vertices.minOf { it.x } + vertices.maxOf { it.x }) / 2.0
        val cy = (vertices.minOf { it.y } + vertices.maxOf { it.y }) / 2.0
        val cz = (vertices.minOf { it.z } + vertices.maxOf { it.z }) / 2.0
        val yr = yaw / 180.0 * PI
        val pr = pitch / 180.0 * PI
        val cosY = cos(yr); val sinY = sin(yr)
        val cosP = cos(pr); val sinP = sin(pr)

        fun raw(v: Semantic3DEngine.Vec3): PreviewPoint {
            val x = v.x - cx
            val y = v.y - cy
            val z = v.z - cz
            val rx = x * cosY - y * sinY
            val ry = x * sinY + y * cosY
            val sy = ry * sinP - z * cosP
            val depth = ry * cosP + z * sinP
            return PreviewPoint(rx.toFloat(), sy.toFloat(), depth)
        }

        val allRaw = vertices.map(::raw)
        val minX = allRaw.minOf { it.x }; val maxX = allRaw.maxOf { it.x }
        val minY = allRaw.minOf { it.y }; val maxY = allRaw.maxOf { it.y }
        val spanX = (maxX - minX).coerceAtLeast(1f)
        val spanY = (maxY - minY).coerceAtLeast(1f)
        val fit = min(size.width / spanX, size.height / spanY) * 0.78f * zoom
        val midX = (minX + maxX) / 2f
        val midY = (minY + maxY) / 2f

        fun screen(p: PreviewPoint) = PreviewPoint(
            x = size.width / 2f + (p.x - midX) * fit,
            y = size.height / 2f + (p.y - midY) * fit,
            depth = p.depth
        )

        val faces = mutableListOf<PreviewFace>()
        meshes.forEach { mesh ->
            val fill = when (mesh.kind) {
                "wall" -> Color(0xFFE6DED2)
                "slab" -> Color(0xFFB8BBB7)
                "structural" -> Color(0xFF9A7447)
                "door" -> Color(0xFF6F4E37)
                "window" -> Color(0xFF7EB5C8)
                "roof" -> Color(0xFF8B8175)
                else -> Color(0xFFD7D3CB)
            }
            val alpha = if (mesh.kind == "window") 0.55f else 0.86f
            val projected = mesh.vertices.map { screen(raw(it)) }
            mesh.faces.forEach { face ->
                val pts = face.indices.mapNotNull { projected.getOrNull(it) }
                if (pts.size >= 3) faces += PreviewFace(pts, fill.copy(alpha = alpha), pts.map { it.depth }.average())
            }
        }

        faces.sortedBy { it.depth }.forEach { face ->
            val path = Path().apply {
                moveTo(face.points.first().x, face.points.first().y)
                face.points.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            drawPath(path, face.fill)
            drawPath(path, Color(0xFF3C423E).copy(alpha = 0.50f), style = Stroke(width = 1.0f))
        }
    }
}

@Composable
private fun Modifier.horizontalScrollCompat(): Modifier {
    val state = rememberScrollState()
    return this.then(Modifier.horizontalScroll(state))
}
