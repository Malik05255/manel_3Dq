package com.manzili.hai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

/**
 * Contextual HAI bubble.
 * Tap = solve the current screen. Drag directly = move it anywhere without waiting for long-press.
 */
@Composable
fun FloatingHaiButton(
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val bubblePx = with(density) { 64.dp.toPx() }
        val maxXPx = (with(density) { maxWidth.toPx() } - bubblePx).coerceAtLeast(0f)
        val maxYPx = (with(density) { maxHeight.toPx() } - bubblePx).coerceAtLeast(0f)

        var x by remember { mutableFloatStateOf(Float.NaN) }
        var y by remember { mutableFloatStateOf(Float.NaN) }

        LaunchedEffect(maxXPx, maxYPx) {
            if (x.isNaN()) x = (maxXPx * .80f).coerceIn(0f, maxXPx)
            else x = x.coerceIn(0f, maxXPx)
            if (y.isNaN()) y = (maxYPx * .62f).coerceIn(0f, maxYPx)
            else y = y.coerceIn(0f, maxYPx)
        }

        Surface(
            modifier = Modifier
                .graphicsLayer {
                    translationX = if (x.isNaN()) 0f else x
                    translationY = if (y.isNaN()) 0f else y
                }
                .size(64.dp)
                .zIndex(30f)
                .shadow(9.dp, CircleShape)
                .pointerInput(maxXPx, maxYPx) {
                    detectDragGestures(
                        onDrag = { change, drag ->
                            change.consume()
                            x = ((if (x.isNaN()) 0f else x) + drag.x).coerceIn(0f, maxXPx)
                            y = ((if (y.isNaN()) 0f else y) + drag.y).coerceIn(0f, maxYPx)
                        }
                    )
                }
                .clickable(enabled = !busy, onClick = onClick),
            shape = CircleShape,
            color = Color(0xFF6652E8),
            tonalElevation = 8.dp,
            shadowElevation = 8.dp
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF6652E8), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(23.dp),
                        color = Color.White,
                        strokeWidth = 2.3.dp
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Rounded.AutoAwesome,
                            contentDescription = "استدع HAI",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.height(1.dp))
                        Text(
                            "HAI",
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 10.sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
