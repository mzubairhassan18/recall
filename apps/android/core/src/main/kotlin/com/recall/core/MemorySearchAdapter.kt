package com.recall.core

import kotlin.math.ln

/**
 * In-memory BM25 reference adapter. Used by the JVM fixture tests and as the Android fallback
 * when FTS5 is unavailable. Scores are not numerically identical to SQLite's bm25(), but the
 * fixture ordering is stable (the same reference the TypeScript fixtures were reviewed against).
 */
class MemorySearchAdapter(corpus: List<CaptureRecord>) : SearchAdapters {
    private data class Doc(val hit: SearchHit, val tokens: List<String>, val length: Int)

    private val docs: List<Doc>
    private val averageLength: Double

    init {
        docs = corpus.map { row ->
            val tokens = buildList {
                addAll(Normalize.tokenize(row.title ?: ""))
                addAll(Normalize.tokenize(row.text ?: ""))
                addAll(Normalize.tokenize(row.ocrText ?: ""))
                addAll(Normalize.tokenize(row.url ?: ""))
            }
            Doc(
                hit = row.toHit(),
                tokens = tokens,
                length = tokens.size,
            )
        }
        val total = docs.sumOf { maxOf(1, it.length).toDouble() }
        averageLength = if (docs.isNotEmpty()) total / docs.size else 1.0
    }

    override fun ftsQuery(match: String, limit: Int): List<SearchHit> {
        val parsed = parseMatch(match)
        if (parsed.terms.isEmpty()) return emptyList()
        val queryTerms = parsed.terms.toSet().toList()

        return docs
            .map { doc -> doc to bm25(doc, queryTerms, parsed.mode) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Doc, Double>> { it.second }.thenByDescending { it.first.hit.createdAt }.thenBy { it.first.hit.id })
            .take(limit)
            .map { (doc, score) -> doc.hit.copy(score = score) }
    }

    override fun fuzzyQuery(terms: List<String>, limit: Int): List<SearchHit> {
        val needles = terms.map { it.lowercase() }.filter { it.isNotEmpty() }
        if (needles.isEmpty()) return emptyList()
        return docs
            .filter { doc ->
                val haystack = listOfNotNull(doc.hit.title, doc.hit.text, doc.hit.ocrText, doc.hit.url)
                    .joinToString(" ")
                    .lowercase()
                needles.any { haystack.contains(it) }
            }
            .sortedWith(compareByDescending<Doc> { it.hit.createdAt }.thenBy { it.hit.id })
            .take(limit)
            .map { it.hit.copy(score = 0.5) }
    }

    override fun recentQuery(limit: Int): List<SearchHit> = docs
        .sortedWith(compareByDescending<Doc> { it.hit.createdAt }.thenBy { it.hit.id })
        .take(limit)
        .map { it.hit.copy(score = 1.0) }

    private fun bm25(doc: Doc, terms: List<String>, mode: String): Double {
        val k1 = 1.2
        val b = 0.75
        val docLength = maxOf(1, doc.length)
        val frequencies = HashMap<String, Int>()
        for (token in doc.tokens) frequencies[token] = (frequencies[token] ?: 0) + 1

        val matches = terms.filter { (frequencies[it] ?: 0) > 0 }
        if (mode == "and" && matches.size != terms.size) return 0.0
        if (matches.isEmpty()) return 0.0

        var score = 0.0
        for (term in matches) {
            val tf = (frequencies[term] ?: 0).toDouble()
            val df = docs.count { it.tokens.contains(term) }.toDouble()
            val idf = ln(1.0 + (docs.size - df + 0.5) / (df + 0.5))
            score += idf * ((tf * (k1 + 1)) / (tf + k1 * (1 - b + (b * docLength) / averageLength)))
        }
        return score
    }

    private fun parseMatch(match: String): ParsedMatch {
        val regex = Regex("\"([^\"]*)\"")
        val terms = regex.findAll(match).map { it.groupValues[1].lowercase() }.filter { it.isNotEmpty() }.toList()
        return ParsedMatch(terms, if (Regex("\\bAND\\b").containsMatchIn(match)) "and" else "or")
    }

    private data class ParsedMatch(val terms: List<String>, val mode: String)
}
