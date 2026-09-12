package com.manzili.hai

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.UploadFile
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
import com.manzili.hai.ai.HaiArchitectClient
import com.manzili.hai.engine.*
import com.manzili.hai.model.FloorPlan
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Composable
fun VerifiedImportScreenV2(nav: NavHostController, source: Uri?, setSource: (Uri) -> Unit, onAnalyzed: (FloorPlan) -> Unit) {
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

    Surface(color = Color(0xFFF7F4EE), modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Rounded.ArrowForward, "رجوع") }
                Column {
                    Text("استيراد مخطط", fontSize = 24.sp, fontWeight = FontWeight.Black)
                    Text(if (remote.available) "Local + Remote evidence fusion" else "Local evidence + Vision", color = Color.Gray, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(18.dp))
            Card(onClick = { picker.launch(arrayOf("image/*", "application/pdf")) }, modifier = Modifier.fillMaxWidth().height(170.dp), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (source == null) Icons.Rounded.UploadFile else Icons.Rounded.TaskAlt, null, modifier = Modifier.size(42.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(if (source == null) "اختر PDF أو صورة" else "الملف جاهز", fontWeight = FontWeight.Bold)
                    Text(if (remote.available) "Deep parser/OCR العربي يستخدمان عند توفرهما على الخادم" else "يمكن تفعيل الخادم لاحقًا من الإعدادات", color = Color.Gray, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("Vision → OCR مكاني → Raster → Remote segmentation/OCR → Fusion", fontWeight = FontWeight.Bold, fontSize = 11.sp)
            Text("الدليل غير المتطابق يبقى uncertainty ولا يتحول إلى هندسة معتمدة تلقائيًا.", color = Color.Gray, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
            Spacer(Modifier.weight(1f))
            Button(enabled = source != null && !busy, onClick = {
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
                            val walls = (rasterResult?.primaryWalls.orEmpty() + remoteResult?.walls.orEmpty()).distinctBy {
                                "${(it.start.x * 2).toInt()}:${(it.start.y * 2).toInt()}:${(it.end.x * 2).toInt()}:${(it.end.y * 2).toInt()}"
                            }
                            val enriched = base.copy(
                                dimensions = (base.dimensions + dims).distinctBy { "${it.pageIndex}:${it.id}:${"%.3f".format(it.valueM)}" },
                                observations = (base.observations + listOfNotNull(
                                    ocr?.let { "OCR محلي: ${it.pagesAnalyzed} صفحة." },
                                    rasterResult?.notes?.joinToString(" "),
                                    remoteResult?.let { "Remote parser: ${it.modelUsed} • ${it.confidence}%." },
                                    remoteResult?.warnings?.takeIf { it.isNotEmpty() }?.joinToString(" ")
                                )).distinct()
                            )
                            FloorplanParserEngine.refine(enriched, walls).plan
                        }
                    }.onSuccess { onAnalyzed(it); nav.navigate("verify") }
                        .onFailure { error = it.message ?: "فشل تحليل المخطط" }
                    busy = false
                }
            }, modifier = Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(18.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(21.dp), strokeWidth = 2.dp, color = Color.White) else Icon(Icons.Rounded.AutoAwesome, null)
                Spacer(Modifier.width(8.dp)); Text(if (busy) "أحلل…" else "حلّل ثم راجع القراءة", fontWeight = FontWeight.Bold)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(12.dp))
        }
    }
}
