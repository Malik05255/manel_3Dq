package com.manzili.hai

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * HAI action pill.
 * - Single tap: execute HAI action.
 * - Long press: pick the pill up, then drag it freely.
 * A normal finger move never drags the pill before the long-press threshold.
 */
@Composable
fun FloatingHaiButton(
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier.fillMaxSize().zIndex(1000f)) {
        val density = LocalDensity.current
        val buttonWidth = 74.dp
        val buttonHeight = 54.dp
        val buttonWidthPx = with(density) { buttonWidth.toPx() }
        val buttonHeightPx = with(density) { buttonHeight.toPx() }
        val maxX = (with(density) { maxWidth.toPx() } - buttonWidthPx).coerceAtLeast(0f)
        val maxY = (with(density) { maxHeight.toPx() } - buttonHeightPx).coerceAtLeast(0f)

        var savedX by rememberSaveable { mutableStateOf<Float?>(null) }
        var savedY by rememberSaveable { mutableStateOf<Float?>(null) }
        var lifted by remember { mutableStateOf(false) }

        val x = (savedX ?: maxX * .80f).coerceIn(0f, maxX)
        val y = (savedY ?: maxY * .34f).coerceIn(0f, maxY)
        val shape = RoundedCornerShape(22.dp)

        Surface(
            modifier = Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .size(buttonWidth, buttonHeight)
                .zIndex(1001f)
                .shadow(if (lifted) 18.dp else 10.dp, shape)
                .pointerInput(busy, maxX, maxY) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val pointerId = down.id
                        var totalTravel = Offset.Zero
                        var releasedBeforeLongPress = false
                        var currentX = (savedX ?: maxX * .80f).coerceIn(0f, maxX)
                        var currentY = (savedY ?: maxY * .34f).coerceIn(0f, maxY)

                        val longPressed = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == pointerId }
                                    ?: return@withTimeoutOrNull false
                                totalTravel += change.positionChange()
                                if (!change.pressed) {
                                    releasedBeforeLongPress = true
                                    return@withTimeoutOrNull false
                                }
                            }
                        } == null

                        if (!longPressed) {
                            if (releasedBeforeLongPress && totalTravel.getDistance() < viewConfiguration.touchSlop && !busy) {
                                onClick()
                            }
                            return@awaitEachGesture
                        }

                        lifted = true
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                                if (!change.pressed) break
                                val delta = change.positionChange()
                                if (delta != Offset.Zero) {
                                    change.consume()
                                    currentX = (currentX + delta.x).coerceIn(0f, maxX)
                                    currentY = (currentY + delta.y).coerceIn(0f, maxY)
                                    savedX = currentX
                                    savedY = currentY
                                }
                            }
                        } finally {
                            lifted = false
                        }
                    }
                },
            shape = shape,
            color = Color(0xFF5E4BDD),
            tonalElevation = if (lifted) 12.dp else 7.dp,
            shadowElevation = if (lifted) 14.dp else 8.dp
        ) {
            Box(
                Modifier.fillMaxSize().background(Color(0xFF5E4BDD), shape),
                contentAlignment = Alignment.Center
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = Color.White,
                        strokeWidth = 2.2.dp
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.AutoAwesome,
                            contentDescription = "HAI",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text("HAI", color = Color.White, fontWeight = FontWeight.Black, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
