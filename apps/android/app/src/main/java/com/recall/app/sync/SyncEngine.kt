package com.recall.app.sync

import com.recall.app.config.AppConfig
import com.recall.app.data.CaptureRepository
import com.recall.app.data.toPayload
import com.recall.core.RecallCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SyncReport(
    val pulled: Int,
    val pushed: Int,
    val failed: Int,
    val localOnly: Boolean,
)

/**
 * Pull-then-push sync. Cursors are `created_at` values; `since` is exclusive, so we re-read the
 * last minute and rely on idempotent local inserts / relay PUTs to absorb the overlap.
 */
class SyncEngine(
    private val config: AppConfig,
    private val repository: CaptureRepository,
    private val rawKey: ByteArray,
) {
    suspend fun sync(): SyncReport = withContext(Dispatchers.IO) {
        val stored = config.load()
        if (stored.localOnly || stored.relayUrl.isNullOrBlank() || stored.userId.isNullOrBlank()) {
            return@withContext SyncReport(0, 0, 0, localOnly = true)
        }
        val secret = config.relaySecret ?: throw RelayException(0, "missing-relay-secret")
        val client = RelayClient(stored.relayUrl, secret, stored.userId)

        var pulled = 0
        var pushed = 0
        var failed = 0
        var cursor = config.syncCursor
        val since = if (cursor > 0) maxOf(0L, cursor - 60_000L) else 0L

        try {
            for (item in client.list(since)) {
                if (repository.exists(item.id)) {
                    cursor = maxOf(cursor, item.createdAt)
                    continue
                }
                try {
                    val envelope = client.getBlob(item.id)
                    val payload = RecallCrypto.decrypt(rawKey, envelope)
                    if (repository.insert(payload, item.createdAt, synced = true, id = item.id)) {
                        pulled += 1
                    }
                    cursor = maxOf(cursor, item.createdAt)
                } catch (_: Exception) {
                    failed += 1
                    break
                }
            }
        } catch (_: Exception) {
            failed += 1
        }

        try {
            for (entity in repository.unsynced(50)) {
                try {
                    val envelope = RecallCrypto.encrypt(rawKey, entity.toPayload())
                    client.putBlob(entity.id, RecallCrypto.envelopeToJson(envelope), entity.createdAt)
                    repository.markSynced(entity.id)
                    pushed += 1
                } catch (_: Exception) {
                    failed += 1
                    break
                }
            }
        } catch (_: Exception) {
            failed += 1
        }

        if (cursor > config.syncCursor) config.syncCursor = cursor
        config.lastSyncAt = System.currentTimeMillis()
        SyncReport(pulled = pulled, pushed = pushed, failed = failed, localOnly = false)
    }
}
