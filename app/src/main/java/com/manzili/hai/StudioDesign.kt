package com.manzili.hai

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Architectural studio: one palette and one type scale throughout the app. */
object StudioColors {
    val Canvas = Color(0xFFF2F5F9)
    val Paper = Color(0xFFFFFFFF)
    val Ink = Color(0xFF142B45)
    val Primary = Color(0xFF2457A6)
    val Muted = Color(0xFF52657A)
    val Line = Color(0xFFDCE4ED)
    val Warning = Color(0xFF9B541B)
    val Success = Color(0xFF276856)
}

@Composable
fun StudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = StudioColors.Primary, onPrimary = Color.White,
            primaryContainer = Color(0xFFE3EDFC), onPrimaryContainer = StudioColors.Ink,
            secondary = StudioColors.Muted, onSecondary = Color.White,
            secondaryContainer = Color(0xFFE7EEF6), onSecondaryContainer = StudioColors.Ink,
            tertiary = StudioColors.Success,
            background = StudioColors.Canvas, onBackground = StudioColors.Ink,
            surface = StudioColors.Paper, onSurface = StudioColors.Ink,
            surfaceVariant = Color(0xFFEAF0F6), onSurfaceVariant = StudioColors.Muted,
            outline = Color(0xFF76879A), outlineVariant = StudioColors.Line,
            error = Color(0xFFB3261E)
        ),
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
            headlineMedium = TextStyle(fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
            titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
            titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 24.sp),
            bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 20.sp),
            labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
        ),
        shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(18.dp)),
        content = content
    )
}

@Composable
fun StudioHeader(title: String, onBack: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowForward, "رجوع") }
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun StudioSection(title: String, subtitle: String? = null) {
    Column(Modifier.padding(vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        subtitle?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = StudioColors.Muted)
        }
    }
}

@Composable
fun StudioEmpty(title: String, action: String, onAction: () -> Unit) {
    Surface(color = StudioColors.Paper, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, StudioColors.Line)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.FolderOpen, null, Modifier.size(40.dp), tint = StudioColors.Primary)
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

/** Decorative wireframe, deliberately separate from the user's actual plan preview. */
@Composable
fun StudioHouseIllustration(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val unit = size.minDimension / 160f
        val origin = Offset(size.width / 2, size.height / 2)
        fun point(x: Float, y: Float) = origin + Offset(x * unit, y * unit)
        val grid = Color.White.copy(alpha = .07f)
        for (i in -8..8) {
            drawLine(grid, point(i*22f-100f, -100f), point(i*22f+100f, 100f), unit)
            drawLine(grid, point(i*22f+100f, -100f), point(i*22f-100f, 100f), unit)
        }
        fun line(ax: Float, ay: Float, bx: Float, by: Float) =
            drawLine(Color(0xFFB8D6FF), point(ax,ay), point(bx,by), 1.6f*unit)
        val roof = Path().apply { moveTo(point(-64f,-18f).x,point(-64f,-18f).y); listOf(0f to -50f,64f to -18f,0f to 14f).forEach{lineTo(point(it.first,it.second).x,point(it.first,it.second).y)};close() }
        drawPath(roof, Color(0xFFB8D6FF).copy(alpha=.12f)); drawPath(roof, Color(0xFFB8D6FF), style=Stroke(1.6f*unit))
        line(-64f,-18f,-64f,35f); line(64f,-18f,64f,35f);line(0f,14f,0f,67f)
        line(-64f,35f,0f,67f);line(0f,67f,64f,35f)
        line(14f,59f,14f,25f);line(14f,25f,32f,16f);line(32f,16f,32f,50f)
        line(-49f,0f,-17f,16f);line(-49f,0f,-49f,18f);line(-49f,18f,-17f,34f);line(-17f,16f,-17f,34f)
        line(-33f,8f,-33f,26f)
        line(-76f,46f,-12f,78f);line(-76f,42f,-76f,50f);line(-12f,74f,-12f,82f)
    }
}
