package com.manzili.hai

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.manzili.hai.data.HaiSettings
import com.manzili.hai.data.ProjectPlanStore
import com.manzili.hai.data.ProjectSourceStore
import com.manzili.hai.engine.MultiFloorGeometryEngine
import com.manzili.hai.engine.PlanNumberEvidenceEngine
import com.manzili.hai.engine.PlanVerificationEngine
import com.manzili.hai.engine.ProjectMemoryEngine
import com.manzili.hai.engine.SaudiProjectTypeEngine
import com.manzili.hai.engine.SaudiResidentialEngine
import com.manzili.hai.model.FloorPlan

@Composable
internal fun Hai360App() {
    val nav = rememberNavController()
    val context = LocalContext.current
    val store = remember { ProjectPlanStore(context) }
    val sourceStore = remember { ProjectSourceStore(context) }

    var plan by remember { mutableStateOf(store.load()?.let(::normalizeHai360Plan)) }
    var source by remember { mutableStateOf(sourceStore.load(store.activeProjectId())) }
    var pending by remember { mutableStateOf<FloorPlan?>(null) }
    var pendingType by remember { mutableStateOf<SaudiProjectTypeEngine.Type?>(null) }
    var creatingNew by remember { mutableStateOf(false) }

    fun openProject(id: String) {
        plan = store.open(id)?.let(::normalizeHai360Plan)
        source = sourceStore.load(id)
        pending = null
        pendingType = null
        creatingNew = false
    }

    fun persist(next: FloorPlan, importedSource: Uri?) {
        val ready = normalizeHai360Plan(next)
        val id = if (creatingNew || store.activeProjectId() == null) {
            store.createProject(ready)
        } else {
            store.save(ready)
            store.activeProjectId().orEmpty()
        }
        if (id.isNotBlank()) sourceStore.save(id, importedSource)
        plan = ready
        source = importedSource
        pending = null
        pendingType = null
        creatingNew = false
    }

    Hai360Theme {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            NavHost(nav, startDestination = "home") {
                composable("home") {
                    Hai360HomeScreen(
                        nav = nav,
                        plan = plan,
                        projectCount = store.listProjects().size,
                        onImport = {
                            creatingNew = true
                            pending = null
                            pendingType = null
                            source = null
                            nav.navigate("import")
                        },
                        onNew = {
                            creatingNew = true
                            pending = null
                            pendingType = null
                            source = null
                            nav.navigate("new")
                        }
                    )
                }
                composable("import") {
                    Hai360ImportScreen(
                        initialSource = null,
                        onBack = { nav.popBackStack() },
                        onSourceChanged = { source = it },
                        onAnalyzed = { analyzed, type ->
                            pending = analyzed
                            pendingType = type
                            creatingNew = true
                            nav.navigate("studio")
                        }
                    )
                }
                composable("new") {
                    Hai360NewBuildScreen(
                        onBack = { nav.popBackStack() },
                        onChoose = { generated, type ->
                            pending = SaudiProjectTypeEngine.apply(generated, type)
                            pendingType = type
                            source = null
                            creatingNew = true
                            nav.navigate("studio")
                        }
                    )
                }
                composable("studio") {
                    val current = pending ?: plan
                    if (current == null) {
                        LaunchedEffect(Unit) { nav.popBackStack() }
                    } else {
                        Hai360StudioScreen(
                            source = source,
                            initialPlan = current,
                            onBack = { nav.popBackStack() },
                            onEdit = { editedBase ->
                                pending = editedBase
                                nav.navigate("editor")
                            },
                            onConfirm = { confirmed ->
                                val typed = pendingType?.let { SaudiProjectTypeEngine.apply(confirmed, it) } ?: confirmed
                                persist(typed, source)
                                nav.navigate("home") { popUpTo("home") { inclusive = true } }
                            }
                        )
                    }
                }
                composable("editor") {
                    val current = pending ?: plan
                    if (current == null) {
                        LaunchedEffect(Unit) { nav.popBackStack() }
                    } else {
                        Hai360EditorScreen(
                            source = source,
                            initialPlan = current,
                            onCancel = { nav.popBackStack() },
                            onDone = {
                                pending = it
                                nav.popBackStack()
                            }
                        )
                    }
                }
                composable("3d") {
                    plan?.let { Production3DScreenV3(nav, it) } ?: LaunchedEffect(Unit) { nav.popBackStack() }
                }
                composable("library") {
                    Hai360LibraryScreen(nav, store) { id ->
                        openProject(id)
                        nav.navigate("home") { popUpTo("home") { inclusive = true } }
                    }
                }
                composable("settings") { Hai360SettingsScreen(nav) }
                composable("cloud") {
                    CloudSyncScreen(nav, store) { opened ->
                        plan = opened?.let(::normalizeHai360Plan)
                        source = sourceStore.load(store.activeProjectId())
                        pending = null
                        pendingType = null
                        creatingNew = false
                    }
                }
            }
        }
    }
}

