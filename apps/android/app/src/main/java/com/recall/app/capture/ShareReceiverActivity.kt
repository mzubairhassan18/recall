package com.recall.app.capture

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.recall.app.MainActivity
import com.recall.app.Session
import com.recall.core.CapturePayload
import kotlinx.coroutines.launch
import java.util.Locale

/** Handles ACTION_SEND for text and images. A locked vault hands the capture to MainActivity. */
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val type = intent.type.orEmpty()
        val text = if (type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        } else {
            null
        }
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()?.ifBlank { null }
        val uri = if (type.startsWith("image/")) parcelableUri(intent, Intent.EXTRA_STREAM) else null

        if (text.isNullOrEmpty() && uri == null) {
            finish()
            return
        }

        if (!Session.isUnlocked()) {
            PendingShare.set(PendingShare.Payload(type, text, subject, uri))
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            Toast.makeText(this, "Unlock Recall to save this capture.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        lifecycleScope.launch {
            val saved = when {
                uri != null -> CaptureSaver.saveScreenshot(this@ShareReceiverActivity, uri)
                !text.isNullOrEmpty() -> CaptureSaver.save(
                    this@ShareReceiverActivity,
                    payloadForText(text, subject),
                )
                else -> false
            }
            Toast.makeText(
                this@ShareReceiverActivity,
                if (saved) "Saved to Recall" else "Could not save",
                Toast.LENGTH_SHORT,
            ).show()
            finish()
        }
    }

    @Suppress("DEPRECATION")
    private fun parcelableUri(intent: Intent, key: String): Uri? =
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(key, Uri::class.java)
        } else {
            intent.getParcelableExtra(key)
        }
}

private val URL_PATTERN = Regex("https?://\\S+")
private val GENERIC_SUBJECTS = setOf(
    "text",
    "plain text",
    "data",
    "clipboard",
    "copied text",
    "content",
)

private fun normalizedSubject(subject: String?): String? = subject
    ?.trim()
    ?.takeIf { it.isNotEmpty() && it.lowercase(Locale.ROOT) !in GENERIC_SUBJECTS }

internal fun payloadForText(text: String, subject: String?, sourceUrl: String? = null): CapturePayload {
    val directUrl = text.takeIf { Regex("^https?://\\S+$").matches(it) }
    val referenceUrl = sourceUrl
        ?: directUrl
        ?: URL_PATTERN.find(text)?.value?.trimEnd('.', ',', ';', '!', '?', ')', ']')
    val title = normalizedSubject(subject)
    return if (directUrl != null) {
        CapturePayload(
            type = "url",
            url = directUrl,
            title = title ?: runCatching { Uri.parse(directUrl).host }.getOrNull() ?: directUrl,
        )
    } else {
        CapturePayload(
            type = "text",
            title = title ?: text.lineSequence().firstOrNull()?.take(120),
            text = text,
            url = referenceUrl,
        )
    }
}
