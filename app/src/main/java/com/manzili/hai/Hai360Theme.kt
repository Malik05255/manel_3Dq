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

internal val H360Ink = Color(0xFF101416)
internal val H360InkSoft = Color(0xFF1A2023)
internal val H360Ivory = Color(0xFFF4F1EA)
internal val H360Paper = Color(0xFFFFFDF8)
internal val H360Line = Color(0xFFD9D5CC)
internal val H360Muted = Color(0xFF716F69)
internal val H360Cyan = Color(0xFF60D5CF)
internal val H360CyanDeep = Color(0xFF0B706E)
internal val H360Amber = Color(0xFFFFB36A)
internal val H360Danger = Color(0xFFE86E5A)
internal val H360Success = Color(0xFF6CC59C)

@Composable
internal fun Hai360Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = H360Ink,
            onPrimary = Color.White,
            secondary = H360CyanDeep,
            onSecondary = Color.White,
            background = H360Ivory,
            onBackground = H360Ink,
            surface = H360Paper,
            onSurface = H360Ink,
            error = H360Danger
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
    val base = if (dark) H360Ink else H360Ivory
    val major = if (dark) Color.White.copy(alpha = .055f) else H360Ink.copy(alpha = .055f)
    val minor = if (dark) Color.White.copy(alpha = .025f) else H360Ink.copy(alpha = .025f)
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
    Box(modifier) {
        BlueprintGrid(Modifier.matchParentSize(), dark = true)
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        H360Ink.copy(alpha = .06f),
                        H360Ink
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
    val bg = if (accent) H360Cyan else H360Paper.copy(alpha = .96f)
    val fg = if (accent) H360Ink else H360Ink
    Surface(
        color = if (enabled) bg else bg.copy(alpha = .45f),
        shape = CircleShape,
        shadowElevation = if (accent) 8.dp else 2.dp,
        modifier = modifier.size(48.dp).clickable(enabled = enabled, onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, description, tint = if (enabled) fg else fg.copy(alpha = .35f), modifier = Modifier.size(21.dp))
        }
    }
}

@Composable
internal fun H360Metric(label: String, value: String, modifier: Modifier = Modifier, highlighted: Boolean = false) {
    Surface(
        color = if (highlighted) H360Cyan else H360Ink.copy(alpha = .84f),
        contentColor = if (highlighted) H360Ink else Color.White,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(value, fontWeight = FontWeight.Black, fontSize = 13.sp)
            Spacer(Modifier.width(6.dp))
            Text(label, fontWeight = FontWeight.Medium, fontSize = 9.sp, color = LocalContentColor.current.copy(alpha = .72f))
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
        shape = RoundedCornerShape(22.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = H360Ink,
            contentColor = Color.White,
            disabledContainerColor = H360Ink.copy(alpha = .14f),
            disabledContentColor = H360Ink.copy(alpha = .32f)
        ),
        modifier = modifier.height(58.dp)
    ) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontWeight = FontWeight.Black, fontSize = 15.sp)
    }
}

@Composable
internal fun H360SectionLabel(kicker: String, title: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(kicker.uppercase(), color = H360CyanDeep, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.3.sp)
        Spacer(Modifier.height(4.dp))
        Text(title, color = H360Ink, fontSize = 28.sp, lineHeight = 31.sp, fontWeight = FontWeight.Black)
    }
}
