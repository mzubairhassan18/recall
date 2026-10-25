package com.recall.app.capture

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.recall.app.config.AppConfig
import java.util.concurrent.TimeUnit

/** Periodic safety net for the in-process MediaStore observer. */
class ScreenshotScanWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        AppConfig(applicationContext).prunePending()
        ScreenshotScanner.scanRecentNow(applicationContext)
        return Result.success()
    }

    companion object {
        private const val NAME = "recall-screenshot-scan"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ScreenshotScanWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.NOT_REQUIRED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
