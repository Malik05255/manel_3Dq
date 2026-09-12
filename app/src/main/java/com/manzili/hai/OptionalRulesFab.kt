package com.manzili.hai

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FactCheck
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.manzili.hai.model.FloorPlan

/** Small universal control: official rules are always opt-in and never block the stage. */
@Composable
fun OptionalRulesStage(
    nav:NavHostController,
    plan:FloorPlan?,
    pendingEnabled:Boolean,
    onToggle:(Boolean)->Unit,
    content:@Composable ()->Unit
){
    Box(Modifier.fillMaxSize()){
        content()
        ExtendedFloatingActionButton(
            onClick={onToggle(!(plan?.saudiRulesEnabled?:pendingEnabled))},
            icon={Icon(Icons.Rounded.FactCheck,null)},
            text={Text(if(plan?.saudiRulesEnabled?:pendingEnabled)"الاشتراطات: مفعلة" else "الاشتراطات: اختيارية")},
            modifier=Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(14.dp)
        )
        if(plan!=null){
            TextButton(onClick={nav.navigate("saudi-rules")},modifier=Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(14.dp)){Text("تفاصيل الاشتراطات")}
        }
    }
}
