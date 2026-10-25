package com.recall.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    private fun resource(name: String): String =
        javaClass.getResourceAsStream("/$name")!!.bufferedReader().use { it.readText() }

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun string(objectValue: JsonObject, key: String): String? =
        objectValue[key]?.jsonPrimitive?.contentOrNull

    @Test
    fun `matches every search fixture top-3 using the reference BM25 adapter`() {
        val root = parse(resource("search.json"))
        val now = root["now"]!!.jsonPrimitive.long
        val corpus = root["corpus"]!!.jsonArray.map { element ->
            val row = element.jsonObject
            CaptureRecord(
                id = row["id"]!!.jsonPrimitive.content,
                createdAt = row["created_at"]!!.jsonPrimitive.long,
                type = CaptureType.fromWire(row["type"]!!.jsonPrimitive.content)!!,
                title = string(row, "title"),
                url = string(row, "url"),
                text = string(row, "text"),
                ocrText = string(row, "ocr_text"),
            )
        }
        val synonyms = buildSynonymIndex(parseSynonymDictionary(resource("synonyms.json")))
        val adapter = MemorySearchAdapter(corpus)

        val cases = root["cases"]!!.jsonArray
        assertTrue(cases.size >= 20)
        for (element in cases) {
            val testCase = element.jsonObject
            val id = testCase["id"]!!.jsonPrimitive.content
            val query = testCase["query"]!!.jsonPrimitive.content
            val expected = testCase["expected_top3_ids"]!!.jsonArray.map { it.jsonPrimitive.content }
            val results = search(query, SearchOptions(adapters = adapter, synonyms = synonyms, now = now))
            assertEquals(id, expected, results.take(expected.size).map { it.id })
        }
    }

    @Test
    fun `never returns an empty result for a non-empty corpus`() {
        val root = parse(resource("search.json"))
        val now = root["now"]!!.jsonPrimitive.long
        val corpus = root["corpus"]!!.jsonArray.map { element ->
            val row = element.jsonObject
            CaptureRecord(
                id = row["id"]!!.jsonPrimitive.content,
                createdAt = row["created_at"]!!.jsonPrimitive.long,
                type = CaptureType.fromWire(row["type"]!!.jsonPrimitive.content)!!,
                title = string(row, "title"),
                url = string(row, "url"),
                text = string(row, "text"),
                ocrText = string(row, "ocr_text"),
            )
        }
        val adapter = MemorySearchAdapter(corpus)
        for (query in listOf("zzzzz", "the of and", "")) {
            val results = search(query, SearchOptions(adapters = adapter, now = now))
            assertTrue(query, results.size >= 10)
        }
    }

    @Test
    fun `normalizes and expands with synonyms`() {
        val normalized = Normalize.normalizeQuery("The Distributed-Locks and FENCING!")
        assertEquals(listOf("distributed", "locks", "fencing"), normalized.terms)

        val synonyms = buildSynonymIndex(
            SynonymDictionary(
                version = 1,
                groups = mapOf("lock" to listOf("mutex", "semaphore")),
            ),
        )
        assertEquals(listOf("lock", "mutex", "semaphore"), expandTerms(listOf("lock"), synonyms))
        assertEquals(listOf("other", "lock", "mutex", "semaphore"), expandTerms(listOf("other", "lock"), synonyms))
    }

    @Test
    fun `builds snippets around the first original term`() {
        val hit = SearchHit(
            id = "x",
            createdAt = 0,
            type = CaptureType.TEXT,
            title = "Title",
            url = null,
            text = "A ".repeat(80) + "distributed lock " + "B ".repeat(80),
            ocrText = null,
            score = 0.0,
        )
        val snippet = Snippets.makeSnippet(hit, listOf("lock"))
        assertTrue(snippet.contains("distributed lock"))
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
    }

    @Test
    fun `parse synonym dictionary is defensive`() {
        assertEquals(null, parseSynonymDictionary("not json"))
        assertEquals(null, parseSynonymDictionary("""{"version":1}"""))
        val parsed = parseSynonymDictionary(
            """{"version":2,"groups":{"lock":["mutex",7,"semaphore"]}}""",
        )
        assertEquals(2, parsed?.version)
        assertEquals(listOf("mutex", "semaphore"), parsed?.groups?.get("lock"))
    }
}
