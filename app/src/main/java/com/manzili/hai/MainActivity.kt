package com.manzili.hai

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.manzili.hai.ai.HaiArchitectClient
import com.manzili.hai.data.HaiSettings
import com.manzili.hai.model.ArchitectMessage
import com.manzili.hai.model.FloorPlan
import kotlinx.coroutines.launch

private val Sand = Color(0xFFF7F4EE)
private val Ink = Color(0xFF20211E)
private val Bronze = Color(0xFF8A6A43)
private val Mist = Color(0xFFE9E5DC)
private val Deep = Color(0xFF27312C)

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
    MaterialTheme(colorScheme = lightColorScheme(primary = Deep, secondary = Bronze, background = Sand, surface = Color.White)) {
        CompositionLocalProvider(LocalContentColor provides Ink) {
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

@Composable private fun Page(content: @Composable ColumnScope.() -> Unit) = Surface(color = Sand, modifier = Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp), content = content)
}

@Composable private fun BrandTop(nav: NavHostController? = null, settings: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (nav != null) IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Rounded.ArrowBack, "رجوع") }
        Box(Modifier.size(42.dp).background(Deep, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
            Text("H", color = Color.White, fontWeight = FontWeight.Black, fontSize = 21.sp)
        }
        Spacer(Modifier.width(10.dp))
        Column { Text("منزلي HAI", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp); Text("معماري ذكي يفهم مشروعك", color = Color.Gray, fontSize = 11.sp) }
        Spacer(Modifier.weight(1f))
        if (settings && nav != null) IconButton(onClick = { nav.navigate("settings") }) { Icon(Icons.Rounded.Tune, "إعدادات HAI") }
    }
}

@Composable fun Home(nav: NavHostController) = Page {
    BrandTop(nav = nav, settings = false)
    Spacer(Modifier.height(18.dp))
    Text("من الفكرة إلى مخطط\nيفهمك قبل أن يرسم.", fontSize = 35.sp, lineHeight = 41.sp, fontWeight = FontWeight.Black, color = Ink)
    Spacer(Modifier.height(10.dp))
    Text("ابنِ منزلًا من الصفر أو ارفع مخططًا موجودًا ودع HAI يقرأه، يناقشك، ويقترح التعديل قبل أن يغيّر أي مساحة.", fontSize = 15.sp, lineHeight = 24.sp, color = Color(0xFF62645F))
    Spacer(Modifier.height(30.dp))
    ActionCard(Icons.Rounded.Architecture, "ابنِ مشروعك", "إنشاء مخطط جديد أو تعديل مشروع سابق", true) { nav.navigate("build") }
    Spacer(Modifier.height(14.dp))
    ActionCard(Icons.Rounded.ViewInAr, "حوّل مشروعك إلى 3D", "المرحلة التالية — محفوظة في مسار مستقل", false) { }
    Spacer(Modifier.weight(1f))
    Row(Modifier.fillMaxWidth().padding(bottom = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Verified, null, tint = Bronze, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("لا تعديل بصمت • يوضح التنازلات • يسأل عند الشك", fontSize = 12.sp, color = Color.Gray)
    }
}

@Composable private fun ActionCard(icon: ImageVector, title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(145.dp), shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = if (enabled) Color.White else Color(0xFFF0EDE6))) {
        Row(Modifier.fillMaxSize().padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(58.dp).background(if (enabled) Deep else Mist, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = if (enabled) Color.White else Color.Gray) }
            Spacer(Modifier.width(18.dp)); Column(Modifier.weight(1f)) { Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(6.dp)); Text(subtitle, color = Color.Gray, fontSize = 13.sp, lineHeight = 19.sp) }
            Icon(Icons.Rounded.ArrowForwardIos, null, modifier = Modifier.size(18.dp), tint = Color.Gray)
        }
    }
}

@Composable fun BuildChoice(nav: NavHostController) = Page {
    BrandTop(nav)
    Spacer(Modifier.height(18.dp)); Text("كيف نبدأ مشروعك؟", fontSize = 30.sp, fontWeight = FontWeight.Black); Text("اختر نقطة البداية. في الحالتين سيبقى مهندس HAI معك داخل المخطط.", color = Color.Gray, modifier = Modifier.padding(top = 8.dp, bottom = 25.dp))
    ActionCard(Icons.Rounded.NoteAdd, "بناء من جديد", "نسألك عن الأرض والأسرة والأولويات ثم نبني مخططًا أوليًا", true) { nav.navigate("new") }
    Spacer(Modifier.height(14.dp))
    ActionCard(Icons.Rounded.UploadFile, "تعديل مشروع سابق", "ارفع صورة أو PDF — حتى لو صممه مكتب آخر", true) { nav.navigate("import") }
}

