package com.manzili.hai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.manzili.hai.engine.GlobalLayoutOptimizer
import com.manzili.hai.engine.NewBuildSolver
import com.manzili.hai.engine.SaudiResidentialEngine
import com.manzili.hai.model.FloorPlan

@Composable
fun SaudiNewBuildScreen(nav: NavHostController, onChoose: (FloorPlan) -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var city by remember { mutableStateOf("الرياض") }
    var width by remember { mutableStateOf("20") }
    var depth by remember { mutableStateOf("25") }
    var streetSide by remember { mutableStateOf("شمال") }
    var streetWidth by remember { mutableStateOf("") }
    var north by remember { mutableStateOf("0") }
    var floors by remember { mutableIntStateOf(2) }
    var bedrooms by remember { mutableIntStateOf(4) }
    var familySize by remember { mutableIntStateOf(6) }
    var parking by remember { mutableIntStateOf(2) }
    var womenReception by remember { mutableStateOf(false) }
    var maid by remember { mutableStateOf(true) }
    var elevator by remember { mutableStateOf(false) }
    var courtyard by remember { mutableStateOf(true) }
    var familyEntry by remember { mutableStateOf(true) }
    var serviceEntry by remember { mutableStateOf(true) }
    var privacy by remember { mutableFloatStateOf(92f) }
    var circulation by remember { mutableFloatStateOf(86f) }
    var daylight by remember { mutableFloatStateOf(82f) }
    var style by remember { mutableStateOf("سعودي معاصر") }
    var notes by remember { mutableStateOf("الضيوف لا يمرون على منطقة العائلة") }
    var candidates by remember { mutableStateOf<List<NewBuildSolver.Candidate>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    Surface(Modifier.fillMaxSize(), color = StudioColors.Canvas) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp)) {
            Row(Modifier.fillMaxWidth()) {
                IconButton(onClick = { if (candidates.isNotEmpty()) candidates=emptyList() else if(step>0) step-- else nav.popBackStack() }) { Icon(Icons.Outlined.ArrowForward,"رجوع") }
                Column(Modifier.weight(1f)) {
                    Text("مهندس الفيلا السعودية", fontSize=23.sp, fontWeight=FontWeight.Bold)
                    Text(if(candidates.isEmpty()) "جلسة تصميم ${step+1} من 3 • الأرض → الأسرة → أسلوب المعيشة" else "أفضل 3 حلول بعد البحث الهندسي السعودي", color=Color.Gray, fontSize=12.sp)
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                if(candidates.isEmpty()) {
                    val ctx=SaudiResidentialEngine.context(city)
                    AssistChip(onClick={}, label={Text("${ctx.regionLabel} • ${ctx.defaultStyle.label}", fontSize=12.sp)})
                    when(step) {
                        0 -> {
                            H("الأرض والشارع","لا أفترض ارتدادًا بلديًا. نثبت فقط ما تعرفه عن الأرض والشارع.")
                            OutlinedTextField(city,{city=it},label={Text("المدينة")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                            Spacer(Modifier.height(7.dp)); Row(horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                                OutlinedTextField(width,{width=it.numeric()},label={Text("عرض الأرض م")},modifier=Modifier.weight(1f),singleLine=true)
                                OutlinedTextField(depth,{depth=it.numeric()},label={Text("طول الأرض م")},modifier=Modifier.weight(1f),singleLine=true)
                            }
                            Text("جهة الشارع الرئيسي",fontWeight=FontWeight.Bold,fontSize=12.sp,modifier=Modifier.padding(top=10.dp))
                            Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) { listOf("شمال","شرق","جنوب","غرب").forEach { s->FilterChip(selected=streetSide==s,onClick={streetSide=s},label={Text(s)}) } }
                            Row(horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                                OutlinedTextField(streetWidth,{streetWidth=it.numeric()},label={Text("عرض الشارع م (إن كان معروفًا)")},modifier=Modifier.weight(1f),singleLine=true)
                                OutlinedTextField(north,{north=it.numericSigned()},label={Text("الشمال °")},modifier=Modifier.weight(.65f),singleLine=true)
                            }
                        }
                        1 -> {
                            H("الأسرة والضيافة","هذه عناصر متكررة في الفيلا السعودية لكنها تبقى اختيارك أنت.")
                            Counter("أفراد الأسرة",familySize,2,15){familySize=it}; Counter("غرف النوم",bedrooms,2,8){bedrooms=it}; Counter("الأدوار",floors,1,3){floors=it}; Counter("مواقف السيارات",parking,0,5){parking=it}
                            Toggle("مجلس رجال بمدخل مستقل",true,{})
                            Toggle("استقبال نساء",womenReception){womenReception=it}
                            Toggle("غرفة عاملة منزلية",maid){maid=it}
                            Toggle("مصعد",elevator){elevator=it}
                        }
                        else -> {
                            H("الحياة اليومية والهوية","نحوّل تفضيلاتك إلى قواعد تصميم، لا إلى زخرفة فقط.")
                            Toggle("حوش/فناء عائلي خاص",courtyard){courtyard=it}; Toggle("مدخل عائلة منفصل",familyEntry){familyEntry=it}; Toggle("مسار خدمة مستقل",serviceEntry){serviceEntry=it}
                            S("الخصوصية",privacy){privacy=it}; S("سهولة الحركة",circulation){circulation=it}; S("الإضاءة الطبيعية",daylight){daylight=it}
                            Text("هوية الواجهة/3D",fontWeight=FontWeight.Bold,fontSize=12.sp)
                            listOf("سعودي معاصر","نجدي معاصر","حجازي معاصر","عسيري معاصر","نيوكلاسيك سعودي").forEach { s-> FilterChip(selected=style==s,onClick={style=s},label={Text(s)},modifier=Modifier.padding(end=4.dp)) }
                            OutlinedTextField(notes,{notes=it},label={Text("تعليماتك الخاصة")},modifier=Modifier.fillMaxWidth(),minLines=2)
                        }
                    }
                    error?.let { Text(it,color=MaterialTheme.colorScheme.error,fontSize=12.sp) }
                    Spacer(Modifier.height(14.dp))
                    Button(onClick={
                        if(step<2){step++;return@Button}
                        val w=width.toDoubleOrNull(); val d=depth.toDoubleOrNull()
                        if(w==null||d==null){error="أدخل أبعاد الأرض بشكل صحيح.";return@Button}
                        val brief=SaudiResidentialEngine.Brief(city=city,familySize=familySize,womenReception=womenReception,familyEntranceSeparate=familyEntry,serviceEntrance=serviceEntry,parkingCars=parking,maidRoom=maid,elevator=elevator,courtyard=courtyard,architectureStyle=style,streetSide=streetSide,streetWidthM=streetWidth.toDoubleOrNull(),northDeg=north.toFloatOrNull()?:0f,privacyPriority=privacy.toInt())
                        runCatching {
                            val base=NewBuildSolver.Program(city=city,plotWidthM=w,plotDepthM=d,floorCount=floors,bedrooms=bedrooms,guestEntranceIndependent=true,privacyPriority=privacy.toInt(),circulationPriority=circulation.toInt(),daylightPriority=daylight.toInt(),notes="$notes • أسرة $familySize • مواقف $parking • $style")
                            GlobalLayoutOptimizer.generate(base).map { c->
                                val p=SaudiResidentialEngine.apply(c.plan,brief); val audit=SaudiResidentialEngine.inspect(p)
                                c.copy(plan=p,overall=(c.overall*.62+audit.score*.38).toInt().coerceIn(0,100),rationale="${c.rationale} ${audit.notes.firstOrNull().orEmpty()}")
                            }.sortedByDescending { it.overall }
                        }.onSuccess { candidates=it;error=null }.onFailure { error=it.message?:"تعذر توليد البدائل" }
                    },modifier=Modifier.fillMaxWidth().height(56.dp),shape=RoundedCornerShape(17.dp)) { Icon(Icons.Outlined.AutoAwesome,null);Spacer(Modifier.width(6.dp));Text(if(step<2)"التالي" else "ابحث عن أفضل 3 فلل سعودية",fontWeight=FontWeight.Bold) }
                } else {
                    candidates.forEachIndexed { i,c-> Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(20.dp),modifier=Modifier.fillMaxWidth().padding(bottom=11.dp)) {
                        Column(Modifier.padding(12.dp)) { Row { Text("${i+1}. ${c.title}",fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));Text("${c.overall}/100",fontWeight=FontWeight.Bold) };Text(c.rationale,color=Color.Gray,fontSize=12.sp);Text(c.metrics.joinToString(" • "),fontSize=12.sp,modifier=Modifier.padding(vertical=5.dp));PlanCanvas(c.plan,Modifier.fillMaxWidth().height(200.dp),previewMode=true,onSelect={});Button(onClick={onChoose(c.plan)},modifier=Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Done,null);Spacer(Modifier.width(5.dp));Text("اعتمد هذه الفيلا") } }
                    } }
                    OutlinedButton(onClick={candidates=emptyList();step=0},modifier=Modifier.fillMaxWidth()){Text("عدّل جلسة التصميم")}
                }
            }
        }
    }
}

