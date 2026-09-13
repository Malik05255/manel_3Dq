package com.manzili.hai

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Exercise real navigation and scrolling, saving native renders for visual review. */
class StudioNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<FocusedMainActivityV2>()

    private fun capture(name: String) {
        compose.waitForIdle()
        val folder = File(compose.activity.getExternalFilesDir(null), "studio-screenshots").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun homeImportAndSettingsAreReachable() {
        compose.onNodeWithText("رفع مخطط").assertIsDisplayed()
        capture("01-home")
        compose.onNodeWithText("رفع مخطط").performClick()
        compose.onNodeWithText("فيلا دور واحد").assertIsDisplayed()
        capture("02-project-type")
        compose.onNodeWithText("استراحة").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("فيلا دور واحد").performScrollTo().performClick()
        compose.onNodeWithText("أضف مخطط بيتك").assertIsDisplayed()
        capture("03-upload")
        compose.onNodeWithContentDescription("رجوع").performClick()
        compose.onNodeWithContentDescription("رجوع").performClick()
        compose.onNodeWithText("مشاريعي").performClick()
        compose.onNodeWithText("لا توجد مشاريع بعد").assertIsDisplayed()
        capture("04-projects")
        compose.onNodeWithContentDescription("رجوع").performClick()
        compose.onNodeWithText("الإعدادات").performClick()
        compose.onNodeWithText("إعدادات الاتصال المتقدمة").assertIsDisplayed()
        capture("05-settings")
        compose.onNodeWithText("إعدادات الاتصال المتقدمة").performClick()
        compose.onNodeWithText("حفظ الاتصال").assertIsDisplayed()
        capture("06-connection")
    }
}