@Composable fun ImportPlan(nav: NavHostController, source: Uri?, setSource: (Uri) -> Unit, setPlan: (FloorPlan) -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope(); val client = remember { HaiArchitectClient(context) }
    var busy by remember { mutableStateOf(false) }; var status by remember { mutableStateOf("") }; var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION); setSource(uri); status = "المخطط جاهز للتحليل" } }
    Page {
        BrandTop(nav); Text("ارفع مخططك", fontSize = 30.sp, fontWeight = FontWeight.Black); Text("صورة JPG/PNG أو PDF. HAI سيقرأ الغرف والمساحات والعلاقات ويعلّم أي جزء غير متأكد منه بدل التخمين.", color = Color.Gray, lineHeight = 21.sp, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
        Card(onClick = { picker.launch(arrayOf("image/*", "application/pdf")) }, Modifier.fillMaxWidth().height(190.dp), shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Icon(if (source == null) Icons.Rounded.CloudUpload else Icons.Rounded.Description, null, tint = Bronze, modifier = Modifier.size(42.dp)); Spacer(Modifier.height(12.dp)); Text(if (source == null) "اختر المخطط من جوالك" else "تم اختيار الملف", fontWeight = FontWeight.Bold); Text(if (source == null) "PDF • JPG • PNG" else "اضغط هنا لتغييره", color = Color.Gray, fontSize = 12.sp) }
        }
        Spacer(Modifier.height(18.dp))
        Button(enabled = source != null && !busy, onClick = {
            busy = true; error = null; status = "HAI يقرأ الجدران والغرف والأبعاد…"
            scope.launch { runCatching { client.analyzePlan(source!!) }.onSuccess { setPlan(it); nav.navigate("editor") }.onFailure { error = it.message }; busy = false }
        }, modifier = Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(18.dp)) { if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White) else Icon(Icons.Rounded.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text(if (busy) status else "حلّل المخطط مع HAI", fontWeight = FontWeight.Bold) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
        Spacer(Modifier.height(16.dp)); InfoStrip("قاعدة HAI", "إذا كان بُعد أو جدار غير واضح، سيذكره كعنصر غير مؤكد ويسألك قبل الاعتماد عليه.")
    }
}

@Composable fun NewProject(nav: NavHostController, setPlan: (FloorPlan) -> Unit) {
    val context = LocalContext.current; val client = remember { HaiArchitectClient(context) }; val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("أرض 20×25، دور واحد، 4 غرف نوم، مجلس رجال بمدخل مستقل، صالة عائلية كبيرة، مطبخ قريب من الصالة، غرفة غسيل، وأبي أقل ممرات ممكنة وخصوصية عالية.") }
    var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    Page {
        BrandTop(nav); Text("صف منزلك بطريقتك", fontSize = 30.sp, fontWeight = FontWeight.Black); Text("لا تحتاج مصطلحات هندسية. اكتب ما تريده وما لا تريد التنازل عنه.", color = Color.Gray, modifier = Modifier.padding(top = 8.dp, bottom = 18.dp))
        OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().height(220.dp), shape = RoundedCornerShape(22.dp), label = { Text("متطلبات المشروع") })
        Spacer(Modifier.height(14.dp)); InfoStrip("مثال", "«أهم شيء المجلس كبير، لا أحب الممرات، ولا أريد الضيوف يمرون على صالة العائلة» تتحول إلى قيود تصميم فعلية.")
        Spacer(Modifier.height(18.dp)); Button(enabled = text.isNotBlank() && !busy, onClick = { busy = true; error = null; scope.launch { runCatching { client.createNewPlan(text) }.onSuccess { setPlan(it); nav.navigate("editor") }.onFailure { error = it.message }; busy = false } }, Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(18.dp)) { if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp) else Icon(Icons.Rounded.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text("ابدأ مع مهندس HAI", fontWeight = FontWeight.Bold) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
    }
}

@Composable private fun InfoStrip(title: String, text: String) { Row(Modifier.fillMaxWidth().background(Mist, RoundedCornerShape(18.dp)).padding(15.dp)) { Icon(Icons.Rounded.Lightbulb, null, tint = Bronze); Spacer(Modifier.width(10.dp)); Column { Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp); Text(text, fontSize = 12.sp, lineHeight = 18.sp, color = Color.DarkGray) } } }

