package com.manzili.hai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.manzili.hai.engine.PlanVerificationEngine
import com.manzili.hai.model.FloorPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val VSSand = Color(0xFFF7F4EE)
private val VSPaper = Color(0xFFFFFEFA)
private val VSDeep = Color(0xFF27312C)
private val VSBronze = Color(0xFF9A7447)

@Composable
fun PlanVerificationScreen(nav: NavHostController, source: Uri?, plan: FloorPlan?, onConfirm: (FloorPlan) -> Unit) {
    if (plan == null) {
        Surface(color = VSSand, modifier = Modifier.fillMaxSize()) { Box(contentAlignment = Alignment.Center) { Text("لا توجد قراءة للمراجعة") } }
        return
    }
    var working by remember(plan) { mutableStateOf(PlanVerificationEngine.inspect(plan).plan) }
    var width by remember(working.widthM) { mutableStateOf(working.widthM?.let { "%.2f".format(it) } ?: "") }
    var height by remember(working.heightM) { mutableStateOf(working.heightM?.let { "%.2f".format(it) } ?: "") }
    val report = remember(working) { PlanVerificationEngine.inspect(working) }

    Surface(color = VSSand, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Rounded.ArrowForward, "رجوع") }
                Column(Modifier.weight(1f)) {
                    Text("تحقق من القراءة", fontSize = 22.sp, fontWeight = FontWeight.Black)
                    Text("ثقة القراءة ${report.readingConfidence}% • المقياس ${report.scaleConfidence}%", color = Color.Gray, fontSize = 10.sp)
                }
                Surface(color = if (report.blocking) MaterialTheme.colorScheme.errorContainer else VSDeep, shape = RoundedCornerShape(50.dp)) {
                    Text(if (report.blocking) "يحتاج إصلاح" else "جاهز للمراجعة", color = if (report.blocking) MaterialTheme.colorScheme.onErrorContainer else Color.White, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp))
                }
            }

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                if (source != null) VerificationOverlay(source, report.plan)
                Spacer(Modifier.height(12.dp))
                Card(colors = CardDefaults.cardColors(containerColor = VSPaper), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(13.dp)) {
                        Text("المقياس والأبعاد", fontWeight = FontWeight.Black, fontSize = 13.sp)
                        Text("تأكيد العرض والطول يحول الإحداثيات النسبية إلى قياسات مترية موثوقة للمخرجات.", color = Color.Gray, fontSize = 9.5.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 3.dp, bottom = 8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(width, { width = it }, label = { Text("العرض م") }, singleLine = true, modifier = Modifier.weight(1f))
                            OutlinedTextField(height, { height = it }, label = { Text("الطول م") }, singleLine = true, modifier = Modifier.weight(1f))
                        }
                        OutlinedButton(onClick = {
                            val w = width.replace(',', '.').toDoubleOrNull()
                            val h = height.replace(',', '.').toDoubleOrNull()
                            if (w != null && h != null && w > .5 && h > .5) working = PlanVerificationEngine.confirmScale(working, w, h)
                        }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Icon(Icons.Rounded.Straighten, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("تأكيد المقياس")
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text("مناطق تحتاج انتباه", fontWeight = FontWeight.Black, fontSize = 13.sp)
                if (report.issues.isEmpty()) Text("لم يجد المحرك نقاطًا منخفضة الثقة.", color = Color.Gray, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
                report.issues.take(14).forEach { issue ->
                    Card(colors = CardDefaults.cardColors(containerColor = VSPaper), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
                            Icon(if (issue.level == "error") Icons.Rounded.ErrorOutline else Icons.Rounded.HelpOutline, null, tint = if (issue.level == "error") MaterialTheme.colorScheme.error else VSBronze, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(7.dp))
                            Column {
                                Text(issue.title, fontWeight = FontWeight.Bold, fontSize = 10.5.sp)
                                Text(issue.detail, color = Color.Gray, fontSize = 9.5.sp, lineHeight = 14.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            Button(enabled = !report.blocking, onClick = { onConfirm(report.plan) }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(17.dp)) {
                Icon(Icons.Rounded.Verified, null); Spacer(Modifier.width(7.dp)); Text("اعتمد القراءة وابدأ التعديل", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun VerificationOverlay(source: Uri, plan: FloorPlan) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, source) {
        value = withContext(Dispatchers.IO) { loadVerificationBitmap(context, source) }
    }
    val image = bitmap
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        if (image == null) {
            Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            val ratio = (image.width.toFloat() / image.height.coerceAtLeast(1)).coerceIn(.55f, 1.8f)
            Box(Modifier.fillMaxWidth().aspectRatio(ratio).background(Color.White)) {
                Image(image.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, alpha = .88f)
                Canvas(Modifier.fillMaxSize()) {
                    fun p(x: Float, y: Float) = Offset(size.width * x / 100f, size.height * y / 100f)
                    plan.rooms.forEach { room ->
                        val poly = room.polygon
                        if (poly.size >= 3) {
                            val path = Path().apply {
                                val first = p(poly[0].x, poly[0].y)
                                moveTo(first.x, first.y)
                                poly.drop(1).forEach { point -> val q = p(point.x, point.y); lineTo(q.x, q.y) }
                                close()
                            }
                            drawPath(path, VSBronze.copy(alpha = .18f))
                            drawPath(path, VSBronze.copy(alpha = .8f), style = Stroke(1.5.dp.toPx()))
                        }
                    }
                    plan.walls.forEach { wall -> drawLine(VSDeep.copy(alpha = .75f), p(wall.start.x, wall.start.y), p(wall.end.x, wall.end.y), strokeWidth = 1.4.dp.toPx()) }
                    plan.openings.forEach { opening -> drawCircle(if (opening.confidence < 65) Color(0xFFC46A4A) else Color(0xFF5F786A), radius = 3.dp.toPx(), center = p(opening.x, opening.y)) }
                }
            }
        }
    }
}

private fun loadVerificationBitmap(context: android.content.Context, uri: Uri): Bitmap? {
    val type = context.contentResolver.getType(uri).orEmpty()
    return runCatching {
        if (type == "application/pdf") {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@runCatching null
            PdfRenderer(pfd).use { renderer ->
                renderer.openPage(0).use { page ->
                    val max = 1500f
                    val scale = minOf(max / page.width.coerceAtLeast(1), max / page.height.coerceAtLeast(1))
                    val w = (page.width * scale).toInt().coerceAtLeast(1)
                    val h = (page.height * scale).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                }
            }
        } else context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
    }.getOrNull()
}
