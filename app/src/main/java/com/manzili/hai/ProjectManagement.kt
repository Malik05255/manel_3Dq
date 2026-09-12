package com.manzili.hai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.manzili.hai.data.ProjectPlanStore
import com.manzili.hai.engine.ProjectConstraintManager
import com.manzili.hai.engine.ProjectMemoryEngine
import com.manzili.hai.model.FloorPlan
import com.manzili.hai.model.ProjectConstraint

private val PMPaper = Color(0xFFFFFEFA)
private val PMSand = Color(0xFFF7F4EE)
private val PMDeep = Color(0xFF27312C)
private val PMBronze = Color(0xFF9A7447)
private val PMMist = Color(0xFFE9E5DC)

@Composable
fun ProjectLibraryScreen(nav: NavHostController, store: ProjectPlanStore, onPlanChanged: (FloorPlan?) -> Unit) {
    var projects by remember { mutableStateOf(store.listProjects()) }
    var expanded by remember { mutableStateOf(store.activeProjectId()) }
    fun refresh() { projects = store.listProjects() }

    PMPage {
        PMTop(nav, "مشاريعي", "المخططات والنسخ المحفوظة على هذا الجهاز")
        Text("${projects.size} مشروع محفوظ", color = Color.Gray, fontSize = 11.sp)
        Spacer(Modifier.height(10.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            projects.forEach { project ->
                val open = expanded == project.id
                Card(colors = CardDefaults.cardColors(containerColor = if (project.active) PMDeep else PMPaper), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(13.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Architecture, null, tint = if (project.active) Color.White else PMDeep)
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f)) {
                                Text(project.title, color = if (project.active) Color.White else Color.Black, fontWeight = FontWeight.Black, fontSize = 14.sp)
                                Text("V${project.revision} • ${project.roomCount} غرفة • ${project.activeConstraints} قاعدة", color = if (project.active) Color.White.copy(alpha = .65f) else Color.Gray, fontSize = 9.5.sp)
                            }
                            IconButton(onClick = { expanded = if (open) null else project.id }) {
                                Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = if (project.active) Color.White else Color.Black)
                            }
                        }
                        Button(
                            onClick = {
                                store.open(project.id)?.let {
                                    onPlanChanged(it); refresh(); nav.navigate("editor")
                                }
                            },
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                            colors = if (project.active) ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = PMDeep) else ButtonDefaults.buttonColors()
                        ) { Text(if (project.active) "فتح المشروع" else "تبديل وفتح", fontSize = 10.sp) }

                        if (open) {
                            HorizontalDivider(color = if (project.active) Color.White.copy(alpha = .15f) else PMMist, modifier = Modifier.padding(vertical = 8.dp))
                            Text("سجل النسخ", color = if (project.active) Color.White else Color.Black, fontWeight = FontWeight.Bold, fontSize = 10.5.sp)
                            Text("الاستعادة تنشئ نسخة أحدث ولا تستبدل التاريخ.", color = if (project.active) Color.White.copy(alpha = .6f) else Color.Gray, fontSize = 8.5.sp)
                            store.listVersions(project.id).take(8).forEach { version ->
                                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Surface(color = if (project.active) Color.White.copy(alpha = .1f) else PMMist, shape = RoundedCornerShape(9.dp)) {
                                        Text("V${version.revision}", color = if (project.active) Color.White else Color.Black, fontWeight = FontWeight.Black, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text("${version.activeConstraints} قاعدة", color = if (project.active) Color.White.copy(alpha = .6f) else Color.Gray, fontSize = 8.5.sp, modifier = Modifier.weight(1f))
                                    TextButton(onClick = {
                                        store.restoreVersion(project.id, version.revision)?.let {
                                            onPlanChanged(it); refresh(); nav.navigate("editor")
                                        }
                                    }) { Text("استعادة", color = if (project.active) Color.White else PMBronze, fontSize = 9.sp) }
                                }
                            }
                        }
                    }
                }
            }
            if (projects.isEmpty()) Text("لا توجد مشاريع محفوظة بعد.", color = Color.Gray, modifier = Modifier.padding(top = 30.dp))
        }
    }
}

