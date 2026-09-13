package com.manzili.hai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.manzili.hai.model.FloorPlan

@Composable
fun OptionalRulesStage(
    nav: NavHostController,
    plan: FloorPlan?,
    pendingEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    content: @Composable () -> Unit
) {
    val enabled = plan?.saudiRulesEnabled ?: pendingEnabled

    Column(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            content()
        }

        Surface(
            color = StudioColors.Canvas,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color.White,
                    modifier = Modifier.height(48.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("الاشتراطات", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(10.dp))
                        Switch(
                            checked = enabled,
                            onCheckedChange = onToggle,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = StudioColors.Primary
                            )
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                if (plan != null) {
                    IconButton(onClick = { nav.navigate("saudi-rules") }) {
                        Icon(Icons.Outlined.ChevronLeft, "التفاصيل")
                    }
                }
            }
        }
    }
}
