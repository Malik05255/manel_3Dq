package com.manzili.hai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.manzili.hai.ai.HaiArchitectClient
import com.manzili.hai.data.HaiSettings
import com.manzili.hai.engine.ArchitecturalEngine
import com.manzili.hai.engine.StructuralGeometryEngine
import com.manzili.hai.model.*
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

private val Sand = StudioColors.Canvas
private val Paper = StudioColors.Paper
private val Ink = StudioColors.Ink
private val Bronze = StudioColors.Primary
private val Mist = StudioColors.Line
private val Deep = StudioColors.Ink
private val Sage = StudioColors.Muted
private val SoftBlue = Color(0xFF70808A)

data class PlanSelection(val kind: String, val id: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ManziliApp() }
    }
}

@Composable
fun ManziliApp() {
    val nav = rememberNavController()
    var plan by remember { mutableStateOf<FloorPlan?>(null) }
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Deep,
            secondary = Bronze,
            background = Sand,
            surface = Paper,
            onPrimary = Color.White,
            onSurface = Ink
        )
    ) {
        CompositionLocalProvider(
            LocalLayoutDirection provides LayoutDirection.Rtl,
            LocalContentColor provides Ink
        ) {
            NavHost(navController = nav, startDestination = "home") {
                composable("home") { Home(nav) }
                composable("build") { BuildChoice(nav) }
                composable("import") { ImportPlan(nav, sourceUri, { sourceUri = it }, { plan = it }) }
                composable("new") { NewProject(nav) { plan = it } }
                composable("editor") { Editor(nav, plan) { plan = it } }
                composable("settings") { AiSettings(nav) }
            }
        }
    }
}

@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) = Surface(color = Sand, modifier = Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp),
        content = content
    )
}

