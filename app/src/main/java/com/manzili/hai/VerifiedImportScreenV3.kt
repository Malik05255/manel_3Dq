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
import com.manzili.hai.ai.HaiArchitectClient
import com.manzili.hai.engine.DimensionEvidenceEngine
import com.manzili.hai.engine.FloorplanParserEngine
import com.manzili.hai.engine.OpeningEvidenceFusion
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
    val vision = remember { HaiArchitectClient(context) }
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
                    Text(if (remote.available) "Vision + OCR + Raster + Deep segmentation" else "Vision + OCR + Raster")
                }
            }
            Spacer(Modifier.height(18.dp))
            OutlinedButton(onClick = { picker.launch(arrayOf("image/*", "application/pdf")) }, modifier = Modifier.fillMaxWidth().height(72.dp)) {
                Text(if (source == null) "اختر PDF أو صورة" else "الملف جاهز للتحليل")
            }
            Spacer(Modifier.height(14.dp))
            Text("الجدران والأبواب والنوافذ القادمة من النموذج تعتبر أدلة. الفتحة لا تدخل الهندسة إلا إذا كانت قريبة من جدار موثوق.", fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Button(
                enabled = source != null && !busy,
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        runCatching {
                            val uri = source!!
                            coroutineScope {
                                val ocrJob = async { runCatching { localOcr.readSpatial(uri) }.getOrNull() }
                                val rasterJob = async { runCatching { raster.analyze(uri) }.getOrNull() }
                                val remoteJob = async { if (remote.available) runCatching { remote.analyze(uri) }.getOrNull() else null }
                                val base = vision.analyzePlan(uri)
                                val ocr = ocrJob.await()
                                val rasterResult = rasterJob.await()
                                val remoteResult = remoteJob.await()
                                val dims = DimensionEvidenceEngine.extractSpatial(ocr?.lines.orEmpty() + remoteResult?.ocrLines.orEmpty())
                                val evidenceWalls = (rasterResult?.primaryWalls.orEmpty() + remoteResult?.walls.orEmpty()).distinctBy {
                                    "${(it.start.x * 2).toInt()}:${(it.start.y * 2).toInt()}:${(it.end.x * 2).toInt()}:${(it.end.y * 2).toInt()}"
                                }
                                val openingFusion = OpeningEvidenceFusion.merge(
                                    base.openings,
                                    remoteResult?.openings.orEmpty(),
                                    base.walls + evidenceWalls
                                )
                                val enriched = base.copy(
                                    openings = openingFusion.openings,
                                    dimensions = (base.dimensions + dims).distinctBy { "${it.pageIndex}:${it.id}:${"%.3f".format(it.valueM)}" },
                                    observations = (base.observations + listOfNotNull(
                                        ocr?.let { "OCR محلي: ${it.pagesAnalyzed} صفحة." },
                                        rasterResult?.notes?.joinToString(" "),
                                        remoteResult?.let { "Deep parser: ${it.modelUsed} • ${it.confidence}% • فتحات مقبولة ${openingFusion.accepted}." },
                                        remoteResult?.warnings?.takeIf { it.isNotEmpty() }?.joinToString(" ")
                                    )).distinct()
                                )
                                FloorplanParserEngine.refine(enriched, evidenceWalls).plan
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
                Text(if (busy) "أحلل المخطط…" else "حلّل ثم راجع")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        }
    }
}
