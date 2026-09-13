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
import androidx.compose.material.icons.rounded.*
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

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF4F40B8),
            onPrimary = Color.White,
            secondary = Color(0xFFE28B5A),
            background = Color(0xFFF8F6F2),
            surface = Color(0xFFFFFEFC)
        )
    ) {
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
                    OptionalRulesStage(nav, plan, rulesEnabled, ::toggleRules) {
                        Production3DScreenV3(nav, plan)
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

                composable("settings") { ProductionAiSettings(nav) }
            }
        }
    }
}

@Composable
private fun FocusedHomeV2(nav: NavHostController, plan: FloorPlan?, projectCount: Int) {
    Surface(Modifier.fillMaxSize(), color = Color(0xFFF8F6F2)) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = Color(0xFF6353D9).copy(alpha = .10f),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(
                        "HAI",
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
                        color = Color(0xFF4F40B8),
                        fontWeight = FontWeight.Black
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text("منزلي", fontSize = 21.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { nav.navigate("cloud") }) {
                    Icon(Icons.Rounded.CloudSync, "السحابة")
                }
                IconButton(onClick = { nav.navigate("settings") }) {
                    Icon(Icons.Rounded.Tune, "الإعدادات")
                }
            }

            Spacer(Modifier.height(30.dp))

            Text(
                "من 2D إلى بيتك.",
                fontSize = 35.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.Black,
                color = Color(0xFF181A18)
            )
            Spacer(Modifier.height(7.dp))
            Text(
                "استورد مخططًا سابقًا، راجعه وعدّله مع HAI، ثم انتقل إلى 3D.",
                color = Color(0xFF6F6A64),
                fontSize = 13.sp,
                lineHeight = 19.sp
            )

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { nav.navigate("import-type") },
                shape = RoundedCornerShape(23.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF181A18)),
                modifier = Modifier.fillMaxWidth().height(64.dp)
            ) {
                Icon(Icons.Rounded.UploadFile, null)
                Spacer(Modifier.width(8.dp))
                Text("استيراد مشروع سابق", fontWeight = FontWeight.Black, fontSize = 17.sp)
            }

            Spacer(Modifier.height(10.dp))

            OutlinedButton(
                onClick = { nav.navigate("new") },
                shape = RoundedCornerShape(23.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Icon(Icons.Rounded.AddHomeWork, null)
                Spacer(Modifier.width(8.dp))
                Text("تصميم مشروع جديد", fontWeight = FontWeight.Bold)
            }

            if (plan != null) {
                Spacer(Modifier.height(22.dp))
                ElevatedCard(
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(46.dp).background(Color(0xFFE28B5A).copy(alpha = .12f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.HomeWork, null, tint = Color(0xFFE28B5A))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(plan.title, fontSize = 17.sp, fontWeight = FontWeight.Black, maxLines = 1)
                                Text("المشروع الحالي", color = Color(0xFF8B857D), fontSize = 11.sp)
                            }
                        }

                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { nav.navigate("editor") },
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier.weight(1f).height(52.dp)
                            ) {
                                Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("تعديل", fontWeight = FontWeight.Black)
                            }
                            FilledTonalButton(
                                onClick = { nav.navigate("3d") },
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier.weight(1f).height(52.dp)
                            ) {
                                Icon(Icons.Rounded.ViewInAr, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("عرض 3D", fontWeight = FontWeight.Black)
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
                Text("مشاريعي  $projectCount", color = Color(0xFF6F6A64), fontWeight = FontWeight.Bold)
            }
        }
    }
}
