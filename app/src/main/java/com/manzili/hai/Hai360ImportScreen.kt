package com.manzili.hai

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manzili.hai.engine.MultiFloorGeometryEngine
import com.manzili.hai.engine.RemoteFloorplanEvidenceClient
import com.manzili.hai.engine.SaudiProjectTypeEngine
import com.manzili.hai.model.FloorPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    val remote = remember { RemoteFloorplanEvidenceClient(context) }

    var source by remember(initialSource) { mutableStateOf(initialSource) }
    var type by remember { mutableStateOf(SaudiProjectTypeEngine.Type.VILLA_TWO) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var progressLabel by remember { mutableStateOf("بدء العملية") }
    var analysisJob by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(Unit) {
        onDispose { analysisJob?.cancel() }
    }
    BackHandler(enabled = busy) { /* لا خروج أثناء التحليل إلا من زر الإلغاء الصريح. */ }

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
                        modifier = Modifier.clickable(enabled = !busy) { type = item }
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
                modifier = Modifier.fillMaxWidth().weight(1f).clickable(enabled = !busy) { picker.launch(arrayOf("image/*", "application/pdf")) }
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
                        Text(if (source == null) "اختر المخطط" else "جاهز للرفع", color = H360Ink, fontWeight = FontWeight.Black, fontSize = 20.sp)
                        if (source != null) {
                            Spacer(Modifier.height(5.dp))
                            Text(source?.lastPathSegment.orEmpty(), color = H360Muted, fontSize = 9.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Row(Modifier.align(Alignment.BottomCenter), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        ScannerTag("سحابي", Icons.Rounded.CloudUpload)
                        ScannerTag("غرف", Icons.Rounded.GridView)
                        ScannerTag("جدران", Icons.Rounded.ViewWeek)
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
                        Text(it, color = H360Ink, fontSize = 10.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            H360PrimaryButton(
                text = "تحليل المخطط سحابيًا",
                modifier = Modifier.fillMaxWidth(),
                enabled = source != null && !busy,
                icon = Icons.Rounded.CloudUpload
            ) {
                val uri = source ?: return@H360PrimaryButton
                busy = true
                error = null
                progress = 0
                progressLabel = "بدء العملية"
                analysisJob = scope.launch {
                    try {
                        if (!remote.available) error("خدمة القراءة السحابية غير مهيأة")
                        val result = remote.analyze(uri, maxPdfPages = 8) { update ->
                            progress = update.percent.coerceIn(0, 100)
                            progressLabel = update.label
                        }
                        progress = 98
                        progressLabel = "تثبيت الهندسة المقروءة"
                        val plan = result.toFloorPlan(title = "مخطط مستورد")
                        val typed = SaudiProjectTypeEngine.apply(plan, type)
                        val normalized = MultiFloorGeometryEngine.persistActive(MultiFloorGeometryEngine.normalize(typed))
                        progress = 100
                        progressLabel = "اكتمل التحليل"
                        delay(140)
                        onAnalyzed(normalized, type)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        error = failure.message ?: "تعذر تحليل المخطط سحابيًا"
                    } finally {
                        busy = false
                        analysisJob = null
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        if (busy) {
            Box(Modifier.fillMaxSize()) {
                // حاجز لمس مستقل تحت محتوى التقدم: يمنع أي نقرة من الوصول إلى منتقي الملفات أو الشاشة الأصلية.
                Box(
                    Modifier
                        .matchParentSize()
                        .background(H360Paper.copy(alpha = .985f))
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                )

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 34.dp)
                ) {
                    Surface(color = H360Cyan, shape = CircleShape, modifier = Modifier.size(76.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.AutoAwesome, null, tint = H360CyanDeep, modifier = Modifier.size(30.dp))
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                    Text("تحليل المخطط", color = H360Ink, fontSize = 20.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(7.dp))
                    Text(progressLabel, color = H360Muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            color = H360CyanDeep,
                            trackColor = H360Cyan,
                            modifier = Modifier.weight(1f).height(8.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("$progress%", color = H360Ink, fontSize = 13.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.height(22.dp))
                    OutlinedButton(
                        onClick = {
                            analysisJob?.cancel()
                            analysisJob = null
                            busy = false
                            progress = 0
                            onBack()
                        },
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = H360Danger),
                        border = BorderStroke(1.dp, H360Danger.copy(alpha = .38f)),
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) {
                        Icon(Icons.Rounded.Close, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("إلغاء العملية", fontWeight = FontWeight.Black)
                    }
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