@Composable
private fun BrandTop(nav: NavHostController? = null, settings: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (nav != null) IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Outlined.ArrowForward, "رجوع") }
        Box(Modifier.size(44.dp).background(Deep, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Text("H", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text("منزلي HAI", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
            Text("HAI Architectural Intelligence", color = Color.Gray, fontSize = 12.sp)
        }
        Spacer(Modifier.weight(1f))
        if (settings && nav != null) IconButton(onClick = { nav.navigate("settings") }) { Icon(Icons.Outlined.Tune, "إعدادات HAI") }
    }
}

@Composable
fun Home(nav: NavHostController) = Page {
    BrandTop(settings = false)
    Spacer(Modifier.height(20.dp))
    Surface(color = Mist, shape = RoundedCornerShape(50.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = Bronze, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text("مهندس HAI • تحليل + قيود + تحقق هندسي", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    Spacer(Modifier.height(16.dp))
    Text("بيتك يبدأ\nبقرار محسوب.", fontSize = 38.sp, lineHeight = 43.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(10.dp))
    Text(
        "ابنِ مخططًا من الصفر أو ارفع مخططًا من أي مكتب. HAI يقرأه، يناقشك، ويعرض أثر كل تعديل قبل اعتماده.",
        fontSize = 15.sp,
        lineHeight = 24.sp,
        color = Color(0xFF62645F)
    )
    Spacer(Modifier.height(28.dp))
    ActionCard(Icons.Outlined.Architecture, "ابنِ مشروعك", "مشروع جديد أو تعديل مخطط قائم", true) { nav.navigate("build") }
    Spacer(Modifier.height(14.dp))
    ActionCard(Icons.Outlined.ViewInAr, "حوّل مشروعك إلى 3D", "محفوظ للمرحلة التالية بدون تشتيت محرك 2D", false) { }
    Spacer(Modifier.weight(1f))
    InfoStrip("مبدأ HAI", "لا تعديل بصمت • لا تخمين عند الشك • العناصر المقفلة لا تُمس • الاعتماد بيدك")
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun ActionCard(icon: ImageVector, title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(140.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (enabled) Paper else Color(0xFFF0EDE6))
    ) {
        Row(Modifier.fillMaxSize().padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(58.dp).background(if (enabled) Deep else Mist, RoundedCornerShape(19.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = if (enabled) Color.White else Color.Gray)
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(subtitle, color = Color.Gray, fontSize = 13.sp, lineHeight = 19.sp)
            }
            Icon(Icons.Outlined.ArrowBackIosNew, null, modifier = Modifier.size(17.dp), tint = Color.Gray)
        }
    }
}

@Composable
fun BuildChoice(nav: NavHostController) = Page {
    BrandTop(nav)
    Spacer(Modifier.height(18.dp))
    Text("كيف نبدأ؟", fontSize = 31.sp, fontWeight = FontWeight.Bold)
    Text(
        "HAI يتعامل مع الحالتين بنفس المحرك المعماري؛ الاختلاف فقط في مصدر المخطط.",
        color = Color.Gray,
        lineHeight = 21.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 25.dp)
    )
    ActionCard(Icons.Outlined.NoteAdd, "بناء من جديد", "أسئلة منظمة عن الأرض، الاحتياجات والأولويات", true) { nav.navigate("new") }
    Spacer(Modifier.height(14.dp))
    ActionCard(Icons.Outlined.UploadFile, "تعديل مشروع سابق", "صورة أو PDF حتى لو لم ينفذه منزلي HAI", true) { nav.navigate("import") }
}

@Composable
fun ImportPlan(nav: NavHostController, source: Uri?, setSource: (Uri) -> Unit, setPlan: (FloorPlan) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { HaiArchitectClient(context) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            setSource(uri)
            error = null
        }
    }

    Page {
        BrandTop(nav)
        Text("أعطني المخطط", fontSize = 31.sp, fontWeight = FontWeight.Bold)
        Text(
            "HAI يحاول تحويل الملف إلى نموذج هندسي قابل للتحرير: غرف، جدران، أبواب ونوافذ، مع درجة ثقة لكل عنصر.",
            color = Color.Gray,
            lineHeight = 21.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 18.dp)
        )
        Card(
            onClick = { picker.launch(arrayOf("image/*", "application/pdf")) },
            modifier = Modifier.fillMaxWidth().height(178.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Paper)
        ) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(if (source == null) Icons.Outlined.CloudUpload else Icons.Outlined.TaskAlt, null, tint = Bronze, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(11.dp))
                Text(if (source == null) "اختر المخطط من الجوال" else "الملف جاهز", fontWeight = FontWeight.Bold)
                Text(if (source == null) "PDF • JPG • PNG" else "اضغط لتغييره", color = Color.Gray, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        PipelineStep("01", "قراءة المخطط", "الغرف والجدران والأبواب والنوافذ والأبعاد")
        PipelineStep("02", "تحويله لهندسة", "كل عنصر له ID وموضع وثقة وقفل مستقل")
        PipelineStep("03", "فحص معماري", "لا اعتماد لتعديل يكسر عنصرًا مقفلًا أو يخلق تداخلًا جديدًا")
        Spacer(Modifier.height(16.dp))
        Button(
            enabled = source != null && !busy,
            onClick = {
                busy = true
                error = null
                scope.launch {
                    runCatching { client.analyzePlan(source!!) }
                        .onSuccess { setPlan(it); nav.navigate("editor") }
                        .onFailure { error = it.message }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth().height(58.dp),
            shape = RoundedCornerShape(18.dp)
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White) else Icon(Icons.Outlined.AutoAwesome, null)
            Spacer(Modifier.width(8.dp))
            Text(if (busy) "HAI يفهم المخطط…" else "ابدأ التحليل", fontWeight = FontWeight.Bold)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 10.dp)) }
    }
}

@Composable
private fun PipelineStep(number: String, title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).background(Mist, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
            Text(number, color = Bronze, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Column { Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold); Text(subtitle, color = Color.Gray, fontSize = 12.sp) }
    }
}

