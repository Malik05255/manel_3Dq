package com.manzili.hai

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manzili.hai.ai.MultiPageHaiPlanAnalyzer
import com.manzili.hai.engine.*
import com.manzili.hai.model.FloorPlan
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun Hai360ImportScreen(
    initialSource: Uri?,
    onBack: () -> Unit,
    onSourceChanged: (Uri?) -> Unit,
    onAnalyzed: (FloorPlan, SaudiProjectTypeEngine.Type) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vision = remember { MultiPageHaiPlanAnalyzer(context) }
    val localOcr = remember { PlanTextOcrEngine(context) }
    val raster = remember { RasterFloorplanParserEngine(context) }
    val remote = remember { RemoteFloorplanEvidenceClient(context) }

    var source by remember(initialSource) { mutableStateOf(initialSource) }
    var type by remember { mutableStateOf(SaudiProjectTypeEngine.Type.VILLA_TWO) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var phase by remember { mutableIntStateOf(0) }

    LaunchedEffect(busy) {
        if (!busy) {
            phase = 0
            return@LaunchedEffect
        }
        while (busy) {
            delay(950)
            phase = (phase + 1).coerceAtMost(3)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            source = uri
            onSourceChanged(uri)
            error = null
        }
    }

    ArchitecturalBackdrop(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                H360IconButton(Icons.Rounded.ArrowForward, "رجوع", onClick = onBack)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("أدخل المخطط", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
                    Text("قراءة هندسية، لا مجرد رفع ملف", color = Color.White.copy(alpha = .52f), fontSize = 10.sp)
                }
                Surface(color = H360Cyan, shape = RoundedCornerShape(50.dp)) {
                    Text("01 / 02", color = H360Ink, fontWeight = FontWeight.Black, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp))
                }
            }

            Spacer(Modifier.height(24.dp))

            Text("نوع المشروع", color = Color.White.copy(alpha = .55f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SaudiProjectTypeEngine.Type.entries.forEach { item ->
                    val selected = item == type
                    Surface(
                        color = if (selected) H360Cyan else Color.White.copy(alpha = .075f),
                        contentColor = if (selected) H360Ink else Color.White,
                        shape = RoundedCornerShape(16.dp),
                        border = if (selected) null else BorderStroke(1.dp, Color.White.copy(alpha = .08f)),
                        modifier = Modifier.clickable { type = item }
                    ) {
                        Text(
                            item.label,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Black else FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            Surface(
                color = Color.White.copy(alpha = .055f),
                shape = RoundedCornerShape(32.dp),
                border = BorderStroke(1.dp, if (source == null) Color.White.copy(alpha = .11f) else H360Cyan.copy(alpha = .55f)),
                modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(32.dp)).clickable {
                    picker.launch(arrayOf("image/*", "application/pdf"))
                }
            ) {
                Box(Modifier.fillMaxSize().padding(24.dp)) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            color = if (source == null) Color.White.copy(alpha = .08f) else H360Cyan,
                            shape = CircleShape,
                            modifier = Modifier.size(74.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    if (source == null) Icons.Rounded.AddPhotoAlternate else Icons.Rounded.Description,
                                    null,
                                    tint = if (source == null) Color.White else H360Ink,
                                    modifier = Modifier.size(31.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        Text(
                            if (source == null) "المخطط هنا" else "تم التقاط المخطط",
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 23.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (source == null) "PDF أو صورة — اضغط للاختيار" else source?.lastPathSegment.orEmpty(),
                            color = Color.White.copy(alpha = .48f),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        Modifier.align(Alignment.BottomCenter),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ScannerTag("غرف", Icons.Rounded.GridView)
                        ScannerTag("جدران", Icons.Rounded.ViewWeek)
                        ScannerTag("أبعاد", Icons.Rounded.Straighten)
                    }
                }
            }

            error?.let {
                Spacer(Modifier.height(10.dp))
                Surface(color = H360Danger.copy(alpha = .14f), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.ErrorOutline, null, tint = H360Danger, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(it, color = Color.White.copy(alpha = .88f), fontSize = 10.5.sp, lineHeight = 15.sp)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Button(
                enabled = source != null && !busy,
                onClick = {
                    val uri = source ?: return@Button
                    busy = true
                    error = null
                    scope.launch {
                        runCatching {
                            coroutineScope {
                                val visionJob = async {
                                    if (vision.available) runCatching { vision.analyze(uri, maxPdfPages = 8, projectType = type) }
                                    else Result.success(null)
                                }
                                val ocrJob = async { runCatching { localOcr.readSpatial(uri, maxPdfPages = 8) } }
                                val rasterJob = async { runCatching { raster.analyze(uri, maxPdfPages = 6) } }
                                val remoteJob = async {
                                    if (remote.available) runCatching { remote.analyze(uri, maxPdfPages = 8) }
                                    else Result.success(null)
                                }

                                val visionAttempt = visionJob.await()
                                val ocrAttempt = ocrJob.await()
                                val rasterAttempt = rasterJob.await()
                                val remoteAttempt = remoteJob.await()

                                val visionPlan = visionAttempt.getOrNull()
                                val ocr = ocrAttempt.getOrNull()
                                val rasterResult = rasterAttempt.getOrNull()
                                val remoteResult = remoteAttempt.getOrNull()

                                if (visionPlan == null && rasterResult == null && remoteResult == null) {
                                    error("لم تستطع أي قناة استخراج هندسة قابلة للمراجعة. جرّب نسخة أوضح من المخطط.")
                                }

                                val channelStatus = listOf(
                                    "Vision ${if (visionPlan != null) "✓" else "—"}",
                                    "OCR ${if (ocr != null) "✓" else "—"}",
                                    "Raster ${if (rasterResult != null) "✓" else "—"}",
                                    "Deep ${if (remoteResult != null) "✓" else "—"}"
                                ).joinToString(" • ")

                                val failures = buildList {
                                    visionAttempt.exceptionOrNull()?.message?.let { add("HAI Vision: ${it.take(110)}") }
                                    ocrAttempt.exceptionOrNull()?.message?.let { add("OCR: ${it.take(110)}") }
                                    rasterAttempt.exceptionOrNull()?.message?.let { add("Raster: ${it.take(110)}") }
                                    remoteAttempt.exceptionOrNull()?.message?.let { add("Deep Parser: ${it.take(110)}") }
                                }

                                val base = visionPlan ?: FloorPlan(
                                    title = "مخطط مستورد",
                                    sourceSummary = "تحليل متعدد القنوات مع مراجعة بشرية قبل الاعتماد.",
                                    observations = listOf("تم التحليل اعتمادًا على القنوات الهندسية المتاحة."),
                                    uncertainties = emptyList()
                                )

                                val evidenceLines = ocr?.lines.orEmpty() + remoteResult?.ocrLines.orEmpty()
                                val dims = DimensionEvidenceEngine.extractSpatial(evidenceLines)
                                val numbers = PlanNumberEvidenceEngine.extract(evidenceLines)
                                val enriched = base.copy(
                                    dimensions = (base.dimensions + dims + numbers).distinctBy { "${it.pageIndex}:${it.id}:${"%.3f".format(it.valueM)}" },
                                    observations = (base.observations + listOfNotNull(
                                        "محركات القراءة: $channelStatus",
                                        "نوع المشروع: ${type.label}.",
                                        numbers.takeIf { it.isNotEmpty() }?.let { "تم حفظ ${it.size} رقمًا مقروءًا كأدلة مكانية للمراجعة." },
                                        remoteResult?.let { "Deep Parser: ${it.pages.size} صفحة • متوسط ${it.confidence}%." }
                                    )).distinct(),
                                    uncertainties = (base.uncertainties + failures).distinct()
                                )

                                val fused = MultiPageEvidenceFusionEngine.apply(enriched, remoteResult?.pages.orEmpty())
                                val refined = FloorplanParserEngine.refine(fused, rasterResult?.primaryWalls.orEmpty()).plan
                                val typed = SaudiProjectTypeEngine.apply(refined, type)
                                val normalized = MultiFloorGeometryEngine.persistActive(MultiFloorGeometryEngine.normalize(typed))

                                if (normalized.walls.isEmpty() && normalized.rooms.isEmpty()) {
                                    normalized.copy(
                                        uncertainties = (normalized.uncertainties + "لم يكتمل استخراج الهندسة؛ استخدم HAI أو أدوات التحرير داخل الاستوديو.").distinct()
                                    )
                                } else normalized
                            }
                        }.onSuccess { onAnalyzed(it, type) }
                            .onFailure { error = it.message ?: "تعذر تحليل المخطط" }
                        busy = false
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = H360Cyan, contentColor = H360Ink),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth().height(62.dp)
            ) {
                Icon(Icons.Rounded.CenterFocusStrong, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("ابدأ المسح الهندسي", fontWeight = FontWeight.Black, fontSize = 15.sp)
            }
            Spacer(Modifier.height(10.dp))
        }

        if (busy) {
            Box(Modifier.fillMaxSize().background(H360Ink.copy(alpha = .96f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 32.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            color = H360Cyan,
                            trackColor = Color.White.copy(alpha = .08f),
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(86.dp)
                        )
                        Icon(Icons.Rounded.Architecture, null, tint = Color.White, modifier = Modifier.size(31.dp))
                    }
                    Spacer(Modifier.height(28.dp))
                    Text("HAI يقرأ الفراغ", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        listOf("فصل الرسم عن النص", "استخراج الجدران والفتحات", "مطابقة الأبعاد والأرقام بين القنوات", "بناء نموذج قابل للتحرير")[phase],
                        color = Color.White.copy(alpha = .55f),
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(24.dp))
                    LinearProgressIndicator(
                        progress = { (phase + 1) / 4f },
                        color = H360Cyan,
                        trackColor = Color.White.copy(alpha = .08f),
                        modifier = Modifier.fillMaxWidth().height(3.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ScannerTag(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(color = Color.Black.copy(alpha = .22f), shape = RoundedCornerShape(50.dp)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = H360Cyan, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text(label, color = Color.White.copy(alpha = .74f), fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
        }
    }
}
