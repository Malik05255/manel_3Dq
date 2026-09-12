package com.manzili.hai

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
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
import com.manzili.hai.engine.WalkthroughEngine
import com.manzili.hai.engine.WalkthroughFreeMoveEngine
import com.manzili.hai.export.GltfPlanExporter
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

@Composable
fun WalkthroughScreen(nav:NavHostController,plan:FloorPlan?) {
    if(plan==null){Surface(Modifier.fillMaxSize()){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text("افتح مشروعًا أولًا")}};return}
    val route=remember(plan){WalkthroughEngine.build(plan)}
    var state by remember(plan.revision){mutableStateOf(WalkthroughFreeMoveEngine.start(plan))}
    var movementNote by remember{mutableStateOf("الحركة حرة داخل الفراغ؛ عبور الجدار يحتاج بابًا موثقًا.")}
    val engine=rememberEngine();val modelLoader=rememberModelLoader(engine);val mainLight=rememberMainLightNode(engine){intensity=110_000f}
    val glb by produceState<ByteArray?>(initialValue=null,plan){value=withContext(Dispatchers.Default){GltfPlanExporter.renderGlb(plan)}}
    val modelLoad=remember(glb,modelLoader){glb?.let{bytes->runCatching{val buffer=ByteBuffer.allocateDirect(bytes.size);buffer.put(bytes);buffer.flip();modelLoader.createModelInstance(buffer)}}}
    val modelNode=remember(modelLoad){modelLoad?.getOrNull()?.let{instance->ModelNode(modelInstance=instance,autoAnimate=false).apply{isShadowCaster=true;isShadowReceiver=true;isEditable=false}}}
    val renderError=modelLoad?.exceptionOrNull()

    fun move(forward:Double=0.0,right:Double=0.0){state?.let{s->val result=WalkthroughFreeMoveEngine.move(plan,s,forward,right);state=result.state;movementNote=when{result.blocked->"توقف: جدار/حد الغرفة. اقترب من باب موثق للعبور.";result.crossedOpeningId!=null->"عبرت فتحة موثقة: ${result.crossedOpeningId}";else->"حركة داخل ${WalkthroughFreeMoveEngine.roomName(plan,result.state)}"}}}
    fun turn(delta:Float){state=state?.let{WalkthroughFreeMoveEngine.turn(it,delta)}}

    Surface(Modifier.fillMaxSize(),color=Color(0xFFF2EFE8)){
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()){
            Row(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically){
                IconButton(onClick={nav.popBackStack()}){Icon(Icons.Rounded.ArrowForward,"رجوع")}
                Icon(Icons.Rounded.DirectionsWalk,null,tint=Color(0xFF27312C));Spacer(Modifier.width(7.dp))
                Column(Modifier.weight(1f)){Text("جولة داخلية حرة",fontWeight=FontWeight.Black,fontSize=20.sp);Text("Collision • Verified doors • Stairs/elevators • ${route.verifiedTransitions} انتقال موثق",fontSize=9.2.sp,color=Color.Gray)}
            }
            val current=state
            if(current==null){Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center){Text("لا توجد غرفة صالحة لبدء الجولة")};return@Column}
            val floorTargets=WalkthroughFreeMoveEngine.verticalTargets(plan,current)
            if(floorTargets.isNotEmpty())Row(Modifier.fillMaxWidth().padding(horizontal=10.dp).horizontalScroll(androidx.compose.foundation.rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                floorTargets.forEach{target->AssistChip(onClick={val r=WalkthroughFreeMoveEngine.changeFloor(plan,current,target);state=r.state;movementNote=if(r.blocked)"اقترب من نواة الدرج/المصعد أولًا" else "انتقلت عبر ${r.crossedOpeningId.orEmpty()}"},label={Text("انتقل إلى $target",fontSize=8.5.sp)})}
            }
            Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFFE9E5DC))){
                when{
                    glb==null->Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()}
                    renderError!=null->Card(Modifier.align(Alignment.Center).padding(22.dp)){Text("تعذر تشغيل الجولة: ${renderError.message.orEmpty().take(150)}",modifier=Modifier.padding(18.dp))}
                    modelNode!=null->key("${current.floorId}:${current.roomId}:${"%.2f".format(current.x)}:${"%.2f".format(current.y)}:${current.yaw}"){
                        val rad=Math.toRadians(current.yaw.toDouble());val lookX=current.x+cos(rad)*1.6;val lookY=current.y+sin(rad)*1.6
                        val camera=rememberCameraManipulator(orbitHomePosition=Position(current.x.toFloat(),current.eyeZ.toFloat(),(-current.y).toFloat()),targetPosition=Position(lookX.toFloat(),current.eyeZ.toFloat(),(-lookY).toFloat()))
                        Scene(modifier=Modifier.fillMaxSize(),engine=engine,modelLoader=modelLoader,mainLightNode=mainLight,cameraManipulator=camera,childNodes=listOf(modelNode))
                    }
                }
                Surface(color=Color(0xE627312C),shape=RoundedCornerShape(16.dp),modifier=Modifier.align(Alignment.TopCenter).padding(12.dp)){
                    Column(Modifier.padding(horizontal=14.dp,vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally){Text(WalkthroughFreeMoveEngine.roomName(plan,current).ifBlank{current.roomId},color=Color.White,fontWeight=FontWeight.Black);Text("${current.floorId} • اتجاه ${current.yaw.toInt()}°",color=Color.White.copy(alpha=.72f),fontSize=8.7.sp)}
                }
            }
            Surface(color=Color(0xFFFFFEFA),tonalElevation=2.dp){Column(Modifier.fillMaxWidth().padding(8.dp),horizontalAlignment=Alignment.CenterHorizontally){
                Text(movementNote,fontSize=8.7.sp,color=Color.Gray,maxLines=1)
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){OutlinedButton(onClick={turn(-15f)}){Text("↺")};Button(onClick={move(forward=1.0)}){Text("↑ أمام")};OutlinedButton(onClick={turn(15f)}){Text("↻")}}
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){OutlinedButton(onClick={move(right=-1.0)}){Text("← يسار")};OutlinedButton(onClick={move(forward=-1.0)}){Text("↓ خلف")};OutlinedButton(onClick={move(right=1.0)}){Text("يمين →")}}
            }}
        }
    }
}
