package com.recall.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The three capture kinds. Fixed by docs/wire-format.md. */
enum class CaptureType(val wire: String) {
    URL("url"),
    TEXT("text"),
    SCREENSHOT("screenshot");

    companion object {
        fun fromWire(value: String): CaptureType? = entries.firstOrNull { it.wire == value }
    }
}

/** Plaintext payload, inside the encrypted envelope only. Field names match the wire format. */
@Serializable
data class CapturePayload(
    val type: String,
    val url: String? = null,
    val title: String? = null,
    val text: String? = null,
    @SerialName("ocr_text") val ocrText: String? = null,
    @SerialName("thumbnail_b64") val thumbnailB64: String? = null,
) {
    fun isValid(): Boolean {
        if (CaptureType.fromWire(type) == null) return false
        if (type == "url" && url == null) return false
        if (type == "text" && text == null) return false
        if (type == "screenshot" && ocrText == null) return false
        return true
    }
}

/** Wire envelope. `ct` is ciphertext || 16-byte GCM tag, base64. */
@Serializable
data class Envelope(
    val v: Int = 1,
    val iv: String,
    val ct: String,
)

/** A candidate row produced by the SQLite adapter, before recency decay. */
data class SearchHit(
    val id: String,
    val createdAt: Long,
    val type: CaptureType,
    val title: String?,
    val url: String?,
    val text: String?,
    val ocrText: String?,
    val score: Double,
    val thumbnailB64: String? = null,
)

/** One result row returned by the search pipeline. */
data class SearchResult(
    val id: String,
    val title: String,
    val snippet: String,
    val createdAt: Long,
    val type: CaptureType,
    val score: Double,
    val url: String? = null,
    val thumbnailB64: String? = null,
)

/** One entry from GET /list. */
@Serializable
data class SyncItem(
    val id: String,
    @SerialName("created_at") val createdAt: Long,
    val size: Int = 0,
)

/** Relay registration response. */
@Serializable
data class AuthRegisterResponse(
    @SerialName("user_id") val userId: String,
    val salt: String,
)

/** Portable export manifest. The zip contains `<id>.json` envelopes only. */
@Serializable
data class ExportManifest(
    val version: Int = 1,
    @SerialName("exported_at") val exportedAt: Long,
    val count: Int,
    val entries: List<ExportEntry>,
)

@Serializable
data class ExportEntry(
    val id: String,
    @SerialName("created_at") val createdAt: Long,
)

/** Local row used by the Android store and the memory adapter. */
data class CaptureRecord(
    val id: String,
    val createdAt: Long,
    val type: CaptureType,
    val url: String? = null,
    val title: String? = null,
    val text: String? = null,
    val ocrText: String? = null,
    val thumbnail: ByteArray? = null,
    val blobRef: String = id,
    val synced: Boolean = false,
) {
    fun toHit(score: Double = 0.0, thumbnailB64: String? = null): SearchHit = SearchHit(
        id = id,
        createdAt = createdAt,
        type = type,
        title = title,
        url = url,
        text = text,
        ocrText = ocrText,
        score = score,
        thumbnailB64 = thumbnailB64,
    )
}
