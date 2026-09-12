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

class ProductionMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ProductionApp() }
    }
}

@Composable
private fun ProductionApp() {
    val nav=rememberNavController()
    val context=androidx.compose.ui.platform.LocalContext.current
    val store=remember{ProjectPlanStore(context)}
    var plan by remember { mutableStateOf(store.load()?.let { MultiFloorGeometryEngine.normalize(SaudiResidentialEngine.normalize(PlanVerificationEngine.inspect(it).plan)) }) }
    var pending by remember { mutableStateOf<FloorPlan?>(null) }
    var source by remember { mutableStateOf<Uri?>(null) }
    var convertTo3D by remember { mutableStateOf(false) }
    var selectedProjectType by remember { mutableStateOf<SaudiProjectTypeEngine.Type?>(null) }
    var pendingRulesEnabled by remember { mutableStateOf(false) }

    fun createProject(next:FloorPlan){
        val requested=next.copy(saudiRulesEnabled=next.saudiRulesEnabled||pendingRulesEnabled)
        val verified=SaudiResidentialEngine.normalize(PlanVerificationEngine.inspect(requested).plan)
        val ready=MultiFloorGeometryEngine.persistActive(MultiFloorGeometryEngine.normalize(ProjectMemoryEngine.reconcile(verified)))
        store.createProject(ready);plan=MultiFloorGeometryEngine.normalize(ready);pendingRulesEnabled=ready.saudiRulesEnabled
    }
    fun updateProject(next:FloorPlan,allowSaudiRulesSettingChange:Boolean=false){
        val verified=SaudiResidentialEngine.normalize(PlanVerificationEngine.inspect(next).plan);val current=plan
        val hydrated=if(current!=null&&verified.floors.isEmpty()&&current.floors.isNotEmpty())verified.copy(floors=current.floors,activeFloorId=current.activeFloorId,site=current.site,saudiRulesEnabled=current.saudiRulesEnabled)else verified
        val carried=current?.let{ProjectMemoryEngine.carryForward(it,hydrated)}?:ProjectMemoryEngine.reconcile(hydrated)
        val settingsSafe=if(current!=null&&!allowSaudiRulesSettingChange)carried.copy(saudiRulesEnabled=current.saudiRulesEnabled)else carried
        val ready=MultiFloorGeometryEngine.persistActive(MultiFloorGeometryEngine.normalize(settingsSafe));store.save(ready);plan=MultiFloorGeometryEngine.normalize(ready);pendingRulesEnabled=ready.saudiRulesEnabled
    }
    fun toggleExistingRules(enabled:Boolean){plan?.let{updateProject(it.copy(saudiRulesEnabled=enabled,revision=it.revision+1),true)}?:run{pendingRulesEnabled=enabled}}

    MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFF27312C),secondary=Color(0xFF9A7447),background=Color(0xFFF7F4EE),surface=Color(0xFFFFFEFA))){
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl){
            NavHost(nav,startDestination="home"){
                composable("home"){ProductionHome(nav,plan,store.listProjects().size)}
                composable("build"){
                    LaunchedEffect(Unit){pendingRulesEnabled=false}
                    OptionalRulesStage(nav,null,pendingRulesEnabled,{pendingRulesEnabled=it}){ProductionBuildChoiceScreen(nav)}
                }
                composable("import-type"){
                    LaunchedEffect(Unit){pendingRulesEnabled=false}
                    OptionalRulesStage(nav,null,pendingRulesEnabled,{pendingRulesEnabled=it}){
                        SaudiProjectTypeScreen(nav,"وش نوع المشروع؟","حدد النوع قبل رفع المخطط حتى يكمل HAI بنفس منطق المبنى."){type->selectedProjectType=type;convertTo3D=false;nav.navigate("import")}
                    }
                }
                composable("import3d-type"){
                    LaunchedEffect(Unit){pendingRulesEnabled=false}
                    OptionalRulesStage(nav,null,pendingRulesEnabled,{pendingRulesEnabled=it}){
                        SaudiProjectTypeScreen(nav,"وش نوع المخطط؟","النوع سيحكم قراءة المشروع وهوية التحويل إلى 3D."){type->selectedProjectType=type;convertTo3D=true;nav.navigate("import3d")}
                    }
                }
                composable("import"){
                    LaunchedEffect(Unit){convertTo3D=false}
                    OptionalRulesStage(nav,null,pendingRulesEnabled,{pendingRulesEnabled=it}){VerifiedImportScreenV3(nav,source,{source=it},{pending=it},selectedProjectType)}
                }
                composable("import3d"){
                    LaunchedEffect(Unit){convertTo3D=true}
                    OptionalRulesStage(nav,null,pendingRulesEnabled,{pendingRulesEnabled=it}){VerifiedImportScreenV3(nav,source,{source=it},{pending=it},selectedProjectType)}
                }
                composable("verify"){
                    OptionalRulesStage(nav,null,pendingRulesEnabled,{pendingRulesEnabled=it}){
                        PlanVerificationScreen(nav,source,pending){confirmed->val type=selectedProjectType?:SaudiProjectTypeEngine.infer(confirmed);createProject(SaudiProjectTypeEngine.apply(confirmed,type));pending=null;val target=if(convertTo3D)"3d" else "editor";convertTo3D=false;nav.navigate(target){popUpTo("home")}}
                    }
                }
                composable("new"){
                    LaunchedEffect(Unit){pendingRulesEnabled=false}
                    OptionalRulesStage(nav,null,pendingRulesEnabled,{pendingRulesEnabled=it}){
                        SaudiProjectTypeScreen(nav,"وش ناوي تبني؟","اختر نوع المشروع أولًا، وبعدها HAI يغيّر الأسئلة وطريقة التصميم."){type->selectedProjectType=type;nav.navigate("new-brief")}
                    }
                }
                composable("new-brief"){
                    val type=selectedProjectType?:SaudiProjectTypeEngine.Type.VILLA_TWO
                    OptionalRulesStage(nav,null,pendingRulesEnabled,{pendingRulesEnabled=it}){SaudiAdaptiveBuildScreen(nav,type){createProject(SaudiProjectTypeEngine.apply(it,type));nav.navigate("editor"){popUpTo("home")}}}
                }
                composable("change-type"){
                    val current=plan
                    OptionalRulesStage(nav,current,pendingRulesEnabled,::toggleExistingRules){SaudiProjectTypeScreen(nav,"غيّر تصنيف المشروع","استخدمها فقط إذا كان التصنيف الحالي غير صحيح.",current?.let(SaudiProjectTypeEngine::infer)){type->selectedProjectType=type;current?.let{updateProject(SaudiProjectTypeEngine.apply(it,type))};nav.popBackStack()}}
                }
                composable("editor"){OptionalRulesStage(nav,plan,pendingRulesEnabled,::toggleExistingRules){EnhancedEditor(nav,plan){updateProject(it)}}}
                composable("polygon"){OptionalRulesStage(nav,plan,pendingRulesEnabled,::toggleExistingRules){PolygonVertexEditorScreen(nav,plan){updateProject(it)}}}
                composable("floors"){OptionalRulesStage(nav,plan,pendingRulesEnabled,::toggleExistingRules){ProjectFloorsScreen(nav,plan){updateProject(it)}}}
                composable("3d"){OptionalRulesStage(nav,plan,pendingRulesEnabled,::toggleExistingRules){Production3DScreenV3(nav,plan)}}
                composable("walkthrough"){OptionalRulesStage(nav,plan,pendingRulesEnabled,::toggleExistingRules){WalkthroughScreen(nav,plan)}}
                composable("saudi-audit"){OptionalRulesStage(nav,plan,pendingRulesEnabled,::toggleExistingRules){SaudiPlanAuditScreen(nav,plan)}}
                composable("4d"){OptionalRulesStage(nav,plan,pendingRulesEnabled,::toggleExistingRules){Saudi4DScreen(nav,plan)}}
                composable("saudi-rules"){SaudiRulesScreen(nav,plan){updateProject(it,true)}}
                composable("projects"){ProjectLibraryScreen(nav,store){opened->plan=opened?.let{MultiFloorGeometryEngine.normalize(SaudiResidentialEngine.normalize(PlanVerificationEngine.inspect(it).plan))};pendingRulesEnabled=plan?.saudiRulesEnabled?:false}}
                composable("memory"){OptionalRulesStage(nav,plan,pendingRulesEnabled,::toggleExistingRules){ProjectMemoryManagerScreen(nav,plan){updateProject(it)}}}
                composable("tools"){ProjectToolsScreen(nav,store,plan){opened->plan=opened?.let{MultiFloorGeometryEngine.normalize(SaudiResidentialEngine.normalize(PlanVerificationEngine.inspect(it).plan))};pendingRulesEnabled=plan?.saudiRulesEnabled?:false}}
                composable("export"){QuickExportScreen(nav,plan)}
                composable("settings"){ProductionAiSettings(nav)}
            }
        }
    }
}

