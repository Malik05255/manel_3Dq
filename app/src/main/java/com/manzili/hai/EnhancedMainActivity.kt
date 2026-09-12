package com.manzili.hai

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
import com.manzili.hai.engine.ProjectMemoryEngine
import com.manzili.hai.model.FloorPlan

private val EnhancedSand = Color(0xFFF7F4EE)
private val EnhancedPaper = Color(0xFFFFFEFA)
private val EnhancedInk = Color(0xFF20211E)
private val EnhancedBronze = Color(0xFF9A7447)
private val EnhancedMist = Color(0xFFE9E5DC)
private val EnhancedDeep = Color(0xFF27312C)

class EnhancedMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { EnhancedManziliApp() }
    }
}

@Composable
private fun EnhancedManziliApp() {
    val nav = rememberNavController()
    val context = LocalContext.current
    val store = remember { ProjectPlanStore(context) }
    var plan by remember { mutableStateOf(store.load()) }
    var sourceUri by remember { mutableStateOf<Uri?>(null) }

    fun createProject(next: FloorPlan) {
        val ready = ProjectMemoryEngine.reconcile(next)
        store.createProject(ready)
        plan = ready
    }

    fun updateProject(next: FloorPlan) {
        val ready = plan?.let { current -> ProjectMemoryEngine.carryForward(current, next) }
            ?: ProjectMemoryEngine.reconcile(next)
        plan = ready
        store.save(ready)
    }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = EnhancedDeep,
            secondary = EnhancedBronze,
            background = EnhancedSand,
            surface = EnhancedPaper,
            onPrimary = Color.White,
            onSurface = EnhancedInk
        )
    ) {
        CompositionLocalProvider(
            LocalLayoutDirection provides LayoutDirection.Rtl,
            LocalContentColor provides EnhancedInk
        ) {
            NavHost(navController = nav, startDestination = "home") {
                composable("home") { PersistentHome(nav, plan, store.listProjects().size) }
                composable("build") { BuildChoice(nav) }
                composable("import") { ImportPlan(nav, sourceUri, { sourceUri = it }, { createProject(it) }) }
                composable("new") { NewProject(nav) { createProject(it) } }
                composable("editor") { EnhancedEditor(nav, plan) { updateProject(it) } }
                composable("projects") {
                    ProjectLibraryScreen(nav, store) { opened -> plan = opened }
                }
                composable("memory") {
                    ProjectMemoryManagerScreen(nav, plan) { updated -> updateProject(updated) }
                }
                composable("tools") {
                    ProjectToolsScreen(nav, store, plan) { opened -> plan = opened }
                }
                composable("settings") { AiSettings(nav) }
            }
        }
    }
}

@Composable
private fun PersistentHome(nav: NavHostController, plan: FloorPlan?, projectCount: Int) {
    Surface(color = EnhancedSand, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(EnhancedDeep, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    Text("H", color = Color.White, fontWeight = FontWeight.Black, fontSize = 22.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("منزلي HAI", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                    Text("HAI Architectural Intelligence", color = Color.Gray, fontSize = 10.sp)
                }
                IconButton(onClick = { nav.navigate("projects") }) { Icon(Icons.Rounded.FolderCopy, "مشاريعي") }
            }

            Spacer(Modifier.height(20.dp))
            Surface(color = EnhancedMist, shape = RoundedCornerShape(50.dp)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = EnhancedBronze, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("$projectCount مشروع • ذاكرة قواعد • نسخ قابلة للنقل", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("بيتك يبدأ\nبقرار محسوب.", fontSize = 38.sp, lineHeight = 43.sp, fontWeight = FontWeight.Black)
            Text(
                "HAI يحفظ كل مشروع مستقلًا، يقارن نسخه، ويستطيع نقل المشروع كاملًا بين الأجهزة بدون فقد القواعد.",
                color = Color.Gray,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                modifier = Modifier.padding(top = 9.dp, bottom = 17.dp)
            )

            if (plan != null) {
                Card(
                    onClick = { nav.navigate("editor") },
                    colors = CardDefaults.cardColors(containerColor = EnhancedDeep),
                    shape = RoundedCornerShape(25.dp),
                    modifier = Modifier.fillMaxWidth().height(126.dp)
                ) {
                    Row(Modifier.fillMaxSize().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(52.dp).background(Color.White.copy(alpha = .11f), RoundedCornerShape(17.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Architecture, null, tint = Color.White)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("أكمل مشروعك", color = Color.White, fontWeight = FontWeight.Black, fontSize = 19.sp)
                            Text(plan.title, color = Color.White.copy(alpha = .78f), fontSize = 11.5.sp)
                            Text("V${plan.revision} • ${plan.constraints.count { it.active }} قاعدة فعالة", color = Color.White.copy(alpha = .62f), fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                        Icon(Icons.Rounded.ArrowBackIosNew, null, tint = Color.White.copy(alpha = .75f), modifier = Modifier.size(17.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { nav.navigate("memory") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Rounded.Bookmarks, null, Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text("القواعد", fontSize = 9.5.sp)
                    }
                    OutlinedButton(onClick = { nav.navigate("projects") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Rounded.History, null, Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text("المشاريع", fontSize = 9.5.sp)
                    }
                }
                OutlinedButton(onClick = { nav.navigate("tools") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Rounded.CompareArrows, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("مقارنة النسخ • تصدير / استيراد HAI", fontSize = 10.sp)
                }
                Spacer(Modifier.height(9.dp))
            }

            Card(
                onClick = { nav.navigate("build") },
                colors = CardDefaults.cardColors(containerColor = EnhancedPaper),
                shape = RoundedCornerShape(23.dp),
                modifier = Modifier.fillMaxWidth().height(98.dp)
            ) {
                Row(Modifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).background(EnhancedMist, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.NoteAdd, null, tint = EnhancedDeep)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (projectCount == 0) "ابدأ مشروعك" else "ابدأ مشروعًا جديدًا", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("يُحفظ كمشروع مستقل عن الموجود", color = Color.Gray, fontSize = 11.sp)
                    }
                    Icon(Icons.Rounded.ArrowBackIosNew, null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                }
            }

            Spacer(Modifier.weight(1f))
            Text("أرشيف HAI يتضمن المخطط والقواعد وسجل النسخ، ويخضع لفحص سلامة قبل الاستيراد.", color = Color.Gray, fontSize = 10.sp, modifier = Modifier.padding(bottom = 14.dp))
        }
    }
}
