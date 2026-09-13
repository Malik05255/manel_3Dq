package com.manzili.hai

import android.net.Uri
import androidx.compose.foundation.Canvas
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

    fun openProject(id: String) {
        plan = store.open(id)?.let(::normalizeHai360Plan)
        source = sourceStore.load(id)
        pending = null
        pendingType = null
    }

    fun persist(next: FloorPlan, importedSource: Uri?): FloorPlan {
        val ready = normalizeHai360Plan(next)
        val id = if (pending != null || store.activeProjectId() == null) {
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
        return ready
    }

    Hai360Theme {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            NavHost(navController = nav, startDestination = "home") {
                composable("home") {
                    Hai360HomeScreen(
                        nav = nav,
                        plan = plan,
                        projectCount = store.listProjects().size
                    )
                }
                composable("import") {
                    Hai360ImportScreen(
                        initialSource = null,
                        onBack = { nav.popBackStack() },
                        onSourceChanged = { source = it },
                        onAnalyzed = { analyzed, type ->
                            pendingType = type
                            pending = analyzed
                            nav.navigate("studio")
                        }
                    )
                }
                composable("new") {
                    Hai360NewBuildScreen(
                        onBack = { nav.popBackStack() },
                        onChoose = { generated, type ->
                            pendingType = type
                            pending = SaudiProjectTypeEngine.apply(generated, type)
                            source = null
                            nav.navigate("studio")
                        }
                    )
                }
                composable("studio") {
                    val current = pending ?: plan
                    if (current == null) {
                        LaunchedEffect(Unit) { nav.navigate("home") { popUpTo("home") { inclusive = true } } }
                    } else {
                        Hai360StudioScreen(
                            source = source,
                            initialPlan = current,
                            onBack = { nav.popBackStack() },
                            onConfirm = { confirmed ->
                                val typed = pendingType?.let { SaudiProjectTypeEngine.apply(confirmed, it) } ?: confirmed
                                persist(typed, source)
                                nav.navigate("home") { popUpTo("home") { inclusive = true } }
                            }
                        )
                    }
                }
                composable("3d") {
                    val current = plan
                    if (current == null) {
                        LaunchedEffect(Unit) { nav.popBackStack() }
                    } else {
                        Production3DScreenV3(nav, current)
                    }
                }
                composable("library") {
                    Hai360LibraryScreen(
                        nav = nav,
                        store = store,
                        onOpen = {
                            openProject(it)
                            nav.navigate("home") { popUpTo("home") { inclusive = true } }
                        }
                    )
                }
                composable("settings") { Hai360SettingsScreen(nav) }
                composable("cloud") {
                    CloudSyncScreen(nav, store) { opened ->
                        plan = opened?.let(::normalizeHai360Plan)
                        source = sourceStore.load(store.activeProjectId())
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
private fun Hai360HomeScreen(nav: NavHostController, plan: FloorPlan?, projectCount: Int) {
    val report = plan?.let { PlanVerificationEngine.inspect(it) }
    ArchitecturalBackdrop(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = H360Cyan, shape = CircleShape, modifier = Modifier.size(40.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("H", color = H360Ink, fontWeight = FontWeight.Black, fontSize = 18.sp)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("منزلي", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
                    Text("HAI ARCHITECTURAL STUDIO", color = Color.White.copy(alpha = .38f), fontSize = 7.5.sp, letterSpacing = 1.2.sp)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { nav.navigate("library") }) {
                    BadgedBox(badge = {
                        if (projectCount > 0) Badge(containerColor = H360Cyan, contentColor = H360Ink) { Text(projectCount.toString()) }
                    }) {
                        Icon(Icons.Rounded.FolderOpen, "المشاريع", tint = Color.White)
                    }
                }
                IconButton(onClick = { nav.navigate("settings") }) {
                    Icon(Icons.Rounded.Tune, "الإعدادات", tint = Color.White)
                }
            }

            Spacer(Modifier.height(36.dp))
            Text("حوّل الورقة\nإلى مساحة.", color = Color.White, fontSize = 43.sp, lineHeight = 46.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(10.dp))
            Text(
                "HAI يقرأ المخطط، يبني هندسته، ويترك القرار النهائي لك.",
                color = Color.White.copy(alpha = .52f),
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                modifier = Modifier.widthIn(max = 310.dp)
            )

            Spacer(Modifier.height(26.dp))

            Surface(
                color = H360Cyan,
                contentColor = H360Ink,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().height(78.dp).clickable { nav.navigate("import") }
            ) {
                Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).background(H360Ink, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.UploadFile, null, tint = Color.White, modifier = Modifier.size(21.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("عندي مخطط", fontSize = 18.sp, fontWeight = FontWeight.Black)
                        Text("PDF أو صورة → نموذج قابل للتحرير", fontSize = 9.5.sp, color = H360Ink.copy(alpha = .58f))
                    }
                    Icon(Icons.Rounded.ArrowBack, null)
                }
            }

            Spacer(Modifier.height(10.dp))

            Surface(
                color = Color.White.copy(alpha = .075f),
                contentColor = Color.White,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().height(62.dp).clickable { nav.navigate("new") }
            ) {
                Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AddHomeWork, null, tint = Color.White.copy(alpha = .82f))
                    Spacer(Modifier.width(12.dp))
                    Text("ابدأ بيتًا من الصفر", fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("NEW", color = H360Cyan, fontSize = 8.dp.value.sp, fontWeight = FontWeight.Black)
                }
            }

            plan?.let { current ->
                Spacer(Modifier.height(22.dp))
                CurrentProjectStrip(current, report, onStudio = { nav.navigate("studio") }, on3d = { nav.navigate("3d") })
            }

            Spacer(Modifier.weight(1f))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(if (report?.blocking == true) H360Amber else H360Success, CircleShape))
                Spacer(Modifier.width(7.dp))
                Text(
                    when {
                        plan == null -> "جاهز لمشروعك الأول"
                        report?.blocking == true -> "المشروع الحالي يحتاج مراجعة"
                        else -> "المشروع الحالي صالح للانتقال إلى 3D"
                    },
                    color = Color.White.copy(alpha = .45f),
                    fontSize = 9.5.sp
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { nav.navigate("cloud") }) {
                    Icon(Icons.Rounded.CloudSync, null, tint = Color.White.copy(alpha = .55f), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("السحابة", color = Color.White.copy(alpha = .55f), fontSize = 9.5.sp)
                }
            }
        }
    }
}

@Composable
private fun CurrentProjectStrip(
    plan: FloorPlan,
    report: PlanVerificationEngine.Report?,
    onStudio: () -> Unit,
    on3d: () -> Unit
) {
    Surface(color = Color.White.copy(alpha = .075f), shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MiniPlanGlyph(plan, Modifier.size(58.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(plan.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${plan.rooms.size} غرف • ${plan.walls.size} جدار • ثقة ${report?.readingConfidence ?: 0}%",
                        color = Color.White.copy(alpha = .42f),
                        fontSize = 9.sp
                    )
                }
                Surface(
                    color = if (report?.blocking == true) H360Amber.copy(alpha = .17f) else H360Success.copy(alpha = .17f),
                    shape = RoundedCornerShape(50.dp)
                ) {
                    Text(
                        if (report?.blocking == true) "راجع" else "جاهز",
                        color = if (report?.blocking == true) H360Amber else H360Success,
                        fontWeight = FontWeight.Black,
                        fontSize = 8.5.sp,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onStudio,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = H360Ink),
                    shape = RoundedCornerShape(17.dp),
                    modifier = Modifier.weight(1f).height(46.dp)
                ) {
                    Icon(Icons.Rounded.Architecture, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("الاستوديو", fontWeight = FontWeight.Black, fontSize = 10.5.sp)
                }
                OutlinedButton(
                    onClick = on3d,
                    enabled = report?.blocking != true,
                    border = ButtonDefaults.outlinedButtonBorder(enabled = report?.blocking != true),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White, disabledContentColor = Color.White.copy(alpha = .25f)),
                    shape = RoundedCornerShape(17.dp),
                    modifier = Modifier.weight(1f).height(46.dp)
                ) {
                    Icon(Icons.Rounded.ViewInAr, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("3D", fontWeight = FontWeight.Black, fontSize = 10.5.sp)
                }
            }
        }
    }
}

@Composable
private fun MiniPlanGlyph(plan: FloorPlan, modifier: Modifier = Modifier) {
    Surface(color = H360InkSoft, shape = RoundedCornerShape(18.dp), modifier = modifier) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            fun p(x: Float, y: Float) = Offset(size.width * x / 100f, size.height * y / 100f)
            plan.walls.take(30).forEach { wall ->
                drawLine(H360Cyan.copy(alpha = .9f), p(wall.start.x, wall.start.y), p(wall.end.x, wall.end.y), 1.5f)
            }
            if (plan.walls.isEmpty()) {
                drawLine(H360Cyan.copy(alpha = .6f), Offset(4f, size.height * .25f), Offset(size.width - 4f, size.height * .25f), 1.5f)
                drawLine(H360Cyan.copy(alpha = .6f), Offset(size.width * .35f, 4f), Offset(size.width * .35f, size.height - 4f), 1.5f)
            }
        }
    }
}

