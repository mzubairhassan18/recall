package com.recall.parity

import com.recall.core.CapturePayload
import com.recall.core.RecallCrypto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Batch JSON-lines CLI used by scripts/crypto-parity.mjs. Reads one request per line and writes
 * one response per line. Never prints keys, passphrases, or plaintext unless the request is an
 * explicit parity operation.
 */
fun main() {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    // stdin/stdout are UTF-8 regardless of the Windows console code page.
    val reader = System.`in`.bufferedReader(Charsets.UTF_8)
    val writer = System.out.bufferedWriter(Charsets.UTF_8)
    generateSequence { reader.readLine() }.filter { it.isNotBlank() }.forEach { line ->
        val response = try {
            val request = json.parseToJsonElement(line).jsonObject
            handle(request)
        } catch (error: Throwable) {
            buildJsonObject {
                put("error", JsonPrimitive(error.message ?: error.javaClass.simpleName))
            }
        }
        writer.write(json.encodeToString(JsonObject.serializer(), response))
        writer.newLine()
        writer.flush()
    }
}

private fun handle(request: JsonObject): JsonObject {
    val op = request.string("op") ?: error("missing-op")
    return when (op) {
        "pbkdf2" -> {
            val dk = RecallCrypto.deriveKeyBytes(
                passphrase = request.string("passphrase") ?: error("missing-passphrase"),
                salt = RecallCrypto.hexToBytes(request.string("salt_hex") ?: error("missing-salt")),
                iterations = request.int("iterations") ?: RecallCrypto.PBKDF2_ITERATIONS,
                dklen = request.int("dklen") ?: RecallCrypto.KEY_BYTES,
            )
            buildJsonObject { put("dk_hex", JsonPrimitive(RecallCrypto.hex(dk))) }
        }

        "sync_username" -> buildJsonObject {
            put("username", JsonPrimitive(RecallCrypto.deriveSyncUsername(request.string("passphrase") ?: error("missing-passphrase"))))
        }

        "fingerprint" -> buildJsonObject {
            put("fingerprint", JsonPrimitive(RecallCrypto.keyFingerprint(RecallCrypto.hexToBytes(request.string("key_hex") ?: error("missing-key")))))
        }

        "gcm_seal" -> {
            val sealed = RecallCrypto.aesGcmSealRaw(
                rawKey = RecallCrypto.hexToBytes(request.string("key_hex") ?: error("missing-key")),
                iv = RecallCrypto.hexToBytes(request.string("iv_hex") ?: error("missing-iv")),
                plaintext = RecallCrypto.hexToBytes(request.string("plaintext_hex") ?: ""),
                aad = RecallCrypto.hexToBytes(request.string("aad_hex") ?: ""),
            )
            val split = sealed.size - RecallCrypto.GCM_TAG_BYTES
            buildJsonObject {
                put("ciphertext_hex", JsonPrimitive(RecallCrypto.hex(sealed.copyOfRange(0, split))))
                put("tag_hex", JsonPrimitive(RecallCrypto.hex(sealed.copyOfRange(split, sealed.size))))
            }
        }

        "gcm_open" -> {
            val plaintext = RecallCrypto.aesGcmOpenRaw(
                rawKey = RecallCrypto.hexToBytes(request.string("key_hex") ?: error("missing-key")),
                iv = RecallCrypto.hexToBytes(request.string("iv_hex") ?: error("missing-iv")),
                ciphertextWithTag = RecallCrypto.hexToBytes(request.string("ct_hex") ?: error("missing-ct")),
                aad = RecallCrypto.hexToBytes(request.string("aad_hex") ?: ""),
            )
            buildJsonObject { put("plaintext_hex", JsonPrimitive(RecallCrypto.hex(plaintext))) }
        }

        "envelope_seal" -> {
            val payload = request["payload"]?.jsonObject ?: error("missing-payload")
            val envelope = RecallCrypto.encrypt(
                rawKey = RecallCrypto.hexToBytes(request.string("key_hex") ?: error("missing-key")),
                payload = payloadOf(payload),
                iv = RecallCrypto.hexToBytes(request.string("iv_hex") ?: error("missing-iv")),
            )
            buildJsonObject { put("envelope", JsonPrimitive(RecallCrypto.envelopeToJson(envelope))) }
        }

        "envelope_open" -> {
            val payload = RecallCrypto.decrypt(
                rawKey = RecallCrypto.hexToBytes(request.string("key_hex") ?: error("missing-key")),
                envelopeJson = request.string("envelope") ?: error("missing-envelope"),
            )
            buildJsonObject { put("payload", JsonPrimitive(RecallCrypto.canonicalPayloadJson(payload))) }
        }

        "version" -> buildJsonObject { put("version", JsonPrimitive(1)) }

        else -> error("unknown-op")
    }
}

private fun payloadOf(objectValue: JsonObject): CapturePayload = CapturePayload(
    type = objectValue.string("type") ?: error("missing-type"),
    url = objectValue.string("url"),
    title = objectValue.string("title"),
    text = objectValue.string("text"),
    ocrText = objectValue.string("ocr_text"),
    thumbnailB64 = objectValue.string("thumbnail_b64"),
)

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.int
