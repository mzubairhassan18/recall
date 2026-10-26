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
import java.util.Locale
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
    val favorite: Boolean = false,
    val archived: Boolean = false,
    val tags: List<String> = emptyList(),
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

    suspend fun countArchived(): Int = withContext(Dispatchers.IO) { dao.countArchived() }

    suspend fun search(query: String, synonyms: SynonymIndex): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val results = search(query, SearchOptions(adapters = adapter, synonyms = synonyms))
            enrich(results)
        }

    suspend fun timeline(
        type: String?,
        domain: String?,
        favoritesOnly: Boolean = false,
        tag: String? = null,
        descending: Boolean = true,
        limit: Int = 200,
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        val entities = dao.timeline(
            type = type,
            domain = domain?.takeIf { it.isNotBlank() },
            favorite = if (favoritesOnly) 1 else 0,
            tag = tag?.takeIf { it.isNotBlank() },
            descending = if (descending) 1 else 0,
            limit = limit,
        )
        enrichEntities(entities)
    }

    suspend fun archived(limit: Int = 200): List<SearchResult> = withContext(Dispatchers.IO) {
        enrichEntities(dao.archived(limit))
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
            favorite = entity.favorite == 1,
            archived = entity.archived == 1,
            tags = tagsFor(id),
        )
    }

    suspend fun exists(id: String): Boolean = withContext(Dispatchers.IO) { dao.find(id) != null }

    suspend fun insert(
        payload: CapturePayload,
        createdAt: Long = System.currentTimeMillis(),
        thumbnail: ByteArray? = null,
        synced: Boolean = false,
        id: String = UUID.randomUUID().toString(),
        favorite: Boolean = false,
        archived: Boolean = false,
        tags: List<String> = emptyList(),
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
            favorite = if (favorite) 1 else 0,
            archived = if (archived) 1 else 0,
        )
        val inserted = dao.insertCapture(entity) != -1L
        if (inserted) {
            for (tag in normalizeTags(tags)) {
                insertTag(id, tag)
            }
        }
        inserted
    }

    suspend fun unsynced(limit: Int = 50): List<CaptureEntity> = withContext(Dispatchers.IO) {
        dao.unsynced(limit)
    }

    suspend fun markSynced(id: String) = withContext(Dispatchers.IO) { dao.markSynced(id) }

    suspend fun all(): List<CaptureEntity> = withContext(Dispatchers.IO) { dao.all() }

    suspend fun setFavorite(id: String, favorite: Boolean) = withContext(Dispatchers.IO) {
        dao.setFavorite(id, if (favorite) 1 else 0)
    }

    suspend fun setArchived(id: String, archived: Boolean) = withContext(Dispatchers.IO) {
        dao.setArchived(id, if (archived) 1 else 0)
    }

    suspend fun archiveMany(ids: Collection<String>): Int = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) 0 else dao.archiveMany(ids.toList())
    }

    suspend fun restoreMany(ids: Collection<String>): Int = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) 0 else dao.restoreMany(ids.toList())
    }

    suspend fun allTags(): List<String> = withContext(Dispatchers.IO) { dao.allTags() }

    suspend fun addTag(id: String, value: String) = withContext(Dispatchers.IO) {
        val tag = normalizeTag(value) ?: return@withContext
        insertTag(id, tag)
    }

    suspend fun removeTag(id: String, value: String) = withContext(Dispatchers.IO) {
        val tag = normalizeTag(value) ?: return@withContext
        dao.deleteTag(id, tag)
    }

    /** Encrypted export: a zip of envelopes plus a plaintext manifest (ids/timestamps only). */
    suspend fun exportTo(resolver: ContentResolver, uri: Uri): Int = withContext(Dispatchers.IO) {
        val captures = dao.all()
        val tags = dao.allTagAnnotations()
            .groupBy({ it.targetId }, { it.value })
            .mapValues { (_, values) -> values.filterNotNull().distinct().sorted() }
        val manifest = ExportManifest(
            exportedAt = System.currentTimeMillis(),
            count = captures.size,
            entries = captures.map { capture ->
                ExportEntry(
                    id = capture.id,
                    createdAt = capture.createdAt,
                    favorite = capture.favorite == 1,
                    archived = capture.archived == 1,
                    tags = tags[capture.id].orEmpty(),
                )
            },
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
                    if (
                        insert(
                            payload = payload,
                            createdAt = entry.createdAt,
                            synced = false,
                            id = entry.id,
                            favorite = entry.favorite,
                            archived = entry.archived,
                            tags = entry.tags,
                        )
                    ) {
                        imported += 1
                    }
                }
            }
        }
        imported
    }

    private suspend fun enrich(results: List<SearchResult>): List<SearchResult> {
        if (results.isEmpty()) return results
        val ids = results.map { it.id }
        val entities = dao.byIds(ids).associateBy { it.id }
        val tags = tagsByTarget(ids)
        return results.map { result ->
            val entity = entities[result.id]
            result.copy(
                favorite = entity?.favorite == 1,
                archived = entity?.archived == 1,
                tags = tags[result.id].orEmpty(),
            )
        }
    }

    private suspend fun enrichEntities(entities: List<CaptureEntity>): List<SearchResult> {
        if (entities.isEmpty()) return emptyList()
        val tags = tagsByTarget(entities.map { it.id })
        return entities.map { it.toResult(tags[it.id].orEmpty()) }
    }

    private suspend fun tagsByTarget(ids: List<String>): Map<String, List<String>> {
        if (ids.isEmpty()) return emptyMap()
        return dao.tagsForIds(ids)
            .groupBy({ it.targetId }, { it.value })
            .mapValues { (_, values) -> values.filterNotNull().distinct() }
    }

    private suspend fun tagsFor(id: String): List<String> =
        dao.tagsFor(id).mapNotNull { it.value }.filter { it.isNotBlank() }.distinct()

    private suspend fun insertTag(id: String, tag: String) {
        val existing = dao.tagsFor(id).mapNotNull { it.value }
        if (existing.size >= MAX_TAGS || tag in existing) return
        dao.insertAnnotation(
            AnnotationEntity(
                id = UUID.randomUUID().toString(),
                targetId = id,
                kind = "tag",
                value = tag,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun CaptureEntity.toResult(tags: List<String>): SearchResult {
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
            favorite = favorite == 1,
            archived = archived == 1,
            tags = tags,
        )
    }

    private fun normalizeTags(values: List<String>): List<String> =
        values.mapNotNull(::normalizeTag).distinct().take(MAX_TAGS)

    private fun normalizeTag(value: String): String? {
        val tag = value.trim().lowercase(Locale.ROOT).take(MAX_TAG_LENGTH)
        return tag.takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val MAX_TAGS = 8
        const val MAX_TAG_LENGTH = 32
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
