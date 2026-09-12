package com.manzili.hai

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current
    val store = remember { ProjectPlanStore(context) }
    var plan by remember {
        mutableStateOf(store.load()?.let { MultiFloorGeometryEngine.normalize(PlanVerificationEngine.inspect(it).plan) })
    }
    var pending by remember { mutableStateOf<FloorPlan?>(null) }
    var source by remember { mutableStateOf<Uri?>(null) }

    fun createProject(next: FloorPlan) {
        val verified = PlanVerificationEngine.inspect(next).plan
        val ready = MultiFloorGeometryEngine.persistActive(MultiFloorGeometryEngine.normalize(ProjectMemoryEngine.reconcile(verified)))
        store.createProject(ready)
        plan = MultiFloorGeometryEngine.normalize(ready)
    }

    fun updateProject(next: FloorPlan) {
        val normalized = PlanVerificationEngine.inspect(next).plan
        val carried = plan?.let { ProjectMemoryEngine.carryForward(it, normalized) } ?: ProjectMemoryEngine.reconcile(normalized)
        val ready = MultiFloorGeometryEngine.persistActive(MultiFloorGeometryEngine.normalize(carried))
        store.save(ready)
        plan = MultiFloorGeometryEngine.normalize(ready)
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF27312C), secondary = Color(0xFF9A7447), background = Color(0xFFF7F4EE), surface = Color(0xFFFFFEFA))) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            NavHost(nav, startDestination = "home") {
                composable("home") { ProductionHome(nav, plan, store.listProjects().size) }
                composable("build") { BuildChoice(nav) }
                composable("import") { VerifiedImportScreen(nav, source, { source = it }, { pending = it }) }
                composable("verify") {
                    PlanVerificationScreen(nav, source, pending) { confirmed ->
                        createProject(confirmed)
                        pending = null
                        nav.navigate("editor") { popUpTo("home") }
                    }
                }
                composable("new") {
                    NewBuildSolverScreen(nav) {
                        createProject(it)
                        nav.navigate("editor") { popUpTo("home") }
                    }
                }
                composable("editor") { EnhancedEditor(nav, plan) { updateProject(it) } }
                composable("polygon") { PolygonVertexEditorScreen(nav, plan) { updateProject(it) } }
                composable("floors") { ProjectFloorsScreen(nav, plan) { updateProject(it) } }
                composable("saudi-rules") { SaudiRulesScreen(nav, plan) { updateProject(it) } }
                composable("projects") {
                    ProjectLibraryScreen(nav, store) { opened ->
                        plan = opened?.let { MultiFloorGeometryEngine.normalize(PlanVerificationEngine.inspect(it).plan) }
                    }
                }
                composable("memory") { ProjectMemoryManagerScreen(nav, plan) { updateProject(it) } }
                composable("tools") {
                    ProjectToolsScreen(nav, store, plan) { opened ->
                        plan = opened?.let { MultiFloorGeometryEngine.normalize(PlanVerificationEngine.inspect(it).plan) }
                    }
                }
                composable("export") { QuickExportScreen(nav, plan) }
                composable("settings") { AiSettings(nav) }
            }
        }
    }
}

@Composable
private fun ProductionHome(nav: NavHostController, plan: FloorPlan?, count: Int) {
    Surface(Modifier.fillMaxSize(), color = Color(0xFFF7F4EE)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp)) {
            Text("منزلي HAI", fontSize = 22.sp, fontWeight = FontWeight.Black)
            Text("$count مشروع • Multi-floor • Solver ×3 • Optional Saudi Rules • Polygon", color = Color.Gray, fontSize = 10.sp)
            Spacer(Modifier.height(20.dp))
            Text("مخطط تقرأه،\nتراجعه، ثم تعدله.", fontSize = 34.sp, lineHeight = 39.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(18.dp))
            if (plan != null) {
                Button(onClick = { nav.navigate("editor") }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Icon(Icons.Rounded.Architecture, null); Spacer(Modifier.width(7.dp)); Text("أكمل ${plan.title}")
                }
                Spacer(Modifier.height(7.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    OutlinedButton(onClick = { nav.navigate("floors") }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Layers, null); Spacer(Modifier.width(4.dp)); Text("الأدوار") }
                    OutlinedButton(onClick = { nav.navigate("saudi-rules") }, modifier = Modifier.weight(1f)) {
                        Icon(if (plan.saudiRulesEnabled) Icons.Rounded.FactCheck else Icons.Rounded.AddTask, null)
                        Spacer(Modifier.width(4.dp))
                        Text(if (plan.saudiRulesEnabled) "اشتراطات ✓" else "إضافة اشتراطات")
                    }
                }
                Spacer(Modifier.height(7.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    OutlinedButton(onClick = { nav.navigate("polygon") }, modifier = Modifier.weight(1f)) { Text("Polygon") }
                    OutlinedButton(onClick = { nav.navigate("export") }, modifier = Modifier.weight(1f)) { Text("تصدير") }
                    OutlinedButton(onClick = { nav.navigate("tools") }, modifier = Modifier.weight(1f)) { Text("النسخ") }
                }
                Spacer(Modifier.height(7.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    TextButton(onClick = { nav.navigate("memory") }, modifier = Modifier.weight(1f)) { Text("قواعد HAI") }
                    TextButton(onClick = { nav.navigate("projects") }, modifier = Modifier.weight(1f)) { Text("مشاريعي") }
                }
                Spacer(Modifier.height(12.dp))
            }
            Button(onClick = { nav.navigate("build") }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Icons.Rounded.AddHomeWork, null); Spacer(Modifier.width(7.dp)); Text("ابدأ مشروعًا جديدًا")
            }
            TextButton(onClick = { nav.navigate("settings") }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Tune, null); Spacer(Modifier.width(5.dp)); Text("إعدادات HAI") }
            Spacer(Modifier.weight(1f))
            Text("محرك الاشتراطات السعودية إضافة اختيارية لكل مشروع؛ التصميم الأساسي لا يعتمد عليه عند إيقافه.", color = Color.Gray, fontSize = 10.sp)
        }
    }
}
