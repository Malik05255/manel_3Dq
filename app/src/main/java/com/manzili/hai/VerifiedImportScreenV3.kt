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

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.Rounded.ArrowForward, "رجوع")
                }
                Column {
                    Text("استيراد مخطط", fontSize = 24.sp, fontWeight = FontWeight.Black)
                    Text(
                        buildString {
                            append("OCR + Raster")
                            if (remote.available) append(" + Deep Parser")
                            if (vision.available) append(" + HAI Vision")
                        },
                        fontSize = 13.sp
                    )
                    projectType?.let {
                        Text("النوع المختار: ${it.label}", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            OutlinedButton(
                onClick = { picker.launch(arrayOf("image/*", "application/pdf")) },
                modifier = Modifier.fillMaxWidth().height(72.dp)
            ) {
                Text(if (source == null) "اختر PDF أو صورة" else "الملف جاهز للتحليل")
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "PDF: يفحص حتى أول 5 صفحات. OCR وRaster يعملان محليًا، وDeep Parser وHAI Vision يضافان عند توفرهما. عدم تفعيل HAI لا يمنعك من المتابعة.",
                fontSize = 11.sp
            )
            Spacer(Modifier.height(5.dp))
            Text(
                "نوع المشروع المختار يساعد على تفسير الوظائف فقط، ولا يسمح باختلاق عناصر غير ظاهرة في المخطط.",
                fontSize = 11.sp
            )

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
                                val visionJob = async {
                                    if (vision.available) {
                                        runCatching {
                                            vision.analyze(uri, maxPdfPages = 5, projectType = projectType)
                                        }.getOrNull()
                                    } else null
                                }
                                val ocrJob = async {
                                    runCatching { localOcr.readSpatial(uri, maxPdfPages = 5) }.getOrNull()
                                }
                                val rasterJob = async {
                                    runCatching { raster.analyze(uri, maxPdfPages = 5) }.getOrNull()
                                }
                                val remoteJob = async {
                                    if (remote.available) {
                                        runCatching { remote.analyze(uri, maxPdfPages = 5) }.getOrNull()
                                    } else null
                                }

                                val visionPlan = visionJob.await()
                                val ocr = ocrJob.await()
                                val rasterResult = rasterJob.await()
                                val remoteResult = remoteJob.await()

                                val base = visionPlan ?: FloorPlan(
                                    title = "مخطط مستورد",
                                    sourceSummary = "تحليل استيراد متعدد المسارات بدون اشتراط مزود HAI Vision.",
                                    observations = listOf(
                                        "تم السماح بالتحليل عبر OCR وRaster وDeep Parser المتاح دون اشتراط HAI Vision."
                                    ),
                                    uncertainties = if (!vision.available) {
                                        listOf("HAI Vision غير مفعّل؛ راجع العناصر المستخرجة في شاشة التحقق قبل اعتمادها.")
                                    } else emptyList()
                                )

                                val dims = DimensionEvidenceEngine.extractSpatial(
                                    ocr?.lines.orEmpty() + remoteResult?.ocrLines.orEmpty()
                                )

                                val enriched = base.copy(
                                    dimensions = (base.dimensions + dims).distinctBy {
                                        "${it.pageIndex}:${it.id}:${"%.3f".format(it.valueM)}"
                                    },
                                    observations = (base.observations + listOfNotNull(
                                        projectType?.let { "نوع المشروع المحدد قبل التحليل: ${it.label}." },
                                        ocr?.let {
                                            "OCR محلي: ${it.pagesAnalyzed} صفحة${if (it.truncated) " (محدود)" else ""}."
                                        },
                                        rasterResult?.notes?.joinToString(" "),
                                        remoteResult?.let {
                                            "Deep Parser: ${it.pages.size} صفحة • ${it.modelUsed} • متوسط ${it.confidence}%."
                                        },
                                        remoteResult?.warnings?.takeIf { it.isNotEmpty() }?.joinToString(" "),
                                        if (visionPlan != null) "HAI Vision شارك في التحليل." else "تمت المتابعة بدون HAI Vision."
                                    )).distinct()
                                )

                                val deepApplied = MultiPageEvidenceFusionEngine.apply(
                                    enriched,
                                    remoteResult?.pages.orEmpty()
                                )
                                val locallyRefined = FloorplanParserEngine.refine(
                                    deepApplied,
                                    rasterResult?.primaryWalls.orEmpty()
                                ).plan
                                val typed = projectType?.let {
                                    SaudiProjectTypeEngine.apply(locallyRefined, it)
                                } ?: locallyRefined

                                MultiFloorGeometryEngine.persistActive(
                                    MultiFloorGeometryEngine.normalize(typed)
                                )
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
                if (busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.AutoAwesome, null)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        busy -> "أحلل المخطط…"
                        source == null -> "اختر ملفًا أولًا"
                        else -> "حلّل المخطط ثم راجع"
                    }
                )
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
