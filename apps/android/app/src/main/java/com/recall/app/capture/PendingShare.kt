package com.recall.app.capture

import android.net.Uri

/**
 * In-memory hand-off for a share/selection capture that arrived while the vault was locked.
 * Nothing is written to disk: the payload is consumed as soon as the app unlocks, and a process
 * death simply loses the pending share rather than leaving plaintext behind.
 */
object PendingShare {
    data class Payload(
        val type: String,
        val text: String?,
        val subject: String?,
        val uri: Uri?,
    )

    @Volatile
    private var pending: Payload? = null

    fun set(payload: Payload) {
        pending = payload
    }

    fun take(): Payload? {
        val value = pending
        pending = null
        return value
    }
}
