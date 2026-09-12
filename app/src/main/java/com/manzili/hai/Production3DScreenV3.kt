package com.manzili.hai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.manzili.hai.engine.Architectural3DEnhancementEngine
import com.manzili.hai.engine.PbrSceneFramingEngine
import com.manzili.hai.engine.SaudiPhotorealisticRenderEngine
import com.manzili.hai.export.TexturedPbrGlbExporter
import com.manzili.hai.model.FloorPlan
import io.github.sceneview.Scene
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMainLightNode
import io.github.sceneview.rememberModelLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Production3DScreenV3(nav:NavHostController,plan:FloorPlan?) {
    if(plan==null){Scaffold(topBar={TopAppBar(title={Text("3D PBR")},navigationIcon={IconButton(onClick={nav.popBackStack()}){Icon(Icons.Rounded.ArrowBack,null)}})}){pad->Box(Modifier.fillMaxSize().padding(pad),contentAlignment=Alignment.Center){Text("لا يوجد مشروع مفتوح")}};return}
    var compatibilityMode by rememberSaveable{mutableStateOf(false)}
    if(compatibilityMode){Production3DScreenV2(nav,plan);return}
    val semantic=remember(plan){Architectural3DEnhancementEngine.build(plan)}
    val frame=remember(semantic){PbrSceneFramingEngine.frame(semantic)}
    val profile=remember(plan){SaudiPhotorealisticRenderEngine.build(plan)}
    val engine=rememberEngine();val modelLoader=rememberModelLoader(engine)
    val mainLight=rememberMainLightNode(engine){intensity=profile.sunIntensity}
    val camera=rememberCameraManipulator(orbitHomePosition=Position(frame.cameraX,frame.cameraY,frame.cameraZ),targetPosition=Position(frame.targetX,frame.targetY,frame.targetZ))
    val glb by produceState<ByteArray?>(initialValue=null,plan){value=withContext(Dispatchers.Default){TexturedPbrGlbExporter.render(plan)}}
    val load=remember(glb,modelLoader){glb?.let{bytes->runCatching{val b=ByteBuffer.allocateDirect(bytes.size);b.put(bytes);b.flip();modelLoader.createModelInstance(b)}}}
    val modelNode=remember(load){load?.getOrNull()?.let{m->ModelNode(modelInstance=m,autoAnimate=false).apply{isShadowCaster=true;isShadowReceiver=true;isEditable=false}}}
    val error=load?.exceptionOrNull()
    val bg=when(profile.climate){SaudiPhotorealisticRenderEngine.ClimateProfile.HOT_HUMID->Color(0xFFE5E7E3);SaudiPhotorealisticRenderEngine.ClimateProfile.HIGHLAND->Color(0xFFE8ECEB);SaudiPhotorealisticRenderEngine.ClimateProfile.CONTINENTAL->Color(0xFFE9E5DC);else->Color(0xFFEDE7DB)}
    Scaffold(topBar={TopAppBar(title={Column{Text("3D واقعي • PBR",fontWeight=FontWeight.Black);Text("Filament • Textured GLB • Geometry V3",fontSize=10.sp,color=Color.Gray)}},navigationIcon={IconButton(onClick={nav.popBackStack()}){Icon(Icons.Rounded.ArrowBack,null)}},actions={TextButton(onClick={nav.navigate("walkthrough")}){Icon(Icons.Rounded.DirectionsWalk,null);Spacer(Modifier.width(4.dp));Text("جولة")}})}){pad->
        Box(Modifier.fillMaxSize().padding(pad).background(bg)){
            if(error==null)Scene(modifier=Modifier.fillMaxSize(),engine=engine,modelLoader=modelLoader,mainLightNode=mainLight,cameraManipulator=camera,childNodes=listOfNotNull(modelNode))
            when{
                glb==null->Surface(Modifier.align(Alignment.Center),shape=MaterialTheme.shapes.large,tonalElevation=6.dp){Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically){CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp);Spacer(Modifier.width(10.dp));Text("يبني GLB بخامات PBR…")}}
                error!=null->Card(Modifier.align(Alignment.Center).padding(24.dp)){Column(Modifier.padding(18.dp),horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.Rounded.ViewInAr,null);Text("تعذر تشغيل Filament على هذا الجهاز",fontWeight=FontWeight.Bold);Spacer(Modifier.height(12.dp));Button(onClick={compatibilityMode=true}){Text("فتح العرض المتوافق")}}}
                modelNode!=null->Surface(Modifier.align(Alignment.BottomCenter).padding(12.dp),color=Color(0xEFFFFFFF),shape=MaterialTheme.shapes.large){Column(Modifier.padding(horizontal=14.dp,vertical=9.dp),horizontalAlignment=Alignment.CenterHorizontally){Text("${profile.qualityLabel} • ${profile.tone}",fontSize=11.sp,fontWeight=FontWeight.Black);Text("خامات PNG مضمّنة • UV • ظلال • واجهة سعودية • موقع Presentation",fontSize=9.sp,color=Color.Gray);Text(if(semantic.metricReady)"المجسم بالمتر" else "المجسم نسبي حتى تأكيد المقياس",fontSize=8.5.sp,color=Color.Gray)}}
            }
        }
    }
}
