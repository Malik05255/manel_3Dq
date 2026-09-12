package com.manzili.hai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController

@Composable
fun ProductionBuildChoiceScreen(nav: NavHostController) {
    Surface(Modifier.fillMaxSize(), color = Color(0xFFF7F4EE)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
            Row {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Rounded.ArrowForward, "رجوع") }
                Column {
                    Text("كيف نبدأ؟", fontSize = 27.sp, fontWeight = FontWeight.Black)
                    Text("في الحالتين نحدد نوع المشروع أولًا حتى تتغير أسئلة HAI ومحركه.", color = Color.Gray, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(22.dp))
            ElevatedCard(
                onClick = { nav.navigate("new") },
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(145.dp)
            ) {
                Row(Modifier.fillMaxSize().padding(20.dp)) {
                    Icon(Icons.Rounded.NoteAdd, null, modifier = Modifier.size(38.dp), tint = Color(0xFF9A7447))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("بناء من جديد", fontWeight = FontWeight.Black, fontSize = 20.sp)
                        Text("نوع المبنى → الأرض → الأسئلة المتكيفة → 3 حلول", color = Color.Gray, fontSize = 11.sp, lineHeight = 17.sp)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            ElevatedCard(
                onClick = { nav.navigate("import-type") },
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(145.dp)
            ) {
                Row(Modifier.fillMaxSize().padding(20.dp)) {
                    Icon(Icons.Rounded.UploadFile, null, modifier = Modifier.size(38.dp), tint = Color(0xFF9A7447))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("تعديل مشروع سابق", fontWeight = FontWeight.Black, fontSize = 20.sp)
                        Text("حدد هل هو فيلا/عمارة/تاون هاوس… ثم ارفع PDF أو صورة ويكمل HAI على نفس النوع.", color = Color.Gray, fontSize = 11.sp, lineHeight = 17.sp)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text("نوع المشروع يُحفظ كقيد صلب داخل المشروع، وليس مجرد اختيار مؤقت.", color = Color.Gray, fontSize = 10.sp, modifier = Modifier.padding(bottom = 14.dp))
        }
    }
}
