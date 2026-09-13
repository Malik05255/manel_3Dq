package com.manzili.hai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.manzili.hai.data.CloudSyncClient
import com.manzili.hai.data.HaiSettings
import com.manzili.hai.data.ProjectPlanStore
import com.manzili.hai.data.SafeCloudUploaderV2
import com.manzili.hai.data.SupabaseOtpAuth
import com.manzili.hai.model.FloorPlan
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudSyncScreen(
    nav: NavHostController,
    store: ProjectPlanStore,
    onPlanChanged: (FloorPlan?) -> Unit
) {
    val context = LocalContext.current
    val settings = remember(context) { HaiSettings(context) }
    val auth = remember(settings) { SupabaseOtpAuth(settings) }
    val cloud = remember(settings) { CloudSyncClient(settings) }
    val uploader = remember(settings, cloud) { SafeCloudUploaderV2(settings, cloud) }
    val scope = rememberCoroutineScope()

    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var codeSent by remember { mutableStateOf(false) }
    var signedIn by remember { mutableStateOf(settings.cloudSessionConfigured) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var remote by remember { mutableStateOf<List<CloudSyncClient.RemoteProjectSummary>>(emptyList()) }

    fun refreshRemote() {
        if (!signedIn || busy) return
        busy = true
        message = null
        scope.launch {
            runCatching { cloud.listProjects() }
                .onSuccess { remote = it; message = "تم تحديث السحابة" }
                .onFailure { message = it.message ?: "تعذر تحديث السحابة" }
            signedIn = settings.cloudSessionConfigured
            busy = false
        }
    }

    LaunchedEffect(signedIn) {
        if (signedIn && settings.cloudConfigured) refreshRemote()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("السحابة", fontWeight = FontWeight.Bold)
                        Text("مشاريعك على أجهزتك", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Outlined.ArrowForward, "رجوع") }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!settings.cloudConfigured) {
                ElevatedCard(shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Icon(Icons.Outlined.CloudOff, null)
                        Spacer(Modifier.height(8.dp))
                        Text("السحابة غير مهيأة", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("أكمل إعدادات الاتصال لتفعيل المزامنة.", fontSize = 12.sp)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { nav.navigate("settings") }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.Settings, null); Spacer(Modifier.width(6.dp)); Text("فتح إعدادات الاتصال")
                        }
                    }
                }
            } else if (!signedIn) {
                ElevatedCard(shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("تسجيل الدخول", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("سنرسل رمز تحقق إلى بريدك. لا يتم تخزين كلمة مرور داخل التطبيق.", fontSize = 12.sp)
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it; message = null },
                            label = { Text("البريد الإلكتروني") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (codeSent) {
                            OutlinedTextField(
                                value = code,
                                onValueChange = { code = it.filter(Char::isDigit).take(12); message = null },
                                label = { Text("رمز التحقق") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Button(
                            enabled = !busy && email.contains('@') && (!codeSent || code.isNotBlank()),
                            onClick = {
                                busy = true
                                message = null
                                scope.launch {
                                    if (!codeSent) {
                                        runCatching { auth.requestCode(email) }
                                            .onSuccess { codeSent = true; message = "تم إرسال رمز التحقق" }
                                            .onFailure { message = it.message ?: "تعذر إرسال الرمز" }
                                    } else {
                                        runCatching { auth.verifyCode(email, code) }
                                            .onSuccess { signedIn = true; message = "تم تسجيل الدخول" }
                                            .onFailure { message = it.message ?: "تعذر تسجيل الدخول" }
                                    }
                                    busy = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(17.dp)
                        ) {
                            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(if (codeSent) Icons.Outlined.VerifiedUser else Icons.Outlined.MarkEmailUnread, null)
                            Spacer(Modifier.width(7.dp))
                            Text(if (codeSent) "تحقق وسجّل الدخول" else "إرسال الرمز", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                ElevatedCard(shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.CloudDone, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text("الجلسة السحابية متصلة", fontWeight = FontWeight.Bold)
                                Text("المشاريع محمية بسياسات RLS ومنع الكتابة فوق إصدار أحدث", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                            }
                            IconButton(onClick = {
                                cloud.signOut(); signedIn = false; remote = emptyList(); message = "تم تسجيل الخروج"
                            }) { Icon(Icons.Outlined.Logout, "تسجيل الخروج") }
                        }

                        val activeId = store.activeProjectId()
                        val active = activeId?.let(store::load)
                        Button(
                            enabled = !busy && activeId != null && active != null,
                            onClick = {
                                busy = true; message = null
                                scope.launch {
                                    runCatching { uploader.upload(activeId!!, active!!) }
                                        .onSuccess { result ->
                                            val synced = store.upsertProject(activeId!!, result.plan, makeActive = true)
                                            onPlanChanged(synced)
                                            message = "تم رفع المشروع الحالي • V${result.revision}"
                                            remote = runCatching { cloud.listProjects() }.getOrDefault(remote)
                                        }
                                        .onFailure { error ->
                                            message = when (error) {
                                                is SafeCloudUploaderV2.Conflict -> error.message
                                                else -> error.message ?: "فشل رفع المشروع"
                                            }
                                            remote = runCatching { cloud.listProjects() }.getOrDefault(remote)
                                        }
                                    busy = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.CloudUpload, null); Spacer(Modifier.width(6.dp)); Text("رفع المشروع الحالي")
                        }

                        OutlinedButton(enabled = !busy, onClick = { refreshRemote() }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(6.dp)); Text("تحديث القائمة")
                        }
                    }
                }

                Text("المشاريع السحابية", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                if (remote.isEmpty()) {
                    Text("لا توجد مشاريع سحابية أو لم تُحمّل القائمة بعد.", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                }
                remote.forEach { item ->
                    Card(shape = RoundedCornerShape(18.dp)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.HomeWork, null)
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.title, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text("V${item.revision}${item.updatedAt.takeIf { it.isNotBlank() }?.let { " • ${it.take(10)}" } ?: ""}", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                            }
                            IconButton(
                                enabled = !busy,
                                onClick = {
                                    busy = true; message = null
                                    scope.launch {
                                        runCatching { cloud.download(item.id) }
                                            .onSuccess { downloaded ->
                                                val local = store.upsertProject(item.id, downloaded, makeActive = true)
                                                onPlanChanged(local)
                                                message = "تم تنزيل وفتح المشروع"
                                            }
                                            .onFailure { message = it.message ?: "فشل تنزيل المشروع" }
                                        busy = false
                                    }
                                }
                            ) { Icon(Icons.Outlined.CloudDownload, "تنزيل") }
                        }
                    }
                }
            }

            message?.let {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(it, Modifier.fillMaxWidth().padding(12.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.navigationBarsPadding().height(16.dp))
        }
    }
}
