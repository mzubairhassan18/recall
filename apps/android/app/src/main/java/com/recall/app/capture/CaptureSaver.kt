package com.recall.app.capture

import android.content.Context
import android.net.Uri
import com.recall.app.Session
import com.recall.app.sync.SyncWorker
import com.recall.app.widget.RecallWidgetProvider
import com.recall.core.CapturePayload

object CaptureSaver {
    suspend fun save(context: Context, payload: CapturePayload, thumbnail: ByteArray? = null): Boolean {
        val repository = Session.repository() ?: return false
        val saved = repository.insert(payload, System.currentTimeMillis(), thumbnail)
        if (saved) {
            RecallWidgetProvider.refresh(context)
            SyncWorker.runOnce(context)
        }
        return saved
    }

    /** OCR + thumbnail + save, all on a background dispatcher by the caller. */
    suspend fun saveScreenshot(context: Context, uri: Uri): Boolean {
        val bitmap = OcrEngine.loadBitmap(context, uri) ?: return false
        return try {
            val thumbnail = OcrEngine.thumbnail(bitmap)
            val text = runCatching { OcrEngine.recognize(bitmap) }.getOrDefault("")
            save(
                context,
                CapturePayload(
                    type = "screenshot",
                    title = "Screenshot",
                    ocrText = text,
                ),
                thumbnail,
            )
        } finally {
            bitmap.recycle()
        }
    }
}
