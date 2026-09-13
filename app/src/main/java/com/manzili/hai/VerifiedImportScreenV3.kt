package com.manzili.hai

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.manzili.hai.ai.MultiPageHaiPlanAnalyzer
import com.manzili.hai.engine.DimensionEvidenceEngine
import com.manzili.hai.engine.FloorplanParserEngine
import com.manzili.hai.engine.MultiFloorGeometryEngine
import com.manzili.hai.engine.MultiPageEvidenceFusionEngine
import com.manzili.hai.engine.PlanTextOcrEngine
import com.manzili.hai.engine.RasterFloorplanParserEngine
import com.manzili.hai.engine.RemoteFloorplanEvidenceClient
import com.manzili.hai.engine.SaudiProjectTypeEngine
import com.manzili.hai.model.FloorPlan
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Composable
fun VerifiedImportScreenV3(
    nav: NavHostController,
    source: Uri?,
    setSource: (Uri) -> Unit,
    onAnalyzed: (FloorPlan) -> Unit,
    projectType: SaudiProjectTypeEngine.Type? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vision = remember { MultiPageHaiPlanAnalyzer(context) }
    val localOcr = remember { PlanTextOcrEngine(context) }
    val raster = remember { RasterFloorplanParserEngine(context) }
    val remote = remember { RemoteFloorplanEvidenceClient(context) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            setSource(uri)
            error = null
        }
    }

    Surface(Modifier.fillMaxSize(), color = StudioColors.Canvas) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Outlined.ArrowForward, "رجوع") }
                Text("المخطط", fontSize = 29.sp, fontWeight = FontWeight.Bold, color = StudioColors.Ink)
                Spacer(Modifier.height(24.dp))
                projectType?.let {
                    Surface(color = StudioColors.Primary.copy(alpha = 0.10f), shape = RoundedCornerShape(50)) {
                        Text(it.label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp), color = StudioColors.Primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }

            StudioSection("أضف مخطط بيتك", "صورة واضحة أو ملف PDF")

            ElevatedCard(
                onClick = { picker.launch(arrayOf("image/*", "application/pdf")) },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth().height(220.dp)
            ) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    val accent = if (source == null) StudioColors.Warning else StudioColors.Primary
                    Box(Modifier.size(72.dp).background(accent.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(if (source == null) Icons.Outlined.UploadFile else Icons.Outlined.CheckCircle, null, tint = accent, modifier = Modifier.size(34.dp))
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(if (source == null) "اختر المخطط" else "تم اختيار المخطط", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = StudioColors.Ink)
                    Spacer(Modifier.height(5.dp))
                    Text(if (source == null) "اضغط لاختيار ملف" else "اضغط لاستبداله", color = StudioColors.Muted, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(24.dp))

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp))
            }

            Button(
                enabled = source != null && !busy,
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        runCatching {
                            val uri = source!!
                            coroutineScope {
                                val visionJob = async {
                                    if (vision.available) runCatching { vision.analyze(uri, maxPdfPages = 8, projectType = projectType) }
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

                                val channelStatus = listOf(
                                    "HAI Vision ${when { !vision.available -> "غير مفعّل"; visionPlan != null -> "✓"; else -> "✗" }}",
                                    "OCR-Latin ${if (ocr != null) "✓" else "✗"}",
                                    "Raster ${if (rasterResult != null) "✓" else "✗"}",
                                    "Deep Parser ${when { !remote.available -> "غير مفعّل"; remoteResult != null -> "✓"; else -> "✗" }}"
                                ).joinToString(" • ")

                                val failures = buildList {
                                    visionAttempt.exceptionOrNull()?.message?.let { add("HAI Vision فشل: ${it.take(120)}") }
                                    ocrAttempt.exceptionOrNull()?.message?.let { add("OCR المحلي فشل: ${it.take(120)}") }
                                    rasterAttempt.exceptionOrNull()?.message?.let { add("Raster فشل: ${it.take(120)}") }
                                    remoteAttempt.exceptionOrNull()?.message?.let { add("Deep Parser فشل: ${it.take(120)}") }
                                }

                                if (visionPlan == null && rasterResult == null && remoteResult == null) {
                                    error("فشلت جميع قنوات استخراج الهندسة. راجع الاتصال أو جرّب ملفًا أوضح.")
                                }

                                val base = visionPlan ?: FloorPlan(
                                    title = "مخطط مستورد",
                                    sourceSummary = "تحليل استيراد متعدد المسارات يتبعه دائمًا فحص بصري ومراجعة بشرية قبل الاعتماد و3D.",
                                    observations = listOf("تم التحليل دون HAI Vision؛ النتيجة تعتمد على الأدلة المحلية/Deep Parser المتاحة."),
                                    uncertainties = emptyList()
                                )

                                val dims = DimensionEvidenceEngine.extractSpatial(ocr?.lines.orEmpty() + remoteResult?.ocrLines.orEmpty())
                                val enriched = base.copy(
                                    dimensions = (base.dimensions + dims).distinctBy { "${it.pageIndex}:${it.id}:${"%.3f".format(it.valueM)}" },
                                    observations = (base.observations + listOfNotNull(
                                        "حالة محركات التحليل: $channelStatus",
                                        projectType?.let { "نوع المشروع المحدد قبل التحليل: ${it.label}." },
                                        ocr?.let { "OCR محلي (Latin): ${it.pagesAnalyzed} صفحة${if (it.truncated) " (محدود)" else ""}." },
                                        rasterResult?.notes?.joinToString(" "),
                                        remoteResult?.let { "Deep Parser: ${it.pages.size} صفحة • ${it.modelUsed} • متوسط ${it.confidence}%." },
                                        remoteResult?.warnings?.takeIf { it.isNotEmpty() }?.joinToString(" ")
                                    )).distinct(),
                                    uncertainties = (base.uncertainties + failures + buildList {
                                        if (!vision.available || visionPlan == null) add("قراءة النص العربي ليست مضمونة محليًا لأن OCR المحلي الحالي Latin؛ تحقق يدويًا من أسماء الغرف والأبعاد العربية.")
                                        if (remote.available && remoteResult == null) add("Deep Parser كان مفعّلًا لكنه لم يشارك في النتيجة؛ لا تعتبر النتيجة مكافئة لتحليل Deep Parser ناجح.")
                                    }).distinct()
                                )

                                val deepApplied = MultiPageEvidenceFusionEngine.apply(enriched, remoteResult?.pages.orEmpty())
                                val locallyRefined = FloorplanParserEngine.refine(deepApplied, rasterResult?.primaryWalls.orEmpty()).plan
                                val typed = projectType?.let { SaudiProjectTypeEngine.apply(locallyRefined, it) } ?: locallyRefined
                                var normalized = MultiFloorGeometryEngine.persistActive(MultiFloorGeometryEngine.normalize(typed))

                                val structuralGeometry = normalized.walls.size + normalized.rooms.size
                                if (structuralGeometry == 0) {
                                    normalized = normalized.copy(
                                        uncertainties = (normalized.uncertainties +
                                            "لم يكتمل استخراج الحدود والجدران تلقائيًا. افتح المراجعة لمقارنة الأصل، ثم استخدم HAI أو التعديل قبل اعتماد المشروع و3D.").distinct()
                                    )
                                }
                                normalized
                            }
                        }.onSuccess {
                            onAnalyzed(it)
                            nav.navigate("verify")
                        }.onFailure {
                            error = it.message ?: "تعذر تحليل المخطط"
                        }
                        busy = false
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = StudioColors.Primary),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth().height(60.dp)
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(Modifier.width(10.dp))
                } else {
                    Icon(Icons.Outlined.AutoAwesome, null)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (busy) "جاري التحليل" else "حلّل ثم راجع", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }

            Spacer(Modifier.navigationBarsPadding().height(8.dp))
        }
    }
}
