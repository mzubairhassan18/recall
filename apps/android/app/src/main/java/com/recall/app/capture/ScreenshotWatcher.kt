package com.recall.app.capture

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.recall.app.Session
import com.recall.app.config.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Watches MediaStore while the process is alive; [ScreenshotScanWorker] covers process death. */
class ScreenshotWatcher(context: Context) : ContentObserver(Handler(Looper.getMainLooper())) {
    private val appContext = context.applicationContext

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        if (uri == null) return
        ScreenshotScanner.inspect(appContext, uri)
    }
}

object ScreenshotScanner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun inspect(context: Context, uri: Uri) {
        scope.launch {
            runCatching { inspectNow(context, uri) }
        }
    }

    private fun inspectNow(context: Context, uri: Uri) {
        val config = AppConfig(context)
        if (config.screenshotMode == AppConfig.MODE_OFF) return

        val resolver = context.contentResolver
        val projection = projection()
        val row = resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return
            val name = string(cursor, MediaStore.Images.Media.DISPLAY_NAME)
            val path = string(cursor, pathColumn())
            val added = long(cursor, MediaStore.Images.Media.DATE_ADDED) * 1000L
            Triple(name, path, added)
        } ?: return

        val (name, path, added) = row
        if (!isScreenshot(name, path)) return
        if (added <= config.lastScreenshotSeen) return
        config.lastScreenshotSeen = added
        ScreenshotProposals.onScreenshot(context, uri)
    }

    /** Scans recent screenshots; first run starts from "now" so old shots are not proposed. */
    fun scanRecent(context: Context) {
        scope.launch { runCatching { scanRecentNow(context) } }
    }

    suspend fun scanRecentNow(context: Context) = kotlinx.coroutines.withContext(Dispatchers.IO) {
        runCatching {
            val config = AppConfig(context)
            if (config.screenshotMode == AppConfig.MODE_OFF) return@runCatching
            val seen = config.lastScreenshotSeen
            if (seen == 0L) {
                config.lastScreenshotSeen = System.currentTimeMillis()
                return@runCatching
            }
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = projection()
            val selection = "${MediaStore.Images.Media.DATE_ADDED} > ?"
            val args = arrayOf((seen / 1000L).toString())
            context.contentResolver.query(
                collection,
                projection,
                selection,
                args,
                "${MediaStore.Images.Media.DATE_ADDED} ASC",
            )?.use { cursor ->
                var newest = seen
                while (cursor.moveToNext()) {
                    val name = string(cursor, MediaStore.Images.Media.DISPLAY_NAME)
                    val path = string(cursor, pathColumn())
                    val added = long(cursor, MediaStore.Images.Media.DATE_ADDED) * 1000L
                    if (!isScreenshot(name, path)) continue
                    newest = maxOf(newest, added)
                    val id = long(cursor, MediaStore.Images.Media._ID)
                    val uri = ContentUris.withAppendedId(collection, id)
                    ScreenshotProposals.onScreenshot(context, uri)
                }
                config.lastScreenshotSeen = newest
            }
        }
    }

    fun isScreenshot(name: String, path: String): Boolean =
        name.contains("screenshot", ignoreCase = true) ||
            path.contains("screenshot", ignoreCase = true)

    private fun projection(): Array<String> = buildList {
        add(MediaStore.Images.Media._ID)
        add(MediaStore.Images.Media.DISPLAY_NAME)
        add(MediaStore.Images.Media.DATE_ADDED)
        add(pathColumn())
    }.toTypedArray()

    private fun pathColumn(): String =
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            MediaStore.Images.Media.RELATIVE_PATH
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.DATA
        }

    private fun string(cursor: android.database.Cursor, column: String): String {
        val index = cursor.getColumnIndex(column)
        return if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else ""
    }

    private fun long(cursor: android.database.Cursor, column: String): Long {
        val index = cursor.getColumnIndex(column)
        return if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else 0L
    }
}

object ScreenshotProposals {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun onScreenshot(context: Context, uri: Uri) {
        val config = AppConfig(context)
        val mode = config.screenshotMode
        if (mode == AppConfig.MODE_OFF) return

        if (mode == AppConfig.MODE_ALLOWLIST) {
            val foreground = UsageAccess.foregroundPackage(context)
            if (foreground != null && foreground in config.allowlist) {
                if (Session.isUnlocked()) {
                    scope.launch {
                        if (CaptureSaver.saveScreenshot(context, uri)) {
                            ScreenshotNotifier.cancel(context, uri)
                        }
                    }
                } else {
                    ScreenshotNotifier.notifyLocked(context, uri)
                }
                return
            }
            // Allowlist mode intentionally ignores screenshots from every other app.
            return
        }

        if (mode == AppConfig.MODE_HOLD) config.addPending(uri.toString())
        ScreenshotNotifier.propose(context, uri, held = mode == AppConfig.MODE_HOLD)
    }
}
