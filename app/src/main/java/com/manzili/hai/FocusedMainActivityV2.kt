package com.manzili.hai

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.manzili.hai.data.ProjectPlanStore
import com.manzili.hai.data.ProjectSourceStore
import com.manzili.hai.engine.MultiFloorGeometryEngine
import com.manzili.hai.engine.PlanVerificationEngine
import com.manzili.hai.engine.ProjectMemoryEngine
import com.manzili.hai.engine.SaudiProjectTypeEngine
import com.manzili.hai.engine.SaudiResidentialEngine
import com.manzili.hai.model.FloorPlan

class FocusedMainActivityV2 : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FocusedAppV2() }
    }
}

@Composable
private fun FocusedAppV2() {
    val nav = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { ProjectPlanStore(context) }
    val sourceStore = remember { ProjectSourceStore(context) }
    var plan by remember {
        mutableStateOf(
            store.load()?.let {
                MultiFloorGeometryEngine.normalize(
                    SaudiResidentialEngine.normalize(PlanVerificationEngine.inspect(it).plan)
                )
            }
        )
    }
    var pending by remember { mutableStateOf<FloorPlan?>(null) }
    var source by remember { mutableStateOf(sourceStore.load(store.activeProjectId())) }
    var selectedType by remember { mutableStateOf<SaudiProjectTypeEngine.Type?>(null) }
    var rulesEnabled by remember { mutableStateOf(plan?.saudiRulesEnabled ?: false) }

    fun createProject(next: FloorPlan): String {
        val requested = next.copy(saudiRulesEnabled = next.saudiRulesEnabled || rulesEnabled)
        val verified = SaudiResidentialEngine.normalize(PlanVerificationEngine.inspect(requested).plan)
        val ready = MultiFloorGeometryEngine.persistActive(
            MultiFloorGeometryEngine.normalize(ProjectMemoryEngine.reconcile(verified))
        )
        val id = store.createProject(ready)
        plan = ready
        rulesEnabled = ready.saudiRulesEnabled
        return id
    }

    fun updateProject(next: FloorPlan, allowRulesChange: Boolean = false) {
        val verified = SaudiResidentialEngine.normalize(PlanVerificationEngine.inspect(next).plan)
        val current = plan
        val carried = current?.let { ProjectMemoryEngine.carryForward(it, verified) }
            ?: ProjectMemoryEngine.reconcile(verified)
        val safe = if (current != null && !allowRulesChange) {
            carried.copy(saudiRulesEnabled = current.saudiRulesEnabled)
        } else carried
        val ready = MultiFloorGeometryEngine.persistActive(MultiFloorGeometryEngine.normalize(safe))
        store.save(ready)
        plan = ready
        rulesEnabled = ready.saudiRulesEnabled
    }

    fun setOpenedPlan(opened: FloorPlan?) {
        plan = opened?.let {
            MultiFloorGeometryEngine.normalize(
                SaudiResidentialEngine.normalize(PlanVerificationEngine.inspect(it).plan)
            )
        }
        source = sourceStore.load(store.activeProjectId())
        rulesEnabled = plan?.saudiRulesEnabled ?: false
    }

    fun toggleRules(enabled: Boolean) {
        plan?.let {
            updateProject(it.copy(saudiRulesEnabled = enabled, revision = it.revision + 1), true)
        } ?: run { rulesEnabled = enabled }
    }

    StudioTheme {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            NavHost(nav, startDestination = "home") {
                composable("home") { FocusedHomeV2(nav, plan, store.listProjects().size) }

                composable("import-type") {
                    LaunchedEffect(Unit) {
                        rulesEnabled = false
                        source = null
                        pending = null
                    }
                    SaudiProjectTypeScreen(nav, "نوع المشروع", "") { type ->
                        selectedType = type
                        nav.navigate("import")
                    }
                }

                composable("import") {
                    VerifiedImportScreenV3(
                        nav = nav,
                        source = source,
                        setSource = { source = it },
                        onAnalyzed = { pending = it },
                        projectType = selectedType
                    )
                }

                composable("verify") {
                    ImportedProjectWorkspaceScreen(nav, source, pending) { confirmed ->
                        val type = selectedType ?: SaudiProjectTypeEngine.infer(confirmed)
                        val id = createProject(SaudiProjectTypeEngine.apply(confirmed, type))
                        sourceStore.save(id, source)
                        pending = null
                        nav.navigate("editor") { popUpTo("home") }
                    }
                }

                composable("new") {
                    LaunchedEffect(Unit) {
                        rulesEnabled = false
                        source = null
                    }
                    SaudiProjectTypeScreen(nav, "نوع المشروع", "") { type ->
                        selectedType = type
                        nav.navigate("new-brief")
                    }
                }

                composable("new-brief") {
                    val type = selectedType ?: SaudiProjectTypeEngine.Type.VILLA_TWO
                    SaudiAdaptiveBuildScreen(nav, type) {
                        createProject(SaudiProjectTypeEngine.apply(it, type))
                        nav.navigate("editor") { popUpTo("home") }
                    }
                }

                composable("editor") {
                    OptionalRulesStage(nav, plan, rulesEnabled, ::toggleRules) {
                        EnhancedEditor(nav, plan) { updateProject(it) }
                    }
                }

                composable("3d") {
                    val current = plan
                    val report = current?.let { PlanVerificationEngine.inspect(it) }
                    if (current == null || report == null || report.blocking) {
                        Incomplete3DBlockedScreen(nav, report?.issues?.firstOrNull { it.level == "error" }?.detail)
                    } else {
                        OptionalRulesStage(nav, current, rulesEnabled, ::toggleRules) {
                            Production3DScreenV3(nav, current)
                        }
                    }
                }

                composable("walkthrough") {
                    OptionalRulesStage(nav, plan, rulesEnabled, ::toggleRules) {
                        WalkthroughScreen(nav, plan)
                    }
                }

                composable("saudi-rules") {
                    SaudiRulesScreen(nav, plan) { updateProject(it, true) }
                }

                composable("projects") {
                    ProjectLibraryScreen(nav, store, ::setOpenedPlan)
                }

                composable("cloud") {
                    CloudSyncScreen(nav, store, ::setOpenedPlan)
                }

                composable("floors") { ProjectFloorsScreen(nav, plan) { updateProject(it) } }
                composable("polygon") { PolygonVertexEditorScreen(nav, plan) { updateProject(it) } }
                composable("memory") { ProjectMemoryManagerScreen(nav, plan) { updateProject(it) } }
                composable("export") { QuickExportScreen(nav, plan) }
                composable("saudi-audit") { SaudiPlanAuditScreen(nav, plan) }
                composable("change-type") {
                    SaudiProjectTypeScreen(nav, "نوع المشروع", "", plan?.let(SaudiProjectTypeEngine::infer)) { type ->
                        plan?.let { updateProject(SaudiProjectTypeEngine.apply(it, type)) }
                        nav.popBackStack()
                    }
                }
                composable("settings") { ProductionAiSettings(nav) }
            }
        }
    }
}

