package com.manzili.hai

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.manzili.hai.engine.SaudiPhotorealisticRenderEngine
import com.manzili.hai.engine.WalkthroughNavigationEngineV2
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
import kotlin.math.cos
import kotlin.math.sin

/** Verified interactive first-person tour over the textured production GLB. */
@Composable
fun WalkthroughScreen(nav:NavHostController,plan:FloorPlan?) {
    if(plan==null){Surface(Modifier.fillMaxSize()){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text("افتح مشروعًا أولًا")}};return}
    val network=remember(plan){WalkthroughNavigationEngineV2.build(plan)}
    var state by remember(plan.revision){mutableStateOf(WalkthroughNavigationEngineV2.initial(network))}
    if(state==null){Surface(Modifier.fillMaxSize()){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text("لا توجد غرف قابلة للجولة")}};return}
    val current=state!!
    val currentRoom=WalkthroughNavigationEngineV2.room(network,current)
    val exits=WalkthroughNavigationEngineV2.exits(network,current)
    val floorExits=WalkthroughNavigationEngineV2.floorExits(network,current)
    val renderProfile=remember(plan){SaudiPhotorealisticRenderEngine.build(plan)}
    val engine=rememberEngine();val modelLoader=rememberModelLoader(engine)
    val mainLight=rememberMainLightNode(engine){intensity=renderProfile.sunIntensity}
    val glb by produceState<ByteArray?>(initialValue=null,plan){value=withContext(Dispatchers.Default){TexturedPbrGlbExporter.render(plan)}}
    val modelLoad=remember(glb,modelLoader){glb?.let{bytes->runCatching{val b=ByteBuffer.allocateDirect(bytes.size);b.put(bytes);b.flip();modelLoader.createModelInstance(b)}}}
    val modelNode=remember(modelLoad){modelLoad?.getOrNull()?.let{m->ModelNode(modelInstance=m,autoAnimate=false).apply{isShadowCaster=true;isShadowReceiver=true;isEditable=false}}}
    val renderError=modelLoad?.exceptionOrNull()
    val world=WalkthroughNavigationEngineV2.worldPosition(plan,network,current)
    val rad=Math.toRadians(current.yawDeg.toDouble());val lookX=world.first+cos(rad).toFloat()*1.6f;val lookZ=-world.third-sin(rad).toFloat()*1.6f

    Surface(Modifier.fillMaxSize(),color=Color(0xFFF2EFE8)){
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()){
            Row(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically){
                IconButton(onClick={nav.popBackStack()}){Icon(Icons.Rounded.ArrowForward,"رجوع")};Icon(Icons.Rounded.DirectionsWalk,null);Spacer(Modifier.width(7.dp))
                Column(Modifier.weight(1f)){Text("جولة داخلية تفاعلية",fontWeight=FontWeight.Black,fontSize=20.sp);Text("أبواب/فتحات وسلالم/مصاعد موثقة فقط • ${renderProfile.qualityLabel}",fontSize=9.sp,color=Color.Gray)}
            }
            Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFFE9E5DC))){
                when{
                    glb==null->Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()}
                    renderError!=null->Card(Modifier.align(Alignment.Center).padding(22.dp)){Column(Modifier.padding(18.dp)){Text("تعذر تشغيل الجولة PBR",fontWeight=FontWeight.Bold);Text(renderError.message.orEmpty().take(180),fontSize=9.sp,color=Color.Gray)}}
                    modelNode!=null->key(current.floorId,current.roomId,current.xPct,current.yPct,current.yawDeg){
                        val camera=rememberCameraManipulator(orbitHomePosition=Position(world.first,world.second,-world.third),targetPosition=Position(lookX,world.second,lookZ))
                        Scene(modifier=Modifier.fillMaxSize(),engine=engine,modelLoader=modelLoader,mainLightNode=mainLight,cameraManipulator=camera,childNodes=listOf(modelNode))
                    }
                }
                Surface(color=Color(0xE627312C),shape=RoundedCornerShape(16.dp),modifier=Modifier.align(Alignment.TopCenter).padding(10.dp)){
                    Column(Modifier.padding(horizontal=14.dp,vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally){Text(currentRoom?.name?.ifBlank{currentRoom.type}.orEmpty(),color=Color.White,fontWeight=FontWeight.Black);Text(network.floors.firstOrNull{it.id==current.floorId}?.name.orEmpty(),color=Color.White.copy(.72f),fontSize=9.sp)}
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=7.dp)){
                Text("حركة حرة داخل حدود الغرفة",fontWeight=FontWeight.Bold,fontSize=10.sp)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center){OutlinedButton(onClick={state=WalkthroughNavigationEngineV2.moveWithinRoom(network,current,0f,-3f)}){Text("أمام")}}
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp,Alignment.CenterHorizontally)){OutlinedButton(onClick={state=WalkthroughNavigationEngineV2.moveWithinRoom(network,current,-3f,0f)}){Text("يسار")};OutlinedButton(onClick={state=WalkthroughNavigationEngineV2.moveWithinRoom(network,current,3f,0f)}){Text("يمين")}}
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center){OutlinedButton(onClick={state=WalkthroughNavigationEngineV2.moveWithinRoom(network,current,0f,3f)}){Text("خلف")}}
                if(exits.isNotEmpty()){Text("أبواب موثقة",fontWeight=FontWeight.Bold,fontSize=10.sp);Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){exits.forEach{e->AssistChip(onClick={state=WalkthroughNavigationEngineV2.enter(network,current,e.roomId)},label={Text("${e.roomName} • ${e.viaOpeningId}",fontSize=8.5.sp)})}}}
                if(floorExits.isNotEmpty()){Text("انتقال بين الأدوار",fontWeight=FontWeight.Bold,fontSize=10.sp);Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){floorExits.forEach{e->AssistChip(onClick={state=WalkthroughNavigationEngineV2.changeFloor(network,current,e.floorId)},label={Text("${e.floorName} • ${e.type}",fontSize=8.5.sp)})}}}
                if(current.note.isNotBlank())Text(current.note,fontSize=8.5.sp,color=Color(0xFF7A664D),modifier=Modifier.padding(top=3.dp))
                network.warnings.firstOrNull()?.let{Text(it,fontSize=8.3.sp,color=Color.Gray,modifier=Modifier.padding(top=3.dp))}
            }
        }
    }
}
