package com.recall.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Error carrying a stable machine-readable code; never contains secret material. */
class CryptoException(val code: String) : Exception(code)

/**
 * The Kotlin side of docs/wire-format.md. Every function here must match
 * packages/crypto-core byte-for-byte; scripts/crypto-parity.mjs enforces it.
 */
object RecallCrypto {
    const val PBKDF2_ITERATIONS = 600_000
    const val SALT_BYTES = 16
    const val KEY_BYTES = 32
    const val IV_BYTES = 12
    const val GCM_TAG_BYTES = 16
    const val ENVELOPE_VERSION = 1
    const val SYNC_IDENTITY_SALT = "recall-sync-v1"

    private const val GCM_TAG_BITS = 128
    private val random = SecureRandom()
    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /* ------------------------------------------------------------------ */
    /* KDF                                                                 */
    /* ------------------------------------------------------------------ */

    fun deriveKeyBytes(
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int = PBKDF2_ITERATIONS,
        dklen: Int = KEY_BYTES,
    ): ByteArray {
        if (passphrase.isEmpty()) throw CryptoException("invalid-passphrase")
        if (salt.isEmpty()) throw CryptoException("invalid-salt")
        require(iterations >= 1) { "invalid-iterations" }
        require(dklen >= 1) { "invalid-dklen" }

        val spec = PBEKeySpec(passphrase, salt, iterations, dklen * 8)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun deriveKeyBytes(
        passphrase: String,
        salt: ByteArray,
        iterations: Int = PBKDF2_ITERATIONS,
        dklen: Int = KEY_BYTES,
    ): ByteArray = deriveKeyBytes(passphrase.toCharArray(), salt, iterations, dklen)

    /** `u_` + PBKDF2(passphrase, "recall-sync-v1", 600k, 16) in hex. */
    fun deriveSyncUsername(passphrase: CharArray): String {
        val digest = deriveKeyBytes(
            passphrase,
            SYNC_IDENTITY_SALT.toByteArray(Charsets.UTF_8),
            PBKDF2_ITERATIONS,
            16,
        )
        return "u_" + hex(digest)
    }

    fun deriveSyncUsername(passphrase: String): String = deriveSyncUsername(passphrase.toCharArray())

    fun keyFingerprint(rawKey: ByteArray): String {
        if (rawKey.size != KEY_BYTES) throw CryptoException("invalid-key")
        val prefix = "recall-verifier-v1:".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").run {
            update(prefix)
            update(rawKey)
            digest()
        }
        return hex(digest).take(32)
    }

    /* ------------------------------------------------------------------ */
    /* Envelope encryption                                                 */
    /* ------------------------------------------------------------------ */

    fun encrypt(
        rawKey: ByteArray,
        payload: CapturePayload,
        iv: ByteArray = randomBytes(IV_BYTES),
    ): Envelope {
        if (!payload.isValid()) throw CryptoException("bad-payload")
        if (iv.size != IV_BYTES) throw CryptoException("invalid-envelope")
        val plaintext = canonicalPayloadJson(payload).toByteArray(Charsets.UTF_8)
        val sealed = aesGcmSealRaw(rawKey, iv, plaintext)
        return Envelope(
            v = ENVELOPE_VERSION,
            iv = encoder.encodeToString(iv),
            ct = encoder.encodeToString(sealed),
        )
    }