@Composable
private fun FocusedHomeV2(nav: NavHostController, plan: FloorPlan?, projectCount: Int) {
    val ready = plan?.let { !PlanVerificationEngine.inspect(it).blocking } == true
    Scaffold(
        containerColor = StudioColors.Canvas,
        bottomBar = {
            NavigationBar(containerColor = StudioColors.Paper, tonalElevation = 0.dp) {
                NavigationBarItem(selected = true, onClick = {}, icon = { Icon(Icons.Outlined.Home, null) }, label = { Text("الرئيسية") })
                NavigationBarItem(selected = false, onClick = { nav.navigate("projects") }, icon = { Icon(Icons.Outlined.FolderOpen, null) }, label = { Text("مشاريعي") })
                NavigationBarItem(selected = false, onClick = { nav.navigate("settings") }, icon = { Icon(Icons.Outlined.Tune, null) }, label = { Text("الإعدادات") })
            }
        }
    ) { inset ->
        Column(Modifier.fillMaxSize().padding(inset).statusBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = StudioColors.Ink, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Outlined.Architecture, null, Modifier.padding(10.dp).size(26.dp), tint = Color.White)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("منزلي", style = MaterialTheme.typography.titleLarge)
                    Text("HAI / استوديو التصميم", style = MaterialTheme.typography.bodySmall, color = StudioColors.Muted)
                }
                IconButton(onClick = { nav.navigate("cloud") }) { Icon(Icons.Outlined.CloudSync, "المزامنة") }
            }
            Surface(color = StudioColors.Ink, shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(22.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("بيتك يبدأ\nبمخطط.", fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Spacer(Modifier.height(8.dp))
                            Text("ارفعه. عدّله. استكشفه.", color = Color(0xFFB8CCE3), style = MaterialTheme.typography.bodyMedium)
                        }
                        StudioHouseIllustration(Modifier.width(120.dp).height(160.dp))
                    }
                    Spacer(Modifier.height(18.dp))
                    Button(onClick = { nav.navigate("import-type") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = StudioColors.Ink),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Outlined.UploadFile, null); Spacer(Modifier.width(8.dp)); Text("رفع مخطط")
                    }
                }
            }
            OutlinedCard(onClick = { nav.navigate("new") }, modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.outlinedCardColors(containerColor = StudioColors.Paper), shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AddHomeWork, null, tint = StudioColors.Primary)
                    Spacer(Modifier.width(12.dp))
                    Text("تصميم من البداية", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Icon(Icons.Outlined.ChevronLeft, null)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("مساحة العمل", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { nav.navigate("projects") }) { Text("المشاريع ($projectCount)") }
            }
            if (plan == null) {
                StudioEmpty("مشروعك الأول يبدأ هنا", "إنشاء مشروع") { nav.navigate("new") }
            } else {
                Card(colors = CardDefaults.cardColors(containerColor = StudioColors.Paper), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        PlanCanvas(plan = plan, modifier = Modifier.fillMaxWidth().height(180.dp), onSelect = {})
                        Spacer(Modifier.height(12.dp))
                        Text(plan.title, style = MaterialTheme.typography.titleMedium)
                        Text(if (ready) "جاهز للاستكشاف" else "أكمل مراجعة المخطط", color = StudioColors.Muted, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { nav.navigate("editor") }, modifier = Modifier.weight(1f).heightIn(min = 50.dp)) { Text("متابعة التصميم") }
                            FilledTonalButton(onClick = { nav.navigate("3d") }, enabled = ready, modifier = Modifier.weight(1f).heightIn(min = 50.dp)) {
                                Icon(Icons.Outlined.ViewInAr, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("المجسم")
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun Incomplete3DBlockedScreen(nav: NavHostController, detail: String?) {
    Surface(Modifier.fillMaxSize(), color = StudioColors.Canvas) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Outlined.Rule, null, tint = StudioColors.Warning, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(14.dp))
            Text("راجع المخطط قبل 3D", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                detail ?: "الهندسة الحالية غير مكتملة. راجع الحدود والجدران والغرف أولًا ثم اعتمد المشروع.",
                color = StudioColors.Muted,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = { nav.navigate("editor") }, shape = RoundedCornerShape(18.dp)) {
                Icon(Icons.Outlined.Edit, null)
                Spacer(Modifier.width(7.dp))
                Text("العودة للمراجعة والتعديل", fontWeight = FontWeight.Bold)
            }
        }
    }
}
