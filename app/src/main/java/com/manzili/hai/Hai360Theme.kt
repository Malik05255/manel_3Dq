package com.manzili.hai

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val H360Ink = Color(0xFF17212B)
internal val H360InkSoft = Color(0xFF2E3A46)
internal val H360Ivory = Color(0xFFF6F8FB)
internal val H360Paper = Color(0xFFFFFFFF)
internal val H360Line = Color(0xFFDDE3EA)
internal val H360Muted = Color(0xFF6F7B87)
internal val H360Cyan = Color(0xFFD9E8FF)
internal val H360CyanDeep = Color(0xFF4D68B1)
internal val H360Amber = Color(0xFFF1B86D)
internal val H360Danger = Color(0xFFD96161)
internal val H360Success = Color(0xFF4E9B78)
internal val H360Lilac = Color(0xFFE9E5FF)
internal val H360Sky = Color(0xFFEAF5FF)
internal val H360Peach = Color(0xFFFFEEE4)
internal val H360Mint = Color(0xFFE8F6F1)

@Composable
internal fun Hai360Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = H360CyanDeep,
            onPrimary = Color.White,
            primaryContainer = H360Cyan,
            onPrimaryContainer = H360Ink,
            secondary = Color(0xFF6E63B6),
            onSecondary = Color.White,
            secondaryContainer = H360Lilac,
            onSecondaryContainer = H360Ink,
            background = H360Ivory,
            onBackground = H360Ink,
            surface = H360Paper,
            onSurface = H360Ink,
            surfaceVariant = Color(0xFFF0F3F7),
            onSurfaceVariant = H360Muted,
            outline = H360Line,
            error = H360Danger,
            onError = Color.White
        ),
        shapes = Shapes(
            small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(22.dp),
            large = RoundedCornerShape(30.dp)
        ),
        content = content
    )
}

@Composable
internal fun BlueprintGrid(
    modifier: Modifier = Modifier,
    dark: Boolean = false,
    step: Float = 34f
) {
    val base = if (dark) Color(0xFFF2F5F9) else H360Ivory
    val major = H360CyanDeep.copy(alpha = if (dark) .10f else .07f)
    val minor = H360CyanDeep.copy(alpha = if (dark) .045f else .025f)
    Canvas(modifier.background(base)) {
        var x = 0f
        var column = 0
        while (x <= size.width) {
            drawLine(if (column % 4 == 0) major else minor, Offset(x, 0f), Offset(x, size.height), 1f)
            x += step
            column++
        }
        var y = 0f
        var row = 0
        while (y <= size.height) {
            drawLine(if (row % 4 == 0) major else minor, Offset(0f, y), Offset(size.width, y), 1f)
            y += step
            row++
        }
    }
}

@Composable
internal fun ArchitecturalBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.background(H360Ivory)) {
        BlueprintGrid(Modifier.matchParentSize(), dark = false, step = 38f)
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = .92f),
                        H360Sky.copy(alpha = .72f),
                        H360Ivory.copy(alpha = .95f)
                    )
                )
            )
        )
        content()
    }
}

@Composable
internal fun H360IconButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        color = if (accent) H360CyanDeep else H360Paper,
        contentColor = if (accent) Color.White else H360Ink,
        shape = CircleShape,
        shadowElevation = if (accent) 8.dp else 3.dp,
        tonalElevation = if (accent) 0.dp else 1.dp,
        border = if (accent) null else androidx.compose.foundation.BorderStroke(1.dp, H360Line),
        modifier = modifier.size(48.dp).clickable(enabled = enabled, onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, description, modifier = Modifier.size(21.dp), tint = LocalContentColor.current.copy(alpha = if (enabled) 1f else .35f))
        }
    }
}

@Composable
internal fun H360Metric(label: String, value: String, modifier: Modifier = Modifier, highlighted: Boolean = false) {
    Surface(
        color = if (highlighted) H360Cyan else H360Paper,
        contentColor = H360Ink,
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (highlighted) H360CyanDeep.copy(alpha = .18f) else H360Line),
        shadowElevation = 1.dp,
        modifier = modifier
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(value, fontWeight = FontWeight.Black, fontSize = 13.sp)
            Spacer(Modifier.width(6.dp))
            Text(label, fontWeight = FontWeight.Bold, fontSize = 9.sp, color = H360Muted)
        }
    }
}

@Composable
internal fun H360PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = H360CyanDeep,
            contentColor = Color.White,
            disabledContainerColor = H360Line,
            disabledContentColor = H360Muted
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp, pressedElevation = 1.dp),
        modifier = modifier.height(56.dp)
    ) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontWeight = FontWeight.Black, fontSize = 14.sp)
    }
}

@Composable
internal fun H360SectionLabel(kicker: String, title: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        if (kicker.isNotBlank()) {
            Text(kicker.uppercase(), color = H360CyanDeep, fontSize = 8.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
            Spacer(Modifier.height(3.dp))
        }
        Text(title, color = H360Ink, fontSize = 27.sp, lineHeight = 30.sp, fontWeight = FontWeight.Black)
    }
}
