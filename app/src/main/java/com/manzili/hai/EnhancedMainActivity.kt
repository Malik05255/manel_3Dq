package com.manzili.hai

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContentColor
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.manzili.hai.model.FloorPlan

private val EnhancedSand = Color(0xFFF7F4EE)
private val EnhancedPaper = Color(0xFFFFFEFA)
private val EnhancedInk = Color(0xFF20211E)
private val EnhancedBronze = Color(0xFF9A7447)
private val EnhancedDeep = Color(0xFF27312C)

class EnhancedMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { EnhancedManziliApp() }
    }
}

@Composable
private fun EnhancedManziliApp() {
    val nav = rememberNavController()
    var plan by remember { mutableStateOf<FloorPlan?>(null) }
    var sourceUri by remember { mutableStateOf<Uri?>(null) }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = EnhancedDeep,
            secondary = EnhancedBronze,
            background = EnhancedSand,
            surface = EnhancedPaper,
            onPrimary = Color.White,
            onSurface = EnhancedInk
        )
    ) {
        CompositionLocalProvider(
            LocalLayoutDirection provides LayoutDirection.Rtl,
            LocalContentColor provides EnhancedInk
        ) {
            NavHost(navController = nav, startDestination = "home") {
                composable("home") { Home(nav) }
                composable("build") { BuildChoice(nav) }
                composable("import") { ImportPlan(nav, sourceUri, { sourceUri = it }, { plan = it }) }
                composable("new") { NewProject(nav) { plan = it } }
                composable("editor") { EnhancedEditor(nav, plan) { plan = it } }
                composable("settings") { AiSettings(nav) }
            }
        }
    }
}
