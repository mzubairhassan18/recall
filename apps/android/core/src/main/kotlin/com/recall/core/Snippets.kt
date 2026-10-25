package com.recall.core

/** Snippet extraction — docs/search-spec.md "Snippets". */
object Snippets {
    private const val BEFORE = 60
    private const val AFTER = 120
    private const val FALLBACK = 160

    fun makeSnippet(hit: SearchHit, originalTerms: List<String>): String {
        val preferred = firstNonEmpty(hit.text, hit.ocrText, hit.title, hit.url)
        if (preferred.isEmpty()) return ""

        val haystack = preferred.replace(Regex("\\s+"), " ").trim()
        var bestIndex = -1
        for (term in originalTerms) {
            if (term.isEmpty()) continue
            val index = haystack.lowercase().indexOf(term.lowercase())
            if (index == -1) continue
            if (bestIndex == -1 || index < bestIndex) bestIndex = index
        }

        if (bestIndex == -1) {
            return if (haystack.length <= FALLBACK) haystack
            else haystack.substring(0, FALLBACK).trimEnd() + "…"
        }

        val rawStart = maxOf(0, bestIndex - BEFORE)
        val rawEnd = minOf(haystack.length, bestIndex + AFTER)
        var start = rawStart
        var end = rawEnd
        if (start > 0) {
            val space = haystack.indexOf(' ', start)
            if (space != -1 && space < bestIndex) start = space + 1
        }
        if (end < haystack.length) {
            val space = haystack.lastIndexOf(' ', end)
            if (space != -1 && space > bestIndex) end = space
        }

        return (if (start > 0) "…" else "") +
            haystack.substring(start, end).trim() +
            (if (end < haystack.length) "…" else "")
    }

    fun displayTitle(hit: SearchHit): String {
        val title = firstNonEmpty(hit.title, hit.url, hit.text, hit.ocrText)
        if (title.isEmpty()) return "(untitled)"
        val flat = title.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= 120) flat else flat.substring(0, 120).trimEnd() + "…"
    }

    private fun firstNonEmpty(vararg values: String?): String {
        for (value in values) {
            if (value != null && value.trim().isNotEmpty()) return value
        }
        return ""
    }
}
