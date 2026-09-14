package com.manzili.hai

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Concept C is intentionally treated as a locked visual specification.
 * The upper hero is sampled from the approved reference itself so the
 * architectural image, proportions and visual hierarchy stay faithful.
 * Interaction hit targets are Compose layers placed over the approved art.
 */
class ConceptCActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        setContent {
            Hai360Theme {
                var openCore by remember { mutableStateOf(false) }
                if (openCore) Hai360App() else ConceptCLockedHome(onOpenCore = { openCore = true })
            }
        }
    }
}

@Composable
private fun ConceptCLockedHome(onOpenCore: () -> Unit) {
    val ink = Color(0xFF101924)
    val muted = Color(0xFF6A7380)
    val page = Color(0xFFF3F5F8)
    val lavender = Color(0xFFEEE9FF)
    val blue = Color(0xFFEAF2FF)
    val mint = Color(0xFFE6F8EF)

    Scaffold(
        containerColor = page,
        bottomBar = { ConceptCBottomBar(onOpenCore) }
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .background(page)
        ) {
            Box(Modifier.fillMaxWidth().height(410.dp)) {
                val bytes = remember { Base64.decode(CONCEPT_C_HERO_B64, Base64.DEFAULT) }
                val bitmap = remember { BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap() }
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 28.dp, vertical = 20.dp)
                        .fillMaxWidth()
                        .height(108.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(Modifier.weight(1f).fillMaxHeight().clickable { onOpenCore() })
                    Box(Modifier.weight(1f).fillMaxHeight().clickable { onOpenCore() })
                    Box(Modifier.weight(1f).fillMaxHeight().clickable { onOpenCore() })
                }
            }

            Spacer(Modifier.height(12.dp))

            Surface(
                modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth().height(78.dp),
                color = Color.White,
                shape = RoundedCornerShape(20.dp),
                shadowElevation = 2.dp
            ) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(modifier = Modifier.size(48.dp), color = Color(0xFF5A78FF), shape = CircleShape) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(28.dp))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("شاهد كيف يعمل", color = ink, fontSize = 14.sp, fontWeight = FontWeight.Black)
                        Text("فيديو توضيحي قصير", color = muted, fontSize = 10.sp)
                    }
                    Box(
                        Modifier.size(54.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFE6EBF1)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.HomeWork, null, tint = Color(0xFF798390))
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
            Text(
                "اكتشف إمكانيات منزلي HAI",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                color = ink,
                fontSize = 17.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(14.dp))

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ConceptCFeature("تحليل ذكي", "يعرف تفاصيل كل غرفة", Icons.Rounded.DocumentScanner, blue, Color(0xFF4B78F5), Modifier.weight(1f), onOpenCore)
                ConceptCFeature("نموذج ثلاثي الأبعاد", "دقيق وقابل للتعديل", Icons.Rounded.ViewInAr, lavender, Color(0xFF7456F6), Modifier.weight(1f), onOpenCore)
                ConceptCFeature("تقرير تفصيلي", "مساحات وقياسات", Icons.Rounded.Assessment, mint, Color(0xFF22A66F), Modifier.weight(1f), onOpenCore)
            }

            Spacer(Modifier.height(18.dp))

            Surface(
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(210.dp).clickable { onOpenCore() },
                color = Color.White,
                shape = RoundedCornerShape(22.dp),
                shadowElevation = 2.dp
            ) {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier.fillMaxWidth().height(150.dp).background(Color(0xFFF2EFEA)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.ViewInAr, null, tint = Color(0xFFB7AA98), modifier = Modifier.size(72.dp))
                    }
                    Row(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(60.dp).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("المعاينة ثلاثية الأبعاد", color = ink, fontSize = 14.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                        Surface(color = Color(0xFFEEF1F5), shape = RoundedCornerShape(50.dp)) {
                            Text("فتح", color = ink, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
        }
    }
}

@Composable
private fun ConceptCFeature(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    bg: Color,
    iconColor: Color,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.height(124.dp).clickable(onClick = onClick),
        color = Color.White,
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 1.dp
    ) {
        Column(
            Modifier.fillMaxSize().padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(color = bg, shape = CircleShape, modifier = Modifier.size(42.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = iconColor, modifier = Modifier.size(21.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(title, color = Color(0xFF17202C), fontSize = 11.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
            Spacer(Modifier.height(3.dp))
            Text(subtitle, color = Color(0xFF7A838D), fontSize = 8.sp, textAlign = TextAlign.Center, lineHeight = 10.sp)
        }
    }
}

@Composable
private fun ConceptCBottomBar(onOpenCore: () -> Unit) {
    NavigationBar(containerColor = Color.White, tonalElevation = 0.dp, modifier = Modifier.height(72.dp)) {
        NavigationBarItem(true, onOpenCore, { Icon(Icons.Rounded.Home, null) }, label = { Text("الرئيسية", fontSize = 9.sp) })
        NavigationBarItem(false, onOpenCore, { Icon(Icons.Rounded.Search, null) }, label = { Text("استكشف", fontSize = 9.sp) })
        NavigationBarItem(false, onOpenCore, {
            Surface(color = Color(0xFF7367F0), shape = CircleShape, shadowElevation = 8.dp, modifier = Modifier.size(48.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White) }
            }
        }, label = { })
        NavigationBarItem(false, onOpenCore, { Icon(Icons.Rounded.FolderOpen, null) }, label = { Text("مشاريعي", fontSize = 9.sp) })
        NavigationBarItem(false, onOpenCore, { Icon(Icons.Rounded.Person, null) }, label = { Text("حسابي", fontSize = 9.sp) })
    }
}

private const val CONCEPT_C_HERO_B64 = "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"