package com.manzili.hai

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent as activitySetContent
import androidx.compose.runtime.Composable

/**
 * Keeps MainActivity concise while making the Compose entry point explicit.
 */
fun ComponentActivity.setContent(content: @Composable () -> Unit) {
    this.activitySetContent(content = content)
}
