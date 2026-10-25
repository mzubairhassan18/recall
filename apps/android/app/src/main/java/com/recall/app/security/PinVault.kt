package com.recall.app.security

import android.content.Context
import com.recall.app.config.KeystoreSecretBox
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * A four-digit daily unlock PIN. The PIN is a local gate, not the data-encryption key: the real
 * passphrase is wrapped by a hardware-backed Android Keystore key. This keeps cross-device sync
 * strong while making daily unlock quick. Wrong attempts are rate-limited and persisted so a
 * borrowed unlocked phone cannot be used to try all 10,000 combinations quickly.
 */
sealed interface PinCheck {
    data class Success(val passphrase: CharArray) : PinCheck
    data class Wrong(val attemptsLeft: Int, val retryAfterMs: Long) : PinCheck
    data object Unavailable : PinCheck
    data object NotConfigured : PinCheck
}

class PinVault(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("recall-pin", Context.MODE_PRIVATE)
    private val secretBox = KeystoreSecretBox()

    val hasPin: Boolean
        get() = prefs.contains(KEY_HASH) && prefs.contains(KEY_PASSPHRASE)

    fun setPin(pin: CharArray, passphrase: CharArray): Boolean {
        if (pin.size != PIN_LENGTH) return false
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = hash(pin, salt)
        val wrapped = secretBox.encrypt(passphrase.concatToString()) ?: return false
        prefs.edit()
            .putString(KEY_SALT, encode(salt))
            .putString(KEY_HASH, encode(hash))
            .putString(KEY_PASSPHRASE, wrapped)
            .putInt(KEY_ATTEMPTS, 0)
            .remove(KEY_LOCKOUT)
            .apply()
        return true
    }

    fun checkPin(pin: CharArray): PinCheck {
        if (!hasPin) return PinCheck.NotConfigured
        val now = System.currentTimeMillis()
        val lockedUntil = prefs.getLong(KEY_LOCKOUT, 0L)
        if (lockedUntil > now) {
            return PinCheck.Wrong(attemptsLeft = 0, retryAfterMs = lockedUntil - now)
        }

        val salt = decode(prefs.getString(KEY_SALT, null)) ?: return PinCheck.Unavailable
        val expected = decode(prefs.getString(KEY_HASH, null)) ?: return PinCheck.Unavailable
        val actual = hash(pin, salt)
        if (!MessageDigest.isEqual(expected, actual)) {
            val attempts = prefs.getInt(KEY_ATTEMPTS, 0) + 1
            val delay = if (attempts >= MAX_ATTEMPTS) {
                val factor = (attempts - MAX_ATTEMPTS).coerceAtMost(4)
                (BASE_DELAY_MS shl factor).coerceAtMost(MAX_DELAY_MS)
            } else {
                0L
            }
            val editor = prefs.edit().putInt(KEY_ATTEMPTS, attempts)
            if (delay > 0) editor.putLong(KEY_LOCKOUT, now + delay)
            editor.apply()
            return PinCheck.Wrong(
                attemptsLeft = (MAX_ATTEMPTS - attempts).coerceAtLeast(0),
                retryAfterMs = delay,
            )
        }

        val wrapped = prefs.getString(KEY_PASSPHRASE, null) ?: return PinCheck.Unavailable
        val passphrase = secretBox.decrypt(wrapped)?.toCharArray() ?: return PinCheck.Unavailable
        prefs.edit().putInt(KEY_ATTEMPTS, 0).remove(KEY_LOCKOUT).apply()
        return PinCheck.Success(passphrase)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun hash(pin: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin, salt, ITERATIONS, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(value: String?): ByteArray? {
        if (value == null) return null
        return runCatching { Base64.getDecoder().decode(value) }.getOrNull()
    }

    companion object {
        const val PIN_LENGTH = 4
        private const val SALT_BYTES = 16
        private const val ITERATIONS = 120_000
        private const val MAX_ATTEMPTS = 5
        private const val BASE_DELAY_MS = 30_000L
        private const val MAX_DELAY_MS = 5L * 60L * 1000L
        private const val KEY_SALT = "salt"
        private const val KEY_HASH = "hash"
        private const val KEY_PASSPHRASE = "passphrase"
        private const val KEY_ATTEMPTS = "attempts"
        private const val KEY_LOCKOUT = "lockout_until"
    }
}
