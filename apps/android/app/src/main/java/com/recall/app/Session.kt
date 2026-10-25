package com.recall.app

import android.content.Context
import com.recall.app.data.CaptureRepository
import com.recall.app.data.RecallDatabase

/**
 * Process-wide unlocked state. Nothing survives process death: the passphrase-derived key lives
 * only in memory, the SQLCipher file is the only persisted copy of the data, and closing the
 * session re-locks the app.
 */
object Session {
    private var database: RecallDatabase? = null
    private var repository: CaptureRepository? = null
    private var rawKey: ByteArray? = null
    private var passphrase: CharArray? = null

    @Synchronized
    fun open(context: Context, passphrase: CharArray, rawKey: ByteArray): CaptureRepository {
        clear()
        val storedKey = rawKey.copyOf()
        val db = try {
            RecallDatabase.open(context, passphrase)
        } catch (error: Throwable) {
            storedKey.fill(0)
            throw error
        }
        val repo = CaptureRepository(db, storedKey)
        database = db
        repository = repo
        this.rawKey = storedKey
        this.passphrase = passphrase.copyOf()
        return repo
    }

    @Synchronized
    fun repository(): CaptureRepository? = repository

    @Synchronized
    fun rawKey(): ByteArray? = rawKey?.copyOf()

    @Synchronized
    fun passphrase(): CharArray? = passphrase?.copyOf()

    @Synchronized
    fun isUnlocked(): Boolean = repository != null

    @Synchronized
    fun clear() {
        repository = null
        runCatching { database?.close() }
        database = null
        rawKey?.fill(0)
        rawKey = null
        passphrase?.fill('\u0000')
        passphrase = null
    }
}
