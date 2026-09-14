package com.manzili.hai.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.manzili.hai.data.PendingAnalysisStore
import com.manzili.hai.engine.MultiFloorGeometryEngine
import com.manzili.hai.engine.RemoteFloorplanEvidenceClient
import com.manzili.hai.engine.SaudiProjectTypeEngine
import kotlinx.coroutines.CancellationException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Durable floor-plan analysis. WorkManager keeps this job alive independently from the Activity,
 * so leaving the app or opening another app does not cancel the analysis.
 */
class FloorplanAnalysisWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val sourceRaw = inputData.getString(KEY_SOURCE_URI)
            ?: return Result.failure(workDataOf(KEY_ERROR to "مصدر المخطط غير موجود"))
        val source = runCatching { Uri.parse(sourceRaw) }.getOrNull()
            ?: return Result.failure(workDataOf(KEY_ERROR to "رابط المخطط غير صالح"))
        val typeRaw = inputData.getString(KEY_PROJECT_TYPE)
        val projectType = SaudiProjectTypeEngine.Type.entries.firstOrNull { it.name == typeRaw }
            ?: SaudiProjectTypeEngine.Type.VILLA_TWO
        val maxPages = inputData.getInt(KEY_MAX_PAGES, 8).coerceIn(1, 12)
        val pendingStore = PendingAnalysisStore(applicationContext)

        setForeground(foregroundInfo(0, "تهيئة تحليل المخطط"))
        setProgress(workDataOf(KEY_PROGRESS to 0, KEY_LABEL to "تهيئة تحليل المخطط"))

        try {
            val remote = RemoteFloorplanEvidenceClient(applicationContext)
            val result = remote.analyze(source, maxPdfPages = maxPages) { update ->
                val percent = update.percent.coerceIn(0, 100)
                setProgress(workDataOf(KEY_PROGRESS to percent, KEY_LABEL to update.label))
                setForeground(foregroundInfo(percent, update.label))
            }

            setProgress(workDataOf(KEY_PROGRESS to 98, KEY_LABEL to "تثبيت الهندسة المقروءة"))
            setForeground(foregroundInfo(98, "تثبيت الهندسة المقروءة"))

            val plan = result.toFloorPlan(title = "مخطط مستورد")
            val typed = SaudiProjectTypeEngine.apply(plan, projectType)
            val normalized = MultiFloorGeometryEngine.persistActive(
                MultiFloorGeometryEngine.normalize(typed)
            )
            check(pendingStore.saveResult(id, normalized)) {
                "تم استبدال مهمة التحليل بمهمة أحدث"
            }

            setProgress(workDataOf(KEY_PROGRESS to 100, KEY_LABEL to "اكتمل تحليل المخطط"))
            return Result.success(workDataOf(KEY_PROGRESS to 100, KEY_LABEL to "اكتمل تحليل المخطط"))
        } catch (cancelled: CancellationException) {
            pendingStore.clear(id)
            throw cancelled
        } catch (failure: Throwable) {
            val message = failure.message?.takeIf { it.isNotBlank() } ?: "تعذر تحليل المخطط سحابيًا"
            if (runAttemptCount < 1 && isTransient(message)) {
                setProgress(workDataOf(KEY_PROGRESS to 18, KEY_LABEL to "إعادة الاتصال بخدمة التحليل"))
                return Result.retry()
            }
            pendingStore.markFailure(id, message)
            return Result.failure(workDataOf(KEY_ERROR to message))
        }
    }

    private fun foregroundInfo(progress: Int, label: String): ForegroundInfo {
        ensureChannel()
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("منزلي HAI — تحليل المخطط")
            .setContentText(label)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$label — يمكنك استخدام الجوال بشكل طبيعي وسيستمر التحليل في الخلفية."))
            .setOnlyAlertOnce(true)
            .setOngoing(progress < 100)
            .setSilent(true)
            .setProgress(100, progress.coerceIn(0, 100), false)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "إلغاء", cancelIntent)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(Service.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "تحليل المخططات",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "يبقي تحليل المخطط السحابي مستمرًا عند مغادرة التطبيق"
                setShowBadge(false)
            }
        )
    }

    private fun isTransient(message: String): Boolean {
        val value = message.lowercase()
        return value.contains("http 5") ||
            value.contains("تعذر الاتصال") ||
            value.contains("تعذر الوصول") ||
            value.contains("temporarily") ||
            value.contains("timeout") ||
            value.contains("unreachable")
    }

    companion object {
        const val UNIQUE_WORK_NAME = "hai-floorplan-analysis"
        const val KEY_SOURCE_URI = "source_uri"
        const val KEY_PROJECT_TYPE = "project_type"
        const val KEY_MAX_PAGES = "max_pages"
        const val KEY_PROGRESS = "progress"
        const val KEY_LABEL = "label"
        const val KEY_ERROR = "error"

        private const val CHANNEL_ID = "hai_floorplan_analysis"
        private const val NOTIFICATION_ID = 4107

        fun enqueue(
            context: Context,
            source: Uri,
            projectType: SaudiProjectTypeEngine.Type,
            maxPages: Int = 8,
        ): UUID {
            val request = request(source, projectType, maxPages)
            PendingAnalysisStore(context).begin(request.id, source, projectType)
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
            return request.id
        }

        fun request(
            source: Uri,
            projectType: SaudiProjectTypeEngine.Type,
            maxPages: Int = 8,
        ): OneTimeWorkRequest = OneTimeWorkRequestBuilder<FloorplanAnalysisWorker>()
            .setInputData(
                workDataOf(
                    KEY_SOURCE_URI to source.toString(),
                    KEY_PROJECT_TYPE to projectType.name,
                    KEY_MAX_PAGES to maxPages.coerceIn(1, 12),
                )
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .addTag(UNIQUE_WORK_NAME)
            .build()
    }
}
