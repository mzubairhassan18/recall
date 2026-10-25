package com.recall.app

import android.app.Application
import android.provider.MediaStore
import com.recall.app.capture.ScreenshotNotifier
import com.recall.app.capture.ScreenshotScanWorker
import com.recall.app.capture.ScreenshotWatcher
import com.recall.app.config.AppConfig
import com.recall.app.sync.SyncWorker

class RecallApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ScreenshotNotifier.ensureChannel(this)
        val config = AppConfig(this)
        config.prunePending()
        SyncWorker.schedule(this)
        ScreenshotScanWorker.schedule(this)
        // Registered unconditionally; the handler returns immediately while proposals are off.
        runCatching {
            contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                ScreenshotWatcher(this),
            )
        }
    }
}
