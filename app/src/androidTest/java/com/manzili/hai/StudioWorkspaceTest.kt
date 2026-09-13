package com.manzili.hai

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import com.manzili.hai.model.FloorPlan
import org.junit.Rule
import org.junit.Test
import java.io.File

class StudioWorkspaceTest {
    @get:Rule val compose = createComposeRule()
    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.getExternalFilesDir(null), "studio-screenshots").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun editToolsChangeGeometryAndUndoRestoresIt() {
        compose.setContent {
            StudioTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    ImportedProjectWorkspaceScreen(rememberNavController(), null,
                        FloorPlan(title = "اختبار المخطط", widthM = 20.0, heightM = 25.0, scaleConfidence = 100)) {}
                }
            }
        }
        compose.onNodeWithText("اعتماد ومتابعة").assertIsNotEnabled()
        capture("07-review")
        compose.onNodeWithText("٢  تعديل").performClick()
        compose.onNodeWithText("غرفة").performClick()
        compose.onNodeWithTag("workspace-canvas").performScrollTo().performTouchInput { click(center) }
        compose.onNodeWithText("تراجع").performScrollTo().assertIsEnabled()
        capture("08-editor")
        compose.onNodeWithText("تراجع").performClick()
        compose.onNodeWithText("تراجع").assertIsNotEnabled()
        compose.onNodeWithText("١  مراجعة").performClick()
        compose.onNodeWithText("اعتماد ومتابعة").assertIsNotEnabled()
    }
}
