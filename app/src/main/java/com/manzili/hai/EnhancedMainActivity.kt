package com.manzili.hai

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Architecture
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.NoteAdd
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

    fun replaceProject(next: FloorPlan) {
        val ready = ProjectMemoryEngine.reconcile(next)
        plan = ready
        store.save(ready)
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
                composable("home") { PersistentHome(nav, plan) }
                composable("build") { BuildChoice(nav) }
                composable("import") { ImportPlan(nav, sourceUri, { sourceUri = it }, { replaceProject(it) }) }
                composable("new") { NewProject(nav) { replaceProject(it) } }
                composable("editor") { EnhancedEditor(nav, plan) { updateProject(it) } }
                composable("settings") { AiSettings(nav) }
            }
        }
    }
}

@Composable
private fun PersistentHome(nav: NavHostController, plan: FloorPlan?) {
    Surface(color = EnhancedSand, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(EnhancedDeep, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    Text("H", color = Color.White, fontWeight = FontWeight.Black, fontSize = 22.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("منزلي HAI", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                    Text("HAI Architectural Intelligence", color = Color.Gray, fontSize = 10.sp)
                }
            }

            Spacer(Modifier.height(26.dp))
            Surface(color = EnhancedMist, shape = RoundedCornerShape(50.dp)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = EnhancedBronze, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("مخطط + قيود + ذاكرة مشروع دائمة", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("بيتك يبدأ\nبقرار محسوب.", fontSize = 38.sp, lineHeight = 43.sp, fontWeight = FontWeight.Black)
            Text(
                "HAI يحفظ قرارات المشروع التي ثبّتها، ويعيد تطبيقها على كل تعديل وبديل لاحق.",
                color = Color.Gray,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                modifier = Modifier.padding(top = 10.dp, bottom = 24.dp)
            )

            if (plan != null) {
                Card(
                    onClick = { nav.navigate("editor") },
                    colors = CardDefaults.cardColors(containerColor = EnhancedDeep),
                    shape = RoundedCornerShape(25.dp),
                    modifier = Modifier.fillMaxWidth().height(145.dp)
                ) {
                    Row(Modifier.fillMaxSize().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(54.dp).background(Color.White.copy(alpha = .11f), RoundedCornerShape(17.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Architecture, null, tint = Color.White)
                        }
                        Spacer(Modifier.width(15.dp))
                        Column(Modifier.weight(1f)) {
                            Text("أكمل مشروعك", color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp)
                            Text(plan.title, color = Color.White.copy(alpha = .78f), fontSize = 12.sp)
                            Text(
                                "نسخة ${plan.revision} • ${plan.constraints.count { it.active }} قاعدة محفوظة",
                                color = Color.White.copy(alpha = .62f),
                                fontSize = 10.5.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        Icon(Icons.Rounded.ArrowBackIosNew, null, tint = Color.White.copy(alpha = .75f), modifier = Modifier.size(17.dp))
                    }
                }
                Spacer(Modifier.height(13.dp))
            }

            Card(
                onClick = { nav.navigate("build") },
                colors = CardDefaults.cardColors(containerColor = EnhancedPaper),
                shape = RoundedCornerShape(23.dp),
                modifier = Modifier.fillMaxWidth().height(112.dp)
            ) {
                Row(Modifier.fillMaxSize().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).background(EnhancedMist, RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.NoteAdd, null, tint = EnhancedDeep)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (plan == null) "ابدأ مشروعك" else "ابدأ مشروعًا جديدًا", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text("من الصفر أو برفع مخطط سابق", color = Color.Gray, fontSize = 11.5.sp)
                    }
                    Icon(Icons.Rounded.ArrowBackIosNew, null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                }
            }

            Spacer(Modifier.weight(1f))
            Text(
                "قواعد المشروع تُحفظ على هذا الجهاز وتبقى مرتبطة بالمخطط الحالي.",
                color = Color.Gray,
                fontSize = 10.5.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
    }
}
