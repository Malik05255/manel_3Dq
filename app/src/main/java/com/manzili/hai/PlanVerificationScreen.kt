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
import com.manzili.hai.ai.HaiArchitectClient
import com.manzili.hai.engine.DimensionEvidenceEngine
import com.manzili.hai.engine.FloorplanParserEngine
import com.manzili.hai.engine.HaiReviewResolutionEngine
import com.manzili.hai.engine.MultiPageEvidenceFusionEngine
import com.manzili.hai.engine.PlanTextOcrEngine
import com.manzili.hai.engine.PlanVerificationEngine
import com.manzili.hai.engine.RasterFloorplanParserEngine
import com.manzili.hai.engine.RemoteFloorplanEvidenceClient
import com.manzili.hai.model.FloorPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val VSSand = Color(0xFFF8F6F2)
private val VSPaper = Color(0xFFFFFEFC)
private val VSDeep = Color(0xFF181A18)
private val VSViolet = Color(0xFF6353D9)
private val VSOrange = Color(0xFFE28B5A)
private val VSGreen = Color(0xFF4C8A78)

@Composable
fun PlanVerificationScreen(nav: NavHostController, source: Uri?, plan: FloorPlan?, onConfirm: (FloorPlan) -> Unit) {
    if (plan == null) {
        Surface(color = VSSand, modifier = Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Description, null, tint = Color(0xFFB0AAA2), modifier = Modifier.size(46.dp))
            }
        }
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haiClient = remember(context) { HaiArchitectClient(context) }
    val localOcr = remember(context) { PlanTextOcrEngine(context) }
    val raster = remember(context) { RasterFloorplanParserEngine(context) }
    val remote = remember(context) { RemoteFloorplanEvidenceClient(context) }
    var working by remember(plan) { mutableStateOf(PlanVerificationEngine.inspect(plan).plan) }
    var width by remember(working.widthM) { mutableStateOf(working.widthM?.let { "%.2f".format(it) } ?: "") }
    var height by remember(working.heightM) { mutableStateOf(working.heightM?.let { "%.2f".format(it) } ?: "") }
    var haiBusy by remember { mutableStateOf(false) }
    var haiStatus by remember { mutableStateOf<String?>(null) }
    val report = remember(working) { PlanVerificationEngine.inspect(working) }
    val hasReviewGap = report.issues.isNotEmpty() || working.widthM == null || working.heightM == null

    fun runHaiResolver() {
        if (haiBusy) return
        haiBusy = true
        haiStatus = "HAI يعيد قراءة المخطط ويبحث عن الأبعاد والهندسة الناقصة..."
        val beforeIssueCount = report.issues.size
        val beforeWidthMissing = working.widthM == null
        val beforeHeightMissing = working.heightM == null

        scope.launch {
            var candidate = HaiReviewResolutionEngine.localResolve(working)
            var candidateReport = PlanVerificationEngine.inspect(candidate)
            var evidenceChannels = 0
            val failures = mutableListOf<String>()
            val uri = source

            if (uri != null && (
                    candidateReport.blocking ||
                        candidate.widthM == null ||
                        candidate.heightM == null ||
                        candidateReport.issues.isNotEmpty()
                    )
            ) {
                val ocrAttempt = runCatching { localOcr.readSpatial(uri, maxPdfPages = 8) }
                val rasterAttempt = runCatching { raster.analyze(uri, maxPdfPages = 6) }
                val remoteAttempt = if (remote.available) {
                    runCatching { remote.analyze(uri, maxPdfPages = 8) }
                } else null

                val ocrResult = ocrAttempt.getOrNull()
                val rasterResult = rasterAttempt.getOrNull()
                val remoteResult = remoteAttempt?.getOrNull()

                ocrAttempt.exceptionOrNull()?.message?.let { failures += "OCR: ${it.take(90)}" }
                rasterAttempt.exceptionOrNull()?.message?.let { failures += "Raster: ${it.take(90)}" }
                remoteAttempt?.exceptionOrNull()?.message?.let { failures += "Deep Parser: ${it.take(90)}" }

                val dimensionLines = ocrResult?.lines.orEmpty() + remoteResult?.ocrLines.orEmpty()
                val recoveredDimensions = DimensionEvidenceEngine.extractSpatial(dimensionLines)
                if (recoveredDimensions.isNotEmpty()) {
                    candidate = candidate.copy(
                        dimensions = (candidate.dimensions + recoveredDimensions).distinctBy {
                            "${it.pageIndex}:${it.id}:${"%.3f".format(it.valueM)}"
                        }
                    )
                    evidenceChannels++
                }

                if (remoteResult != null) {
                    candidate = MultiPageEvidenceFusionEngine.apply(candidate, remoteResult.pages)
                    evidenceChannels++
                }

                if (rasterResult != null) {
                    candidate = FloorplanParserEngine.refine(candidate, rasterResult.primaryWalls).plan
                    evidenceChannels++
                }

                candidate = HaiReviewResolutionEngine.localResolve(candidate)
                candidateReport = PlanVerificationEngine.inspect(candidate)

                val stillNeedsVision = candidateReport.blocking ||
                    candidate.widthM == null ||
                    candidate.heightM == null ||
                    candidateReport.issues.isNotEmpty()

                if (stillNeedsVision) {
                    runCatching { haiClient.analyzePlan(uri) }
                        .onSuccess { visual ->
                            candidate = HaiReviewResolutionEngine.mergeVisualEvidence(candidate, visual)
                            candidate = HaiReviewResolutionEngine.localResolve(candidate)
                            candidateReport = PlanVerificationEngine.inspect(candidate)
                            evidenceChannels++
                        }
                        .onFailure { error -> failures += "HAI Vision: ${error.message.orEmpty().take(90)}" }
                }
            }

            working = candidateReport.plan
            width = candidateReport.plan.widthM?.let { "%.2f".format(it) } ?: ""
            height = candidateReport.plan.heightM?.let { "%.2f".format(it) } ?: ""

            val solvedCount = (beforeIssueCount - candidateReport.issues.size).coerceAtLeast(0)
            val widthSolved = beforeWidthMissing && candidateReport.plan.widthM != null
            val heightSolved = beforeHeightMissing && candidateReport.plan.heightM != null
            val scaleSolved = widthSolved || heightSolved

            haiStatus = when {
                scaleSolved && !candidateReport.blocking -> "تمت تعبئة الأبعاد تلقائيًا وإصلاح المراجعة. المشروع جاهز للاعتماد."
                scaleSolved -> "تمت تعبئة ${listOfNotNull(if (widthSolved) "العرض" else null, if (heightSolved) "الطول" else null).joinToString(" و")} تلقائيًا من المخطط."
                !candidateReport.blocking && candidateReport.issues.isEmpty() -> "تم حل مشاكل المراجعة تلقائيًا. المشروع جاهز للاعتماد."
                solvedCount > 0 -> "حل HAI $solvedCount من مشاكل المراجعة تلقائيًا. راجع ما تبقى فقط."
                uri == null -> "المصدر الأصلي غير متاح لهذه الجلسة؛ لا يمكن استخراج أبعاد جديدة. أعد فتح المخطط أو أدخل القيم يدويًا."
                evidenceChannels > 0 -> "أعاد HAI قراءة المخطط، لكن لم يجد دليلًا موثوقًا كافيًا لتعبئة الحقول المتبقية دون تخمين."
                failures.isNotEmpty() -> "تعذر إكمال الحل التلقائي: ${failures.first()}"
                else -> "لم يجد HAI دليلًا كافيًا لحل المشكلة بأمان. أكمل الحقل أو العنصر يدويًا."
            }
            haiBusy = false
        }
    }

    Surface(color = VSSand, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.Rounded.ArrowForward, "رجوع")
                }
                Text("راجع", fontSize = 28.sp, fontWeight = FontWeight.Black, color = VSDeep)
                Spacer(Modifier.weight(1f))
                Surface(
                    color = if (report.blocking) VSOrange.copy(alpha = 0.12f) else VSViolet.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(50.dp)
                ) {
                    Text(
                        if (report.blocking) "راجع" else "جاهز",
                        color = if (report.blocking) VSOrange else VSViolet,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                if (source != null) VerificationOverlay(source, report.plan)

                if (working.widthM == null || working.heightM == null) {
                    Spacer(Modifier.height(12.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = VSPaper),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    width,
                                    { width = it },
                                    label = { Text("العرض") },
                                    suffix = { Text("م") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    height,
                                    { height = it },
                                    label = { Text("الطول") },
                                    suffix = { Text("م") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    val w = width.replace(',', '.').toDoubleOrNull()
                                    val h = height.replace(',', '.').toDoubleOrNull()
                                    if (w != null && h != null && w > .5 && h > .5) {
                                        working = PlanVerificationEngine.confirmScale(working, w, h)
                                        haiStatus = null
                                    }
                                },
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Rounded.Straighten, null, Modifier.size(17.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("تأكيد يدوي")
                            }
                        }
                    }
                }

                if (report.issues.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
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
                                    Text(issue.title, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }

                haiStatus?.let { status ->
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        color = if (report.blocking) VSPaper else VSGreen.copy(alpha = .10f),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(horizontal = 13.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.AutoAwesome, null, tint = if (report.blocking) VSViolet else VSGreen, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(status, fontSize = 10.5.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
            }

            FilledTonalButton(
                onClick = { runHaiResolver() },
                enabled = !haiBusy && hasReviewGap,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = VSViolet.copy(alpha = .12f),
                    contentColor = VSViolet
                ),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) {
                if (haiBusy) {
                    CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp, color = VSViolet)
                } else {
                    Icon(Icons.Rounded.AutoAwesome, null)
                }
                Spacer(Modifier.width(7.dp))
                Text(if (haiBusy) "HAI يحل المشكلة..." else "HAI • حل تلقائي", fontWeight = FontWeight.Black, fontSize = 15.sp)
            }

            Spacer(Modifier.height(8.dp))

            Button(
                enabled = !report.blocking && !haiBusy,
                onClick = { onConfirm(report.plan) },
                colors = ButtonDefaults.buttonColors(containerColor = VSViolet),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth().height(58.dp)
            ) {
                Icon(Icons.Rounded.Verified, null)
                Spacer(Modifier.width(7.dp))
                Text("اعتماد", fontWeight = FontWeight.Black, fontSize = 16.sp)
            }

            Spacer(Modifier.height(10.dp))
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

    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(26.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (image == null) {
            Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = VSViolet, strokeWidth = 2.5.dp)
            }
        } else {
            val ratio = (image.width.toFloat() / image.height.coerceAtLeast(1)).coerceIn(.55f, 1.8f)
            Box(Modifier.fillMaxWidth().aspectRatio(ratio).background(Color.White)) {
                Image(
                    image.asImageBitmap(),
                    null,
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                    alpha = .90f
                )
                Canvas(Modifier.fillMaxSize()) {
                    fun p(x: Float, y: Float) = Offset(size.width * x / 100f, size.height * y / 100f)
                    plan.rooms.forEach { room ->
                        val poly = room.polygon
                        if (poly.size >= 3) {
                            val path = Path().apply {
                                val first = p(poly[0].x, poly[0].y)
                                moveTo(first.x, first.y)
                                poly.drop(1).forEach { point ->
                                    val q = p(point.x, point.y)
                                    lineTo(q.x, q.y)
                                }
                                close()
                            }
                            drawPath(path, VSViolet.copy(alpha = .10f))
                            drawPath(path, VSViolet.copy(alpha = .65f), style = Stroke(1.3.dp.toPx()))
                        }
                    }
                    plan.walls.forEach { wall ->
                        drawLine(VSDeep.copy(alpha = .70f), p(wall.start.x, wall.start.y), p(wall.end.x, wall.end.y), strokeWidth = 1.3.dp.toPx())
                    }
                    plan.openings.forEach { opening ->
                        drawCircle(
                            if (opening.confidence < 65) VSOrange else VSGreen,
                            radius = 3.dp.toPx(),
                            center = p(opening.x, opening.y)
                        )
                    }
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
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        } else {
            context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
        }
    }.getOrNull()
}
