package com.recall.core

import kotlin.math.round

/** 180-day recency half-life — docs/search-spec.md. */
const val RECENCY_DAYS = 180.0
private const val DAY_MS = 86_400_000.0
private const val FTS_LIMIT = 50
private const val FUZZY_LIMIT = 20
private const val FUZZY_THRESHOLD = 3
private const val FALLBACK_MIN = 10
private const val FALLBACK_POOL = 100
private const val FUZZY_SCORE = 0.5
private const val RECENT_SCORE = 0.25
private const val SHARED_SCORE = 0.1

/** Injected SQLite adapter contract (mirrors packages/shared-types). */
interface SearchAdapters {
    fun ftsQuery(match: String, limit: Int): List<SearchHit>
    fun fuzzyQuery(terms: List<String>, limit: Int): List<SearchHit>
    fun recentQuery(limit: Int): List<SearchHit>
}

data class SearchOptions(
    val adapters: SearchAdapters,
    val synonyms: SynonymIndex = SynonymIndex.EMPTY,
    val now: Long = System.currentTimeMillis(),
    val limit: Int = 50,
    val ftsLimit: Int = FTS_LIMIT,
    val fuzzyThreshold: Int = FUZZY_THRESHOLD,
)

fun recencyMultiplier(createdAt: Long, now: Long): Double {
    val ageDays = maxOf(0.0, (now - createdAt) / DAY_MS)
    return 1.0 / (1.0 + ageDays / RECENCY_DAYS)
}

/** Safe FTS5 MATCH expression: each term quoted, OR-joined. */
fun buildMatchExpression(terms: List<String>): String = terms
    .map { it.replace("\"", "").trim() }
    .filter { it.isNotEmpty() }
    .joinToString(" OR ") { "\"$it\"" }

/**
 * The fixed 7-step pipeline. Pure over the injected adapters; no SQLite, no clock, no I/O.
 * `now` is injectable so fixtures are deterministic.
 */
fun search(query: String, options: SearchOptions): List<SearchResult> {
    val now = options.now
    val limit = maxOf(1, options.limit)
    val adapters = options.adapters

    // 1. normalize, 2. expand
    val queryTerms = Normalize.normalizeQuery(query)
    val terms = queryTerms.terms
    val expanded = expandTerms(terms, options.synonyms)

    val merged = LinkedHashMap<String, Entry>()
    fun add(hit: SearchHit, score: Double) {
        if (!score.isFinite()) return
        val existing = merged[hit.id]
        if (existing == null || score > existing.score) merged[hit.id] = Entry(hit, score)
    }

    // 3. FTS5 + BM25
    if (expanded.isNotEmpty()) {
        val match = buildMatchExpression(expanded)
        for (hit in adapters.ftsQuery(match, options.ftsLimit)) {
            // 4. recency decay
            add(hit, hit.score * recencyMultiplier(hit.createdAt, now))
        }
    }

    // 5. fuzzy fallback when FTS is thin
    if (merged.size < options.fuzzyThreshold && terms.isNotEmpty()) {
        for (hit in adapters.fuzzyQuery(terms, FUZZY_LIMIT)) {
            add(hit, FUZZY_SCORE * recencyMultiplier(hit.createdAt, now))
        }
    }

    // 6. never return an empty screen
    if (merged.isEmpty()) {
        val pool = adapters.recentQuery(FALLBACK_POOL)

        for (hit in pool.take(5)) {
            add(hit, RECENT_SCORE * recencyMultiplier(hit.createdAt, now))
        }

        if (terms.isNotEmpty()) {
            val shared = pool
                .map { hit -> hit to countSharedTokens(hit, terms) }
                .filter { it.second > 0 }
                .sortedWith(compareByDescending<Pair<SearchHit, Int>> { it.second }.thenByDescending { it.first.createdAt })
                .take(5)
            for ((hit, count) in shared) {
                add(hit, SHARED_SCORE * count * recencyMultiplier(hit.createdAt, now))
            }
        }

        for (hit in pool) {
            if (merged.size >= FALLBACK_MIN) break
            add(hit, RECENT_SCORE * recencyMultiplier(hit.createdAt, now))
        }
    }

    // 7. shape the result rows
    return merged.values
        .filter { it.score > 0 }
        .sortedWith(
            compareByDescending<Entry> { it.score }
                .thenByDescending { it.hit.createdAt }
                .thenBy { it.hit.id },
        )
        .take(limit)
        .map { entry ->
            SearchResult(
                id = entry.hit.id,
                title = Snippets.displayTitle(entry.hit),
                snippet = Snippets.makeSnippet(entry.hit, terms),
                createdAt = entry.hit.createdAt,
                type = entry.hit.type,
                score = round(entry.score * 1e9) / 1e9,
                url = entry.hit.url,
                thumbnailB64 = entry.hit.thumbnailB64,
            )
        }
}

private data class Entry(val hit: SearchHit, val score: Double)

private fun countSharedTokens(hit: SearchHit, terms: List<String>): Int {
    val haystack = Normalize.dedupe(
        Normalize.normalizeQuery(
            listOfNotNull(hit.title, hit.text, hit.ocrText, hit.url).joinToString(" "),
        ).tokens,
    )
    val set = haystack.toHashSet()
    var count = 0
    for (term in terms) if (term in set) count += 1
    return count
}