@Composable private fun H(t:String,s:String){Text(t,fontSize=20.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=10.dp));Text(s,color=Color.Gray,fontSize=12.sp,lineHeight=20.sp,modifier=Modifier.padding(bottom=12.dp))}
@Composable private fun Toggle(t:String,v:Boolean,on:(Boolean)->Unit){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(t,fontSize=12.sp,modifier=Modifier.padding(top=13.dp));Switch(v,on)}}
@Composable private fun Counter(t:String,v:Int,min:Int,max:Int,on:(Int)->Unit){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("$t: $v",fontWeight=FontWeight.Bold,fontSize=12.sp,modifier=Modifier.padding(top=14.dp));Row{TextButton(onClick={on((v-1).coerceAtLeast(min))}){Text("−")};TextButton(onClick={on((v+1).coerceAtMost(max))}){Text("+")}}}}
@Composable private fun S(t:String,v:Float,on:(Float)->Unit){Text("$t: ${v.toInt()}/100",fontWeight=FontWeight.Bold,fontSize=12.sp);Slider(v,on,valueRange=50f..100f)}
private fun String.numeric()=filter { it.isDigit()||it=='.'||it=='٫' }.replace('٫','.')
private fun String.numericSigned()=filter { it.isDigit()||it=='.'||it=='-' }.trim()
