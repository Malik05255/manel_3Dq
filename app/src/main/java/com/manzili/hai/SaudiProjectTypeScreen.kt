package com.manzili.hai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.HomeWork
import androidx.compose.material.icons.outlined.House
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
    Surface(Modifier.fillMaxSize(), color = StudioColors.Canvas) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.Outlined.ArrowForward, "رجوع")
                }
                Text(
                    title,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = StudioColors.Ink
                )
            }

            Spacer(Modifier.height(18.dp))

            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SaudiProjectTypeEngine.Type.entries.forEach { type ->
                    ProjectTypeCard(type, current == type, Modifier.fillMaxWidth()) { onChoose(type) }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun ProjectTypeCard(
    type: SaudiProjectTypeEngine.Type,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val accent = if (type.apartmentMode) StudioColors.Primary else StudioColors.Warning
    ElevatedCard(
        onClick = onClick,
        modifier = modifier.heightIn(min = 88.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) accent.copy(alpha = 0.10f) else Color.White
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = if (selected) 5.dp else 1.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(
                Modifier
                    .size(42.dp)
                    .background(accent.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    when {
                        type.apartmentMode -> Icons.Outlined.Apartment
                        type == SaudiProjectTypeEngine.Type.TRADITIONAL || type == SaudiProjectTypeEngine.Type.REST_HOUSE -> Icons.Outlined.House
                        else -> Icons.Outlined.HomeWork
                    },
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(23.dp)
                )
            }
            Text(
                type.label,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = StudioColors.Ink,
                maxLines = 2
            )
        }
    }
}
