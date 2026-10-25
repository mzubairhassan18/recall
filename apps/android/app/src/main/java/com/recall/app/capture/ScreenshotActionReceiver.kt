package com.recall.app.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.recall.app.Session
import com.recall.app.config.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ScreenshotActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.getStringExtra(ScreenshotNotifier.EXTRA_URI)?.let(Uri::parse) ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ScreenshotNotifier.ACTION_DISMISS -> {
                        AppConfig(context).removePending(uri.toString())
                        ScreenshotNotifier.cancel(context, uri)
                    }
                    ScreenshotNotifier.ACTION_SAVE -> {
                        val config = AppConfig(context)
                        if (!Session.isUnlocked()) {
                            ScreenshotNotifier.notifyLocked(context, uri)
                        } else if (CaptureSaver.saveScreenshot(context, uri)) {
                            config.removePending(uri.toString())
                            ScreenshotNotifier.cancel(context, uri)
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
