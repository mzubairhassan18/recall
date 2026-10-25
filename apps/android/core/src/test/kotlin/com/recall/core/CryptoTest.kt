package com.recall.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoTest {
    private fun resource(name: String): String =
        javaClass.getResourceAsStream("/$name")!!.bufferedReader().use { it.readText() }

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `matches the PBKDF2 fixtures including the production 600k vector`() {
        val root = parse(resource("pbkdf2.json"))
        val vectors = root["vectors"]!!.jsonArray
        assertTrue(vectors.isNotEmpty())
        for (element in vectors) {
            val vector = element.jsonObject
            val name = vector["name"]!!.jsonPrimitive.content
            val derived = RecallCrypto.deriveKeyBytes(
                passphrase = vector["passphrase"]!!.jsonPrimitive.content,
                salt = vector["salt_utf8"]!!.jsonPrimitive.content.toByteArray(Charsets.UTF_8),
                iterations = vector["iterations"]!!.jsonPrimitive.int,
                dklen = vector["dklen"]!!.jsonPrimitive.int,
            )
            assertEquals(name, vector["dk_hex"]!!.jsonPrimitive.content, RecallCrypto.hex(derived))
        }
    }

    @Test
    fun `matches the AES-256-GCM NIST and OpenSSL vectors`() {
        val root = parse(resource("aesgcm.json"))
        for (element in root["vectors"]!!.jsonArray) {
            val vector = element.jsonObject
            val name = vector["name"]!!.jsonPrimitive.content
            val key = RecallCrypto.hexToBytes(vector["key_hex"]!!.jsonPrimitive.content)
            val iv = RecallCrypto.hexToBytes(vector["iv_hex"]!!.jsonPrimitive.content)
            val plaintext = RecallCrypto.hexToBytes(vector["plaintext_hex"]!!.jsonPrimitive.content)
            val aad = RecallCrypto.hexToBytes(vector["aad_hex"]!!.jsonPrimitive.content)
            val expectedCiphertext = vector["ciphertext_hex"]!!.jsonPrimitive.content
            val expectedTag = vector["tag_hex"]!!.jsonPrimitive.content

            val sealed = RecallCrypto.aesGcmSealRaw(key, iv, plaintext, aad)
            val ciphertext = sealed.copyOfRange(0, sealed.size - RecallCrypto.GCM_TAG_BYTES)
            val tag = sealed.copyOfRange(sealed.size - RecallCrypto.GCM_TAG_BYTES, sealed.size)
            assertEquals("$name ciphertext", expectedCiphertext, RecallCrypto.hex(ciphertext))
            assertEquals("$name tag", expectedTag, RecallCrypto.hex(tag))
            assertArrayEquals(name, plaintext, RecallCrypto.aesGcmOpenRaw(key, iv, sealed, aad))
        }
    }

    @Test
    fun `encrypts and decrypts every envelope round-trip fixture`() {
        val root = parse(resource("envelope-roundtrip.json"))
        val key = RecallCrypto.hexToBytes(root["key_hex"]!!.jsonPrimitive.content)
        for (element in root["vectors"]!!.jsonArray) {
            val vector = element.jsonObject
            val name = vector["name"]!!.jsonPrimitive.content
            val iv = RecallCrypto.hexToBytes(vector["iv_hex"]!!.jsonPrimitive.content)
            val payload = payloadFrom(vector["payload"]!!.jsonObject)
            val envelope = RecallCrypto.encrypt(key, payload, iv)
            assertEquals(name, RecallCrypto.decrypt(key, envelope), payload)
            val reparsed = RecallCrypto.parseEnvelope(RecallCrypto.envelopeToJson(envelope))
            assertEquals(name, RecallCrypto.decrypt(key, reparsed), payload)
        }
    }

    @Test
    fun `rejects tampered ciphertext and wrong keys`() {
        val key = ByteArray(32) { it.toByte() }
        val payload = CapturePayload(type = "text", text = "secret")
        val envelope = RecallCrypto.encrypt(key, payload, ByteArray(12) { 1 })
        val tampered = envelope.copy(ct = envelope.ct.dropLast(4) + "AAAA")
        try {
            RecallCrypto.decrypt(key, tampered)
            throw AssertionError("tampered envelope was accepted")
        } catch (expected: CryptoException) {
            assertEquals("decrypt-failed", expected.code)
        }
        val other = ByteArray(32) { (it + 1).toByte() }
        try {
            RecallCrypto.decrypt(other, envelope)
            throw AssertionError("wrong key was accepted")
        } catch (expected: CryptoException) {
            assertEquals("decrypt-failed", expected.code)
        }
    }

    @Test
    fun `derives a stable sync handle and fingerprint`() {
        val first = RecallCrypto.deriveSyncUsername("correct horse battery staple")
        val second = RecallCrypto.deriveSyncUsername("correct horse battery staple")
        val other = RecallCrypto.deriveSyncUsername("correct horse battery stapl3")
        assertEquals(first, second)
        assertTrue(first.matches(Regex("u_[0-9a-f]{32}")))
        assertNotEquals(first, other)

        val key = ByteArray(32) { it.toByte() }
        assertEquals(RecallCrypto.keyFingerprint(key), RecallCrypto.keyFingerprint(key.copyOf()))
        assertEquals(32, RecallCrypto.keyFingerprint(key).length)
    }

    @Test
    fun `canonical payload JSON is deterministic and omits nulls`() {
        val payload = CapturePayload(
            type = "text",
            text = "line\nwith \"quotes\" and \\ slash",
            title = "héllo",
        )
        assertEquals(
            """{"type":"text","title":"héllo","text":"line\nwith \"quotes\" and \\ slash"}""",
            RecallCrypto.canonicalPayloadJson(payload),
        )
    }

    private fun payloadFrom(objectValue: JsonObject): CapturePayload = CapturePayload(
        type = objectValue["type"]!!.jsonPrimitive.content,
        url = objectValue["url"]?.jsonPrimitive?.contentOrNull,
        title = objectValue["title"]?.jsonPrimitive?.contentOrNull,
        text = objectValue["text"]?.jsonPrimitive?.contentOrNull,
        ocrText = objectValue["ocr_text"]?.jsonPrimitive?.contentOrNull,
        thumbnailB64 = objectValue["thumbnail_b64"]?.jsonPrimitive?.contentOrNull,
    )
}
