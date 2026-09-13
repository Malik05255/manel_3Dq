package com.manzili.hai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.manzili.hai.data.HaiSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private data class BackendProbe(val ok: Boolean, val message: String)

@Composable
fun ProductionAiSettings(nav: NavHostController) {
    val context = LocalContext.current
    val settings = remember(context) { HaiSettings(context) }
    val scope = rememberCoroutineScope()
    var backendMode by remember { mutableStateOf(settings.backendMode) }
    var backendUrl by remember { mutableStateOf(settings.backendBaseUrl) }
    var backendToken by remember { mutableStateOf(settings.backendServiceToken) }
    var supabaseUrl by remember { mutableStateOf(settings.supabaseUrl) }
    var publishable by remember { mutableStateOf(settings.supabasePublishableKey) }
    var directEndpoint by remember { mutableStateOf(settings.directEndpoint) }
    var directKey by remember { mutableStateOf(settings.directApiKey) }
    var model by remember { mutableStateOf(settings.model) }
    var saved by remember { mutableStateOf(false) }
    var probing by remember { mutableStateOf(false) }
    var probe by remember { mutableStateOf<BackendProbe?>(null) }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(18.dp)) {
            Row {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Rounded.ArrowForward, "رجوع") }
                Column {
                    Text("اتصال HAI", fontSize = 23.sp, fontWeight = FontWeight.Black)
                    Text("Backend آمن للإنتاج • Direct للتطوير", color = MaterialTheme.colorScheme.secondary, fontSize = 10.sp)
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("استخدم Backend الآمن", fontWeight = FontWeight.Bold)
                        Text("عند تفعيله لا يحتاج التطبيق مفتاح مزود AI.", fontSize = 10.sp, color = MaterialTheme.colorScheme.secondary)
                    }
                    Switch(backendMode, { backendMode = it; probe = null })
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(model, { model = it }, label = { Text("Model ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                if (backendMode) {
                    OutlinedTextField(
                        backendUrl,
                        { backendUrl = it; probe = null },
                        label = { Text("Backend URL") },
                        supportingText = { Text("مثال: https://manzili-hai-deep-parser.onrender.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        backendToken,
                        { backendToken = it; probe = null },
                        label = { Text("Backend service token") },
                        supportingText = { Text("مستقل عن جلسة Supabase، ويُخزن AES-256 مشفرًا على الجهاز.") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        enabled = !probing && backendUrl.startsWith("https://") && backendToken.isNotBlank(),
                        onClick = {
                            probing = true
                            probe = null
                            scope.launch {
                                val result = probeBackend(backendUrl, backendToken)
                                probe = result
                                probing = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        if (probing) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.CloudDone, null)
                        Spacer(Modifier.width(7.dp))
                        Text(if (probing) "يفحص Deep Parser…" else "اختبار Backend وDeep Parser")
                    }
                    probe?.let { result ->
                        Text(
                            result.message,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (result.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 7.dp)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        supabaseUrl,
                        { supabaseUrl = it },
                        label = { Text("Supabase URL (اختياري)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        publishable,
                        { publishable = it },
                        label = { Text("Supabase publishable key (اختياري)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "جلسة Supabase مستقلة عن رمز الخدمة. تسجيل الدخول للسحابة لن يغيّر رمز Backend المحفوظ.",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                } else {
                    OutlinedTextField(
                        directEndpoint,
                        { directEndpoint = it },
                        label = { Text("Provider endpoint") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        directKey,
                        { directKey = it },
                        label = { Text("Provider API key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Direct mode مناسب للتطوير فقط؛ المفتاح يُخزن مشفرًا لكنه يبقى موجودًا على جهاز العميل.",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Button(
                onClick = {
                    settings.backendMode = backendMode
                    settings.backendBaseUrl = backendUrl
                    settings.backendServiceToken = backendToken
                    settings.supabaseUrl = supabaseUrl
                    settings.supabasePublishableKey = publishable
                    settings.directEndpoint = directEndpoint
                    settings.directApiKey = directKey
                    settings.model = model
                    saved = true
                },
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Icon(Icons.Rounded.Save, null)
                Spacer(Modifier.width(7.dp))
                Text("حفظ الاتصال")
            }
            if (saved) Text("تم الحفظ", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(10.dp))
        }
    }
}

private suspend fun probeBackend(rawBaseUrl: String, token: String): BackendProbe = withContext(Dispatchers.IO) {
    val base = rawBaseUrl.trim().trimEnd('/')
    if (!base.startsWith("https://")) {
        return@withContext BackendProbe(false, "رابط Backend يجب أن يستخدم HTTPS")
    }
    val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    runCatching {
        val request = Request.Builder()
            .url("$base/v1/parser/status")
            .header("Authorization", "Bearer ${token.trim()}")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return@use BackendProbe(false, "Backend رد ${response.code}: ${body.take(120)}")
            }
            val json = JSONObject(body)
            val ready = json.optBoolean("ready", false)
            val path = json.optString("preferred_path", "")
            if (ready) {
                BackendProbe(true, "متصل ✓ Deep Parser جاهز فعليًا${if (path.isNotBlank()) " • $path" else ""}")
            } else {
                BackendProbe(false, "Backend متصل لكن نموذج Deep Parser غير جاهز")
            }
        }
    }.getOrElse { BackendProbe(false, "فشل الاتصال: ${it.message.orEmpty().take(140)}") }
}
