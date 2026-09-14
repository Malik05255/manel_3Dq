package com.manzili.hai

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Locked implementation of the approved Concept C.
 * Geometry is intentionally fixed to the approved mobile reference instead of
 * being reinterpreted through generic Material layouts.
 */
class ConceptCActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Concept C visibly includes the real Android status/navigation bars.
        window.statusBarColor = android.graphics.Color.rgb(241, 245, 249)
        window.navigationBarColor = android.graphics.Color.WHITE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        setContent {
            Hai360Theme {
                var openCore by remember { mutableStateOf(false) }
                if (openCore) {
                    Hai360App()
                } else {
                    ConceptCLockedHome(onOpenCore = { openCore = true })
                }
            }
        }
    }
}

@Composable
private fun ConceptCLockedHome(onOpenCore: () -> Unit) {
    val page = Color(0xFFF2F5F9)
    val ink = Color(0xFF101820)

    Scaffold(
        containerColor = page,
        bottomBar = { ConceptCBottomBar(onOpenCore) }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .background(page)
        ) {
            ConceptCHero(onOpenCore)
            Spacer(Modifier.height(10.dp))
            ConceptCVideoCard(onOpenCore)
            Spacer(Modifier.height(18.dp))
            Text(
                text = "اكتشف إمكانيات منزلي HAI",
                modifier = Modifier.fillMaxWidth(),
                color = ink,
                fontSize = 16.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            ConceptCFeatureRow(onOpenCore)
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun ConceptCHero(onOpenCore: () -> Unit) {
    val bytes = remember { Base64.decode(CONCEPT_C_HOUSE_B64, Base64.DEFAULT) }
    val house = remember { BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(333.dp)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFFEAF2F8),
                        Color(0xFFF7FAFC),
                        Color(0xFFE9EEF2)
                    )
                )
            )
    ) {
        Image(
            bitmap = house,
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .height(196.dp)
                .align(Alignment.BottomCenter)
        )

        Icon(
            imageVector = Icons.Rounded.ArrowBack,
            contentDescription = "رجوع",
            tint = Color(0xFF17212B),
            modifier = Modifier
                .padding(start = 27.dp, top = 18.dp)
                .size(22.dp)
                .align(Alignment.TopStart)
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "منزلي",
                    color = Color(0xFF111920),
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Black
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    "HAI",
                    color = Color(0xFF66717E),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(Modifier.height(5.dp))
            Text(
                "مستقبلك المعماري يبدأ من هنا",
                color = Color(0xFF202830),
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold
            )
        }

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Surface(
                modifier = Modifier
                    .padding(horizontal = 27.dp)
                    .fillMaxWidth()
                    .height(99.dp)
                    .align(Alignment.BottomCenter),
                color = Color.White.copy(alpha = 0.96f),
                shape = RoundedCornerShape(14.dp),
                shadowElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ConceptCAction(
                        icon = Icons.Rounded.FileUpload,
                        label = "رفع مخطط",
                        iconTint = Color(0xFF1E2730),
                        onClick = onOpenCore
                    )
                    ConceptCAction(
                        icon = Icons.Rounded.Image,
                        label = "من المعرض",
                        iconTint = Color(0xFF4672DA),
                        halo = Color(0xFFE8F0FF),
                        onClick = onOpenCore
                    )
                    ConceptCAction(
                        icon = Icons.Rounded.PhotoCamera,
                        label = "تصوير",
                        iconTint = Color(0xFF26323C),
                        onClick = onOpenCore
                    )
                }
            }
        }
    }
}

