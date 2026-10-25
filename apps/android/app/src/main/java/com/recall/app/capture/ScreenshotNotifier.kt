package com.recall.app.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.recall.app.MainActivity
import com.recall.app.R

object ScreenshotNotifier {
    const val CHANNEL = "screenshot-proposals"
    const val ACTION_SAVE = "com.recall.app.SAVE_SCREENSHOT"
    const val ACTION_DISMISS = "com.recall.app.DISMISS_SCREENSHOT"
    const val EXTRA_URI = "uri"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL,
            context.getString(R.string.notification_channel_capture),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Screenshot capture proposals"
        }
        manager.createNotificationChannel(channel)
    }

    fun propose(context: Context, uri: Uri, held: Boolean) {
        ensureChannel(context)
        val notification = base(context, uri)
            .setContentTitle(if (held) "Screenshot held for 60 minutes" else "Screenshot captured")
            .setContentText(if (held) "Save it to Recall before it expires." else "Save this screenshot to Recall?")
            .addAction(R.drawable.ic_notification, context.getString(R.string.screenshot_save), action(context, uri, ACTION_SAVE))
            .addAction(R.drawable.ic_notification, context.getString(R.string.screenshot_dismiss), action(context, uri, ACTION_DISMISS))
            .build()
        post(context, uri, notification)
    }

    fun notifyLocked(context: Context, uri: Uri) {
        ensureChannel(context)
        val notification = base(context, uri)
            .setContentTitle("Screenshot waiting")
            .setContentText("Unlock Recall to save it.")
            .build()
        post(context, uri, notification)
    }

    fun cancel(context: Context, uri: Uri) {
        NotificationManagerCompat.from(context).cancel(id(uri))
    }

    private fun base(context: Context, uri: Uri): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)

    private fun post(context: Context, uri: Uri, notification: android.app.Notification) {
        runCatching {
            NotificationManagerCompat.from(context).notify(id(uri), notification)
        }
    }

    private fun action(context: Context, uri: Uri, action: String): PendingIntent {
        val intent = Intent(context, ScreenshotActionReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_URI, uri.toString())
        return PendingIntent.getBroadcast(
            context,
            (action + uri.toString()).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun id(uri: Uri): Int = uri.toString().hashCode()
}
