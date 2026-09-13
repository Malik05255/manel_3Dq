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
            delay(850)
            phase = (phase + 1).coerceAtMost(3)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            source = uri
            onSourceChanged(uri)
            error = null
        }
    }

    Surface(Modifier.fillMaxSize(), color = H360Ivory) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp)) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                H360IconButton(Icons.Rounded.ArrowForward, "رجوع") { onBack() }
                Spacer(Modifier.width(12.dp))
                Text("استيراد مخطط", color = H360Ink, fontSize = 25.sp, fontWeight = FontWeight.Black)
            }

            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SaudiProjectTypeEngine.Type.entries.forEach { item ->
                    val selected = item == type
                    Surface(
                        color = if (selected) H360CyanDeep else H360Paper,
                        contentColor = if (selected) Color.White else H360Ink,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, if (selected) H360CyanDeep else H360Line),
                        modifier = Modifier.clickable { type = item }
                    ) {
                        Text(item.label, fontSize = 10.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp))
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Surface(
                color = H360Paper,
                shape = RoundedCornerShape(30.dp),
                border = BorderStroke(1.5.dp, if (source == null) H360Line else H360CyanDeep.copy(alpha = .42f)),
                shadowElevation = 3.dp,
                modifier = Modifier.fillMaxWidth().weight(1f).clickable { picker.launch(arrayOf("image/*", "application/pdf")) }
            ) {
                Box(Modifier.fillMaxSize().padding(22.dp)) {
                    BlueprintGrid(Modifier.matchParentSize(), step = 30f)
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(color = if (source == null) H360Sky else H360Cyan, shape = CircleShape, modifier = Modifier.size(82.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    if (source == null) Icons.Rounded.AddPhotoAlternate else Icons.Rounded.Description,
                                    null,
                                    tint = H360CyanDeep,
                                    modifier = Modifier.size(34.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                        Text(if (source == null) "اختر المخطط" else "جاهز للمسح", color = H360Ink, fontWeight = FontWeight.Black, fontSize = 20.sp)
                        if (source != null) {
                            Spacer(Modifier.height(5.dp))
                            Text(source?.lastPathSegment.orEmpty(), color = H360Muted, fontSize = 9.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Row(Modifier.align(Alignment.BottomCenter), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        ScannerTag("غرف", Icons.Rounded.GridView)
                        ScannerTag("جدران", Icons.Rounded.ViewWeek)
                        ScannerTag("أرقام", Icons.Rounded.Pin)
                        ScannerTag("فتحات", Icons.Rounded.DoorFront)
                    }
                }
            }

            error?.let {
                Spacer(Modifier.height(9.dp))
                Surface(color = Color(0xFFFFF0EE), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, H360Danger.copy(alpha = .2f)), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.ErrorOutline, null, tint = H360Danger, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(it, color = H360Ink, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            H360PrimaryButton(
                text = "مسح المخطط",
                modifier = Modifier.fillMaxWidth(),
                enabled = source != null && !busy,
                icon = Icons.Rounded.CenterFocusStrong
            ) {
                val uri = source ?: return@H360PrimaryButton
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
                                error("تعذر استخراج هندسة من الملف")
                            }

                            val failures = buildList {
                                visionAttempt.exceptionOrNull()?.message?.let { add("Vision: ${it.take(100)}") }
                                ocrAttempt.exceptionOrNull()?.message?.let { add("OCR: ${it.take(100)}") }
                                rasterAttempt.exceptionOrNull()?.message?.let { add("Raster: ${it.take(100)}") }
                                remoteAttempt.exceptionOrNull()?.message?.let { add("Deep: ${it.take(100)}") }
                            }

                            val base = visionPlan ?: FloorPlan(title = "مخطط مستورد")
                            val evidenceLines = ocr?.lines.orEmpty() + remoteResult?.ocrLines.orEmpty()
                            val dims = DimensionEvidenceEngine.extractSpatial(evidenceLines)
                            val numbers = PlanNumberEvidenceEngine.extract(evidenceLines)
                            val enriched = base.copy(
                                dimensions = (base.dimensions + dims + numbers).distinctBy { "${it.pageIndex}:${it.id}:${"%.3f".format(it.valueM)}" },
                                uncertainties = (base.uncertainties + failures).distinct()
                            )

                            val fused = MultiPageEvidenceFusionEngine.apply(enriched, remoteResult?.pages.orEmpty())
                            val refined = FloorplanParserEngine.refine(fused, rasterResult?.primaryWalls.orEmpty()).plan
                            val typed = SaudiProjectTypeEngine.apply(refined, type)
                            MultiFloorGeometryEngine.persistActive(MultiFloorGeometryEngine.normalize(typed))
                        }
                    }.onSuccess { onAnalyzed(it, type) }
                        .onFailure { error = it.message ?: "تعذر تحليل المخطط" }
                    busy = false
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        if (busy) {
            Box(Modifier.fillMaxSize().background(H360Paper.copy(alpha = .98f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 34.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = H360CyanDeep, trackColor = H360Cyan, strokeWidth = 4.dp, modifier = Modifier.size(92.dp))
                        Icon(
                            listOf(Icons.Rounded.DocumentScanner, Icons.Rounded.Architecture, Icons.Rounded.Pin, Icons.Rounded.AutoAwesome)[phase],
                            null,
                            tint = H360CyanDeep,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(listOf("تهيئة", "هندسة", "أرقام", "دمج")[phase], color = H360Ink, fontSize = 18.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(18.dp))
                    LinearProgressIndicator(
                        progress = { (phase + 1) / 4f },
                        color = H360CyanDeep,
                        trackColor = H360Cyan,
                        modifier = Modifier.fillMaxWidth().height(5.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ScannerTag(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(color = H360Paper.copy(alpha = .94f), shape = RoundedCornerShape(50.dp), border = BorderStroke(1.dp, H360Line)) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = H360CyanDeep, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, color = H360Ink, fontSize = 8.5.sp, fontWeight = FontWeight.Black)
        }
    }
}
