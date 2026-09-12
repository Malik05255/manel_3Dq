package com.manzili.hai

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.manzili.hai.ai.AiProviderRouter
import com.manzili.hai.data.HaiSettings
import kotlinx.coroutines.launch

private val AiInk = Color(0xFF171817)
private val AiPurple = Color(0xFF5546C8)
private val AiWarm = Color(0xFFF7F4EE)
private val AiMint = Color(0xFFE9F3EE)
private val AiLavender = Color(0xFFEDEAFF)

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
    var savedPulse by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { nanoState = router.nanoState() }

    Surface(Modifier.fillMaxSize(), color = AiWarm) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.Rounded.ArrowForward, "رجوع")
                }
                Spacer(Modifier.weight(1f))
                Surface(color = AiLavender, shape = RoundedCornerShape(18.dp)) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(8.dp).background(Color(0xFF4C9B78), CircleShape)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("HAI", fontWeight = FontWeight.Black, color = AiPurple)
                    }
                }
            }

            Text("الذكاء", fontSize = 36.sp, fontWeight = FontWeight.Black, color = AiInk)
            Text("اختره مرة واحدة.", fontSize = 14.sp, color = Color(0xFF77716A))
            Spacer(Modifier.height(18.dp))

            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SmartRouterCard(
                    enabled = smartRouting,
                    configuredCount = selectedOpenRouter.size + selectedGoogle.size + if (nanoEnabled && nanoState.available) 1 else 0,
                    onToggle = { smartRouting = it }
                )

                NanoProviderCard(
                    state = nanoState,
                    enabled = nanoEnabled,
                    onEnabled = { nanoEnabled = it },
                    onPrepare = {
                        scope.launch {
                            message = ""
                            nanoState = router.prepareNano()
                            if (nanoState.available) nanoEnabled = true
                        }
                    }
                )

                ProviderSetupCard(
                    title = "OpenRouter",
                    badge = "مجاني",
                    accent = Color(0xFF6246D7),
                    apiKey = openRouterKey,
                    onApiKey = { openRouterKey = it },
                    loading = loadingOpenRouter,
                    connected = openRouterKey.isNotBlank() && selectedOpenRouter.isNotEmpty(),
                    actionLabel = "عرض المجاني",
                    models = openRouterModels,
                    selected = selectedOpenRouter,
                    onLoad = {
                        loadingOpenRouter = true
                        message = ""
                        scope.launch {
                            runCatching { router.discoverOpenRouterFree(openRouterKey) }
                                .onSuccess {
                                    openRouterModels = it
                                    if (selectedOpenRouter.isEmpty() && it.isNotEmpty()) {
                                        selectedOpenRouter = setOf(it.first().id)
                                    }
                                }
                                .onFailure { message = it.message.orEmpty() }
                            loadingOpenRouter = false
                        }
                    },
                    onSelect = { id, checked ->
                        selectedOpenRouter = if (checked) selectedOpenRouter + id else selectedOpenRouter - id
                    }
                )

                ProviderSetupCard(
                    title = "Google AI Studio",
                    badge = "Google",
                    accent = Color(0xFF3B7C6A),
                    apiKey = googleKey,
                    onApiKey = { googleKey = it },
                    loading = loadingGoogle,
                    connected = googleKey.isNotBlank() && selectedGoogle.isNotEmpty(),
                    actionLabel = "عرض المتاح",
                    models = googleModels,
                    selected = selectedGoogle,
                    onLoad = {
                        loadingGoogle = true
                        message = ""
                        scope.launch {
                            runCatching { router.discoverGoogleFreeCandidates(googleKey) }
                                .onSuccess {
                                    googleModels = it
                                    if (selectedGoogle.isEmpty() && it.isNotEmpty()) {
                                        selectedGoogle = setOf(it.first().id)
                                    }
                                }
                                .onFailure { message = it.message.orEmpty() }
                            loadingGoogle = false
                        }
                    },
                    onSelect = { id, checked ->
                        selectedGoogle = if (checked) selectedGoogle + id else selectedGoogle - id
                    }
                )

                if (message.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            message,
                            modifier = Modifier.padding(13.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 12.sp
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
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
                    savedPulse = !savedPulse
                },
                modifier = Modifier.fillMaxWidth().height(58.dp),
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AiInk)
            ) {
                Icon(if (savedPulse) Icons.Rounded.CheckCircle else Icons.Rounded.Save, null)
                Spacer(Modifier.width(8.dp))
                Text("حفظ", fontWeight = FontWeight.Black, fontSize = 16.sp)
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun SmartRouterCard(enabled: Boolean, configuredCount: Int, onToggle: (Boolean) -> Unit) {
    ElevatedCard(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = AiInk),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(46.dp).background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(15.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("HAI تلقائي", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
                Text(
                    if (configuredCount > 0) "الأقوى أولًا  •  $configuredCount جاهز" else "الأقوى ثم البديل",
                    color = Color.White.copy(alpha = 0.66f),
                    fontSize = 11.sp
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun NanoProviderCard(
    state: AiProviderRouter.NanoState,
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    onPrepare: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = AiMint),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(42.dp).background(Color.White.copy(alpha = 0.72f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Memory, null, tint = Color(0xFF3B7C6A))
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Gemini Nano", fontWeight = FontWeight.Black, fontSize = 16.sp)
                        Spacer(Modifier.width(7.dp))
                        Surface(color = Color.White.copy(alpha = 0.75f), shape = RoundedCornerShape(20.dp)) {
                            Text("بدون API", Modifier.padding(horizontal = 8.dp, vertical = 3.dp), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(state.label, fontSize = 11.sp, color = Color(0xFF65736C))
                }
                Switch(
                    checked = enabled && (state.available || state.downloadable),
                    onCheckedChange = onEnabled,
                    enabled = state.available || state.downloadable
                )
            }
            if (state.downloadable && !state.available) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onPrepare, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Rounded.CloudDownload, null)
                    Spacer(Modifier.width(6.dp))
                    Text("تجهيز")
                }
            }
        }
    }
}

@Composable
private fun ProviderSetupCard(
    title: String,
    badge: String,
    accent: Color,
    apiKey: String,
    onApiKey: (String) -> Unit,
    loading: Boolean,
    connected: Boolean,
    actionLabel: String,
    models: List<AiProviderRouter.ModelOption>,
    selected: Set<String>,
    onLoad: () -> Unit,
    onSelect: (String, Boolean) -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(42.dp).background(accent.copy(alpha = 0.10f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Hub, null, tint = accent)
                }
                Spacer(Modifier.width(11.dp))
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                Surface(
                    color = if (connected) Color(0xFFE3F2E9) else accent.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        if (connected) "متصل" else badge,
                        Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        color = if (connected) Color(0xFF2F7658) else accent,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            Spacer(Modifier.height(13.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKey,
                placeholder = { Text("API key") },
                leadingIcon = { Icon(Icons.Rounded.Key, null) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                shape = RoundedCornerShape(17.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                enabled = apiKey.isNotBlank() && !loading,
                onClick = onLoad,
                shape = RoundedCornerShape(17.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.Refresh, null)
                }
                Spacer(Modifier.width(7.dp))
                Text(actionLabel, fontWeight = FontWeight.Bold)
            }

            if (models.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    models.forEach { model ->
                        FilterChip(
                            selected = model.id in selected,
                            onClick = { onSelect(model.id, model.id !in selected) },
                            label = { Text(model.name, maxLines = 1, fontSize = 11.sp) },
                            leadingIcon = if (model.id in selected) {
                                { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }
                            } else null
                        )
                    }
                }
            }
        }
    }
}
