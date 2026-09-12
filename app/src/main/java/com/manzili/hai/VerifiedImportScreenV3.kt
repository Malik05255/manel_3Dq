package com.manzili.hai

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
import com.manzili.hai.model.FloorPlan
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Composable
fun VerifiedImportScreenV3(
    nav: NavHostController,
    source: Uri?,
    setSource: (Uri) -> Unit,
    onAnalyzed: (FloorPlan) -> Unit
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
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            setSource(uri)
            error = null
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp)) {
            Row {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Rounded.ArrowForward, "رجوع") }
                Column {
                    Text("استيراد مخطط", fontSize = 24.sp, fontWeight = FontWeight.Black)
                    Text(if (remote.available) "Multi-page Vision + OCR + Raster + Deep Parser" else "Multi-page Vision + OCR + Raster")
                }
            }
            Spacer(Modifier.height(18.dp))
            OutlinedButton(onClick = { picker.launch(arrayOf("image/*", "application/pdf")) }, modifier = Modifier.fillMaxWidth().height(72.dp)) {
                Text(if (source == null) "اختر PDF أو صورة" else "الملف جاهز للتحليل")
            }
            Spacer(Modifier.height(14.dp))
            Text("PDF: يحلل HAI أول 5 صفحات بصريًا، والـOCR والـDeep Parser يحتفظان برقم الصفحة. كل صفحة تدخل طبقة مستقلة للمراجعة بدل تجاهلها.", fontSize = 11.sp)
            Spacer(Modifier.height(5.dp))
            Text("الجدران والأبواب والنوافذ القادمة من النماذج تعتبر أدلة؛ لا تتحول إلى هندسة موثوقة إلا بعد الدمج والتحقق.", fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Button(
                enabled = source != null && !busy && vision.available,
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        runCatching {
                            val uri = source!!
                            coroutineScope {
                                val ocrJob = async { runCatching { localOcr.readSpatial(uri, maxPdfPages = 5) }.getOrNull() }
                                val rasterJob = async { runCatching { raster.analyze(uri) }.getOrNull() }
                                val remoteJob = async { if (remote.available) runCatching { remote.analyze(uri, maxPdfPages = 5) }.getOrNull() else null }
                                val base = vision.analyze(uri, maxPdfPages = 5)
                                val ocr = ocrJob.await()
                                val rasterResult = rasterJob.await()
                                val remoteResult = remoteJob.await()
                                val dims = DimensionEvidenceEngine.extractSpatial(ocr?.lines.orEmpty() + remoteResult?.ocrLines.orEmpty())
                                val enriched = base.copy(
                                    dimensions = (base.dimensions + dims).distinctBy { "${it.pageIndex}:${it.id}:${"%.3f".format(it.valueM)}" },
                                    observations = (base.observations + listOfNotNull(
                                        ocr?.let { "OCR محلي: ${it.pagesAnalyzed} صفحة${if (it.truncated) " (محدود)" else ""}." },
                                        rasterResult?.notes?.joinToString(" "),
                                        remoteResult?.let { "Deep Parser: ${it.pages.size} صفحة • ${it.modelUsed} • متوسط ${it.confidence}%." },
                                        remoteResult?.warnings?.takeIf { it.isNotEmpty() }?.joinToString(" ")
                                    )).distinct()
                                )
                                val deepApplied = MultiPageEvidenceFusionEngine.apply(enriched, remoteResult?.pages.orEmpty())
                                val locallyRefined = FloorplanParserEngine.refine(deepApplied, rasterResult?.primaryWalls.orEmpty()).plan
                                MultiFloorGeometryEngine.persistActive(MultiFloorGeometryEngine.normalize(locallyRefined))
                            }
                        }.onSuccess {
                            onAnalyzed(it)
                            nav.navigate("verify")
                        }.onFailure {
                            error = it.message ?: "فشل تحليل المخطط"
                        }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth().height(58.dp)
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Rounded.AutoAwesome, null)
                Spacer(Modifier.width(8.dp))
                Text(if (busy) "أحلل جميع الصفحات…" else if (!vision.available) "فعّل HAI أولًا" else "حلّل كل الصفحات ثم راجع")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        }
    }
}