private fun normalizeHai360Plan(input: FloorPlan): FloorPlan {
    val verified = SaudiResidentialEngine.normalize(PlanVerificationEngine.inspect(input).plan)
    return MultiFloorGeometryEngine.persistActive(
        MultiFloorGeometryEngine.normalize(ProjectMemoryEngine.reconcile(verified))
    )
}

@Composable
private fun Hai360HomeScreen(
    nav: NavHostController,
    plan: FloorPlan?,
    projectCount: Int,
    onImport: () -> Unit,
    onNew: () -> Unit
) {
    val report = plan?.let { PlanVerificationEngine.inspect(it) }
    Scaffold(
        containerColor = H360Ivory,
        bottomBar = { HomeNavigation(nav, projectCount) }
    ) { pad ->
        ArchitecturalBackdrop(Modifier.fillMaxSize().padding(pad)) {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 18.dp).verticalScroll(rememberScrollState())
            ) {
                Spacer(Modifier.height(10.dp))
                HomeBrand()
                Spacer(Modifier.height(24.dp))

                if (plan != null) {
                    ActiveProjectCard(
                        plan = plan,
                        report = report,
                        onReview = { nav.navigate("studio") },
                        on3d = { nav.navigate("3d") }
                    )
                    Spacer(Modifier.height(18.dp))
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LaunchTile(
                        title = "استيراد",
                        icon = Icons.Rounded.DocumentScanner,
                        background = H360Cyan,
                        modifier = Modifier.weight(1f),
                        onClick = onImport
                    )
                    LaunchTile(
                        title = "مشروع جديد",
                        icon = Icons.Rounded.AddHomeWork,
                        background = H360Lilac,
                        modifier = Modifier.weight(1f),
                        onClick = onNew
                    )
                }

                if (plan == null) {
                    Spacer(Modifier.height(18.dp))
                    EmptyProjectCanvas()
                }

                Spacer(Modifier.height(22.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CompactShortcut("المشاريع", Icons.Rounded.FolderOpen, H360Peach, Modifier.weight(1f)) { nav.navigate("library") }
                    CompactShortcut("السحابة", Icons.Rounded.CloudSync, H360Mint, Modifier.weight(1f)) { nav.navigate("cloud") }
                    CompactShortcut("الإعدادات", Icons.Rounded.Tune, H360Sky, Modifier.weight(1f)) { nav.navigate("settings") }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun HomeBrand() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = H360CyanDeep, shape = RoundedCornerShape(16.dp), modifier = Modifier.size(46.dp), shadowElevation = 6.dp) {
            Box(contentAlignment = Alignment.Center) { Text("H", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black) }
        }
        Spacer(Modifier.width(11.dp))
        Column {
            Text("منزلي HAI", color = H360Ink, fontSize = 22.sp, fontWeight = FontWeight.Black)
            Text("ANDROID", color = H360Muted, fontSize = 8.sp, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp)
        }
    }
}

@Composable
private fun LaunchTile(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    background: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        color = background,
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 3.dp,
        border = BorderStroke(1.dp, H360Line.copy(alpha = .7f)),
        modifier = modifier.height(132.dp).clickable(onClick = onClick)
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Surface(color = Color.White.copy(alpha = .85f), shape = CircleShape, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = H360Ink, modifier = Modifier.size(22.dp)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = H360Ink, fontSize = 16.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.ArrowBack, null, tint = H360Ink, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ActiveProjectCard(
    plan: FloorPlan,
    report: PlanVerificationEngine.Report?,
    onReview: () -> Unit,
    on3d: () -> Unit
) {
    val numericCount = PlanNumberEvidenceEngine.numericLabels(plan.dimensions).size
    Surface(
        color = H360Paper,
        shape = RoundedCornerShape(30.dp),
        shadowElevation = 5.dp,
        border = BorderStroke(1.dp, H360Line),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MiniPlanGlyph(plan, Modifier.size(86.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(plan.title, color = H360Ink, fontSize = 18.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(9.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusPill("${report?.readingConfidence ?: 0}%", if ((report?.readingConfidence ?: 0) >= 90) H360Mint else H360Peach)
                        StatusPill("${plan.rooms.size} غرف", H360Sky)
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusPill("${plan.walls.size} جدار", H360Lilac)
                        StatusPill("$numericCount رقم", H360Cyan)
                    }
                }
            }
            Spacer(Modifier.height(15.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Button(
                    onClick = onReview,
                    colors = ButtonDefaults.buttonColors(containerColor = H360CyanDeep, contentColor = Color.White),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Icon(Icons.Rounded.Architecture, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("المخطط", fontWeight = FontWeight.Black)
                }
                OutlinedButton(
                    onClick = on3d,
                    enabled = report?.blocking != true,
                    border = BorderStroke(1.dp, H360Line),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Icon(Icons.Rounded.ViewInAr, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("3D", fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Surface(color = color, shape = RoundedCornerShape(50.dp)) {
        Text(text, color = H360Ink, fontSize = 9.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp))
    }
}

@Composable
private fun MiniPlanGlyph(plan: FloorPlan, modifier: Modifier) {
    Surface(color = Color(0xFFF3F6FA), shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, H360Line), modifier = modifier) {
        Canvas(Modifier.fillMaxSize().padding(10.dp)) {
            fun p(x: Float, y: Float) = Offset(size.width * x / 100f, size.height * y / 100f)
            plan.rooms.take(24).forEach { room ->
                drawRect(H360CyanDeep.copy(alpha = .06f), p(room.x, room.y), androidx.compose.ui.geometry.Size(size.width * room.width / 100f, size.height * room.height / 100f))
            }
            plan.walls.take(70).forEach { drawLine(H360CyanDeep.copy(alpha = .92f), p(it.start.x, it.start.y), p(it.end.x, it.end.y), 2f) }
        }
    }
}

@Composable
private fun EmptyProjectCanvas() {
    Surface(color = H360Paper.copy(alpha = .82f), shape = RoundedCornerShape(30.dp), border = BorderStroke(1.dp, H360Line), modifier = Modifier.fillMaxWidth().height(230.dp)) {
        Box(Modifier.fillMaxSize()) {
            BlueprintGrid(Modifier.matchParentSize(), step = 28f)
            Surface(color = H360Cyan, shape = CircleShape, modifier = Modifier.align(Alignment.Center).size(76.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Architecture, null, tint = H360CyanDeep, modifier = Modifier.size(32.dp)) }
            }
        }
    }
}

@Composable
private fun CompactShortcut(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Surface(color = color, shape = RoundedCornerShape(20.dp), modifier = modifier.height(86.dp).clickable(onClick = onClick), border = BorderStroke(1.dp, H360Line.copy(alpha = .6f))) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(icon, null, tint = H360Ink, modifier = Modifier.size(20.dp))
            Text(title, color = H360Ink, fontSize = 10.sp, fontWeight = FontWeight.Black, maxLines = 1)
        }
    }
}

@Composable
private fun HomeNavigation(nav: NavHostController, projectCount: Int) {
    NavigationBar(containerColor = H360Paper, tonalElevation = 0.dp) {
        NavigationBarItem(true, { nav.navigate("home") }, { Icon(Icons.Rounded.Home, null) }, label = { Text("الرئيسية") })
        NavigationBarItem(false, { nav.navigate("library") }, {
            BadgedBox(badge = { if (projectCount > 0) Badge { Text(projectCount.toString()) } }) { Icon(Icons.Rounded.FolderOpen, null) }
        }, label = { Text("المشاريع") })
        NavigationBarItem(false, { nav.navigate("cloud") }, { Icon(Icons.Rounded.CloudSync, null) }, label = { Text("السحابة") })
        NavigationBarItem(false, { nav.navigate("settings") }, { Icon(Icons.Rounded.Tune, null) }, label = { Text("الإعدادات") })
    }
}

@Composable
private fun Hai360LibraryScreen(nav: NavHostController, store: ProjectPlanStore, onOpen: (String) -> Unit) {
    val projects = remember { store.listProjects() }
    Scaffold(containerColor = H360Ivory, topBar = { SimpleTopBar("المشاريع") { nav.popBackStack() } }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            if (projects.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Surface(color = H360Cyan, shape = CircleShape, modifier = Modifier.size(72.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.FolderOpen, null, tint = H360CyanDeep, modifier = Modifier.size(30.dp)) }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Spacer(Modifier.height(6.dp))
                    projects.forEach { p ->
                        Surface(
                            color = H360Paper,
                            shape = RoundedCornerShape(22.dp),
                            border = BorderStroke(1.dp, if (p.active) H360CyanDeep.copy(alpha = .35f) else H360Line),
                            shadowElevation = if (p.active) 3.dp else 1.dp,
                            modifier = Modifier.fillMaxWidth().clickable { onOpen(p.id) }
                        ) {
                            Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                                Surface(color = if (p.active) H360Cyan else H360Sky, shape = CircleShape, modifier = Modifier.size(42.dp)) {
                                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.HomeWork, null, tint = H360CyanDeep, modifier = Modifier.size(20.dp)) }
                                }
                                Spacer(Modifier.width(11.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(p.title, color = H360Ink, fontWeight = FontWeight.Black, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${p.roomCount} غرف  ·  v${p.revision}", color = H360Muted, fontSize = 9.sp)
                                }
                                Icon(Icons.Rounded.ArrowBack, null, tint = H360Muted, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun Hai360SettingsScreen(nav: NavHostController) {
    val context = LocalContext.current
    val settings = remember { HaiSettings(context) }
    var backendMode by remember { mutableStateOf(settings.backendMode) }
    var backendUrl by remember { mutableStateOf(settings.backendBaseUrl) }
    var backendToken by remember { mutableStateOf(settings.backendServiceToken) }
    var directEndpoint by remember { mutableStateOf(settings.directEndpoint) }
    var directKey by remember { mutableStateOf(settings.directApiKey) }
    var model by remember { mutableStateOf(settings.model) }
    var saved by remember { mutableStateOf(false) }

    Scaffold(containerColor = H360Ivory, topBar = { SimpleTopBar("HAI") { nav.popBackStack() } }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(8.dp))
            Surface(color = H360Paper, shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, H360Line), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = H360Cyan, shape = CircleShape, modifier = Modifier.size(42.dp)) {
                        Box(contentAlignment = Alignment.Center) { Text("H", color = H360CyanDeep, fontWeight = FontWeight.Black) }
                    }
                    Spacer(Modifier.width(11.dp))
                    Text(if (backendMode) "الخادم" else "مباشر", color = H360Ink, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                    Switch(checked = backendMode, onCheckedChange = { backendMode = it; saved = false })
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(model, { model = it; saved = false }, label = { Text("الموديل") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(9.dp))
            if (backendMode) {
                OutlinedTextField(backendUrl, { backendUrl = it; saved = false }, label = { Text("Backend URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(9.dp))
                OutlinedTextField(backendToken, { backendToken = it; saved = false }, label = { Text("Token") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            } else {
                OutlinedTextField(directEndpoint, { directEndpoint = it; saved = false }, label = { Text("Endpoint") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(9.dp))
                OutlinedTextField(directKey, { directKey = it; saved = false }, label = { Text("API key") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
            Spacer(Modifier.height(14.dp))
            H360PrimaryButton(if (saved) "تم" else "حفظ", Modifier.fillMaxWidth(), icon = if (saved) Icons.Rounded.Check else Icons.Rounded.Save) {
                settings.backendMode = backendMode
                settings.backendBaseUrl = backendUrl
                settings.backendServiceToken = backendToken
                settings.directEndpoint = directEndpoint
                settings.directApiKey = directKey
                settings.model = model
                saved = true
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, color = H360Ink, fontSize = 22.sp, fontWeight = FontWeight.Black) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowForward, "رجوع", tint = H360Ink) } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = H360Ivory)
    )
}
