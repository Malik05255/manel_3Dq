package com.manzili.hai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/**
 * Production launcher. The legacy card-based surface was intentionally removed; all active UI now
 * starts from the HAI 360 architectural shell.
 */
class FocusedMainActivityV2 : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Hai360App() }
    }
}
