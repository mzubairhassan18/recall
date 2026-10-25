package com.recall.app.data

import android.database.Cursor
import com.recall.core.CaptureType
import com.recall.core.SearchAdapters
import com.recall.core.SearchHit
import java.util.Base64

/**
 * SQLite/FTS5 adapter for the fixed search pipeline. Mirrors apps/chrome-ext/src/lib/db.ts:
 * `bm25()` comes straight from FTS5, recency decay and fallbacks live in core.
 */
class SqliteSearchAdapter(private val database: RecallDatabase) : SearchAdapters {

    private val columns =
        "c.id, c.created_at, c.type, c.title, c.url, c.text, c.ocr_text, c.thumbnail"

    override fun ftsQuery(match: String, limit: Int): List<SearchHit> {
        val sql = """
            SELECT $columns, -bm25(captures_fts) AS score
            FROM captures_fts
            JOIN captures c ON c.rowid = captures_fts.rowid
            WHERE captures_fts MATCH ?
            ORDER BY score DESC, c.created_at DESC, c.id ASC
            LIMIT ?
        """.trimIndent()
        return runCatching {
            query(sql, arrayOf<Any?>(match, limit)) { cursor ->
                cursor.getDouble(cursor.getColumnIndexOrThrow("score"))
            }
        }.getOrElse { emptyList() }
    }

    override fun fuzzyQuery(terms: List<String>, limit: Int): List<SearchHit> {
        val needles = terms.filter { it.isNotBlank() }
        if (needles.isEmpty()) return emptyList()
        val clauses = ArrayList<String>()
        val args = ArrayList<Any?>()
        for (term in needles) {
            val escaped = "%" + escapeLike(term.lowercase()) + "%"
            clauses += "(lower(coalesce(title,'')) LIKE ? ESCAPE '\\' OR " +
                "lower(coalesce(text,'')) LIKE ? ESCAPE '\\' OR " +
                "lower(coalesce(ocr_text,'')) LIKE ? ESCAPE '\\' OR " +
                "lower(coalesce(url,'')) LIKE ? ESCAPE '\\')"
            repeat(4) { args += escaped }
        }
        args += limit
        val sql = """
            SELECT $columns, 0.5 AS score
            FROM captures c
            WHERE ${clauses.joinToString(" OR ")}
            ORDER BY c.created_at DESC, c.id ASC
            LIMIT ?
        """.trimIndent()
        return runCatching {
            query(sql, args.toTypedArray()) { 0.5 }
        }.getOrElse { emptyList() }
    }

    override fun recentQuery(limit: Int): List<SearchHit> {
        val sql = """
            SELECT $columns, 1.0 AS score
            FROM captures c
            ORDER BY c.created_at DESC, c.id ASC
            LIMIT ?
        """.trimIndent()
        return runCatching {
            query(sql, arrayOf<Any?>(limit)) { 1.0 }
        }.getOrElse { emptyList() }
    }

    private fun query(
        sql: String,
        args: Array<out Any?>,
        score: (Cursor) -> Double,
    ): List<SearchHit> {
        val cursor = database.openHelper.writableDatabase.query(sql, args)
        cursor.use {
            val out = ArrayList<SearchHit>(it.count)
            val idIndex = it.getColumnIndexOrThrow("id")
            val createdIndex = it.getColumnIndexOrThrow("created_at")
            val typeIndex = it.getColumnIndexOrThrow("type")
            val titleIndex = it.getColumnIndexOrThrow("title")
            val urlIndex = it.getColumnIndexOrThrow("url")
            val textIndex = it.getColumnIndexOrThrow("text")
            val ocrIndex = it.getColumnIndexOrThrow("ocr_text")
            val thumbIndex = it.getColumnIndexOrThrow("thumbnail")
            while (it.moveToNext()) {
                val thumbnail = if (it.isNull(thumbIndex)) null else it.getBlob(thumbIndex)
                out += SearchHit(
                    id = it.getString(idIndex),
                    createdAt = it.getLong(createdIndex),
                    type = CaptureType.fromWire(it.getString(typeIndex)) ?: CaptureType.TEXT,
                    title = if (it.isNull(titleIndex)) null else it.getString(titleIndex),
                    url = if (it.isNull(urlIndex)) null else it.getString(urlIndex),
                    text = if (it.isNull(textIndex)) null else it.getString(textIndex),
                    ocrText = if (it.isNull(ocrIndex)) null else it.getString(ocrIndex),
                    score = score(it),
                    thumbnailB64 = thumbnail?.let { bytes -> Base64.getEncoder().encodeToString(bytes) },
                )
            }
            return out
        }
    }

    private fun escapeLike(value: String): String = value
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")
}
