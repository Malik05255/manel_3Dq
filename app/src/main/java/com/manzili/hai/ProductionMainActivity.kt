package com.manzili.hai

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.manzili.hai.engine.MultiFloorGeometryEngine
import com.manzili.hai.engine.PlanVerificationEngine
import com.manzili.hai.engine.ProjectMemoryEngine
import com.manzili.hai.engine.SaudiProjectTypeEngine
import com.manzili.hai.engine.SaudiResidentialEngine
import com.manzili.hai.model.FloorPlan

class ProductionMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ProductionApp() }
    }
}

@Composable
private fun ProductionApp() {
    val nav = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { ProjectPlanStore(context) }
    var plan by remember { mutableStateOf(store.load()?.let { MultiFloorGeometryEngine.normalize(SaudiResidentialEngine.normalize(PlanVerificationEngine.inspect(it).plan)) }) }
    var pending by remember { mutableStateOf<FloorPlan?>(null) }
    var source by remember { mutableStateOf<Uri?>(null) }
    var convertTo3D by remember { mutableStateOf(false) }
    var selectedProjectType by remember { mutableStateOf<SaudiProjectTypeEngine.Type?>(null) }
    var pendingRulesEnabled by remember { mutableStateOf(false) }

    fun createProject(next: FloorPlan) {
        val requested = next.copy(saudiRulesEnabled = next.saudiRulesEnabled || pendingRulesEnabled)
        val verified = SaudiResidentialEngine.normalize(PlanVerificationEngine.inspect(requested).plan)
        val ready = MultiFloorGeometryEngine.persistActive(MultiFloorGeometryEngine.normalize(ProjectMemoryEngine.reconcile(verified)))
        store.createProject(ready)
        plan = MultiFloorGeometryEngine.normalize(ready)
        pendingRulesEnabled = ready.saudiRulesEnabled
    }

    fun updateProject(next: FloorPlan, allowSaudiRulesSettingChange: Boolean = false) {
        val verified = SaudiResidentialEngine.normalize(PlanVerificationEngine.inspect(next).plan)
        val current = plan
        val hydrated = if (current != null && verified.floors.isEmpty() && current.floors.isNotEmpty()) {
            verified.copy(
                floors = current.floors,
                activeFloorId = current.activeFloorId,
                site = current.site,
                saudiRulesEnabled = current.saudiRulesEnabled
            )
        } else verified
        val carried = current?.let { ProjectMemoryEngine.carryForward(it, hydrated) } ?: ProjectMemoryEngine.reconcile(hydrated)
        val settingsSafe = if (current != null && !allowSaudiRulesSettingChange) carried.copy(saudiRulesEnabled = current.saudiRulesEnabled) else carried
        val ready = MultiFloorGeometryEngine.persistActive(MultiFloorGeometryEngine.normalize(settingsSafe))
        store.save(ready)
        plan = MultiFloorGeometryEngine.normalize(ready)
        pendingRulesEnabled = ready.saudiRulesEnabled
    }

    fun toggleExistingRules(enabled: Boolean) {
        plan?.let { updateProject(it.copy(saudiRulesEnabled = enabled, revision = it.revision + 1), true) }
            ?: run { pendingRulesEnabled = enabled }
    }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = StudioColors.Primary,
            onPrimary = Color.White,
            secondary = StudioColors.Warning,
            background = StudioColors.Canvas,
            surface = StudioColors.Paper,
            surfaceVariant = StudioColors.Line,
            onBackground = StudioColors.Ink,
            onSurface = StudioColors.Ink
        )
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            NavHost(nav, startDestination = "home") {
                composable("home") { ProductionHome(nav, plan, store.listProjects().size) }
                composable("build") {
                    LaunchedEffect(Unit) { pendingRulesEnabled = false }
                    OptionalRulesStage(nav, null, pendingRulesEnabled, { pendingRulesEnabled = it }) { ProductionBuildChoiceScreen(nav) }
                }
                composable("import-type") {
                    LaunchedEffect(Unit) { pendingRulesEnabled = false }
                    OptionalRulesStage(nav, null, pendingRulesEnabled, { pendingRulesEnabled = it }) {
                        SaudiProjectTypeScreen(nav, "نوع المشروع", "") { type ->
                            selectedProjectType = type
                            convertTo3D = false
                            nav.navigate("import")
                        }
                    }
                }
                composable("import3d-type") {
                    LaunchedEffect(Unit) { pendingRulesEnabled = false }
                    OptionalRulesStage(nav, null, pendingRulesEnabled, { pendingRulesEnabled = it }) {
                        SaudiProjectTypeScreen(nav, "نوع المشروع", "") { type ->
                            selectedProjectType = type
                            convertTo3D = true
                            nav.navigate("import3d")
                        }
                    }
                }
                composable("import") {
                    LaunchedEffect(Unit) { convertTo3D = false }
                    OptionalRulesStage(nav, null, pendingRulesEnabled, { pendingRulesEnabled = it }) {
                        VerifiedImportScreenV3(nav, source, { source = it }, { pending = it }, selectedProjectType)
                    }
                }
                composable("import3d") {
                    LaunchedEffect(Unit) { convertTo3D = true }
                    OptionalRulesStage(nav, null, pendingRulesEnabled, { pendingRulesEnabled = it }) {
                        VerifiedImportScreenV3(nav, source, { source = it }, { pending = it }, selectedProjectType)
                    }
                }
                composable("verify") {
                    OptionalRulesStage(nav, null, pendingRulesEnabled, { pendingRulesEnabled = it }) {
                        PlanVerificationScreen(nav, source, pending) { confirmed ->
                            val type = selectedProjectType ?: SaudiProjectTypeEngine.infer(confirmed)
                            createProject(SaudiProjectTypeEngine.apply(confirmed, type))
                            pending = null
                            val target = if (convertTo3D) "3d" else "editor"
                            convertTo3D = false
                            nav.navigate(target) { popUpTo("home") }
                        }
                    }
                }
                composable("new") {
                    LaunchedEffect(Unit) { pendingRulesEnabled = false }
                    OptionalRulesStage(nav, null, pendingRulesEnabled, { pendingRulesEnabled = it }) {
                        SaudiProjectTypeScreen(nav, "نوع المشروع", "") { type ->
                            selectedProjectType = type
                            nav.navigate("new-brief")
                        }
                    }
                }
                composable("new-brief") {
                    val type = selectedProjectType ?: SaudiProjectTypeEngine.Type.VILLA_TWO
                    OptionalRulesStage(nav, null, pendingRulesEnabled, { pendingRulesEnabled = it }) {
                        SaudiAdaptiveBuildScreen(nav, type) {
                            createProject(SaudiProjectTypeEngine.apply(it, type))
                            nav.navigate("editor") { popUpTo("home") }
                        }
                    }
                }
                composable("change-type") {
                    val current = plan
                    OptionalRulesStage(nav, current, pendingRulesEnabled, ::toggleExistingRules) {
                        SaudiProjectTypeScreen(nav, "نوع المشروع", "", current?.let(SaudiProjectTypeEngine::infer)) { type ->
                            selectedProjectType = type
                            current?.let { updateProject(SaudiProjectTypeEngine.apply(it, type)) }
                            nav.popBackStack()
                        }
                    }
                }
                composable("editor") { OptionalRulesStage(nav, plan, pendingRulesEnabled, ::toggleExistingRules) { EnhancedEditor(nav, plan) { updateProject(it) } } }
                composable("polygon") { OptionalRulesStage(nav, plan, pendingRulesEnabled, ::toggleExistingRules) { PolygonVertexEditorScreen(nav, plan) { updateProject(it) } } }
                composable("floors") { OptionalRulesStage(nav, plan, pendingRulesEnabled, ::toggleExistingRules) { ProjectFloorsScreen(nav, plan) { updateProject(it) } } }
                composable("3d") { OptionalRulesStage(nav, plan, pendingRulesEnabled, ::toggleExistingRules) { Production3DScreenV3(nav, plan) } }
                composable("walkthrough") { OptionalRulesStage(nav, plan, pendingRulesEnabled, ::toggleExistingRules) { WalkthroughScreen(nav, plan) } }
                composable("saudi-audit") { OptionalRulesStage(nav, plan, pendingRulesEnabled, ::toggleExistingRules) { SaudiPlanAuditScreen(nav, plan) } }
                composable("saudi-rules") { SaudiRulesScreen(nav, plan) { updateProject(it, true) } }
                composable("projects") {
                    ProjectLibraryScreen(nav, store) { opened ->
                        plan = opened?.let { MultiFloorGeometryEngine.normalize(SaudiResidentialEngine.normalize(PlanVerificationEngine.inspect(it).plan)) }
                        pendingRulesEnabled = plan?.saudiRulesEnabled ?: false
                    }
                }
                composable("memory") { OptionalRulesStage(nav, plan, pendingRulesEnabled, ::toggleExistingRules) { ProjectMemoryManagerScreen(nav, plan) { updateProject(it) } } }
                composable("tools") {
                    ProjectToolsScreen(nav, store, plan) { opened ->
                        plan = opened?.let { MultiFloorGeometryEngine.normalize(SaudiResidentialEngine.normalize(PlanVerificationEngine.inspect(it).plan)) }
                        pendingRulesEnabled = plan?.saudiRulesEnabled ?: false
                    }
                }
                composable("export") { QuickExportScreen(nav, plan) }
                composable("settings") { ProductionAiSettings(nav) }
            }
        }
    }
}

