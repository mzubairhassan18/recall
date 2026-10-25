package com.recall.app.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Optional biometric unlock. The passphrase is encrypted with an AES key that lives in the
 * Android Keystore and is bound to BIOMETRIC_STRONG; decryption only succeeds inside a successful
 * BiometricPrompt authentication. Enrolling a new fingerprint invalidates the key, which clears
 * the stored credential instead of silently weakening the guarantee.
 */
class BiometricVault(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("recall-biometric", Context.MODE_PRIVATE)

    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG,
        ) == BiometricManager.BIOMETRIC_SUCCESS

    fun hasCredential(): Boolean = prefs.contains(KEY_BLOB)

    /**
     * Encryption with an auth-bound key must happen inside a successful BiometricPrompt. The
     * caller initializes this cipher, shows the prompt with it as the CryptoObject, then passes the
     * authorized cipher to [storeWithCipher].
     */
    fun prepareEncrypt(): Cipher? = runCatching {
        Cipher.getInstance(TRANSFORMATION).also { it.init(Cipher.ENCRYPT_MODE, key()) }
    }.getOrNull()

    fun storeWithCipher(cipher: Cipher, passphrase: CharArray): Boolean = runCatching {
        val ciphertext = cipher.doFinal(passphrase.concatToString().toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(KEY_BLOB, encode(cipher.iv) + ":" + encode(ciphertext))
            .apply()
        true
    }.getOrDefault(false)

    /** Cipher to hand to BiometricPrompt; it is authorized by a successful authentication. */
    fun prepareDecrypt(): Cipher? = runCatching {
        val blob = prefs.getString(KEY_BLOB, null) ?: return null
        val parts = blob.split(":")
        if (parts.size != 2) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(128, decode(parts[0])),
        )
        cipher
    }.getOrNull()

    fun decrypt(cipher: Cipher): CharArray? = runCatching {
        val blob = prefs.getString(KEY_BLOB, null) ?: return null
        val ciphertext = decode(blob.split(":")[1])
        cipher.doFinal(ciphertext).toString(Charsets.UTF_8).toCharArray()
    }.getOrNull()

    fun clear() {
        prefs.edit().remove(KEY_BLOB).apply()
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS)
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }

        val builder = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(builder.build())
        return generator.generateKey()
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)

    companion object {
        private const val ALIAS = "recall-biometric-v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BLOB = "blob"
    }
}
