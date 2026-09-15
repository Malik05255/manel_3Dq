package com.manzili.hai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Dataset
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manzili.hai.data.CloudSyncClient
import com.manzili.hai.data.HaiSettings
import com.manzili.hai.data.ReaderCorrectionCandidate
import com.manzili.hai.data.ReaderCorrectionStore
import com.manzili.hai.data.ReaderLearningCloudUploader
import com.manzili.hai.data.ReaderLearningConsent
import com.manzili.hai.data.ReaderLearningDatasetExporter
import com.manzili.hai.engine.SaudiProjectTypeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReaderLearningActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Hai360Theme {
                ReaderLearningScreen(onClose = { finish() })
            }
        }
    }
}

@Composable
private fun ReaderLearningScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ReaderCorrectionStore(context) }
    val exporter = remember { ReaderLearningDatasetExporter(context) }
    val settings = remember { HaiSettings(context) }
    val cloud = remember { CloudSyncClient(settings) }
    val cloudUploader = remember { ReaderLearningCloudUploader(context, settings, cloud) }

    var candidates by remember { mutableStateOf(store.listCandidates()) }
    var selectedId by remember(candidates) { mutableStateOf(candidates.firstOrNull()?.id) }
    var city by remember { mutableStateOf("") }
    var region by remember { mutableStateOf("") }
    var floors by remember { mutableStateOf("1") }
    var projectType by remember { mutableStateOf(SaudiProjectTypeEngine.Type.VILLA_TWO) }
    var ownsOrLicensed by remember { mutableStateOf(false) }
    var deidentified by remember { mutableStateOf(false) }
    var pendingConsent by remember { mutableStateOf<ReaderLearningConsent?>(null) }
    var pendingCandidate by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val createZip = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { destination ->
        val consent = pendingConsent
        val candidateId = pendingCandidate
        if (destination == null || consent == null || candidateId == null) {
            busy = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { exporter.export(candidateId, destination, consent) }
            }
            message = result.fold(
                onSuccess = { "تم تجهيز Dataset محليًا. لم يتم رفع أي ملف تلقائيًا." },
                onFailure = { "تعذر التصدير: ${it.message.orEmpty().take(180)}" }
            )
            candidates = store.listCandidates()
            busy = false
            pendingConsent = null
            pendingCandidate = null
        }
    }

    val selected = candidates.firstOrNull { it.id == selectedId }
    val consent = ReaderLearningConsent(
        ownsOrLicensed = ownsOrLicensed,
        deidentified = deidentified,
        city = city,
        region = region,
        projectType = projectType.name.lowercase(),
        floors = floors.toIntOrNull() ?: 0
    )

    Scaffold(containerColor = H360Ivory) { padding ->
        Column(
            Modifier.fillMaxSize()
                .padding(padding)
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                H360IconButton(Icons.Rounded.ArrowForward, "إغلاق", onClick = onClose)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("تحسين قارئ HAI", color = H360Ink, fontSize = 23.sp, fontWeight = FontWeight.Black)
                    Text("تصحيحاتك تبقى خاصة حتى تختار أنت الحفظ أو الإرسال", color = H360Muted, fontSize = 10.sp)
                }
            }

            Spacer(Modifier.height(16.dp))
            Surface(
                color = H360Paper,
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, H360Line),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, null, tint = H360CyanDeep)
                    Spacer(Modifier.width(9.dp))
                    Text(
                        "لا يوجد رفع تلقائي. الإرسال للسحابة يحدث فقط بعد موافقتك وضغط زر إرسال لتحسين HAI، ويُحفظ في مساحة خاصة بحسابك.",
                        color = H360Ink,
                        fontSize = 10.5.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("الحالات المصححة (${candidates.size})", color = H360Ink, fontWeight = FontWeight.Black, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))

            if (candidates.isEmpty()) {
                Surface(color = H360Paper, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, H360Line), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Dataset, null, tint = H360CyanDeep, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("لا توجد تصحيحات محفوظة بعد", color = H360Ink, fontWeight = FontWeight.Bold)
                        Text("بعد أن يصحح المستخدم قراءة خاطئة ويعتمد المخطط، ستظهر الحالة هنا.", color = H360Muted, fontSize = 10.sp)
                    }
                }
            } else {
                candidates.take(20).forEach { candidate ->
                    CandidateCard(candidate, selected = candidate.id == selectedId) { selectedId = candidate.id }
                    Spacer(Modifier.height(7.dp))
                }
            }

            if (selected != null) {
                Spacer(Modifier.height(12.dp))
                Text("بيانات الحالة", color = H360Ink, fontWeight = FontWeight.Black, fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(city, { city = it }, label = { Text("المدينة") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(region, { region = it }, label = { Text("المنطقة / Region") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(floors, { floors = it.filter(Char::isDigit).take(2) }, label = { Text("عدد الأدوار") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))

                Text("نوع المشروع", color = H360Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                SaudiProjectTypeEngine.Type.entries.forEach { type ->
                    Row(
                        Modifier.fillMaxWidth().clickable { projectType = type }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = projectType == type, onClick = { projectType = type })
                        Text(type.label, color = H360Ink, fontSize = 11.sp)
                    }
                }

                Spacer(Modifier.height(8.dp))
                ConsentRow(
                    checked = ownsOrLicensed,
                    onChecked = { ownsOrLicensed = it },
                    text = "أؤكد أن المخطط ملكي أو لدي إذن صريح لاستخدامه لتحسين القارئ."
                )
                ConsentRow(
                    checked = deidentified,
                    onChecked = { deidentified = it },
                    text = "أؤكد أن الملف الذي سأرسله أو أصدره لا يحتوي أسماء أو هواتف أو أرقام قطع أو بيانات تعريفية غير لازمة."
                )

                consent.validationErrors().firstOrNull()?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = H360Danger, fontSize = 9.5.sp)
                }

                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = {
                        if (!settings.cloudSessionConfigured) {
                            message = "سجّل الدخول للسحابة أولًا، ثم أعد محاولة الإرسال."
                        } else {
                            busy = true
                            scope.launch {
                                val result = runCatching { cloudUploader.upload(selected.id, consent) }
                                message = result.fold(
                                    onSuccess = { "تم إرسال الحالة إلى Dataset الخاص بـHAI. لن تدخل نموذجًا جديدًا إلا بعد اجتياز اختبارات الدقة." },
                                    onFailure = { "تعذر الإرسال: ${it.message.orEmpty().take(180)}" }
                                )
                                candidates = store.listCandidates()
                                busy = false
                            }
                        }
                    },
                    enabled = consent.valid && !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = H360CyanDeep),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    if (busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary)
                    else Icon(Icons.Rounded.Dataset, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("إرسال لتحسين HAI", fontWeight = FontWeight.Black)
                }

                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        pendingConsent = consent
                        pendingCandidate = selected.id
                        busy = true
                        createZip.launch("manzili-hai-${selected.id}.zip")
                    },
                    enabled = consent.valid && !busy,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    Icon(Icons.Rounded.SaveAlt, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("حفظ Dataset على الجهاز", fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        store.delete(selected.id)
                        candidates = store.listCandidates()
                        selectedId = candidates.firstOrNull()?.id
                        message = "تم حذف حالة التصحيح من الجهاز."
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = H360Danger),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Icon(Icons.Rounded.DeleteOutline, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("حذف هذه الحالة", fontWeight = FontWeight.Bold)
                }
            }

            message?.let {
                Spacer(Modifier.height(12.dp))
                Surface(color = H360Cyan, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(it, color = H360Ink, fontSize = 10.5.sp, modifier = Modifier.padding(12.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun CandidateCard(candidate: ReaderCorrectionCandidate, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) H360Cyan else H360Paper,
        shape = RoundedCornerShape(19.dp),
        border = BorderStroke(1.dp, if (selected) H360CyanDeep else H360Line),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Dataset, null, tint = H360CyanDeep)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text("${candidate.delta.totalChanges} تصحيح", color = H360Ink, fontWeight = FontWeight.Black, fontSize = 12.sp)
                Text(
                    "غرف ${candidate.delta.roomsChanged} · جدران ${candidate.delta.wallsChanged} · فتحات ${candidate.delta.openingsChanged} · أبعاد ${candidate.delta.dimensionsChanged}",
                    color = H360Muted,
                    fontSize = 8.8.sp
                )
            }
            if (candidate.status == ReaderCorrectionStore.STATUS_EXPORTED) {
                Text("صُدّرت", color = H360CyanDeep, fontSize = 8.5.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun ConsentRow(checked: Boolean, onChecked: (Boolean) -> Unit, text: String) {
    Row(
        Modifier.fillMaxWidth().clickable { onChecked(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Spacer(Modifier.width(5.dp))
        Text(text, color = H360Ink, fontSize = 10.5.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 11.dp))
    }
}