@Composable
fun NewProject(nav: NavHostController, setPlan: (FloorPlan) -> Unit) {
    val context = LocalContext.current
    val client = remember { HaiArchitectClient(context) }
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableIntStateOf(0) }
    var plot by rememberSaveable { mutableStateOf("20×25") }
    var city by rememberSaveable { mutableStateOf("السعودية") }
    var floors by rememberSaveable { mutableStateOf("دور واحد") }
    var bedrooms by rememberSaveable { mutableStateOf("4") }
    var needs by rememberSaveable { mutableStateOf("مجلس رجال بمدخل مستقل، صالة عائلية كبيرة، مطبخ قريب من الصالة، غرفة غسيل") }
    var privacy by rememberSaveable { mutableStateOf(true) }
    var shortCorridors by rememberSaveable { mutableStateOf(true) }
    var daylight by rememberSaveable { mutableStateOf(true) }
    var future by rememberSaveable { mutableStateOf(false) }
    var notes by rememberSaveable { mutableStateOf("الضيوف لا يمرون على منطقة العائلة") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Page {
        BrandTop(nav)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("جلسة التصميم الأولى", fontSize = 29.sp, fontWeight = FontWeight.Bold)
                Text("سؤال ${step + 1} من 3", color = Color.Gray, fontSize = 12.sp)
            }
            CircularProgressIndicator(progress = { (step + 1) / 3f }, modifier = Modifier.size(40.dp), strokeWidth = 4.dp, color = Bronze, trackColor = Mist)
        }
        Spacer(Modifier.height(18.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when (step) {
                0 -> {
                    FormHeading("الأرض والموقع", "هذه المعلومات تمنع HAI من بناء فكرة جميلة على أبعاد خاطئة.")
                    OutlinedTextField(plot, { plot = it }, Modifier.fillMaxWidth(), label = { Text("أبعاد الأرض") }, singleLine = true)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(city, { city = it }, Modifier.fillMaxWidth(), label = { Text("الدولة / المدينة") }, singleLine = true)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(floors, { floors = it }, Modifier.fillMaxWidth(), label = { Text("عدد الأدوار") }, singleLine = true)
                    Spacer(Modifier.height(12.dp))
                    InfoStrip("سياسة الدقة", "إذا احتاج HAI اتجاه الشارع أو الارتدادات لاتخاذ قرار، سيطلبها بدل اختراعها.")
                }
                1 -> {
                    FormHeading("برنامج البيت", "حدد المطلوب، ثم دع HAI يرتب العلاقات بينها.")
                    OutlinedTextField(bedrooms, { bedrooms = it }, Modifier.fillMaxWidth(), label = { Text("عدد غرف النوم") }, singleLine = true)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(needs, { needs = it }, Modifier.fillMaxWidth().height(180.dp), label = { Text("المجلس، الصالات، المطبخ والخدمات") })
                }
                else -> {
                    FormHeading("ما الذي لا تريد التضحية به؟", "هذه الأولويات تتحول إلى قيود عند مقارنة البدائل.")
                    PrioritySwitch("خصوصية عالية بين الضيوف والعائلة", privacy) { privacy = it }
                    PrioritySwitch("أقل ممرات ومساحات مهدرة", shortCorridors) { shortCorridors = it }
                    PrioritySwitch("إضاءة طبيعية جيدة", daylight) { daylight = it }
                    PrioritySwitch("مرونة للتوسع مستقبلًا", future) { future = it }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth().height(130.dp), label = { Text("قاعدة خاصة بمشروعك") })
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (step > 0) OutlinedButton(onClick = { step-- }, modifier = Modifier.weight(1f).height(55.dp), shape = RoundedCornerShape(17.dp)) { Text("السابق") }
            Button(
                enabled = !busy,
                onClick = {
                    if (step < 2) step++ else {
                        val requirements = buildString {
                            appendLine("الموقع: $city")
                            appendLine("أبعاد الأرض: $plot")
                            appendLine("الأدوار: $floors")
                            appendLine("غرف النوم: $bedrooms")
                            appendLine("الاحتياجات: $needs")
                            appendLine("خصوصية عالية: ${if (privacy) "نعم" else "ليست أولوية"}")
                            appendLine("تقليل الممرات: ${if (shortCorridors) "أولوية" else "مرن"}")
                            appendLine("الإضاءة الطبيعية: ${if (daylight) "أولوية" else "مرنة"}")
                            appendLine("التوسع المستقبلي: ${if (future) "مهم" else "ليس شرطًا"}")
                            appendLine("تعليمات العميل: $notes")
                        }
                        busy = true
                        error = null
                        scope.launch {
                            runCatching { client.createNewPlan(requirements) }
                                .onSuccess { setPlan(it); nav.navigate("editor") }
                                .onFailure { error = it.message }
                            busy = false
                        }
                    }
                },
                modifier = Modifier.weight(1.4f).height(55.dp),
                shape = RoundedCornerShape(17.dp)
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp) else Icon(if (step == 2) Icons.Outlined.AutoAwesome else Icons.Outlined.ArrowBack, null)
                Spacer(Modifier.width(7.dp))
                Text(if (step == 2) "صمّم الاقتراح الأول" else "التالي", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun FormHeading(title: String, subtitle: String) {
    Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Text(subtitle, color = Color.Gray, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 5.dp, bottom = 16.dp))
}

@Composable
private fun PrioritySwitch(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Paper, RoundedCornerShape(16.dp)).padding(horizontal = 13.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun InfoStrip(title: String, text: String) {
    Row(Modifier.fillMaxWidth().background(Mist, RoundedCornerShape(18.dp)).padding(14.dp)) {
        Icon(Icons.Outlined.Lightbulb, null, tint = Bronze, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(text, fontSize = 12.sp, lineHeight = 18.sp, color = Color.DarkGray)
        }
    }
}

@Composable
fun Editor(nav: NavHostController, plan: FloorPlan?, setPlan: (FloorPlan) -> Unit) {
    val context = LocalContext.current
    val client = remember { HaiArchitectClient(context) }
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PlanProposal?>(null) }
    var history by remember { mutableStateOf<List<FloorPlan>>(emptyList()) }
    var selection by remember { mutableStateOf<PlanSelection?>(null) }
    var messages by remember {
        mutableStateOf(listOf(ArchitectMessage(false, plan?.sourceSummary?.takeIf { it.isNotBlank() } ?: "راجعت المخطط. اضغط على أي غرفة أو جدار أو باب لقفله، ثم قل لي التعديل المطلوب.")))
    }
    if (plan == null) {
        Page { BrandTop(nav); Text("لا يوجد مخطط مفتوح"); Button({ nav.popBackStack() }) { Text("رجوع") } }
        return
    }

    val score = ArchitecturalEngine.score(plan)
    val structure = StructuralGeometryEngine.inspect(plan)
    val validation = pending?.let { ArchitecturalEngine.validate(plan, it) }
    val preview = pending?.updatedPlan ?: plan

    Page {
        BrandTop(nav)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(plan.title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("نسخة ${plan.revision} • ${plan.rooms.size} غرفة • ${plan.walls.size} جدار • ${plan.openings.size} فتحة", color = Color.Gray, fontSize = 12.sp)
            }
            if (history.isNotEmpty()) {
                TextButton(onClick = {
                    val previous = history.last()
                    history = history.dropLast(1)
                    pending = null
                    selection = null
                    setPlan(previous.copy(revision = plan.revision + 1))
                    messages = messages + ArchitectMessage(false, "رجعت للنسخة السابقة.")
                }) { Icon(Icons.Outlined.Undo, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("تراجع") }
            }
        }
        ScoreBar(score)
        Spacer(Modifier.height(7.dp))
        StructuralBar(structure)
        Spacer(Modifier.height(8.dp))
        PlanCanvas(
            plan = preview,
            modifier = Modifier.fillMaxWidth().height(285.dp),
            previewMode = pending != null,
            selected = selection,
            onSelect = { selection = it }
        )
        if (pending != null) Text("معاينة اقتراح HAI — لم يُعتمد بعد", color = Bronze, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp))
        selection?.let { selected ->
            Spacer(Modifier.height(7.dp))
            ElementInspector(plan, selected) { updated ->
                history = history + plan
                pending = null
                setPlan(updated.copy(revision = plan.revision + 1))
            }
        }
        if (plan.uncertainties.isNotEmpty()) Text("⚠ ${plan.uncertainties.first()}", fontSize = 12.sp, color = Bronze, modifier = Modifier.padding(vertical = 5.dp))
        pending?.let { proposal ->
            ProposalCard(
                proposal = proposal,
                validation = validation!!,
                onReject = { pending = null; messages = messages + ArchitectMessage(false, "ألغيت الاقتراح. المخطط الحالي لم يتغير.") },
                onApply = {
                    val next = proposal.updatedPlan ?: return@ProposalCard
                    history = history + plan
                    setPlan(next.copy(revision = plan.revision + 1))
                    pending = null
                    selection = null
                    messages = messages + ArchitectMessage(false, "تم اعتماد التعديل كنسخة ${plan.revision + 1}. يمكنك التراجع عنه.")
                }
            )
        }
        HorizontalDivider(color = Mist, modifier = Modifier.padding(top = 7.dp))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Engineering, null, tint = Bronze, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(6.dp))
            Text("مهندس HAI", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            Text("يفهم • يقترح • المحرك يتحقق", color = Color.Gray, fontSize = 12.sp)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { messages.takeLast(6).forEach { Bubble(it) } }
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("مثال: أضف صالة 3×3 ولا تلمس المطبخ") },
                shape = RoundedCornerShape(20.dp),
                maxLines = 4
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                enabled = input.isNotBlank() && !busy && pending == null,
                onClick = {
                    val q = input.trim()
                    input = ""
                    messages = messages + ArchitectMessage(true, q)
                    busy = true
                    scope.launch {
                        runCatching { client.proposeChange(plan, q) }
                            .onSuccess { proposal ->
                                messages = messages + ArchitectMessage(false, proposal.message)
                                if (proposal.updatedPlan != null) pending = proposal
                            }
                            .onFailure { messages = messages + ArchitectMessage(false, "تعذر التحليل: ${it.message}") }
                        busy = false
                    }
                },
                modifier = Modifier.size(52.dp)
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp) else Icon(Icons.Outlined.ArrowUpward, null)
            }
        }
    }
}

