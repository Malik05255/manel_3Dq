package com.manzili.hai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddHomeWork
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController

@Composable
fun ProductionBuildChoiceScreen(nav: NavHostController) {
    Surface(Modifier.fillMaxSize(), color = Color(0xFFF8F6F2)) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.Rounded.ArrowForward, "رجوع")
                }
                Text(
                    "ابدأ",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFF181A18)
                )
            }

            Spacer(Modifier.height(28.dp))

            StartChoiceCard(
                title = "مشروع جديد",
                icon = Icons.Rounded.AddHomeWork,
                accent = Color(0xFF6353D9),
                onClick = { nav.navigate("new") }
            )

            Spacer(Modifier.height(14.dp))

            StartChoiceCard(
                title = "عندي مخطط",
                icon = Icons.Rounded.UploadFile,
                accent = Color(0xFFE28B5A),
                onClick = { nav.navigate("import-type") }
            )
        }
    }
}

@Composable
private fun StartChoiceCard(
    title: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit
) {
    ElevatedCard(
        onClick = onClick,
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(164.dp)
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(64.dp)
                    .background(accent.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(31.dp))
            }
            Spacer(Modifier.width(18.dp))
            Text(
                title,
                fontSize = 23.sp,
                fontWeight = FontWeight.Black,
                color = Color(0xFF181A18)
            )
        }
    }
}