@Composable fun Editor(nav: NavHostController, plan: FloorPlan?, setPlan: (FloorPlan) -> Unit) {
    val context = LocalContext.current; val client = remember { HaiArchitectClient(context) }; val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }; var messages by remember { mutableStateOf(listOf(ArchitectMessage(false, plan?.sourceSummary?.takeIf { it.isNotBlank() } ?: "اطلعت على المخطط. قل لي ما الذي تريد تغييره، وسأوضح أثره قبل أي تضحية بالمساحات."))) }
    if (plan == null) { Page { BrandTop(nav); Text("لا يوجد مخطط مفتوح"); Button({ nav.popBackStack() }) { Text("رجوع") } }; return }
    Page {
        BrandTop(nav); Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(plan.title, fontSize = 23.sp, fontWeight = FontWeight.Black); Text("${plan.rooms.size} مساحة مقروءة • ${plan.uncertainties.size} عناصر تحتاج تحقق", color = Color.Gray, fontSize = 12.sp) }; AssistChip(onClick = {}, label = { Text("2D حي") }, leadingIcon = { Icon(Icons.Rounded.GridOn, null, Modifier.size(16.dp)) }) }
        Spacer(Modifier.height(12.dp)); PlanCanvas(plan, Modifier.fillMaxWidth().height(285.dp))
        if (plan.uncertainties.isNotEmpty()) Text("⚠ ${plan.uncertainties.first()}", fontSize = 11.sp, color = Bronze, modifier = Modifier.padding(vertical = 8.dp))
        HorizontalDivider(color = Mist); Text("مهندس HAI", fontWeight = FontWeight.Black, fontSize = 17.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { messages.takeLast(5).forEach { Bubble(it) } }
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("مثال: أضف صالة 3×3 ولا تلمس المطبخ") }, shape = RoundedCornerShape(20.dp), maxLines = 4)
            Spacer(Modifier.width(8.dp)); FilledIconButton(enabled = input.isNotBlank() && !busy, onClick = { val q = input; input = ""; messages = messages + ArchitectMessage(true, q); busy = true; scope.launch { runCatching { client.advise(plan, q) }.onSuccess { messages = messages + ArchitectMessage(false, it) }.onFailure { messages = messages + ArchitectMessage(false, "تعذر التحليل: ${it.message}") }; busy = false } }, modifier = Modifier.size(52.dp)) { if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp) else Icon(Icons.Rounded.ArrowUpward, null) }
        }
    }
}

@Composable private fun Bubble(m: ArchitectMessage) { Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start) { Surface(color = if (m.fromUser) Deep else Color.White, shape = RoundedCornerShape(18.dp), modifier = Modifier.widthIn(max = 320.dp)) { Text(m.text, color = if (m.fromUser) Color.White else Ink, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(13.dp)) } } }

@Composable fun PlanCanvas(plan: FloorPlan, modifier: Modifier = Modifier) {
    Surface(modifier, color = Color.White, shape = RoundedCornerShape(24.dp), tonalElevation = 1.dp) {
        Box(Modifier.fillMaxSize().padding(12.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val pad = 12.dp.toPx(); val w = size.width - pad * 2; val h = size.height - pad * 2
                drawRect(Mist, Offset(pad, pad), Size(w, h), style = Stroke(2.dp.toPx()))
                plan.rooms.forEach { r ->
                    val left = pad + w * (r.x / 100f); val top = pad + h * (r.y / 100f); val rw = w * (r.width / 100f); val rh = h * (r.height / 100f)
                    drawRect(if (r.confidence >= 80) Deep else Bronze, Offset(left, top), Size(rw.coerceAtMost(size.width-left-pad), rh.coerceAtMost(size.height-top-pad)), style = Stroke(if (r.confidence >= 80) 2.2.dp.toPx() else 1.5.dp.toPx()))
                }
            }
            plan.rooms.take(12).forEach { r ->
                val alignX = (r.x + r.width / 2).coerceIn(5f, 95f) / 100f; val alignY = (r.y + r.height / 2).coerceIn(5f, 95f) / 100f
                Text(r.name + if (r.areaM2 > 0) "\n${"%.1f".format(r.areaM2)}م²" else "", fontSize = 9.sp, lineHeight = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.TopStart).offset(x = ((alignX * 300)-35).dp, y = ((alignY * 245)-12).dp).width(70.dp))
            }
        }
    }
}

@Composable fun AiSettings(nav: NavHostController) {
    val context = LocalContext.current; val settings = remember { HaiSettings(context) }
    var endpoint by remember { mutableStateOf(settings.endpoint) }; var key by remember { mutableStateOf(settings.apiKey) }; var model by remember { mutableStateOf(settings.model) }; var saved by remember { mutableStateOf(false) }
    Page {
        BrandTop(nav, settings = false); Text("إعدادات HAI", fontSize = 30.sp, fontWeight = FontWeight.Black); Text("الاتصال حقيقي بمزود OpenAI-compatible. المفتاح يبقى داخل إعدادات التطبيق ولا يوضع في GitHub.", color = Color.Gray, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
        OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth(), label = { Text("API endpoint") }, singleLine = true); Spacer(Modifier.height(10.dp))
        OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("Model ID") }, singleLine = true); Spacer(Modifier.height(10.dp))
        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("API key") }, singleLine = true); Spacer(Modifier.height(18.dp))
        Button(onClick = { settings.endpoint = endpoint; settings.model = model; settings.apiKey = key; saved = true }, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) { Icon(Icons.Rounded.Save, null); Spacer(Modifier.width(8.dp)); Text("حفظ الاتصال") }
        if (saved) Text("تم حفظ إعدادات HAI", color = Deep, modifier = Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(18.dp)); InfoStrip("مهم", "قبل أي نشر فعلي سننقل المفتاح إلى تخزين مشفر/خادم وسيط. النسخة الحالية مناسبة للتطوير والاختبار فقط.")
    }
}
