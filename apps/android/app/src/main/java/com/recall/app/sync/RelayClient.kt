package com.recall.app.sync

import com.recall.core.AuthRegisterResponse
import com.recall.core.SyncItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class RelayException(val status: Int, val code: String) : IOException(code)

/**
 * Minimal relay client. The relay only ever sees ciphertext plus `{id, created_at, size}`.
 * Uses HttpURLConnection so the app has no HTTP stack beyond the platform.
 */
class RelayClient(
    baseUrl: String,
    private val secret: String,
    private val userId: String? = null,
) {
    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun register(username: String): AuthRegisterResponse = withContext(Dispatchers.IO) {
        val body = """{"username":${quote(username)}}"""
        val response = request("POST", "/auth/register", body)
        json.decodeFromString(AuthRegisterResponse.serializer(), response)
    }

    suspend fun list(since: Long): List<SyncItem> = withContext(Dispatchers.IO) {
        val suffix = if (since > 0) "?since=$since" else ""
        val response = request("GET", "/list$suffix", null)
        json.parseToJsonElement(response).jsonArray.map { element ->
            val row = element.jsonObject
            SyncItem(
                id = row["id"]!!.jsonPrimitive.content,
                createdAt = row["created_at"]!!.jsonPrimitive.content.toLong(),
                size = row["size"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            )
        }
    }

    suspend fun getBlob(id: String): String = withContext(Dispatchers.IO) {
        request("GET", "/blob/$id", null)
    }

    suspend fun putBlob(id: String, envelopeJson: String, createdAt: Long) = withContext(Dispatchers.IO) {
        request("PUT", "/blob/$id", envelopeJson, mapOf("X-Created-At" to createdAt.toString()))
        Unit
    }

    private fun request(
        method: String,
        path: String,
        body: String?,
        extraHeaders: Map<String, String> = emptyMap(),
    ): String {
        val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("X-Relay-Secret", secret)
            setRequestProperty("Accept", "application/json")
            if (userId != null) setRequestProperty("X-User-Id", userId)
            for ((key, value) in extraHeaders) setRequestProperty(key, value)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val code = runCatching {
                    json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.contentOrNull
                }.getOrNull() ?: "http-$status"
                throw RelayException(status, code)
            }
            return text
        } finally {
            connection.disconnect()
        }
    }

    private fun quote(value: String): String =
        kotlinx.serialization.json.JsonPrimitive(value).toString()
}
