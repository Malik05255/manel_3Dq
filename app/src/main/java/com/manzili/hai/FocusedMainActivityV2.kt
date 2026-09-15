package com.manzili.hai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.manzili.hai.data.ReaderLearningNotifier

/**
 * Production launcher. The legacy card-based surface was intentionally removed; all active UI now
 * starts from the HAI 360 architectural shell.
 */
class FocusedMainActivityV2 : ComponentActivity() {
    private var correctionReceiverRegistered = false

    private val correctionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ReaderLearningNotifier.ACTION_CORRECTION_READY || isFinishing) return
            startActivity(
                Intent(this@FocusedMainActivityV2, ReaderLearningActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Hai360App() }
    }

    override fun onStart() {
        super.onStart()
        if (!correctionReceiverRegistered) {
            val filter = IntentFilter(ReaderLearningNotifier.ACTION_CORRECTION_READY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(correctionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(correctionReceiver, filter)
            }
            correctionReceiverRegistered = true
        }
    }

    override fun onStop() {
        if (correctionReceiverRegistered) {
            unregisterReceiver(correctionReceiver)
            correctionReceiverRegistered = false
        }
        super.onStop()
    }
}
