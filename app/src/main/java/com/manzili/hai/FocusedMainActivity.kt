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

class FocusedMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FocusedProductionApp() }
    }
}

@Composable
private fun FocusedProductionApp() {
    val nav = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { ProjectPlanStore(context) }
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
    var source by remember { mutableStateOf<Uri?>(null) }
    var selectedProjectType by remember { mutableStateOf<SaudiProjectTypeEngine.Type?>(null) }
    var pendingRulesEnabled by remember { mutableStateOf(false) }

    fun createProject(next: FloorPlan) {
        val requested = next.copy(saudiRulesEnabled = next.saudiRulesEnabled || pendingRulesEnabled)
        val verified = SaudiResidentialEngine.normalize(PlanVerificationEngine.inspect(requested).plan)
        val ready = MultiFloorGeometryEngine.persistActive(
            MultiFloorGeometryEngine.normalize(ProjectMemoryEngine.reconcile(verified))
        )
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
        val carried = current?.let { ProjectMemoryEngine.carryForward(it, hydrated) }
            ?: ProjectMemoryEngine.reconcile(hydrated)
        val settingsSafe = if (current != null && !allowSaudiRulesSettingChange) {
            carried.copy(saudiRulesEnabled = current.saudiRulesEnabled)
        } else carried
        val ready = MultiFloorGeometryEngine.persistActive(MultiFloorGeometryEngine.normalize(settingsSafe))
        store.save(ready)
        plan = MultiFloorGeometryEngine.normalize(ready)
        pendingRulesEnabled = ready.saudiRulesEnabled
    }

    fun toggleRules(enabled: Boolean) {
        plan?.let {
            updateProject(it.copy(saudiRulesEnabled = enabled, revision = it.revision + 1), true)
        } ?: run {
            pendingRulesEnabled = enabled
        }
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
                composable("home") {
                    FocusedHome(nav, plan, store.listProjects().size)
                }
                composable("import-type") {
                    LaunchedEffect(Unit) { pendingRulesEnabled = false }
                    OptionalRulesStage(nav, null, pendingRulesEnabled, { pendingRulesEnabled = it }) {
                        SaudiProjectTypeScreen(nav, "نوع المشروع", "") { type ->
                            selectedProjectType = type
                            nav.navigate("import")
                        }
                    }
                }
                composable("import") {
                    OptionalRulesStage(nav, null, pendingRulesEnabled, { pendingRulesEnabled = it }) {
                        VerifiedImportScreenV3(
                            nav = nav,
                            source = source,
                            setSource = { source = it },
                            onAnalyzed = { pending = it },
                            projectType = selectedProjectType
                        )
                    }
                }
                composable("verify") {
                    OptionalRulesStage(nav, null, pendingRulesEnabled, { pendingRulesEnabled = it }) {
                        PlanVerificationScreen(nav, source, pending) { confirmed ->
                            val type = selectedProjectType ?: SaudiProjectTypeEngine.infer(confirmed)
                            createProject(SaudiProjectTypeEngine.apply(confirmed, type))
                            pending = null
                            nav.navigate("editor") { popUpTo("home") }
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
                composable("editor") {
                    OptionalRulesStage(nav, plan, pendingRulesEnabled, ::toggleRules) {
                        FocusedEditorStage(nav, plan) { updateProject(it) }
                    }
                }
                composable("3d") {
                    OptionalRulesStage(nav, plan, pendingRulesEnabled, ::toggleRules) {
                        Production3DScreenV3(nav, plan)
                    }
                }
                composable("walkthrough") {
                    OptionalRulesStage(nav, plan, pendingRulesEnabled, ::toggleRules) {
                        WalkthroughScreen(nav, plan)
                    }
                }
                composable("saudi-rules") {
                    SaudiRulesScreen(nav, plan) { updateProject(it, true) }
                }
                composable("projects") {
                    ProjectLibraryScreen(nav, store) { opened ->
                        plan = opened?.let {
                            MultiFloorGeometryEngine.normalize(
                                SaudiResidentialEngine.normalize(PlanVerificationEngine.inspect(it).plan)
                            )
                        }
                        pendingRulesEnabled = plan?.saudiRulesEnabled ?: false
                    }
                }
                composable("settings") {
                    ProductionAiSettings(nav)
                }
            }
        }
    }
}

@Composable
private fun FocusedHome(nav: NavHostController, plan: FloorPlan?, projectCount: Int) {
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

            Spacer(Modifier.height(30.dp))

            Text(
                "من المخطط إلى 3D.",
                fontSize = 35.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.Bold,
                color = StudioColors.Ink
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "ارفع المخطط، راجع ما فهمه HAI وصححه، وبعدها افتح النموذج ثلاثي الأبعاد.",
                color = StudioColors.Muted,
                fontSize = 13.sp,
                lineHeight = 19.sp
            )

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { nav.navigate("import-type") },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = StudioColors.Ink),
                modifier = Modifier.fillMaxWidth().height(62.dp)
            ) {
                Icon(Icons.Outlined.UploadFile, null)
                Spacer(Modifier.width(8.dp))
                Text("استيراد مخطط", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }

            Spacer(Modifier.height(10.dp))

            OutlinedButton(
                onClick = { nav.navigate("new") },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Icon(Icons.Outlined.AddHomeWork, null)
                Spacer(Modifier.width(8.dp))
                Text("تصميم من الصفر", fontWeight = FontWeight.Bold)
            }

            if (plan != null) {
                Spacer(Modifier.height(22.dp))
                val type = SaudiProjectTypeEngine.infer(plan)
                ElevatedCard(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(18.dp)) {
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
                                Text(plan.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text(type.label, color = StudioColors.Muted, fontSize = 12.sp)
                            }
                        }

                        Spacer(Modifier.height(15.dp))

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { nav.navigate("editor") },
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier.weight(1f).height(52.dp)
                            ) {
                                Icon(Icons.Outlined.FactCheck, null, modifier = Modifier.size(19.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("مراجعة", fontWeight = FontWeight.Bold)
                            }
                            FilledTonalButton(
                                onClick = { nav.navigate("3d") },
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier.weight(1f).height(52.dp)
                            ) {
                                Icon(Icons.Outlined.ViewInAr, null, modifier = Modifier.size(19.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("عرض 3D", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            TextButton(
                onClick = { nav.navigate("projects") },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("مشاريعي  $projectCount", color = StudioColors.Muted, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun FocusedEditorStage(
    nav: NavHostController,
    plan: FloorPlan?,
    setPlan: (FloorPlan) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Surface(color = StudioColors.Paper, tonalElevation = 1.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("راجع المخطط قبل 3D", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("صحح أي غرفة أو جدار غير مطابق أولًا", color = Color.Gray, fontSize = 12.sp)
                }
                Button(
                    enabled = plan != null,
                    onClick = { nav.navigate("3d") },
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 13.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Outlined.ViewInAr, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("عرض 3D", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
        Box(Modifier.weight(1f)) {
            EnhancedEditor(nav, plan, setPlan)
        }
    }
}
