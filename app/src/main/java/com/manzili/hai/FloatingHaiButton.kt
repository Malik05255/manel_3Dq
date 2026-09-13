package com.manzili.hai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Small draggable HAI summon bubble. Long-press then drag; tap invokes contextual help. */
@Composable
fun FloatingHaiButton(
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val bubblePx = with(density) { 74.dp.toPx() }
        val maxXPx = with(density) { maxWidth.toPx() } - bubblePx
        val maxYPx = with(density) { maxHeight.toPx() } - bubblePx
        var x by remember(maxXPx) { mutableFloatStateOf((maxXPx * .78f).coerceAtLeast(0f)) }
        var y by remember(maxYPx) { mutableFloatStateOf((maxYPx * .58f).coerceAtLeast(0f)) }

        Surface(
            modifier = Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .size(74.dp)
                .shadow(12.dp, CircleShape)
                .pointerInput(maxXPx, maxYPx) {
                    detectDragGesturesAfterLongPress { change, drag ->
                        change.consume()
                        x = (x + drag.x).coerceIn(0f, maxXPx.coerceAtLeast(0f))
                        y = (y + drag.y).coerceIn(0f, maxYPx.coerceAtLeast(0f))
                    }
                }
                .clickable(enabled = !busy, onClick = onClick),
            shape = CircleShape,
            color = Color(0xFF6652E8),
            tonalElevation = 10.dp,
            shadowElevation = 10.dp
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF6652E8), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(27.dp),
                        color = Color.White,
                        strokeWidth = 2.5.dp
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(21.dp)
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "استدع HAI",
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 9.5.sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
