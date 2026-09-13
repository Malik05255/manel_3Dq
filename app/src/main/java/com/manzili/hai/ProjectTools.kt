package com.manzili.hai

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.manzili.hai.data.ProjectArchiveStore
import com.manzili.hai.data.ProjectPlanStore
import com.manzili.hai.engine.VersionComparisonEngine
import com.manzili.hai.model.FloorPlan

private val ToolSand = StudioColors.Canvas
private val ToolPaper = StudioColors.Paper
private val ToolDeep = StudioColors.Ink
private val ToolBronze = StudioColors.Primary
private val ToolMist = StudioColors.Line

@Composable
fun ProjectToolsScreen(
    nav: NavHostController,
    store: ProjectPlanStore,
    plan: FloorPlan?,
    onPlanChanged: (FloorPlan?) -> Unit
) {
    val context = LocalContext.current
    val archive = remember(store) { ProjectArchiveStore(store) }
    var status by remember { mutableStateOf<String?>(null) }
    var exportPayload by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val payload = exportPayload
        if (uri != null && payload != null) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(payload) } ?: error("تعذر فتح الملف")
            }.isSuccess
            status = if (ok) "تم تصدير المشروع مع سجل نسخه وقواعد HAI." else "تعذر كتابة ملف النسخة الاحتياطية."
        }
        exportPayload = null
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val result = runCatching {
            val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("ملف فارغ")
            archive.importProject(raw)
        }.getOrNull()
        if (result != null) {
            onPlanChanged(result.plan)
            status = "تم التحقق من سلامة ملف HAI واستيراد ${result.restoredVersions} نسخة كمشروع مستقل."
        } else {
            status = "رفضت الاستيراد: الملف ليس أرشيف HAI صالحًا أو فشل فحص السلامة."
        }
    }

    val activeId = store.activeProjectId()
    val versions = activeId?.let { store.listVersions(it) }.orEmpty()
    var selectedRevision by remember(activeId, plan?.revision) {
        mutableStateOf(versions.firstOrNull { it.revision != plan?.revision }?.revision)
    }
    var versionMenu by remember { mutableStateOf(false) }
    val oldPlan = if (activeId != null && selectedRevision != null) store.loadVersion(activeId, selectedRevision!!) else null
    val diff = if (oldPlan != null && plan != null) VersionComparisonEngine.compare(oldPlan, plan) else null

    Surface(color = ToolSand, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Outlined.ArrowForward, "رجوع") }
                Box(Modifier.size(40.dp).background(ToolDeep, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Handyman, null, tint = Color.White)
                }
                Spacer(Modifier.width(9.dp))
                Column {
                    Text("أدوات المشروع", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("مقارنة • تصدير • استيراد • تحقق", color = Color.Gray, fontSize = 12.sp)
                }
            }

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                if (plan == null || activeId == null) {
                    Text("لا يوجد مشروع مفتوح. استيراد ملف HAI متاح أدناه.", color = Color.Gray)
                } else {
                    Card(colors = CardDefaults.cardColors(containerColor = ToolDeep), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text("مقارنة النسخ", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("النسخة الحالية V${plan.revision}", color = Color.White.copy(alpha = .65f), fontSize = 12.sp)
                            Spacer(Modifier.height(8.dp))
                            Box {
                                OutlinedButton(onClick = { versionMenu = true }, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
                                    Text(selectedRevision?.let { "قارن مع V$it" } ?: "اختر نسخة سابقة")
                                    Spacer(Modifier.width(5.dp)); Icon(Icons.Outlined.ArrowDropDown, null)
                                }
                                DropdownMenu(expanded = versionMenu, onDismissRequest = { versionMenu = false }) {
                                    versions.filter { it.revision != plan.revision }.forEach { v ->
                                        DropdownMenuItem(text = { Text("V${v.revision}") }, onClick = { selectedRevision = v.revision; versionMenu = false })
                                    }
                                }
                            }
                        }
                    }

                    if (oldPlan != null && diff != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(diff.headline, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("بصريًا", color = ToolBronze, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, bottom = 5.dp))
                        Text("V${oldPlan.revision}", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        PlanCanvas(oldPlan, Modifier.fillMaxWidth().height(175.dp), previewMode = false, onSelect = {})
                        Spacer(Modifier.height(7.dp))
                        Text("V${plan.revision}", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        PlanCanvas(plan, Modifier.fillMaxWidth().height(175.dp), previewMode = true, onSelect = {})

                        Spacer(Modifier.height(9.dp))
                        Card(colors = CardDefaults.cardColors(containerColor = ToolPaper), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("ملخص HAI للفروقات", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                                if (diff.changes.isEmpty()) Text("لم أجد فرقًا هندسيًا ذا دلالة بين النسختين.", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                                diff.changes.take(14).forEach { Text("• $it", fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 4.dp)) }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = ToolPaper), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(13.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Backup, null, tint = ToolBronze)
                                Spacer(Modifier.width(7.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("نسخة HAI احتياطية", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                                    Text("تشمل المخطط، القواعد، وسجل النسخ المحفوظ.", color = Color.Gray, fontSize = 12.sp)
                                }
                            }
                            Button(onClick = {
                                exportPayload = archive.exportProject(activeId)
                                if (exportPayload != null) {
                                    val safe = plan.title.replace(Regex("[^A-Za-z0-9ء-ي_-]+"), "-").take(32).ifBlank { "project" }
                                    exportLauncher.launch("$safe.hai.json")
                                } else status = "تعذر تجهيز أرشيف المشروع."
                            }, modifier = Modifier.fillMaxWidth().padding(top = 9.dp), shape = RoundedCornerShape(12.dp)) {
                                Icon(Icons.Outlined.FileDownload, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("تصدير المشروع كاملًا")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Card(colors = CardDefaults.cardColors(containerColor = ToolPaper), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(13.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.RestorePage, null, tint = ToolBronze)
                            Spacer(Modifier.width(7.dp))
                            Column(Modifier.weight(1f)) {
                                Text("استيراد مشروع HAI", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                                Text("يفحص نوع الملف وSHA-256 قبل إنشاء مشروع مستقل.", color = Color.Gray, fontSize = 12.sp)
                            }
                        }
                        OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }, modifier = Modifier.fillMaxWidth().padding(top = 9.dp), shape = RoundedCornerShape(12.dp)) {
                            Icon(Icons.Outlined.FileUpload, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("اختيار ملف .hai.json")
                        }
                    }
                }

                status?.let {
                    Spacer(Modifier.height(10.dp))
                    Surface(color = ToolMist, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(it, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(11.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