@Composable
private fun Hai360LibraryScreen(
    nav: NavHostController,
    store: ProjectPlanStore,
    onOpen: (String) -> Unit
) {
    val projects = remember { store.listProjects() }
    Surface(Modifier.fillMaxSize(), color = H360Ivory) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                H360IconButton(Icons.Rounded.ArrowForward, "رجوع", onClick = { nav.popBackStack() })
                Spacer(Modifier.width(12.dp))
                H360SectionLabel("ARCHIVE", "مشاريعك")
            }
            Spacer(Modifier.height(22.dp))
            if (projects.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.FolderOpen, null, tint = H360Line, modifier = Modifier.size(54.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("لا توجد مشاريع بعد", color = H360Muted, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    projects.forEachIndexed { index, p ->
                        Surface(
                            color = if (p.active) H360Ink else H360Paper,
                            contentColor = if (p.active) Color.White else H360Ink,
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.fillMaxWidth().clickable { onOpen(p.id) }
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Surface(color = if (p.active) H360Cyan else H360Ivory, shape = CircleShape, modifier = Modifier.size(42.dp)) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text((index + 1).toString().padStart(2, '0'), color = H360Ink, fontWeight = FontWeight.Black, fontSize = 11.sp)
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(p.title, fontWeight = FontWeight.Black, fontSize = 14.sp, maxLines = 1)
                                    Text("${p.roomCount} غرف • مراجعة ${p.revision}", color = LocalContentColor.current.copy(alpha = .55f), fontSize = 9.5.sp)
                                }
                                Icon(Icons.Rounded.ArrowBack, null, tint = LocalContentColor.current.copy(alpha = .55f))
                            }
                        }
                    }
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

    Surface(Modifier.fillMaxSize(), color = H360Ivory) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                H360IconButton(Icons.Rounded.ArrowForward, "رجوع", onClick = { nav.popBackStack() })
                Spacer(Modifier.width(12.dp))
                H360SectionLabel("SYSTEM", "اتصال HAI")
            }
            Spacer(Modifier.height(18.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Surface(color = H360Ink, contentColor = Color.White, shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = H360Cyan, shape = CircleShape, modifier = Modifier.size(42.dp)) {
                            Box(contentAlignment = Alignment.Center) { Text("H", color = H360Ink, fontWeight = FontWeight.Black) }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (backendMode) "مسار الخادم" else "اتصال مباشر", fontWeight = FontWeight.Black)
                            Text(if (settings.configured) "الإعداد الحالي قابل للاستخدام" else "يحتاج بيانات اتصال", color = Color.White.copy(alpha = .5f), fontSize = 9.5.sp)
                        }
                        Switch(checked = backendMode, onCheckedChange = { backendMode = it; saved = false })
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("الموديل", fontWeight = FontWeight.Black, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(model, { model = it; saved = false }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("google/gemini-2.5-flash") })
                Spacer(Modifier.height(12.dp))
                if (backendMode) {
                    Text("Backend URL", fontWeight = FontWeight.Black, fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(backendUrl, { backendUrl = it; saved = false }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Spacer(Modifier.height(10.dp))
                    Text("Service token", fontWeight = FontWeight.Black, fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(backendToken, { backendToken = it; saved = false }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                } else {
                    Text("Endpoint", fontWeight = FontWeight.Black, fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(directEndpoint, { directEndpoint = it; saved = false }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Spacer(Modifier.height(10.dp))
                    Text("API key", fontWeight = FontWeight.Black, fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(directKey, { directKey = it; saved = false }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
                Spacer(Modifier.height(18.dp))
                H360PrimaryButton(
                    text = if (saved) "تم الحفظ" else "حفظ الاتصال",
                    icon = if (saved) Icons.Rounded.Check else Icons.Rounded.Save,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    settings.backendMode = backendMode
                    settings.backendBaseUrl = backendUrl
                    settings.backendServiceToken = backendToken
                    settings.directEndpoint = directEndpoint
                    settings.directApiKey = directKey
                    settings.model = model
                    saved = true
                }
            }
        }
    }
}
