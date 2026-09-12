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

/** Official rules stay opt-in, but the control must never cover or intercept stage actions. */
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
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = enabled,
                    onClick = { onToggle(!enabled) },
                    leadingIcon = { Icon(Icons.Rounded.FactCheck, contentDescription = null) },
                    label = { Text(if (enabled) "الاشتراطات: مفعلة" else "الاشتراطات: اختيارية") }
                )

                if (plan != null) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { nav.navigate("saudi-rules") }) {
                        Text("التفاصيل")
                    }
                }
            }
        }
    }
}