@Composable
private fun ConceptCAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    iconTint: Color,
    halo: Color = Color.Transparent,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(78.dp)
            .fillMaxHeight()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(43.dp)
                .clip(CircleShape)
                .background(halo),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = label,
            color = Color(0xFF17212B),
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ConceptCVideoCard(onClick: () -> Unit) {
    val bytes = remember { Base64.decode(CONCEPT_C_HOUSE_B64, Base64.DEFAULT) }
    val house = remember { BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap() }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 27.dp)
                .fillMaxWidth()
                .height(78.dp)
                .clickable(onClick = onClick),
            color = Color.White.copy(alpha = 0.97f),
            shape = RoundedCornerShape(14.dp),
            shadowElevation = 1.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    bitmap = house,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 82.dp, height = 45.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(Modifier.width(11.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        "شاهد كيف يعمل",
                        color = Color(0xFF111920),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.End
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "فيديو توضيحي قصير",
                        color = Color(0xFF8B95A1),
                        fontSize = 8.5.sp,
                        textAlign = TextAlign.End
                    )
                }
                Spacer(Modifier.width(10.dp))
                Surface(
                    modifier = Modifier.size(38.dp),
                    color = Color(0xFF5D82F4),
                    shape = CircleShape
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.PlayArrow,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(23.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConceptCFeatureRow(onClick: () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 27.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            ConceptCFeature(
                title = "تحليل ذكي",
                subtitle = "يعرف تفاصيل كل غرفة",
                icon = Icons.Rounded.DocumentScanner,
                halo = Color(0xFFEAF2FF),
                iconColor = Color(0xFF4F7BE7),
                modifier = Modifier.weight(1f),
                onClick = onClick
            )
            ConceptCFeature(
                title = "نموذج ثلاثي الأبعاد",
                subtitle = "دقيق وقابل للتعديل",
                icon = Icons.Rounded.ViewInAr,
                halo = Color(0xFFF0EBFF),
                iconColor = Color(0xFF7A5CF4),
                modifier = Modifier.weight(1f),
                onClick = onClick
            )
            ConceptCFeature(
                title = "تقرير تفصيلي",
                subtitle = "مساحات وقياسات",
                icon = Icons.Rounded.Assessment,
                halo = Color(0xFFE9F8F0),
                iconColor = Color(0xFF28AD79),
                modifier = Modifier.weight(1f),
                onClick = onClick
            )
        }
    }
}

@Composable
private fun ConceptCFeature(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    halo: Color,
    iconColor: Color,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .height(108.dp)
            .clickable(onClick = onClick),
        color = Color.White,
        shape = RoundedCornerShape(13.dp),
        shadowElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                modifier = Modifier.size(39.dp),
                color = halo,
                shape = CircleShape
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = iconColor, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.height(7.dp))
            Text(
                title,
                color = Color(0xFF18212B),
                fontSize = 9.2.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                lineHeight = 11.sp,
                maxLines = 2
            )
            Spacer(Modifier.height(3.dp))
            Text(
                subtitle,
                color = Color(0xFF8A939D),
                fontSize = 7.2.sp,
                textAlign = TextAlign.Center,
                lineHeight = 9.sp,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun ConceptCBottomBar(onClick: () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Surface(
            color = Color.White,
            shadowElevation = 4.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(62.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ConceptCNavItem(
                    icon = Icons.Rounded.Home,
                    label = "الرئيسية",
                    selected = true,
                    modifier = Modifier.weight(1f),
                    onClick = onClick
                )
                ConceptCNavItem(
                    icon = Icons.Rounded.Search,
                    label = "استكشف",
                    selected = false,
                    modifier = Modifier.weight(1f),
                    onClick = onClick
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(onClick = onClick),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier.size(45.dp),
                        color = Color(0xFF7362F3),
                        shape = CircleShape,
                        shadowElevation = 8.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.AutoAwesome,
                                null,
                                tint = Color.White,
                                modifier = Modifier.size(23.dp)
                            )
                        }
                    }
                }
                ConceptCNavItem(
                    icon = Icons.Rounded.FolderOpen,
                    label = "مشاريعي",
                    selected = false,
                    modifier = Modifier.weight(1f),
                    onClick = onClick
                )
                ConceptCNavItem(
                    icon = Icons.Rounded.PersonOutline,
                    label = "حسابي",
                    selected = false,
                    modifier = Modifier.weight(1f),
                    onClick = onClick
                )
            }
        }
    }
}

@Composable
private fun ConceptCNavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val color = if (selected) Color(0xFF13202B) else Color(0xFF87929C)
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            color = color,
            fontSize = 7.8.sp,
            fontWeight = if (selected) FontWeight.Black else FontWeight.Medium
        )
    }
}

private const val CONCEPT_C_HOUSE_B64 = "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"