@Composable
private fun ProductionHome(nav:NavHostController,plan:FloorPlan?,count:Int){
    Surface(Modifier.fillMaxSize(),color=Color(0xFFF7F4EE)){
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp)){
            Text("منزلي HAI",fontSize=22.sp,fontWeight=FontWeight.Black)
            Text("$count مشروع • Saudi-first 2D/PBR 3D/4D • Geometry V3 • Deep Parser",color=Color.Gray,fontSize=10.sp)
            Spacer(Modifier.height(18.dp));Text("مشروعك السعودي\nمن الفكرة إلى التنفيذ.",fontSize=34.sp,lineHeight=39.sp,fontWeight=FontWeight.Black);Spacer(Modifier.height(16.dp))
            if(plan!=null){
                val type=SaudiProjectTypeEngine.infer(plan);Text("${type.label} • ${plan.title}",fontWeight=FontWeight.Bold,fontSize=11.sp,color=Color(0xFF706B62));Spacer(Modifier.height(5.dp))
                Button(onClick={nav.navigate("editor")},modifier=Modifier.fillMaxWidth().height(54.dp)){Icon(Icons.Rounded.Architecture,null);Spacer(Modifier.width(7.dp));Text("أكمل المشروع")};Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){OutlinedButton(onClick={nav.navigate("saudi-audit")},modifier=Modifier.weight(1f)){Icon(Icons.Rounded.HomeWork,null);Spacer(Modifier.width(4.dp));Text("مراجعة سعودية")};OutlinedButton(onClick={nav.navigate("3d")},modifier=Modifier.weight(1f)){Icon(Icons.Rounded.ViewInAr,null);Spacer(Modifier.width(4.dp));Text("3D PBR")}}
                Spacer(Modifier.height(6.dp));Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){OutlinedButton(onClick={nav.navigate("walkthrough")},modifier=Modifier.weight(1f)){Icon(Icons.Rounded.DirectionsWalk,null);Spacer(Modifier.width(4.dp));Text("جولة داخلية")};OutlinedButton(onClick={nav.navigate("4d")},modifier=Modifier.weight(1f)){Icon(Icons.Rounded.Schedule,null);Spacer(Modifier.width(4.dp));Text("4D")}}
                Spacer(Modifier.height(6.dp));Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){OutlinedButton(onClick={nav.navigate("saudi-rules")},modifier=Modifier.weight(1f)){Icon(if(plan.saudiRulesEnabled)Icons.Rounded.FactCheck else Icons.Rounded.AddTask,null);Spacer(Modifier.width(4.dp));Text(if(plan.saudiRulesEnabled)"الاشتراطات مفعلة" else "اشتراطات اختيارية")};OutlinedButton(onClick={nav.navigate("floors")},modifier=Modifier.weight(1f)){Text("الأدوار")}}
                Spacer(Modifier.height(5.dp));Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){OutlinedButton(onClick={nav.navigate("polygon")},modifier=Modifier.weight(1f)){Text("Polygon")};OutlinedButton(onClick={nav.navigate("export")},modifier=Modifier.weight(1f)){Text("تصدير")};OutlinedButton(onClick={nav.navigate("projects")},modifier=Modifier.weight(1f)){Text("مشاريعي")}}
                Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){TextButton(onClick={nav.navigate("change-type")},modifier=Modifier.weight(1f)){Text("نوع المشروع")};TextButton(onClick={nav.navigate("memory")},modifier=Modifier.weight(1f)){Text("قواعد HAI")}}
                Spacer(Modifier.height(8.dp))
            }
            Button(onClick={nav.navigate("build")},modifier=Modifier.fillMaxWidth().height(56.dp)){Icon(Icons.Rounded.AddHomeWork,null);Spacer(Modifier.width(7.dp));Text("ابدأ مشروع سعودي")};Spacer(Modifier.height(7.dp))
            OutlinedButton(onClick={nav.navigate("import3d-type")},modifier=Modifier.fillMaxWidth().height(52.dp)){Icon(Icons.Rounded.ViewInAr,null);Spacer(Modifier.width(7.dp));Text("حوّل مخطط سابق إلى 3D")}
            TextButton(onClick={nav.navigate("settings")},modifier=Modifier.fillMaxWidth()){Icon(Icons.Rounded.Tune,null);Spacer(Modifier.width(5.dp));Text("إعدادات HAI / Backend")}
            Spacer(Modifier.weight(1f));Text("الاشتراطات الرسمية اختيارية في كل مرحلة. إذا لم تُفعّل، يستمر التصميم والتعديل و3D و4D دونها.",color=Color.Gray,fontSize=10.sp)
        }
    }
}
