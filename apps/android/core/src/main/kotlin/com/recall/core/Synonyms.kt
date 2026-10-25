package com.recall.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bidirectional synonym index. Every term in a group expands to every other term in the same
 * group. Built at runtime from assets/synonyms.json.
 */
class SynonymIndex(val lookup: Map<String, List<String>>) {
    val size: Int get() = lookup.size

    companion object {
        val EMPTY = SynonymIndex(emptyMap())
    }
}

data class SynonymDictionary(val version: Int, val groups: Map<String, List<String>>)

fun buildSynonymIndex(dictionary: SynonymDictionary?): SynonymIndex {
    val groups = LinkedHashMap<String, LinkedHashSet<String>>()
    dictionary?.groups?.forEach { (canonical, aliases) ->
        val members = LinkedHashSet<String>()
        for (raw in listOf(canonical) + aliases) {
            members.addAll(Normalize.tokenize(raw))
        }
        if (members.size < 2) return@forEach
        for (member in members) {
            val existing = groups.getOrPut(member) { LinkedHashSet() }
            for (other in members) if (other != member) existing.add(other)
        }
    }

    val lookup = LinkedHashMap<String, List<String>>()
    for ((term, expanded) in groups) lookup[term] = expanded.sorted()
    return SynonymIndex(lookup)
}

/** Expand terms with synonyms, original terms first, capped to keep MATCH expressions sane. */
fun expandTerms(
    terms: List<String>,
    synonyms: SynonymIndex = SynonymIndex.EMPTY,
    cap: Int = 24,
): List<String> {
    val out = ArrayList<String>()
    for (term in terms) {
        if (out.size >= cap) break
        out += term
        val expanded = synonyms.lookup[term] ?: continue
        for (synonym in expanded) {
            if (out.size >= cap) break
            out += synonym
        }
    }
    return Normalize.dedupe(out)
}

/** Parse the on-disk synonyms.json shape defensively. Returns null when unusable. */
fun parseSynonymDictionary(text: String): SynonymDictionary? {
    val root = try {
        Json.parseToJsonElement(text).jsonObject
    } catch (_: Exception) {
        return null
    }
    val groupsObject = root["groups"] as? JsonObject ?: return null
    val groups = LinkedHashMap<String, List<String>>()
    for ((key, value) in groupsObject) {
        val array = value as? JsonArray ?: continue
        groups[key] = array.mapNotNull { element ->
            try {
                element.jsonPrimitive.takeIf { it.isString }?.content
            } catch (_: Exception) {
                null
            }
        }
    }
    val version = root["version"]?.jsonPrimitive?.intOrNull ?: 1
    return SynonymDictionary(version = version, groups = groups)
}
