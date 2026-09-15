package com.manzili.hai

import android.content.Context
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
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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

private val VSSand = Color(0xFFF8F6F2)
private val VSPaper = Color(0xFFFFFEFC)
private val VSDeep = Color(0xFF181A18)
private val VSViolet = Color(0xFF6353D9)
private val VSOrange = Color(0xFFE28B5A)
private val VSGreen = Color(0xFF4C8A78)

private enum class VerificationPreviewMode(val title: String) {
    ORIGINAL("الأصلي"),
    VECTOR("المتجه"),
    OVERLAY("تراكب")
}

@Composable
fun PlanVerificationScreen(
    nav: NavHostController,
    source: Uri?,
    plan: FloorPlan?,
    onPlanChange: (FloorPlan) -> Unit = {},
    onConfirm: (FloorPlan) -> Unit
) {
    if (plan == null) {
        Surface(color = VSSand, modifier = Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Description, null, tint = Color(0xFFB0AAA2), modifier = Modifier.size(46.dp))
            }
        }
        return
    }

    var working by remember(plan) { mutableStateOf(PlanVerificationEngine.inspect(plan).plan) }
    var widthText by remember(working.widthM) { mutableStateOf(working.widthM?.let { "%.2f".format(it) } ?: "") }
    var heightText by remember(working.heightM) { mutableStateOf(working.heightM?.let { "%.2f".format(it) } ?: "") }
    val report = remember(working) { PlanVerificationEngine.inspect(working) }

    fun publish(next: FloorPlan) {
        working = next
        onPlanChange(next)
    }

    Surface(color = VSSand, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.Rounded.ArrowForward, "رجوع")
                }
                Column(Modifier.weight(1f)) {
                    Text("مراجعة المخطط", fontSize = 26.sp, fontWeight = FontWeight.Black, color = VSDeep)
                    Text("قارن الأصل بالمتجه قبل الاعتماد", color = Color.Gray, fontSize = 10.sp)
                }
                Surface(
                    color = if (report.blocking) VSOrange.copy(alpha = .12f) else VSGreen.copy(alpha = .12f),
                    shape = RoundedCornerShape(50.dp)
                ) {
                    Text(
                        if (report.blocking) "مراجعة ${report.readingConfidence}%" else "جاهز ${report.readingConfidence}%",
                        color = if (report.blocking) VSOrange else VSGreen,
                        fontWeight = FontWeight.Black,
                        fontSize = 10.5.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                if (source != null) VerificationSourcePreview(source, report.plan)

                Spacer(Modifier.height(12.dp))

                Card(
                    colors = CardDefaults.cardColors(containerColor = VSPaper),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("أبعاد المبنى", fontWeight = FontWeight.Black, fontSize = 13.sp)
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
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                val w = widthText.replace(',', '.').toDoubleOrNull()
                                val h = heightText.replace(',', '.').toDoubleOrNull()
                                if (w != null && h != null && w > .5 && h > .5) {
                                    publish(PlanVerificationEngine.confirmScale(working, w, h))
                                }
                            },
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.Straighten, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("تأكيد المقياس يدويًا")
                        }
                    }
                }

                if (report.issues.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("ما يحتاج انتباهك", fontWeight = FontWeight.Black, fontSize = 13.sp)
                    Spacer(Modifier.height(7.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        report.issues.take(8).forEach { issue ->
                            Surface(
                                color = VSPaper,
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (issue.level == "error") Icons.Rounded.ErrorOutline else Icons.Rounded.HelpOutline,
                                        null,
                                        tint = if (issue.level == "error") VSOrange else VSViolet,
                                        modifier = Modifier.size(19.dp)
                                    )
                                    Spacer(Modifier.width(9.dp))
                                    Column {
                                        Text(issue.title, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                        if (issue.detail.isNotBlank()) {
                                            Text(issue.detail, color = Color.Gray, fontSize = 9.5.sp, lineHeight = 13.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.height(12.dp))
                    Surface(color = VSGreen.copy(alpha = .10f), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Verified, null, tint = VSGreen)
                            Spacer(Modifier.width(8.dp))
                            Text("الهندسة تجاوزت حد الاعتماد ويمكن متابعتها.", color = VSGreen, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                }

                Spacer(Modifier.height(22.dp))
            }

            Button(
                enabled = !report.blocking,
                onClick = { onConfirm(report.plan) },
                colors = ButtonDefaults.buttonColors(containerColor = VSViolet),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text(
                    if (report.blocking) "أكمل المراجعة قبل الاعتماد" else "اعتماد ومتابعة",
                    fontWeight = FontWeight.Black,
                    fontSize = 15.sp
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun VerificationSourcePreview(source: Uri, plan: FloorPlan) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, source) {
        value = withContext(Dispatchers.IO) { loadReviewBitmap(context, source) }
    }
    var mode by remember { mutableStateOf(VerificationPreviewMode.OVERLAY) }
    val image = bitmap

    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(26.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                VerificationPreviewMode.entries.forEach { item ->
                    FilterChip(
                        selected = mode == item,
                        onClick = { mode = item },
                        label = { Text(item.title, fontWeight = FontWeight.Bold, fontSize = 11.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            if (image == null) {
                Box(Modifier.fillMaxWidth().height(250.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = VSViolet, strokeWidth = 2.5.dp)
                }
            } else {
                val ratio = image.width.toFloat() / image.height.coerceAtLeast(1).toFloat()
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(ratio.coerceAtLeast(.20f))
                        .background(Color.White, RoundedCornerShape(18.dp))
                ) {
                    if (mode != VerificationPreviewMode.VECTOR) {
                        Image(
                            bitmap = image.asImageBitmap(),
                            contentDescription = "المخطط الأصلي",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    if (mode != VerificationPreviewMode.ORIGINAL) {
                        Canvas(Modifier.fillMaxSize()) {
                            fun p(x: Float, y: Float) = Offset(size.width * x / 100f, size.height * y / 100f)
                            val roomAlpha = if (mode == VerificationPreviewMode.OVERLAY) .10f else .18f
                            val wallAlpha = if (mode == VerificationPreviewMode.OVERLAY) .82f else 1f
                            val wallWidth = if (mode == VerificationPreviewMode.OVERLAY) 2.dp.toPx() else 3.dp.toPx()

                            plan.rooms.forEach { room ->
                                drawRect(
                                    VSViolet.copy(alpha = roomAlpha),
                                    topLeft = p(room.x, room.y),
                                    size = Size(size.width * room.width / 100f, size.height * room.height / 100f)
                                )
                            }
                            plan.walls.forEach { wall ->
                                drawLine(
                                    VSViolet.copy(alpha = wallAlpha),
                                    p(wall.start.x, wall.start.y),
                                    p(wall.end.x, wall.end.y),
                                    strokeWidth = wallWidth
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                when (mode) {
                    VerificationPreviewMode.ORIGINAL -> "الأصل كما تم رفعه، بدون أي رسم من HAI."
                    VerificationPreviewMode.VECTOR -> "الجدران والغرف التي استخرجها HAI فقط."
                    VerificationPreviewMode.OVERLAY -> "تراكب مباشر: يجب أن تقع الخطوط البنفسجية فوق الجدران الأصلية."
                },
                color = Color(0xFF77736D),
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
}

private fun loadReviewBitmap(context: Context, source: Uri): Bitmap? {
    val type = context.contentResolver.getType(source).orEmpty()
    return runCatching {
        if (type == "application/pdf") {
            val pfd = context.contentResolver.openFileDescriptor(source, "r") ?: return@runCatching null
            PdfRenderer(pfd).use { renderer ->
                if (renderer.pageCount <= 0) return@use null
                renderer.openPage(0).use { page ->
                    val target = 1500f
                    val scale = target / page.width.coerceAtLeast(1)
                    val w = target.toInt()
                    val h = (page.height * scale).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                }
            }
        } else {
            context.contentResolver.openInputStream(source).use { BitmapFactory.decodeStream(it) }
        }
    }.getOrNull()
}