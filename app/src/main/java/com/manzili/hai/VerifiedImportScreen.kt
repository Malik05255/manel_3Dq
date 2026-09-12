package com.manzili.hai

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.manzili.hai.ai.HaiArchitectClient
import com.manzili.hai.engine.PlanVerificationEngine
import com.manzili.hai.model.FloorPlan
import kotlinx.coroutines.launch

private val VISand = Color(0xFFF7F4EE)
private val VIPaper = Color(0xFFFFFEFA)
private val VIBronze = Color(0xFF9A7447)

@Composable
fun VerifiedImportScreen(
    nav: NavHostController,
    source: Uri?,
    setSource: (Uri) -> Unit,
    onAnalyzed: (FloorPlan) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { HaiArchitectClient(context) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            setSource(uri)
            error = null
        }
    }

    Surface(color = VISand, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Rounded.ArrowForward, "رجوع") }
                Column {
                    Text("استيراد مخطط", fontSize = 24.sp, fontWeight = FontWeight.Black)
                    Text("التحليل يمر بشاشة تحقق قبل المحرر", color = Color.Gray, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(18.dp))
            Card(
                onClick = { picker.launch(arrayOf("image/*", "application/pdf")) },
                colors = CardDefaults.cardColors(containerColor = VIPaper),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().height(170.dp)
            ) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (source == null) Icons.Rounded.UploadFile else Icons.Rounded.TaskAlt, null, tint = VIBronze, modifier = Modifier.size(42.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(if (source == null) "اختر PDF أو صورة" else "الملف جاهز للتحليل", fontWeight = FontWeight.Bold)
                    Text("لن نعتمد أي بعد غير مؤكد بصمت", color = Color.Gray, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Vision → هندسة → مضلعات → فحص ثقة ومقياس → تأكيدك", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text("أي منطقة ضعيفة الثقة تظهر لك قبل أن تدخل التعديل.", color = Color.Gray, lineHeight = 18.sp, fontSize = 10.5.sp, modifier = Modifier.padding(top = 5.dp))
            Spacer(Modifier.weight(1f))
            Button(
                enabled = source != null && !busy,
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        runCatching {
                            val raw = client.analyzePlan(source!!)
                            PlanVerificationEngine.inspect(raw).plan
                        }.onSuccess {
                            onAnalyzed(it)
                            nav.navigate("verify")
                        }.onFailure { error = it.message ?: "فشل تحليل المخطط" }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth().height(58.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(21.dp), strokeWidth = 2.dp, color = Color.White) else Icon(Icons.Rounded.AutoAwesome, null)
                Spacer(Modifier.width(8.dp))
                Text(if (busy) "أقرأ وأحوّل الهندسة…" else "حلّل ثم راجع القراءة", fontWeight = FontWeight.Bold)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(14.dp))
        }
    }
}
