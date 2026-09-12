package com.manzili.hai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apartment
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.HomeWork
import androidx.compose.material.icons.rounded.House
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.manzili.hai.engine.SaudiProjectTypeEngine

@Composable
fun SaudiProjectTypeScreen(
    nav: NavHostController,
    title: String,
    subtitle: String,
    current: SaudiProjectTypeEngine.Type? = null,
    onChoose: (SaudiProjectTypeEngine.Type) -> Unit
) {
    Surface(Modifier.fillMaxSize(), color = Color(0xFFF7F4EE)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp)) {
            Row(Modifier.fillMaxWidth()) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Rounded.ArrowForward, "رجوع") }
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 23.sp, fontWeight = FontWeight.Black)
                    Text(subtitle, color = Color.Gray, fontSize = 10.5.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                SaudiProjectTypeEngine.Type.entries.forEach { type ->
                    val profile = SaudiProjectTypeEngine.profile(type)
                    ElevatedCard(
                        onClick = { onChoose(type) },
                        colors = CardDefaults.elevatedCardColors(containerColor = if (current == type) Color(0xFFFFF1D6) else Color.White),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp)
                    ) {
                        Row(Modifier.padding(14.dp)) {
                            Icon(
                                when {
                                    type.apartmentMode -> Icons.Rounded.Apartment
                                    type == SaudiProjectTypeEngine.Type.TRADITIONAL || type == SaudiProjectTypeEngine.Type.REST_HOUSE -> Icons.Rounded.House
                                    else -> Icons.Rounded.HomeWork
                                }, null, modifier = Modifier.size(28.dp), tint = Color(0xFF9A7447)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(type.label, fontWeight = FontWeight.Black, fontSize = 16.sp)
                                Text(type.subtitle, color = Color.Gray, fontSize = 10.5.sp, lineHeight = 15.sp)
                                Spacer(Modifier.height(5.dp))
                                Text(profile.priorities.take(3).joinToString(" • "), fontSize = 9.5.sp, color = Color(0xFF5B625E))
                            }
                        }
                    }
                }
                Text("بعد اختيار النوع ستتغير أسئلة HAI تلقائيًا، ولن أتعامل مع عمارة أو تاون هاوس بنفس منطق الفيلا.", color = Color.Gray, fontSize = 10.sp, modifier = Modifier.padding(vertical = 10.dp))
            }
        }
    }
}
