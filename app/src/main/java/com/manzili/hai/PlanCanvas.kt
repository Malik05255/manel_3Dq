package com.manzili.hai

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manzili.hai.model.FloorPlan
import kotlin.math.abs
import kotlin.math.hypot

/** Shared editor selection primitive extracted from the removed legacy Activity. */
data class PlanSelection(val kind:String,val id:String)

private val CanvasPaper=Color(0xFFFFFEFA)
private val CanvasMist=Color(0xFFE9E5DC)
private val CanvasInk=Color(0xFF20211E)
private val CanvasBronze=Color(0xFF9A7447)
private val CanvasDeep=Color(0xFF27312C)
private val CanvasSage=Color(0xFF64756B)
private val CanvasSoftBlue=Color(0xFF70808A)

@Composable
fun PlanCanvas(plan:FloorPlan,modifier:Modifier=Modifier,previewMode:Boolean=false,selected:PlanSelection?=null,onSelect:(PlanSelection?)->Unit={}) {
    Surface(modifier,color=CanvasPaper,shape=RoundedCornerShape(24.dp),tonalElevation=1.dp){
        BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)){
            Canvas(Modifier.fillMaxSize().pointerInput(plan,selected){
                detectTapGestures{tap->
                    val pad=8.dp.toPx();val w=size.width-pad*2;val h=size.height-pad*2;if(w<=0f||h<=0f)return@detectTapGestures
                    val px=((tap.x-pad)/w*100f).coerceIn(0f,100f);val py=((tap.y-pad)/h*100f).coerceIn(0f,100f)
                    val opening=plan.openings.minByOrNull{hypot((it.x-px).toDouble(),(it.y-py).toDouble())}
                    if(opening!=null&&hypot((opening.x-px).toDouble(),(opening.y-py).toDouble())<=4.5){onSelect(PlanSelection("opening",opening.id));return@detectTapGestures}
                    val wall=plan.walls.minByOrNull{pointToSegment(px,py,it.start.x,it.start.y,it.end.x,it.end.y)}
                    if(wall!=null&&pointToSegment(px,py,wall.start.x,wall.start.y,wall.end.x,wall.end.y)<=2.3){onSelect(PlanSelection("wall",wall.id));return@detectTapGestures}
                    val room=plan.rooms.lastOrNull{px>=it.x&&px<=it.x+it.width&&py>=it.y&&py<=it.y+it.height};onSelect(room?.let{PlanSelection("room",it.id)})
                }
            }){
                val pad=8.dp.toPx();val w=size.width-pad*2;val h=size.height-pad*2
                fun p(x:Float,y:Float)=Offset(pad+w*x/100f,pad+h*y/100f)
                drawRect(CanvasMist,Offset(pad,pad),Size(w,h),style=Stroke(2.dp.toPx()))
                plan.rooms.forEach{r->
                    val left=pad+w*r.x/100f;val top=pad+h*r.y/100f;val rw=(w*r.width/100f).coerceAtLeast(1f).coerceAtMost((size.width-left-pad).coerceAtLeast(1f));val rh=(h*r.height/100f).coerceAtLeast(1f).coerceAtMost((size.height-top-pad).coerceAtLeast(1f));val active=selected?.kind=="room"&&selected.id==r.id
                    val c=when{active||r.locked->CanvasBronze;r.confidence<75->Color(0xFFB78858);else->CanvasDeep}
                    drawRect(c.copy(alpha=if(active).13f else if(previewMode).06f else .035f),Offset(left,top),Size(rw,rh));if(plan.walls.isEmpty())drawRect(c.copy(alpha=.72f),Offset(left,top),Size(rw,rh),style=Stroke(if(active||r.locked)3.dp.toPx() else 1.5.dp.toPx()))
                }
                plan.walls.forEach{wall->val active=selected?.kind=="wall"&&selected.id==wall.id;val c=when{active||wall.locked->CanvasBronze;wall.confidence<60->Color(0xFFB78858);else->CanvasInk};val t=wall.thicknessCm?.let{(it/7.0).coerceIn(2.0,6.0).toFloat()}?:3f;drawLine(c,p(wall.start.x,wall.start.y),p(wall.end.x,wall.end.y),strokeWidth=(if(active)t+3f else t).dp.toPx())}
                plan.openings.forEach{o->val active=selected?.kind=="opening"&&selected.id==o.id;val win=o.type.lowercase().contains("window")||o.type.contains("ناف");val c=when{active||o.locked->CanvasBronze;o.confidence<60->Color(0xFFB78858);win->CanvasSoftBlue;else->CanvasSage};val center=p(o.x,o.y);val radius=(if(active)6.5f else 4.5f).dp.toPx();if(win){drawLine(c,Offset(center.x-radius,center.y),Offset(center.x+radius,center.y),strokeWidth=2.5.dp.toPx());drawLine(CanvasPaper,Offset(center.x-radius*.55f,center.y),Offset(center.x+radius*.55f,center.y),strokeWidth=.8.dp.toPx())}else{drawCircle(CanvasPaper,radius,center);drawCircle(c,radius,center,style=Stroke(2.dp.toPx()));val rad=Math.toRadians(o.rotationDeg.toDouble());val dx=kotlin.math.cos(rad).toFloat()*radius;val dy=kotlin.math.sin(rad).toFloat()*radius;drawLine(c,center,Offset(center.x+dx,center.y+dy),strokeWidth=2.dp.toPx())}}
            }
            val aw=maxWidth.value-16f;val ah=maxHeight.value-16f
            plan.rooms.take(20).forEach{r->val cx=(r.x+r.width/2f).coerceIn(4f,96f)/100f;val cy=(r.y+r.height/2f).coerceIn(4f,96f)/100f;Text(r.name+if(r.areaM2>0)"\n${"%.1f".format(r.areaM2)}م²" else "",fontSize=8.2.sp,lineHeight=9.5.sp,textAlign=TextAlign.Center,modifier=Modifier.offset(x=(aw*cx-34f).dp,y=(ah*cy-12f).dp).width(68.dp))}
            if(plan.walls.isNotEmpty()||plan.openings.isNotEmpty())Surface(color=CanvasPaper.copy(alpha=.92f),shape=RoundedCornerShape(50.dp),modifier=Modifier.align(Alignment.BottomStart)){Text("اضغط غرفة / جدار / باب",modifier=Modifier.padding(horizontal=9.dp,vertical=5.dp),fontSize=9.5.sp,color=Color.Gray)}
        }
    }
}

private fun pointToSegment(px:Float,py:Float,ax:Float,ay:Float,bx:Float,by:Float):Double{val dx=bx-ax;val dy=by-ay;if(abs(dx)<.0001f&&abs(dy)<.0001f)return hypot((px-ax).toDouble(),(py-ay).toDouble());val t=(((px-ax)*dx+(py-ay)*dy)/(dx*dx+dy*dy)).coerceIn(0f,1f);val x=ax+t*dx;val y=ay+t*dy;return hypot((px-x).toDouble(),(py-y).toDouble())}
