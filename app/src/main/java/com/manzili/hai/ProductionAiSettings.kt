package com.manzili.hai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.manzili.hai.ai.AiProviderRouter
import com.manzili.hai.data.HaiSettings
import kotlinx.coroutines.launch

@Composable
fun ProductionAiSettings(nav: NavHostController) {
    val context = LocalContext.current
    val settings = remember(context) { HaiSettings(context) }
    val router = remember(context) { AiProviderRouter(context) }
    val scope = rememberCoroutineScope()

    var openRouterKey by remember { mutableStateOf(settings.openRouterApiKey) }
    var googleKey by remember { mutableStateOf(settings.googleApiKey) }
    var openRouterModels by remember { mutableStateOf<List<AiProviderRouter.ModelOption>>(emptyList()) }
    var googleModels by remember { mutableStateOf<List<AiProviderRouter.ModelOption>>(emptyList()) }
    var selectedOpenRouter by remember { mutableStateOf(settings.openRouterModels.toSet()) }
    var selectedGoogle by remember { mutableStateOf(settings.googleModels.toSet()) }
    var smartRouting by remember { mutableStateOf(settings.smartRoutingEnabled) }
    var nanoEnabled by remember { mutableStateOf(settings.nanoEnabled) }
    var nanoState by remember { mutableStateOf(AiProviderRouter.NanoState(false, false, "يفحص…")) }
    var loadingOpenRouter by remember { mutableStateOf(false) }
    var loadingGoogle by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { nanoState = router.nanoState() }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Rounded.ArrowForward, "رجوع") }
                Text("الذكاء", fontSize = 28.sp, fontWeight = FontWeight.Black)
            }

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ProviderCard(title = "بدون API") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Memory, null)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Gemini Nano", fontWeight = FontWeight.Black)
                            Text(nanoState.label, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                        }
                        Switch(checked = nanoEnabled, onCheckedChange = { nanoEnabled = it })
                    }
                    if (nanoState.downloadable) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    message = ""
                                    nanoState = router.prepareNano()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.CloudDownload, null)
                            Spacer(Modifier.width(6.dp))
                            Text("تجهيز")
                        }
                    }
                }

                ProviderCard(title = "OpenRouter") {
                    OutlinedTextField(
                        value = openRouterKey,
                        onValueChange = { openRouterKey = it },
                        label = { Text("API") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        enabled = openRouterKey.isNotBlank() && !loadingOpenRouter,
                        onClick = {
                            loadingOpenRouter = true; message = ""
                            scope.launch {
                                runCatching { router.discoverOpenRouterFree(openRouterKey) }
                                    .onSuccess { openRouterModels = it }
                                    .onFailure { message = it.message.orEmpty() }
                                loadingOpenRouter = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (loadingOpenRouter) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Refresh, null)
                        Spacer(Modifier.width(6.dp))
                        Text("النماذج المجانية")
                    }
                    ModelChoices(openRouterModels, selectedOpenRouter) { id, checked ->
                        selectedOpenRouter = if (checked) selectedOpenRouter + id else selectedOpenRouter - id
                    }
                }

                ProviderCard(title = "Google AI Studio") {
                    OutlinedTextField(
                        value = googleKey,
                        onValueChange = { googleKey = it },
                        label = { Text("API") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        enabled = googleKey.isNotBlank() && !loadingGoogle,
                        onClick = {
                            loadingGoogle = true; message = ""
                            scope.launch {
                                runCatching { router.discoverGoogleFreeCandidates(googleKey) }
                                    .onSuccess { googleModels = it }
                                    .onFailure { message = it.message.orEmpty() }
                                loadingGoogle = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (loadingGoogle) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Refresh, null)
                        Spacer(Modifier.width(6.dp))
                        Text("النماذج المجانية")
                    }
                    ModelChoices(googleModels, selectedGoogle) { id, checked ->
                        selectedGoogle = if (checked) selectedGoogle + id else selectedGoogle - id
                    }
                }

                ProviderCard(title = "تلقائي") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("الأقوى ثم البديل", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Switch(checked = smartRouting, onCheckedChange = { smartRouting = it })
                    }
                }

                if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
            }

            Button(
                onClick = {
                    settings.openRouterApiKey = openRouterKey
                    settings.googleApiKey = googleKey
                    settings.openRouterModels = selectedOpenRouter.toList()
                    settings.googleModels = selectedGoogle.toList()
                    settings.smartRoutingEnabled = smartRouting
                    settings.nanoEnabled = nanoEnabled
                    message = "تم الحفظ"
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Icon(Icons.Rounded.Save, null)
                Spacer(Modifier.width(7.dp))
                Text("حفظ", fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun ProviderCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun ModelChoices(
    models: List<AiProviderRouter.ModelOption>,
    selected: Set<String>,
    onChange: (String, Boolean) -> Unit
) {
    if (models.isEmpty()) return
    Spacer(Modifier.height(8.dp))
    models.forEach { model ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = model.id in selected, onCheckedChange = { onChange(model.id, it) })
            Column(Modifier.weight(1f)) {
                Text(model.name, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                Text(model.id, fontSize = 10.sp, color = MaterialTheme.colorScheme.secondary, maxLines = 1)
            }
        }
    }
}
