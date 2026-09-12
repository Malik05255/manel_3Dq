package com.manzili.hai

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.manzili.hai.engine.Architectural3DEnhancementEngine
import com.manzili.hai.engine.SaudiVisualRenderEngine
import com.manzili.hai.engine.Semantic3DEngine
import com.manzili.hai.model.FloorPlan
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private data class RPoint(val x:Float,val y:Float,val depth:Double)
private data class RFace(val points:List<RPoint>,val color:Color,val depth:Double)

@Composable
fun Production3DScreenV2(nav:NavHostController,plan:FloorPlan?) {
    var quality by remember { mutableStateOf(SaudiVisualRenderEngine.Quality.HIGH) }
    var yaw by remember { mutableFloatStateOf(-35f) };var pitch by remember { mutableFloatStateOf(32f) };var zoom by remember { mutableFloatStateOf(1f) }
    val scene=remember(plan){plan?.let(Architectural3DEnhancementEngine::build)}
    val profile=remember(plan,quality){plan?.let { SaudiVisualRenderEngine.build(it,quality) }}
    Surface(Modifier.fillMaxSize(),color=Color(0xFFF5F2EC)){
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal=16.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){
                IconButton(onClick={nav.popBackStack()}){Icon(Icons.Rounded.ArrowForward,"رجوع")}
                Box(Modifier.size(42.dp).background(Color(0xFF27312C),RoundedCornerShape(13.dp)),contentAlignment=Alignment.Center){Icon(Icons.Rounded.ViewInAr,null,tint=Color.White)}
                Spacer(Modifier.width(9.dp));Column(Modifier.weight(1f)){Text("3D سعودي واقعي",fontWeight=FontWeight.Black,fontSize=20.sp);Text("خامات • شمس • ظل • واجهة • Geometry V3",color=Color.Gray,fontSize=9.5.sp)}
                IconButton(onClick={nav.navigate("walkthrough")}){Icon(Icons.Rounded.DirectionsWalk,"جولة داخلية")}
            }
            if(scene==null||profile==null||plan==null){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text("افتح مشروعًا أولًا")};return@Column}
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                SaudiVisualRenderEngine.Quality.entries.forEach { q->FilterChip(selected=quality==q,onClick={quality=q},label={Text(q.label,fontSize=9.sp)}) }
                AssistChip(onClick={},label={Text(profile.facade.style,fontSize=9.sp)},leadingIcon={Icon(Icons.Rounded.Tune,null,Modifier.size(14.dp))})
                AssistChip(onClick={},label={Text("شمس ${profile.sun.elevationDeg.toInt()}°",fontSize=9.sp)})
            }
            Spacer(Modifier.height(7.dp))
            Card(shape=RoundedCornerShape(24.dp),colors=CardDefaults.cardColors(containerColor=Color(0xFFFFFEFA)),modifier=Modifier.fillMaxWidth().weight(1f)){
                RenderCanvas(scene,profile,yaw,pitch,zoom,{dx,dy,scale,rotation->yaw+=rotation+dx*.1f;pitch=(pitch+dy*.08f).coerceIn(8f,78f);zoom=(zoom*scale).coerceIn(.45f,5f)},Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(5.dp)){
                profile.facade.features.forEach { AssistChip(onClick={},label={Text(it,fontSize=8.5.sp)}) }
            }
            Text("${scene.meshes.size} عنصر 3D • ${profile.materials.size} خامات منطقية • جودة ${quality.label}",fontSize=9.5.sp,color=Color.Gray,modifier=Modifier.padding(vertical=5.dp))
            Surface(color=Color(0xFFECE7DC),shape=RoundedCornerShape(13.dp),modifier=Modifier.fillMaxWidth().padding(bottom=8.dp)){Text(profile.warnings.first(),fontSize=9.2.sp,lineHeight=14.sp,modifier=Modifier.padding(9.dp))}
        }
    }
}

@Composable
private fun RenderCanvas(scene:Semantic3DEngine.Scene,profile:SaudiVisualRenderEngine.Profile,yaw:Float,pitch:Float,zoom:Float,onGesture:(Float,Float,Float,Float)->Unit,modifier:Modifier){
    Canvas(modifier.pointerInput(scene){detectTransformGestures{_,pan,scale,rotation->onGesture(pan.x,pan.y,scale,rotation)}}){
        val vertices=scene.meshes.flatMap{it.vertices};if(vertices.isEmpty())return@Canvas
        val cx=(vertices.minOf{it.x}+vertices.maxOf{it.x})/2;val cy=(vertices.minOf{it.y}+vertices.maxOf{it.y})/2;val cz=(vertices.minOf{it.z}+vertices.maxOf{it.z})/2
        val yr=yaw/180.0*PI;val pr=pitch/180.0*PI;val cyaw=cos(yr);val syaw=sin(yr);val cp=cos(pr);val sp=sin(pr)
        fun raw(v:Semantic3DEngine.Vec3):RPoint{val x=v.x-cx;val y=v.y-cy;val z=v.z-cz;val rx=x*cyaw-y*syaw;val ry=x*syaw+y*cyaw;return RPoint(rx.toFloat(),(ry*sp-z*cp).toFloat(),ry*cp+z*sp)}
        val all=vertices.map(::raw);val minX=all.minOf{it.x};val maxX=all.maxOf{it.x};val minY=all.minOf{it.y};val maxY=all.maxOf{it.y};val fit=min(size.width/(maxX-minX).coerceAtLeast(1f),size.height/(maxY-minY).coerceAtLeast(1f))*.78f*zoom;val mx=(minX+maxX)/2;val my=(minY+maxY)/2
        fun screen(p:RPoint)=RPoint(size.width/2+(p.x-mx)*fit,size.height/2+(p.y-my)*fit,p.depth)
        val faces=mutableListOf<RFace>()
        scene.meshes.forEach{mesh->
            val base=kindColor(mesh.kind,profile);mesh.faces.forEach{f->val pts=f.indices.mapNotNull{idx->mesh.vertices.getOrNull(idx)?.let(::raw)?.let(::screen)};if(pts.size>=3){val shade=(.72+((pts.map{it.depth}.average()%1.0+1.0)%1.0)*.22).toFloat();faces+=RFace(pts,base.copy(alpha=shade.coerceIn(.68f,1f)),pts.map{it.depth}.average())}}
        }
        faces.sortedByDescending{it.depth}.forEach{face->val path=Path();face.points.forEachIndexed{i,p->if(i==0)path.moveTo(p.x,p.y)else path.lineTo(p.x,p.y)};path.close();drawPath(path,face.color);drawPath(path,Color.Black.copy(alpha=.10f),style=androidx.compose.ui.graphics.drawscope.Stroke(1f))}
    }
}

private fun kindColor(kind:String,profile:SaudiVisualRenderEngine.Profile)=when(kind){
    "wall"->if(profile.facade.style.contains("نجدي"))Color(0xFFD8C9B4) else Color(0xFFE6DDD0)
    "slab"->Color(0xFFB5B7B4);"roof"->Color(0xFF8A8177);"door"->Color(0xFF72513D);"window"->Color(0xFF78AEC1).copy(alpha=.82f);"structural"->Color(0xFF9A7447);"saudi-parapet"->Color(0xFFD4C9B8);else->Color(0xFFD8D5CF)
}