    fun decrypt(rawKey: ByteArray, envelope: Envelope): CapturePayload {
        val parsed = validateEnvelope(envelope)
        val iv = decodeBase64(parsed.iv)
        val ciphertext = decodeBase64(parsed.ct)
        if (iv.size != IV_BYTES || ciphertext.size < GCM_TAG_BYTES) {
            throw CryptoException("invalid-envelope")
        }
        val plaintext = try {
            aesGcmOpenRaw(rawKey, iv, ciphertext)
        } catch (_: Exception) {
            throw CryptoException("decrypt-failed")
        }
        val payload = try {
            json.decodeFromString(CapturePayload.serializer(), plaintext.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            throw CryptoException("bad-payload")
        }
        if (!payload.isValid()) throw CryptoException("bad-payload")
        return payload
    }

    fun decrypt(rawKey: ByteArray, envelopeJson: String): CapturePayload =
        decrypt(rawKey, parseEnvelope(envelopeJson))

    fun parseEnvelope(text: String): Envelope {
        val envelope = try {
            json.decodeFromString(Envelope.serializer(), text)
        } catch (_: Exception) {
            throw CryptoException("invalid-envelope")
        }
        return validateEnvelope(envelope)
    }

    fun validateEnvelope(envelope: Envelope): Envelope {
        if (envelope.v != ENVELOPE_VERSION) throw CryptoException("invalid-envelope")
        val iv = try {
            decodeBase64(envelope.iv)
        } catch (_: Exception) {
            throw CryptoException("invalid-envelope")
        }
        val ct = try {
            decodeBase64(envelope.ct)
        } catch (_: Exception) {
            throw CryptoException("invalid-envelope")
        }
        if (iv.size != IV_BYTES || ct.size < GCM_TAG_BYTES) throw CryptoException("invalid-envelope")
        return envelope
    }

    /** Canonical envelope JSON: `{"v":1,"iv":"...","ct":"..."}` — no whitespace. */
    fun envelopeToJson(envelope: Envelope): String {
        val valid = validateEnvelope(envelope)
        return """{"v":${valid.v},"iv":${quote(valid.iv)},"ct":${quote(valid.ct)}}"""
    }

    /* ------------------------------------------------------------------ */
    /* Raw byte sealing (local database at rest)                           */
    /* ------------------------------------------------------------------ */

    fun sealBytes(
        rawKey: ByteArray,
        plaintext: ByteArray,
        iv: ByteArray = randomBytes(IV_BYTES),
    ): Pair<ByteArray, ByteArray> {
        if (iv.size != IV_BYTES) throw CryptoException("invalid-envelope")
        return iv to aesGcmSealRaw(rawKey, iv, plaintext)
    }

    fun openBytes(rawKey: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        if (iv.size != IV_BYTES || ciphertext.size < GCM_TAG_BYTES) {
            throw CryptoException("invalid-envelope")
        }
        return try {
            aesGcmOpenRaw(rawKey, iv, ciphertext)
        } catch (_: Exception) {
            throw CryptoException("decrypt-failed")
        }
    }

    /* ------------------------------------------------------------------ */
    /* Low-level AES-256-GCM                                               */
    /* ------------------------------------------------------------------ */

    fun aesGcmSealRaw(
        rawKey: ByteArray,
        iv: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray {
        if (rawKey.size != KEY_BYTES) throw CryptoException("invalid-key")
        if (iv.size != IV_BYTES) throw CryptoException("invalid-envelope")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(rawKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    fun aesGcmOpenRaw(
        rawKey: ByteArray,
        iv: ByteArray,
        ciphertextWithTag: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray {
        if (rawKey.size != KEY_BYTES) throw CryptoException("invalid-key")
        if (iv.size != IV_BYTES) throw CryptoException("invalid-envelope")
        if (ciphertextWithTag.size < GCM_TAG_BYTES) throw CryptoException("invalid-envelope")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(rawKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(ciphertextWithTag)
    }

    /* ------------------------------------------------------------------ */
    /* Canonical payload JSON                                              */
    /* ------------------------------------------------------------------ */

    fun canonicalPayloadJson(payload: CapturePayload): String {
        if (!payload.isValid()) throw CryptoException("bad-payload")
        val parts = ArrayList<String>(6)
        fun add(key: String, value: String?) {
            if (value != null) parts += "${quote(key)}:${quote(value)}"
        }
        add("type", payload.type)
        add("url", payload.url)
        add("title", payload.title)
        add("text", payload.text)
        add("ocr_text", payload.ocrText)
        add("thumbnail_b64", payload.thumbnailB64)
        return parts.joinToString(prefix = "{", postfix = "}", separator = ",")
    }

    /* ------------------------------------------------------------------ */
    /* Encoding helpers                                                    */
    /* ------------------------------------------------------------------ */

    fun generateSalt(): ByteArray = randomBytes(SALT_BYTES)

    fun generateIv(): ByteArray = randomBytes(IV_BYTES)

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    fun base64Encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    fun base64Decode(value: String): ByteArray = decodeBase64(value)

    private fun decodeBase64(value: String): ByteArray = try {
        decoder.decode(value)
    } catch (error: IllegalArgumentException) {
        throw CryptoException("invalid-envelope")
    }

    private fun quote(value: String): String = JsonPrimitive(value).toString()

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun hexToBytes(value: String): ByteArray {
        require(value.length % 2 == 0) { "invalid-hex" }
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    /** Defensive parser for an arbitrary JSON object; used by tests and backups. */
    fun jsonObject(text: String) = json.parseToJsonElement(text).jsonObject

    fun jsonString(element: kotlinx.serialization.json.JsonElement): String =
        element.jsonPrimitive.content
}
