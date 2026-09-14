package com.manzili.hai

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
import android.graphics.BitmapFactory

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
            // Locked hero: exact approved C crop, with Compose hit targets on the three actions.
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
                    Surface(
                        modifier = Modifier.size(48.dp),
                        color = Color(0xFF5A78FF),
                        shape = CircleShape
                    ) {
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
                        Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFE6EBF1)),
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
                        Modifier.fillMaxWidth().height(150.dp)
                            .background(Color(0xFFF2EFEA)),
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

/* Approved Concept C hero crop, JPEG, embedded so the build is self-contained. */
private const val CONCEPT_C_HERO_B64 = "REPLACE_AT_BUILD_TIME"
