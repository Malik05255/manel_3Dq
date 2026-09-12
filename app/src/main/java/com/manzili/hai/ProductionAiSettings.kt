package com.manzili.hai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
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

@Composable
fun ProductionAiSettings(nav: NavHostController) {
    val context = LocalContext.current
    val settings = remember(context) { HaiSettings(context) }
    var backendMode by remember { mutableStateOf(settings.backendMode) }
    var backendUrl by remember { mutableStateOf(settings.backendBaseUrl) }
    var supabaseUrl by remember { mutableStateOf(settings.supabaseUrl) }
    var publishable by remember { mutableStateOf(settings.supabasePublishableKey) }
    var directEndpoint by remember { mutableStateOf(settings.directEndpoint) }
    var directKey by remember { mutableStateOf(settings.directApiKey) }
    var model by remember { mutableStateOf(settings.model) }
    var saved by remember { mutableStateOf(false) }

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
                    Switch(backendMode, { backendMode = it })
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(model, { model = it }, label = { Text("Model ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                if (backendMode) {
                    OutlinedTextField(backendUrl, { backendUrl = it }, label = { Text("Backend URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(supabaseUrl, { supabaseUrl = it }, label = { Text("Supabase URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(publishable, { publishable = it }, label = { Text("Supabase publishable key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("رمز الجلسة يُخزن مشفرًا على الجهاز عند ربط الحساب.", fontSize = 10.sp, color = MaterialTheme.colorScheme.secondary)
                } else {
                    OutlinedTextField(directEndpoint, { directEndpoint = it }, label = { Text("Provider endpoint") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(directKey, { directKey = it }, label = { Text("Provider API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("Direct mode مناسب للتطوير فقط؛ المفتاح يُخزن مشفرًا لكنه يبقى موجودًا على جهاز العميل.", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                }
            }
            Button(onClick = {
                settings.backendMode = backendMode
                settings.backendBaseUrl = backendUrl
                settings.supabaseUrl = supabaseUrl
                settings.supabasePublishableKey = publishable
                settings.directEndpoint = directEndpoint
                settings.directApiKey = directKey
                settings.model = model
                saved = true
            }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Icons.Rounded.Save, null); Spacer(Modifier.width(7.dp)); Text("حفظ الاتصال")
            }
            if (saved) Text("تم الحفظ", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(10.dp))
        }
    }
}