@Composable
private fun StructuralBar(report: StructuralGeometryEngine.Report) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        item { StructurePill("جدران", report.wallCount) }
        item { StructurePill("أبواب", report.doorCount) }
        item { StructurePill("نوافذ", report.windowCount) }
        item { StructurePill("ثقة البنية", report.confidence, "%") }
    }
}

@Composable
private fun StructurePill(label: String, value: Int, suffix: String = "") {
    Surface(color = Mist, shape = RoundedCornerShape(50.dp)) {
        Text("$label  $value$suffix", modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ScoreBar(score: PlanScore) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
        item { ScorePill("HAI", score.overall) }
        item { ScorePill("الحركة", score.efficiency) }
        item { ScorePill("الخصوصية", score.privacy) }
        item { ScorePill("الهندسة", score.geometry) }
        item { ScorePill("ثقة القراءة", score.readingConfidence) }
    }
}

@Composable
private fun ScorePill(label: String, value: Int) {
    Surface(color = Paper, shape = RoundedCornerShape(50.dp)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 12.sp, color = Color.Gray)
            Spacer(Modifier.width(5.dp))
            Text("$value", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (value >= 75) Sage else Bronze)
        }
    }
}

@Composable
private fun ElementInspector(plan: FloorPlan, selection: PlanSelection, onUpdate: (FloorPlan) -> Unit) {
    val room = plan.rooms.firstOrNull { selection.kind == "room" && it.id == selection.id }
    val wall = plan.walls.firstOrNull { selection.kind == "wall" && it.id == selection.id }
    val opening = plan.openings.firstOrNull { selection.kind == "opening" && it.id == selection.id }
    val title: String
    val subtitle: String
    val confidence: Int
    val locked: Boolean
    when {
        room != null -> {
            title = room.name
            subtitle = buildString {
                append("غرفة")
                if (room.areaM2 > 0) append(" • ${"%.1f".format(room.areaM2)}م²")
                append(" • ${room.type}")
            }
            confidence = room.confidence
            locked = room.locked
        }
        wall != null -> {
            title = "جدار ${wall.id}"
            subtitle = buildString {
                append(wall.kind)
                wall.thicknessCm?.let { append(" • ${"%.0f".format(it)}سم") }
            }
            confidence = wall.confidence
            locked = wall.locked
        }
        opening != null -> {
            val isWindow = opening.type.lowercase().contains("window") || opening.type.contains("ناف")
            title = if (isWindow) "نافذة ${opening.id}" else "باب ${opening.id}"
            subtitle = buildString {
                append("عرض نسبي ${"%.1f".format(opening.width)}")
                if (opening.connectsRoomIds.isNotEmpty()) append(" • يربط ${opening.connectsRoomIds.size} مساحة")
            }
            confidence = opening.confidence
            locked = opening.locked
        }
        else -> return
    }

    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Paper), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).background(if (locked) Bronze.copy(alpha = .14f) else Mist, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(if (locked) Icons.Outlined.Lock else Icons.Outlined.TouchApp, null, tint = if (locked) Bronze else Deep, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("$subtitle • ثقة $confidence%", color = Color.Gray, fontSize = 12.sp)
            }
            FilledTonalButton(
                onClick = {
                    val next = when {
                        room != null -> plan.copy(rooms = plan.rooms.map { if (it.id == room.id) it.copy(locked = !it.locked) else it })
                        wall != null -> plan.copy(walls = plan.walls.map { if (it.id == wall.id) it.copy(locked = !it.locked) else it })
                        opening != null -> plan.copy(openings = plan.openings.map { if (it.id == opening.id) it.copy(locked = !it.locked) else it })
                        else -> plan
                    }
                    onUpdate(next)
                },
                shape = RoundedCornerShape(13.dp)
            ) {
                Icon(if (locked) Icons.Outlined.LockOpen else Icons.Outlined.Lock, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text(if (locked) "فتح" else "قفل", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ProposalCard(proposal: PlanProposal, validation: ValidationReport, onReject: () -> Unit, onApply: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Paper)) {
        Column(Modifier.padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (validation.valid) Icons.Outlined.FactCheck else Icons.Outlined.ReportProblem, null, tint = if (validation.valid) Sage else Bronze, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(7.dp))
                Text(if (validation.valid) "اقتراح اجتاز فحص HAI" else "الاقتراح مرفوض هندسيًا", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                Text("ثقة ${proposal.confidence}%", color = Color.Gray, fontSize = 12.sp)
            }
            proposal.changes.take(4).forEach { change ->
                val areas = if (change.beforeAreaM2 != null || change.afterAreaM2 != null) {
                    " ${change.beforeAreaM2?.let { "%.1f".format(it) } ?: "؟"} ← ${change.afterAreaM2?.let { "%.1f".format(it) } ?: "؟"}م²"
                } else ""
                Text("• ${change.roomName.ifBlank { "عنصر" }}$areas — ${change.note}", fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 4.dp))
            }
            validation.errors.take(2).forEach { Text("✕ $it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
            validation.warnings.take(2).forEach { Text("⚠ $it", color = Bronze, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
            validation.after?.let { after -> Text("تقييم المخطط: ${validation.before.overall} ← ${after.overall}", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onReject, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("ارفض") }
                Button(onClick = onApply, enabled = validation.valid && proposal.updatedPlan != null, modifier = Modifier.weight(1.4f), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Outlined.Done, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("اعتمد التعديل")
                }
            }
        }
    }
}

@Composable
private fun Bubble(m: ArchitectMessage) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = if (m.fromUser) Arrangement.Start else Arrangement.End) {
        Surface(color = if (m.fromUser) Deep else Paper, shape = RoundedCornerShape(18.dp), modifier = Modifier.widthIn(max = 325.dp)) {
            Text(m.text, color = if (m.fromUser) Color.White else Ink, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(13.dp))
        }
    }
}

@Composable
fun PlanCanvas(
    plan: FloorPlan,
    modifier: Modifier = Modifier,
    previewMode: Boolean = false,
    selected: PlanSelection? = null,
    onSelect: (PlanSelection?) -> Unit = {}
) {
    Surface(modifier, color = Paper, shape = RoundedCornerShape(16.dp), tonalElevation = 1.dp) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
            Canvas(
                Modifier.fillMaxSize().pointerInput(plan, selected) {
                    detectTapGestures { tap ->
                        val pad = 8.dp.toPx()
                        val w = size.width - pad * 2
                        val h = size.height - pad * 2
                        if (w <= 0f || h <= 0f) return@detectTapGestures
                        val px = ((tap.x - pad) / w * 100f).coerceIn(0f, 100f)
                        val py = ((tap.y - pad) / h * 100f).coerceIn(0f, 100f)
                        val nearestOpening = plan.openings.minByOrNull { hypot((it.x - px).toDouble(), (it.y - py).toDouble()) }
                        if (nearestOpening != null && hypot((nearestOpening.x - px).toDouble(), (nearestOpening.y - py).toDouble()) <= 4.5) {
                            onSelect(PlanSelection("opening", nearestOpening.id)); return@detectTapGestures
                        }
                        val nearestWall = plan.walls.minByOrNull { pointToSegment(px, py, it.start.x, it.start.y, it.end.x, it.end.y) }
                        if (nearestWall != null && pointToSegment(px, py, nearestWall.start.x, nearestWall.start.y, nearestWall.end.x, nearestWall.end.y) <= 2.3) {
                            onSelect(PlanSelection("wall", nearestWall.id)); return@detectTapGestures
                        }
                        val room = plan.rooms.lastOrNull { px >= it.x && px <= it.x + it.width && py >= it.y && py <= it.y + it.height }
                        onSelect(room?.let { PlanSelection("room", it.id) })
                    }
                }
            ) {
                val pad = 8.dp.toPx()
                val w = size.width - pad * 2
                val h = size.height - pad * 2
                fun p(x: Float, y: Float) = Offset(pad + w * x / 100f, pad + h * y / 100f)
                drawRect(Mist, Offset(pad, pad), Size(w, h), style = Stroke(2.dp.toPx()))

                plan.rooms.forEach { r ->
                    val left = pad + w * r.x / 100f
                    val top = pad + h * r.y / 100f
                    val rw = (w * r.width / 100f).coerceAtLeast(1f).coerceAtMost((size.width - left - pad).coerceAtLeast(1f))
                    val rh = (h * r.height / 100f).coerceAtLeast(1f).coerceAtMost((size.height - top - pad).coerceAtLeast(1f))
                    val active = selected?.kind == "room" && selected.id == r.id
                    val roomColor = when {
                        active || r.locked -> Bronze
                        r.confidence < 75 -> Color(0xFFB78858)
                        else -> Deep
                    }
                    drawRect(roomColor.copy(alpha = if (active) .13f else if (previewMode) .06f else .035f), Offset(left, top), Size(rw, rh))
                    if (plan.walls.isEmpty()) drawRect(roomColor.copy(alpha = .72f), Offset(left, top), Size(rw, rh), style = Stroke(if (active || r.locked) 3.dp.toPx() else 1.5.dp.toPx()))
                }

                plan.walls.forEach { wall ->
                    val active = selected?.kind == "wall" && selected.id == wall.id
                    val c = when {
                        active || wall.locked -> Bronze
                        wall.confidence < 60 -> Color(0xFFB78858)
                        else -> Ink
                    }
                    val thickness = wall.thicknessCm?.let { (it / 7.0).coerceIn(2.0, 6.0).toFloat() } ?: 3f
                    drawLine(c, p(wall.start.x, wall.start.y), p(wall.end.x, wall.end.y), strokeWidth = (if (active) thickness + 3f else thickness).dp.toPx())
                }

                plan.openings.forEach { opening ->
                    val active = selected?.kind == "opening" && selected.id == opening.id
                    val isWindow = opening.type.lowercase().contains("window") || opening.type.contains("ناف")
                    val c = when {
                        active || opening.locked -> Bronze
                        opening.confidence < 60 -> Color(0xFFB78858)
                        isWindow -> SoftBlue
                        else -> Sage
                    }
                    val center = p(opening.x, opening.y)
                    val radius = (if (active) 6.5f else 4.5f).dp.toPx()
                    if (isWindow) {
                        drawLine(c, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), strokeWidth = 2.5.dp.toPx())
                        drawLine(Paper, Offset(center.x - radius * .55f, center.y), Offset(center.x + radius * .55f, center.y), strokeWidth = .8.dp.toPx())
                    } else {
                        drawCircle(Paper, radius, center)
                        drawCircle(c, radius, center, style = Stroke(2.dp.toPx()))
                        val rad = Math.toRadians(opening.rotationDeg.toDouble())
                        val dx = kotlin.math.cos(rad).toFloat() * radius
                        val dy = kotlin.math.sin(rad).toFloat() * radius
                        drawLine(c, center, Offset(center.x + dx, center.y + dy), strokeWidth = 2.dp.toPx())
                    }
                }
            }

            val availableW = maxWidth.value - 16f
            val availableH = maxHeight.value - 16f
            plan.rooms.take(20).forEach { r ->
                val centerX = ((r.x + r.width / 2f).coerceIn(4f, 96f) / 100f)
                val centerY = ((r.y + r.height / 2f).coerceIn(4f, 96f) / 100f)
                Text(
                    r.name + if (r.areaM2 > 0) "\n${"%.1f".format(r.areaM2)}م²" else "",
                    fontSize = 12.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.offset(x = (availableW * centerX - 34f).dp, y = (availableH * centerY - 12f).dp).width(68.dp)
                )
            }
            if (plan.walls.isNotEmpty() || plan.openings.isNotEmpty()) {
                Surface(color = Paper.copy(alpha = .92f), shape = RoundedCornerShape(50.dp), modifier = Modifier.align(Alignment.BottomStart)) {
                    Text("اضغط غرفة / جدار / باب", modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp), fontSize = 12.sp, color = Color.Gray)
                }
            }
        }
    }
}

