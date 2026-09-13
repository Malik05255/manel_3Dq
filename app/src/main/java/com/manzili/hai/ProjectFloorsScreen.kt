package com.manzili.hai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.manzili.hai.engine.MultiFloorGeometryEngine
import com.manzili.hai.model.FloorPlan

@Composable
fun ProjectFloorsScreen(nav: NavHostController, plan: FloorPlan?, onUpdate: (FloorPlan) -> Unit) {
    if (plan == null) {
        Surface(Modifier.fillMaxSize()) { Column(Modifier.padding(18.dp)) { Text("لا يوجد مشروع مفتوح") } }
        return
    }
    val normalized = MultiFloorGeometryEngine.normalize(plan)
    Surface(Modifier.fillMaxSize(), color = StudioColors.Canvas) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Outlined.ArrowForward, "رجوع") }
                Text("إدارة الأدوار", fontSize = 21.sp, fontWeight = FontWeight.Bold)
            }
            Text("${normalized.floors.size} دور محفوظ داخل نفس المشروع", color = Color.Gray, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                normalized.floors.sortedBy { it.index }.forEach { floor ->
                    val active = floor.id == normalized.activeFloorId
                    Card(colors = CardDefaults.cardColors(containerColor = if (active) StudioColors.Ink else Color.White), shape = RoundedCornerShape(18.dp)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Layers, null, tint = if (active) Color.White else StudioColors.Ink)
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f)) {
                                Text(floor.name, color = if (active) Color.White else Color.Black, fontWeight = FontWeight.Bold)
                                Text("${floor.rooms.size} غرفة • ${floor.walls.size} جدار • منسوب ${"%.1f".format(floor.elevationM)}م", color = if (active) Color.White.copy(alpha=.65f) else Color.Gray, fontSize = 12.sp)
                            }
                            TextButton(onClick = {
                                onUpdate(MultiFloorGeometryEngine.selectFloor(normalized, floor.id).copy(revision = normalized.revision + 1))
                                nav.navigate("editor")
                            }) { Text(if (active) "فتح" else "اختيار", color = if (active) Color.White else MaterialTheme.colorScheme.secondary) }
                        }
                    }
                }
                if (normalized.floors.size < 3) {
                    OutlinedButton(onClick = { onUpdate(MultiFloorGeometryEngine.addFloor(normalized).copy(revision = normalized.revision + 1)) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(5.dp)); Text("إضافة دور")
                    }
                }
            }
        }
    }
}