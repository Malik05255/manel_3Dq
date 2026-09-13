package com.manzili.hai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Save
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
    var advanced by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var probing by remember { mutableStateOf(false) }
    var probe by remember { mutableStateOf<BackendProbe?>(null) }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(18.dp)) {
            StudioHeader("الإعدادات", { nav.popBackStack() })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                StudioSection("منزلي HAI", "استوديو التصميم المعماري")
                Card(colors = CardDefaults.cardColors(containerColor = StudioColors.Canvas)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp)) {
                        Text("المظهر", style = MaterialTheme.typography.titleMedium)
                        Text("أبيض وكحلي · أيقونات خطية", color = StudioColors.Muted)
                    }
                }
                TextButton(onClick = { nav.navigate("cloud") }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.CloudSync, null); Spacer(Modifier.width(8.dp)); Text("الحساب والمزامنة")
                }
                OutlinedButton(onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth()) {
                    Text("إعدادات الاتصال المتقدمة"); Spacer(Modifier.width(8.dp))
                    Icon(if (advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                }
                if (advanced) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("الاتصال عبر الخادم", fontWeight = FontWeight.Bold)
                        Text("استخدام خدمة HAI المهيأة.", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                    }
                    Switch(backendMode, { backendMode = it; probe = null })
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(model, { model = it }, label = { Text("اسم النموذج") }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
                        else Icon(Icons.Outlined.CloudDone, null)
                        Spacer(Modifier.width(7.dp))
                        Text(if (probing) "يفحص الخدمات…" else "اختبار الاتصال")
                    }
                    probe?.let { result ->
                        Text(
                            result.message,
                            fontSize = 12.sp,
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
                        fontSize = 12.sp,
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
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            }
            if (advanced) {
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
                Icon(Icons.Outlined.Save, null)
                Spacer(Modifier.width(7.dp))
                Text("حفظ الاتصال")
            }
            if (saved) Text("تم الحفظ", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(10.dp))
            }
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
        val healthRequest = Request.Builder()
            .url("$base/health")
            .get()
            .build()
        val health = client.newCall(healthRequest).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return@use null
            }
            JSONObject(body)
        } ?: return@runCatching BackendProbe(false, "Backend لا يعرض حالة الخدمات عبر /health")

        val aiConfigured = health.optBoolean("ai_configured", false)
        val serviceAuthConfigured = health.optBoolean("service_auth_configured", false)
        val supabaseConfigured = health.optBoolean("supabase_configured", false)

        val parserRequest = Request.Builder()
            .url("$base/v1/parser/status")
            .header("Authorization", "Bearer ${token.trim()}")
            .get()
            .build()
        client.newCall(parserRequest).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return@use BackendProbe(false, "فشل مصادقة Backend (${response.code}): ${body.take(120)}")
            }
            val json = JSONObject(body)
            val parserReady = json.optBoolean("ready", false)
            val path = json.optString("preferred_path", "")
            val missing = buildList {
                if (!parserReady) add("Deep Parser")
                if (!aiConfigured) add("HAI/AI_API_KEY")
                if (!serviceAuthConfigured) add("Service auth")
            }
            if (missing.isNotEmpty()) {
                BackendProbe(false, "Backend متصل لكن غير مكتمل: ${missing.joinToString("، ")}")
            } else {
                val cloud = if (supabaseConfigured) "Cloud/Supabase ✓" else "Cloud/Supabase غير مهيأ"
                BackendProbe(
                    true,
                    "Backend ✓ • HAI ✓ • Deep Parser ✓${if (path.isNotBlank()) " ($path)" else ""} • $cloud"
                )
            }
        }
    }.getOrElse { BackendProbe(false, "فشل الاتصال: ${it.message.orEmpty().take(140)}") }
}