private fun pointToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Double {
    val dx = bx - ax
    val dy = by - ay
    if (abs(dx) < .0001f && abs(dy) < .0001f) return hypot((px - ax).toDouble(), (py - ay).toDouble())
    val t = (((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
    val x = ax + t * dx
    val y = ay + t * dy
    return hypot((px - x).toDouble(), (py - y).toDouble())
}

@Composable
fun AiSettings(nav: NavHostController) {
    val context = LocalContext.current
    val settings = remember { HaiSettings(context) }
    var endpoint by remember { mutableStateOf(settings.endpoint) }
    var key by remember { mutableStateOf(settings.apiKey) }
    var model by remember { mutableStateOf(settings.model) }
    var saved by remember { mutableStateOf(false) }
    Page {
        BrandTop(nav, settings = false)
        Text("اتصال HAI", fontSize = 31.sp, fontWeight = FontWeight.Bold)
        Text("اختر مزود OpenAI-compatible. لا يوجد مفتاح API مخزن في GitHub.", color = Color.Gray, modifier = Modifier.padding(top = 8.dp, bottom = 18.dp))
        OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth(), label = { Text("API endpoint") }, singleLine = true)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("Model ID") }, singleLine = true)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = { settings.endpoint = endpoint.trim(); settings.model = model.trim(); settings.apiKey = key.trim(); saved = true },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(18.dp)
        ) { Icon(Icons.Outlined.Save, null); Spacer(Modifier.width(8.dp)); Text("حفظ الاتصال") }
        if (saved) Text("تم حفظ إعدادات الاتصال", color = Sage, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
        Spacer(Modifier.height(18.dp))
        InfoStrip("أمان النسخة الحالية", "للنشر العام يجب نقل مفاتيح الخدمة إلى خادم وسيط أو تخزين آمن. إعداد الجهاز مناسب للاختبار والتطوير.")
    }
}
