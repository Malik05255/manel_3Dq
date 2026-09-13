package com.manzili.hai

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Architecture
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
import kotlin.math.roundToInt

/**
 * Global HAI summon bubble.
 * One gesture recognizer owns both tap and drag so dragging never fights the click handler.
 */
@Composable
fun FloatingHaiButton(
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier.fillMaxSize().zIndex(1000f)) {
        val density = LocalDensity.current
        val bubbleDp = 52.dp
        val bubblePx = with(density) { bubbleDp.toPx() }
        val maxX = (with(density) { maxWidth.toPx() } - bubblePx).coerceAtLeast(0f)
        val maxY = (with(density) { maxHeight.toPx() } - bubblePx).coerceAtLeast(0f)

        var savedX by rememberSaveable { mutableStateOf<Float?>(null) }
        var savedY by rememberSaveable { mutableStateOf<Float?>(null) }

        val x = (savedX ?: maxX * .82f).coerceIn(0f, maxX)
        val y = (savedY ?: maxY * .36f).coerceIn(0f, maxY)

        Surface(
            modifier = Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .size(bubbleDp)
                .zIndex(1001f)
                .shadow(4.dp, RoundedCornerShape(16.dp))
                .semantics {
                    contentDescription = if (busy) "HAI يحلل المخطط" else "مساعد HAI"
                    role = Role.Button
                    onClick { if (!busy) onClick(); true }
                }
                .pointerInput(busy, maxX, maxY) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val pointerId = down.id
                        var dragging = false
                        var travel = Offset.Zero
                        var currentX = (savedX ?: maxX * .82f).coerceIn(0f, maxX)
                        var currentY = (savedY ?: maxY * .36f).coerceIn(0f, maxY)

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                            if (!change.pressed) break
                            val delta = change.positionChange()
                            travel += delta
                            if (!dragging && travel.getDistance() >= viewConfiguration.touchSlop) {
                                dragging = true
                            }
                            if (dragging) {
                                change.consume()
                                currentX = (currentX + delta.x).coerceIn(0f, maxX)
                                currentY = (currentY + delta.y).coerceIn(0f, maxY)
                                savedX = currentX
                                savedY = currentY
                            }
                        }

                        if (!dragging && !busy) onClick()
                    }
                },
            shape = RoundedCornerShape(16.dp),
            color = StudioColors.Primary,
            tonalElevation = 8.dp,
            shadowElevation = 8.dp
        ) {
            Box(
                Modifier.fillMaxSize().background(StudioColors.Primary, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = Color.White,
                        strokeWidth = 2.2.dp
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.Architecture,
                            contentDescription = "استدع HAI",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Text("HAI", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
