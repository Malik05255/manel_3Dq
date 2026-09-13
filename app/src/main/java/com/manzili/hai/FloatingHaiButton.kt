package com.manzili.hai

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

/**
 * Always-visible contextual HAI bubble.
 * Tap invokes HAI. Drag immediately moves the bubble; it is clamped inside the visible workspace.
 */
@Composable
fun FloatingHaiButton(
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier.fillMaxSize().zIndex(100f)) {
        val density = LocalDensity.current
        val bubbleDp = 58.dp
        val bubblePx = with(density) { bubbleDp.toPx() }
        val maxXPx = (with(density) { maxWidth.toPx() } - bubblePx).coerceAtLeast(0f)
        val maxYPx = (with(density) { maxHeight.toPx() } - bubblePx).coerceAtLeast(0f)

        var x by remember { mutableFloatStateOf(Float.NaN) }
        var y by remember { mutableFloatStateOf(Float.NaN) }

        LaunchedEffect(maxXPx, maxYPx) {
            if (x.isNaN()) x = (maxXPx * .82f).coerceIn(0f, maxXPx)
            if (y.isNaN()) y = (maxYPx * .34f).coerceIn(0f, maxYPx)
            x = x.coerceIn(0f, maxXPx)
            y = y.coerceIn(0f, maxYPx)
        }

        Surface(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (if (x.isNaN()) 0f else x).roundToInt(),
                        (if (y.isNaN()) 0f else y).roundToInt()
                    )
                }
                .size(bubbleDp)
                .zIndex(101f)
                .shadow(10.dp, CircleShape)
                .pointerInput(maxXPx, maxYPx) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        x = ((if (x.isNaN()) 0f else x) + drag.x).coerceIn(0f, maxXPx)
                        y = ((if (y.isNaN()) 0f else y) + drag.y).coerceIn(0f, maxYPx)
                    }
                }
                .pointerInput(busy, onClick) {
                    detectTapGestures(onTap = { if (!busy) onClick() })
                },
            shape = CircleShape,
            color = Color(0xFF6652E8),
            tonalElevation = 8.dp,
            shadowElevation = 8.dp
        ) {
            Box(
                Modifier.fillMaxSize().background(Color(0xFF6652E8), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
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
                        Text(
                            "HAI",
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
