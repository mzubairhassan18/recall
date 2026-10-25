package com.recall.app.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.recall.app.Session
import com.recall.app.config.AppConfig
import com.recall.app.widget.RecallWidgetProvider
import java.util.concurrent.TimeUnit

/**
 * Background sync only runs while the app is unlocked: the passphrase-derived key is deliberately
 * never persisted, so a locked process cannot decrypt anything. A locked run is a no-op.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val repository = Session.repository() ?: return Result.success()
        val rawKey = Session.rawKey() ?: return Result.success()
        return try {
            SyncEngine(AppConfig(applicationContext), repository, rawKey).sync()
            RecallWidgetProvider.refresh(applicationContext)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        } finally {
            rawKey.fill(0)
        }
    }

    companion object {
        private const val PERIODIC = "recall-sync-periodic"
        private const val ONCE = "recall-sync-once"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun runOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
