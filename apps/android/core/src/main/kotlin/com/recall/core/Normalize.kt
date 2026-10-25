package com.recall.core

import java.text.Normalizer
import java.util.Locale

/** Step 1: lowercase, strip punctuation, drop stopwords. Fixed by docs/search-spec.md. */
object Normalize {
    val STOPWORDS: Set<String> = setOf(
        "a", "an", "the", "of", "to", "in", "on", "for", "and", "or", "is", "are", "was", "were",
        "be", "been", "being", "it", "its", "that", "this", "these", "those", "with", "at", "by",
        "from", "as", "about", "into", "over", "after", "before", "i", "me", "my", "we", "our",
        "you", "your", "he", "she", "they", "them", "his", "her", "their", "there", "here", "what",
        "which", "who", "whom", "why", "how", "when", "where", "do", "does", "did", "done", "can",
        "could", "should", "would", "will", "just", "very", "so", "than", "then", "too", "also",
    )

    private val SEPARATOR = Regex("[^\\p{L}\\p{N}]+")

    /** Unicode-aware tokenizer, identical to search-core's `tokenize`. */
    fun tokenize(value: String): List<String> =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKC)
            .split(SEPARATOR)
            .filter { it.isNotEmpty() }

    fun dedupe(values: Iterable<String>): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (value in values) {
            val key = value.lowercase(Locale.ROOT)
            if (!seen.add(key)) continue
            out += value
        }
        return out
    }

    fun normalizeQuery(query: String): QueryTerms {
        val tokens = dedupe(tokenize(query))
        return QueryTerms(tokens = tokens, terms = tokens.filterNot { it in STOPWORDS })
    }
}

data class QueryTerms(val tokens: List<String>, val terms: List<String>)
