package com.recall.app.config

import android.content.Context
import java.util.Base64

data class StoredConfig(
    val username: String?,
    val userId: String?,
    val salt: String?,
    val fingerprint: String?,
    val relayUrl: String?,
    val localOnly: Boolean,
)

data class PendingScreenshot(val uri: String, val addedAt: Long)

/**
 * Non-secret settings plus the relay secret, which is wrapped by [KeystoreSecretBox]. The
 * passphrase is never written here in plaintext; the optional biometric vault and PIN vault store
 * only Keystore-wrapped copies so daily unlock can be quick without weakening the data key.
 */
class AppConfig(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("recall", Context.MODE_PRIVATE)
    private val secretBox = KeystoreSecretBox()

    val isConfigured: Boolean
        get() = prefs.getString(KEY_FINGERPRINT, null) != null

    fun load(): StoredConfig = StoredConfig(
        username = prefs.getString(KEY_USERNAME, null),
        userId = prefs.getString(KEY_USER_ID, null),
        salt = prefs.getString(KEY_SALT, null),
        fingerprint = prefs.getString(KEY_FINGERPRINT, null),
        relayUrl = prefs.getString(KEY_RELAY_URL, null)?.takeIf { it.isNotBlank() },
        localOnly = prefs.getBoolean(KEY_LOCAL_ONLY, true),
    )

    fun saveIdentity(
        username: String,
        userId: String,
        salt: String,
        fingerprint: String,
        relayUrl: String?,
        localOnly: Boolean,
    ) {
        prefs.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_USER_ID, userId)
            .putString(KEY_SALT, salt)
            .putString(KEY_FINGERPRINT, fingerprint)
            .putString(KEY_RELAY_URL, relayUrl)
            .putBoolean(KEY_LOCAL_ONLY, localOnly)
            .apply()
    }

    var relaySecret: String?
        get() {
            val token = prefs.getString(KEY_RELAY_SECRET, null) ?: return null
            return if (token.startsWith("ks:")) {
                secretBox.decrypt(token.removePrefix("ks:"))
            } else if (token.startsWith("b64:")) {
                runCatching { String(Base64.getDecoder().decode(token.removePrefix("b64:")), Charsets.UTF_8) }
                    .getOrNull()
            } else {
                token
            }
        }
        set(value) {
            if (value.isNullOrBlank()) {
                prefs.edit().remove(KEY_RELAY_SECRET).apply()
                return
            }
            val wrapped = secretBox.encrypt(value)
            val token = if (wrapped != null) {
                "ks:$wrapped"
            } else {
                // Documented fallback for devices without a usable Android Keystore. It is not
                // encryption; it only avoids accidental shoulder-surfing of the raw value.
                "b64:" + Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
            }
            prefs.edit().putString(KEY_RELAY_SECRET, token).apply()
        }

    var syncCursor: Long
        get() = prefs.getLong(KEY_CURSOR, 0L)
        set(value) {
            prefs.edit().putLong(KEY_CURSOR, value).apply()
        }

    var lastSyncAt: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_SYNC, value).apply()
        }

    var screenshotMode: String
        get() = prefs.getString(KEY_SCREENSHOT_MODE, MODE_OFF) ?: MODE_OFF
        set(value) {
            prefs.edit().putString(KEY_SCREENSHOT_MODE, value).apply()
        }

    var allowlist: Set<String>
        get() = prefs.getStringSet(KEY_ALLOWLIST, emptySet())?.toSet() ?: emptySet()
        set(value) {
            prefs.edit().putStringSet(KEY_ALLOWLIST, value).apply()
        }

    var biometricEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC, false)
        set(value) {
            prefs.edit().putBoolean(KEY_BIOMETRIC, value).apply()
        }

    var lastScreenshotSeen: Long
        get() = prefs.getLong(KEY_LAST_SCREENSHOT, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_SCREENSHOT, value).apply()
        }

    fun pendingScreenshots(): List<PendingScreenshot> = prefs.getStringSet(KEY_PENDING, emptySet())
        .orEmpty()
        .mapNotNull { entry ->
            val parts = entry.split("|")
            if (parts.size != 2) return@mapNotNull null
            val at = parts[1].toLongOrNull() ?: return@mapNotNull null
            PendingScreenshot(parts[0], at)
        }
        .sortedBy { it.addedAt }

    fun addPending(uri: String) {
        prunePending()
        val entries = prefs.getStringSet(KEY_PENDING, emptySet()).orEmpty().toMutableSet()
        entries += "$uri|${System.currentTimeMillis()}"
        prefs.edit().putStringSet(KEY_PENDING, entries).apply()
    }

    fun removePending(uri: String) {
        val entries = prefs.getStringSet(KEY_PENDING, emptySet()).orEmpty()
            .filterNot { it.startsWith("$uri|") }
            .toSet()
        prefs.edit().putStringSet(KEY_PENDING, entries).apply()
    }

    fun clearPending() {
        prefs.edit().remove(KEY_PENDING).apply()
    }

    fun prunePending(maxAgeMs: Long = HOLD_LIFETIME_MS) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        val entries = prefs.getStringSet(KEY_PENDING, emptySet()).orEmpty()
            .filter { entry ->
                val at = entry.substringAfterLast("|").toLongOrNull()
                at != null && at >= cutoff
            }
            .toSet()
        if (entries.size != prefs.getStringSet(KEY_PENDING, emptySet()).orEmpty().size) {
            prefs.edit().putStringSet(KEY_PENDING, entries).apply()
        }
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val MODE_OFF = "off"
        const val MODE_ASK = "ask"
        const val MODE_HOLD = "hold"
        const val MODE_ALLOWLIST = "allowlist"
        const val HOLD_LIFETIME_MS = 60L * 60L * 1000L

        private const val KEY_USERNAME = "username"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_SALT = "salt"
        private const val KEY_FINGERPRINT = "fingerprint"
        private const val KEY_RELAY_URL = "relay_url"
        private const val KEY_LOCAL_ONLY = "local_only"
        private const val KEY_RELAY_SECRET = "relay_secret"
        private const val KEY_CURSOR = "sync_cursor"
        private const val KEY_LAST_SYNC = "last_sync"
        private const val KEY_SCREENSHOT_MODE = "screenshot_mode"
        private const val KEY_ALLOWLIST = "screenshot_allowlist"
        private const val KEY_BIOMETRIC = "biometric_enabled"
        private const val KEY_LAST_SCREENSHOT = "last_screenshot_seen"
        private const val KEY_PENDING = "pending_screenshots"
    }
}
