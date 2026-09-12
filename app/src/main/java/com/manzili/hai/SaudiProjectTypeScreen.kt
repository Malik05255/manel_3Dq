package com.manzili.hai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apartment
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.HomeWork
import androidx.compose.material.icons.rounded.House
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
    Surface(Modifier.fillMaxSize(), color = Color(0xFFF8F6F2)) {
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
                    Icon(Icons.Rounded.ArrowForward, "رجوع")
                }
                Text(
                    title,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFF181A18)
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
                SaudiProjectTypeEngine.Type.entries.chunked(2).forEach { rowTypes ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        rowTypes.forEach { type ->
                            ProjectTypeCard(
                                type = type,
                                selected = current == type,
                                modifier = Modifier.weight(1f),
                                onClick = { onChoose(type) }
                            )
                        }
                        if (rowTypes.size == 1) Spacer(Modifier.weight(1f))
                    }
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
    val accent = if (type.apartmentMode) Color(0xFF6353D9) else Color(0xFFE28B5A)
    ElevatedCard(
        onClick = onClick,
        modifier = modifier.height(126.dp),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) accent.copy(alpha = 0.10f) else Color.White
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = if (selected) 5.dp else 1.dp)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .background(accent.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    when {
                        type.apartmentMode -> Icons.Rounded.Apartment
                        type == SaudiProjectTypeEngine.Type.TRADITIONAL || type == SaudiProjectTypeEngine.Type.REST_HOUSE -> Icons.Rounded.House
                        else -> Icons.Rounded.HomeWork
                    },
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(23.dp)
                )
            }
            Text(
                type.label,
                fontWeight = FontWeight.Black,
                fontSize = 15.sp,
                color = Color(0xFF181A18),
                maxLines = 2
            )
        }
    }
}