@Composable
private fun ProductionHome(nav: NavHostController, plan: FloorPlan?, count: Int) {
    Surface(Modifier.fillMaxSize(), color = StudioColors.Canvas) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = StudioColors.Primary.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(
                        "HAI",
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
                        color = StudioColors.Primary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text("منزلي", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { nav.navigate("settings") }) {
                    Icon(Icons.Outlined.Tune, "الإعدادات")
                }
            }

            Spacer(Modifier.height(28.dp))

            Text(
                "صمّم بيتك.",
                fontSize = 38.sp,
                lineHeight = 42.sp,
                fontWeight = FontWeight.Bold,
                color = StudioColors.Ink
            )

            Spacer(Modifier.height(24.dp))

            if (plan != null) {
                val type = SaudiProjectTypeEngine.infer(plan)
                ElevatedCard(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(46.dp)
                                    .background(StudioColors.Warning.copy(alpha = 0.12f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Outlined.HomeWork, null, tint = StudioColors.Warning)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(plan.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text(type.label, color = StudioColors.Muted, fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { nav.navigate("editor") },
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = StudioColors.Primary),
                            modifier = Modifier.fillMaxWidth().height(54.dp)
                        ) {
                            Text("أكمل", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HomeAction(Icons.Outlined.ViewInAr, "3D", StudioColors.Primary, Modifier.weight(1f)) { nav.navigate("3d") }
                    HomeAction(Icons.Outlined.DirectionsWalk, "جولة", StudioColors.Warning, Modifier.weight(1f)) { nav.navigate("walkthrough") }
                }

                Spacer(Modifier.height(10.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HomeAction(Icons.Outlined.FactCheck, "مراجعة", StudioColors.Success, Modifier.weight(1f)) { nav.navigate("saudi-audit") }
                    HomeAction(Icons.Outlined.Layers, "الأدوار", StudioColors.Primary, Modifier.weight(1f)) { nav.navigate("floors") }
                    HomeAction(Icons.Outlined.IosShare, "تصدير", StudioColors.Warning, Modifier.weight(1f)) { nav.navigate("export") }
                }

                Spacer(Modifier.height(18.dp))
            }

            Button(
                onClick = { nav.navigate("build") },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = StudioColors.Ink),
                modifier = Modifier.fillMaxWidth().height(60.dp)
            ) {
                Icon(Icons.Outlined.AddHomeWork, null)
                Spacer(Modifier.width(8.dp))
                Text("مشروع جديد", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }

            Spacer(Modifier.height(10.dp))

            OutlinedButton(
                onClick = { nav.navigate("import3d-type") },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(58.dp)
            ) {
                Icon(Icons.Outlined.UploadFile, null)
                Spacer(Modifier.width(8.dp))
                Text("استيراد مخطط", fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.weight(1f))

            TextButton(
                onClick = { nav.navigate("projects") },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("مشاريعي  $count", color = StudioColors.Muted, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun HomeAction(
    icon: ImageVector,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    ElevatedCard(
        onClick = onClick,
        modifier = modifier.height(92.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .background(accent.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.height(7.dp))
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}