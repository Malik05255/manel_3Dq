package com.manzili.hai.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.manzili.hai.ReaderLearningActivity

object ReaderLearningNotifier {
    fun notify(context: Context, candidateCount: Int) {
        if (candidateCount <= 0) return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "تحسين قارئ HAI",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "يعرض التصحيحات الجاهزة للتصدير بموافقة المستخدم لتحسين قارئ المخططات"
                    setShowBadge(true)
                }
            )
        }

        val intent = Intent(context, ReaderLearningActivity::class.java)
        val pending = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("HAI تعلّم من تصحيحك")
            .setContentText("لديك $candidateCount حالة مصححة. يمكنك تجهيزها للتدريب بموافقتك.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "تم حفظ التصحيح داخل جهازك فقط. اضغط هنا لمراجعته وتجهيز Dataset اختياري لتحسين قارئ HAI. لا يوجد رفع تلقائي."
                )
            )
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private const val CHANNEL_ID = "hai_reader_learning"
    private const val NOTIFICATION_ID = 4310
    private const val REQUEST_CODE = 4310
}
