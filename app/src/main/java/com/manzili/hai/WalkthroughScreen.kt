package com.manzili.hai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
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

/** Real 3D interior walkthrough over the exact same GLB/Geometry V3 used by Production3DScreenV3. */
@Composable
fun WalkthroughScreen(nav:NavHostController,plan:FloorPlan?) {
    if(plan==null){
        Surface(Modifier.fillMaxSize()){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text("افتح مشروعًا أولًا")}}
        return
    }
    val route=remember(plan){WalkthroughEngine.build(plan)}
    var index by remember(plan.revision){mutableIntStateOf(0)}
    val engine=rememberEngine()
    val modelLoader=rememberModelLoader(engine)
    val mainLight=rememberMainLightNode(engine){intensity=105_000.0f}
    val glb by produceState<ByteArray?>(initialValue=null,plan){value=withContext(Dispatchers.Default){GltfPlanExporter.renderGlb(plan)}}
    val modelLoad=remember(glb,modelLoader){glb?.let{bytes->runCatching{val buffer=ByteBuffer.allocateDirect(bytes.size);buffer.put(bytes);buffer.flip();modelLoader.createModelInstance(buffer)}}}
    val modelNode=remember(modelLoad){modelLoad?.getOrNull()?.let{instance->ModelNode(modelInstance=instance,autoAnimate=false).apply{isShadowCaster=true;isShadowReceiver=true;isEditable=false}}}
    val renderError=modelLoad?.exceptionOrNull()

    Surface(Modifier.fillMaxSize(),color=Color(0xFFF2EFE8)){
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()){
            Row(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically){
                IconButton(onClick={nav.popBackStack()}){Icon(Icons.Rounded.ArrowForward,"رجوع")}
                Icon(Icons.Rounded.DirectionsWalk,null,tint=Color(0xFF27312C));Spacer(Modifier.width(7.dp))
                Column(Modifier.weight(1f)){Text("جولة داخلية 3D",fontWeight=FontWeight.Black,fontSize=20.sp);Text("First-person • Geometry V3 • ${route.verifiedTransitions} انتقال موثق",fontSize=9.5.sp,color=Color.Gray)}
                Text("${if(route.points.isEmpty())0 else index+1}/${route.points.size}",fontSize=10.sp,color=Color.Gray)
            }
            if(route.points.isEmpty()){
                Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center){Text(route.warnings.firstOrNull()?:"لا توجد جولة")}
                return@Column
            }
            if(index>route.points.lastIndex)index=route.points.lastIndex
            val current=route.points[index]
            val next=route.points.getOrNull(index+1)
            val target=if(next!=null&&next.floorId==current.floorId&&next.connectedFromPrevious) next else null

            Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFFE9E5DC))){
                when {
                    glb==null -> Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()}
                    renderError!=null -> Card(Modifier.align(Alignment.Center).padding(22.dp)){Column(Modifier.padding(18.dp),horizontalAlignment=Alignment.CenterHorizontally){Text("تعذر تشغيل الجولة ثلاثية الأبعاد",fontWeight=FontWeight.Bold);Text(renderError.message.orEmpty().take(180),fontSize=9.sp,color=Color.Gray)}}
                    modelNode!=null -> key(current.id){
                        val home=Position(current.worldX,current.eyeZ,-current.worldY)
                        val look=if(target!=null)Position(target.worldX,current.eyeZ,-target.worldY) else Position(current.worldX+1.4f,current.eyeZ,-current.worldY)
                        val cameraManipulator=rememberCameraManipulator(orbitHomePosition=home,targetPosition=look)
                        Scene(modifier=Modifier.fillMaxSize(),engine=engine,modelLoader=modelLoader,mainLightNode=mainLight,cameraManipulator=cameraManipulator,childNodes=listOf(modelNode))
                    }
                }
                Surface(color=Color(0xE627312C),shape=RoundedCornerShape(16.dp),modifier=Modifier.align(Alignment.TopCenter).padding(12.dp)){
                    Column(Modifier.padding(horizontal=14.dp,vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally){
                        Text(current.roomName,color=Color.White,fontWeight=FontWeight.Black)
                        Text("${current.floorName} • ${if(current.connectedFromPrevious)"عبر فتحة ${current.viaOpeningId.orEmpty()}" else "بداية قطاع"}",color=Color.White.copy(alpha=.72f),fontSize=8.7.sp)
                    }
                }
                Surface(color=Color(0xEFFFFFFF),shape=RoundedCornerShape(14.dp),modifier=Modifier.align(Alignment.BottomCenter).padding(10.dp)){
                    Text("اسحب للنظر حولك • الكاميرا على ارتفاع العين داخل الغرفة",fontSize=9.2.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(horizontal=12.dp,vertical=7.dp))
                }
            }
            LinearProgressIndicator(progress={(index+1f)/route.points.size},modifier=Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedButton(onClick={index=(index-1).coerceAtLeast(0)},enabled=index>0,modifier=Modifier.weight(1f)){Icon(Icons.Rounded.ArrowForward,null);Spacer(Modifier.width(4.dp));Text("السابق")}
                Button(onClick={index=(index+1).coerceAtMost(route.points.lastIndex)},enabled=index<route.points.lastIndex,modifier=Modifier.weight(1f)){Text("التالي");Spacer(Modifier.width(4.dp));Icon(Icons.Rounded.ArrowBack,null)}
            }
            route.warnings.firstOrNull{it.contains("غير متصل")||it.contains("مقياس") }?.let{warning->Surface(color=Color(0xFFFFF1D6),modifier=Modifier.fillMaxWidth()){Text(warning,fontSize=8.8.sp,lineHeight=13.sp,modifier=Modifier.padding(8.dp))}}
        }
    }
}