@Composable
fun ProjectMemoryManagerScreen(nav: NavHostController, plan: FloorPlan?, onUpdate: (FloorPlan) -> Unit) {
    if (plan == null) {
        PMPage { PMTop(nav, "ذاكرة المشروع", "لا يوجد مشروع مفتوح"); Text("افتح مشروعًا أولًا.", color = Color.Gray) }
        return
    }
    var editing by remember { mutableStateOf<ProjectConstraint?>(null) }
    var value by remember { mutableStateOf("") }
    val rules = plan.constraints.sortedWith(compareByDescending<ProjectConstraint> { it.active }.thenByDescending { it.priority })

    PMPage {
        PMTop(nav, "ذاكرة المشروع", plan.title)
        Text("${rules.count { it.active }} قاعدة فعالة من ${rules.size}", color = Color.Gray, fontSize = 10.sp)
        Spacer(Modifier.height(9.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rules.forEach { rule ->
                Card(colors = CardDefaults.cardColors(containerColor = PMPaper), shape = RoundedCornerShape(17.dp)) {
                    Column(Modifier.padding(11.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (rule.active) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, null, tint = if (rule.active) PMBronze else Color.Gray, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(ProjectConstraintManager.describe(plan, rule), fontWeight = FontWeight.Black, fontSize = 11.sp)
                                Text(if (rule.hard) "قيد إلزامي" else "أولوية تصميمية", color = Color.Gray, fontSize = 8.5.sp)
                            }
                            Switch(checked = rule.active, onCheckedChange = { enabled ->
                                onUpdate(ProjectConstraintManager.setActive(plan, rule.id, enabled).copy(revision = plan.revision + 1))
                            })
                        }
                        Row(Modifier.fillMaxWidth()) {
                            if (rule.kind == ProjectMemoryEngine.MIN_ROOM_AREA) {
                                TextButton(onClick = { editing = rule; value = rule.value?.let { "%.1f".format(it) } ?: "" }, modifier = Modifier.weight(1f)) {
                                    Text("تعديل الحد", fontSize = 9.5.sp)
                                }
                            }
                            TextButton(onClick = { onUpdate(ProjectConstraintManager.forget(plan, rule.id).copy(revision = plan.revision + 1)) }, modifier = Modifier.weight(1f)) {
                                Text("نسيان القاعدة", color = PMBronze, fontSize = 9.5.sp)
                            }
                        }
                    }
                }
            }
            if (rules.isEmpty()) Text("قل لـ HAI مثلًا: «المجلس ممنوع يصغر» أو «مدخل الضيوف مستقل».", color = Color.Gray, fontSize = 11.sp)
        }
    }

    editing?.let { rule ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("تعديل الحد الأدنى") },
            text = { OutlinedTextField(value, { value = it.replace(',', '.') }, label = { Text("المساحة بالمتر المربع") }, singleLine = true) },
            confirmButton = { TextButton(onClick = {
                value.toDoubleOrNull()?.takeIf { it > 0 }?.let { number -> onUpdate(ProjectConstraintManager.updateValue(plan, rule.id, number).copy(revision = plan.revision + 1)) }
                editing = null
            }) { Text("حفظ") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("إلغاء") } }
        )
    }
}

@Composable
private fun PMPage(content: @Composable ColumnScope.() -> Unit) = Surface(color = PMSand, modifier = Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp), content = content)
}

@Composable
private fun PMTop(nav: NavHostController, title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Rounded.ArrowForward, "رجوع") }
        Box(Modifier.size(40.dp).background(PMDeep, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.FolderSpecial, null, tint = Color.White) }
        Spacer(Modifier.width(9.dp))
        Column { Text(title, fontWeight = FontWeight.Black, fontSize = 18.sp); Text(subtitle, color = Color.Gray, fontSize = 9.5.sp) }
    }
}
