package com.recall.app.data

import android.content.ContentResolver
import android.net.Uri
import com.recall.core.CapturePayload
import com.recall.core.CaptureRecord
import com.recall.core.CaptureType
import com.recall.core.ExportEntry
import com.recall.core.ExportManifest
import com.recall.core.RecallCrypto
import com.recall.core.SearchHit
import com.recall.core.SearchOptions
import com.recall.core.SearchResult
import com.recall.core.Snippets
import com.recall.core.SynonymIndex
import com.recall.core.search
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class CaptureDetail(
    val id: String,
    val createdAt: Long,
    val type: CaptureType,
    val title: String,
    val url: String?,
    val text: String?,
    val ocrText: String?,
    val thumbnail: ByteArray?,
    val synced: Boolean,
)

/**
 * Everything that touches the decrypted database. Only reachable through [com.recall.app.Session]
 * while the app is unlocked.
 */
class CaptureRepository(
    private val database: RecallDatabase,
    private val rawKey: ByteArray,
) {
    private val dao = database.dao()
    private val adapter = SqliteSearchAdapter(database)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    suspend fun count(): Int = withContext(Dispatchers.IO) { dao.count() }

    suspend fun search(query: String, synonyms: SynonymIndex): List<SearchResult> =
        withContext(Dispatchers.IO) {
            search(query, SearchOptions(adapters = adapter, synonyms = synonyms))
        }

    suspend fun timeline(type: String?, domain: String?, limit: Int = 200): List<SearchResult> =
        withContext(Dispatchers.IO) {
            dao.timeline(type, domain?.takeIf { it.isNotBlank() }, limit).map { it.toResult() }
        }

    suspend fun recent(limit: Int): List<CaptureEntity> = withContext(Dispatchers.IO) {
        dao.recent(limit)
    }

    suspend fun detail(id: String): CaptureDetail? = withContext(Dispatchers.IO) {
        val entity = dao.find(id) ?: return@withContext null
        CaptureDetail(
            id = entity.id,
            createdAt = entity.createdAt,
            type = CaptureType.fromWire(entity.type) ?: CaptureType.TEXT,
            title = Snippets.displayTitle(entity.toHit()),
            url = entity.url,
            text = entity.text,
            ocrText = entity.ocrText,
            thumbnail = entity.thumbnail,
            synced = entity.synced == 1,
        )
    }

    suspend fun exists(id: String): Boolean = withContext(Dispatchers.IO) { dao.find(id) != null }

    suspend fun insert(
        payload: CapturePayload,
        createdAt: Long = System.currentTimeMillis(),
        thumbnail: ByteArray? = null,
        synced: Boolean = false,
        id: String = UUID.randomUUID().toString(),
    ): Boolean = withContext(Dispatchers.IO) {
        val thumb = thumbnail ?: payload.thumbnailB64?.let {
            runCatching { Base64.getDecoder().decode(it) }.getOrNull()
        }
        val entity = CaptureEntity(
            id = id,
            createdAt = createdAt,
            type = payload.type,
            url = payload.url,
            title = payload.title,
            text = payload.text,
            ocrText = payload.ocrText,
            thumbnail = thumb,
            blobRef = id,
            synced = if (synced) 1 else 0,
        )
        dao.insertCapture(entity) != -1L
    }

    suspend fun unsynced(limit: Int = 50): List<CaptureEntity> = withContext(Dispatchers.IO) {
        dao.unsynced(limit)
    }

    suspend fun markSynced(id: String) = withContext(Dispatchers.IO) { dao.markSynced(id) }

    suspend fun all(): List<CaptureEntity> = withContext(Dispatchers.IO) { dao.all() }

    /** Encrypted export: a zip of envelopes plus a plaintext manifest (ids/timestamps only). */
    suspend fun exportTo(resolver: ContentResolver, uri: Uri): Int = withContext(Dispatchers.IO) {
        val captures = dao.all()
        val manifest = ExportManifest(
            exportedAt = System.currentTimeMillis(),
            count = captures.size,
            entries = captures.map { ExportEntry(it.id, it.createdAt) },
        )
        val stream = resolver.openOutputStream(uri) ?: throw IOException("export-open-failed")
        stream.use { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(json.encodeToString(ExportManifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                for (capture in captures) {
                    val envelope = RecallCrypto.encrypt(rawKey, capture.toPayload())
                    zip.putNextEntry(ZipEntry("${capture.id}.json"))
                    zip.write(RecallCrypto.envelopeToJson(envelope).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }
        captures.size
    }

    suspend fun importFrom(resolver: ContentResolver, uri: Uri): Int = withContext(Dispatchers.IO) {
        val stream = resolver.openInputStream(uri) ?: throw IOException("import-open-failed")
        var imported = 0
        stream.use { input ->
            ZipInputStream(input).use { zip ->
                var manifest: ExportManifest? = null
                val envelopes = LinkedHashMap<String, String>()
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val bytes = zip.readBytes()
                    when {
                        entry.name == "manifest.json" ->
                            manifest = runCatching {
                                json.decodeFromString(ExportManifest.serializer(), bytes.toString(Charsets.UTF_8))
                            }.getOrNull()
                        entry.name.endsWith(".json") ->
                            envelopes[entry.name.removeSuffix(".json")] = bytes.toString(Charsets.UTF_8)
                    }
                }
                val entries = manifest?.entries
                    ?: envelopes.keys.map { ExportEntry(it, System.currentTimeMillis()) }
                for (entry in entries) {
                    val envelopeJson = envelopes[entry.id] ?: continue
                    val payload = runCatching { RecallCrypto.decrypt(rawKey, envelopeJson) }.getOrNull() ?: continue
                    if (insert(payload, entry.createdAt, synced = false, id = entry.id)) imported += 1
                }
            }
        }
        imported
    }

    private fun CaptureEntity.toResult(): SearchResult {
        val hit = toHit()
        return SearchResult(
            id = id,
            title = Snippets.displayTitle(hit),
            snippet = Snippets.makeSnippet(hit, emptyList()),
            createdAt = createdAt,
            type = CaptureType.fromWire(type) ?: CaptureType.TEXT,
            score = 1.0,
            url = url,
            thumbnailB64 = thumbnail?.let { Base64.getEncoder().encodeToString(it) },
        )
    }
}

fun CaptureEntity.toHit(): SearchHit = SearchHit(
    id = id,
    createdAt = createdAt,
    type = CaptureType.fromWire(type) ?: CaptureType.TEXT,
    title = title,
    url = url,
    text = text,
    ocrText = ocrText,
    score = 0.0,
    thumbnailB64 = thumbnail?.let { Base64.getEncoder().encodeToString(it) },
)

fun CaptureEntity.toRecord(): CaptureRecord = CaptureRecord(
    id = id,
    createdAt = createdAt,
    type = CaptureType.fromWire(type) ?: CaptureType.TEXT,
    url = url,
    title = title,
    text = text,
    ocrText = ocrText,
    thumbnail = thumbnail,
    blobRef = blobRef,
    synced = synced == 1,
)

fun CaptureEntity.toPayload(): CapturePayload = CapturePayload(
    type = type,
    url = url,
    title = title,
    text = text,
    ocrText = ocrText,
    thumbnailB64 = thumbnail?.let { Base64.getEncoder().encodeToString(it) },
)
