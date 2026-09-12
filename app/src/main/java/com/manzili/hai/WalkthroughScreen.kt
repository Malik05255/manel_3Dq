package com.manzili.hai

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.manzili.hai.engine.WalkthroughEngine
import com.manzili.hai.model.FloorPlan

@Composable
fun WalkthroughScreen(nav:NavHostController,plan:FloorPlan?) {
    val route=remember(plan){plan?.let(WalkthroughEngine::build)}
    var index by remember { mutableIntStateOf(0) }
    Surface(Modifier.fillMaxSize(),color=Color(0xFFF7F4EE)){
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal=18.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){IconButton(onClick={nav.popBackStack()}){Icon(Icons.Rounded.ArrowForward,"رجوع")};Icon(Icons.Rounded.DirectionsWalk,null,tint=Color(0xFF27312C));Spacer(Modifier.width(8.dp));Column{Text("جولة داخلية",fontWeight=FontWeight.Black,fontSize=21.sp);Text("مسار فعلي مشتق من غرف المشروع",fontSize=9.5.sp,color=Color.Gray)}}
            if(route==null||route.points.isEmpty()||plan==null){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text(route?.warnings?.firstOrNull()?:"افتح مشروعًا أولًا")};return@Column}
            if(index>route.points.lastIndex)index=route.points.lastIndex
            val current=route.points[index]
            Card(shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=Color.White),modifier=Modifier.fillMaxWidth().weight(1f)){
                Box(Modifier.fillMaxSize()){
                    WalkMap(plan,current.floorId,current.xPct,current.yPct,Modifier.fillMaxSize().padding(14.dp))
                    Surface(color=Color(0xE627312C),shape=RoundedCornerShape(16.dp),modifier=Modifier.align(Alignment.TopCenter).padding(12.dp)){Column(Modifier.padding(horizontal=13.dp,vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally){Text(current.roomName,color=Color.White,fontWeight=FontWeight.Black);Text(current.floorName,color=Color.White.copy(alpha=.72f),fontSize=9.sp)}}
                }
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress={(index+1f)/route.points.size},modifier=Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedButton(onClick={index=(index-1).coerceAtLeast(0)},enabled=index>0,modifier=Modifier.weight(1f)){Icon(Icons.Rounded.ArrowForward,null);Spacer(Modifier.width(4.dp));Text("السابق")}
                Button(onClick={index=(index+1).coerceAtMost(route.points.lastIndex)},enabled=index<route.points.lastIndex,modifier=Modifier.weight(1f)){Text("التالي");Spacer(Modifier.width(4.dp));Icon(Icons.Rounded.ArrowBack,null)}
            }
            Text("${index+1}/${route.points.size} • ${current.roomType}",fontSize=9.5.sp,color=Color.Gray)
            Surface(color=Color(0xFFFFF1D6),shape=RoundedCornerShape(13.dp),modifier=Modifier.fillMaxWidth().padding(top=6.dp,bottom=8.dp)){Text(route.warnings.last(),fontSize=9.sp,lineHeight=14.sp,modifier=Modifier.padding(9.dp))}
        }
    }
}

@Composable
private fun WalkMap(plan:FloorPlan,floorId:String,xPct:Float,yPct:Float,modifier:Modifier){
    val floor=plan.floors.firstOrNull{it.id==floorId};val rooms=floor?.rooms?:plan.rooms
    Canvas(modifier){
        rooms.forEach{room->val poly=room.polygon.ifEmpty{listOf(com.manzili.hai.model.PlanPoint(room.x,room.y),com.manzili.hai.model.PlanPoint(room.x+room.width,room.y),com.manzili.hai.model.PlanPoint(room.x+room.width,room.y+room.height),com.manzili.hai.model.PlanPoint(room.x,room.y+room.height))};if(poly.size>=3){val p=Path();poly.forEachIndexed{i,v->val x=v.x/100f*size.width;val y=v.y/100f*size.height;if(i==0)p.moveTo(x,y)else p.lineTo(x,y)};p.close();drawPath(p,Color(0xFFE6DED2));drawPath(p,Color(0xFF736B62),style=androidx.compose.ui.graphics.drawscope.Stroke(1.4f))}}
        drawCircle(Color(0xFFB5543C),radius=10f,center=androidx.compose.ui.geometry.Offset(xPct/100f*size.width,yPct/100f*size.height));drawCircle(Color.White,radius=4f,center=androidx.compose.ui.geometry.Offset(xPct/100f*size.width,yPct/100f*size.height))
    }
}
