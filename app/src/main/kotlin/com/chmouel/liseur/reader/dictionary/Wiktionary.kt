package com.chmouel.liseur.reader.dictionary

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

internal const val MAX_CARD_DEFINITIONS = 4

/** One sense of a word, as Wiktionary groups them under a part of speech. */
data class DictionarySense(
    val partOfSpeech: String,
    val definitions: List<String>,
    val language: String = "",
    val references: Map<Int, DictionaryReference> = emptyMap(),
    val resolved: Map<Int, ResolvedDefinition> = emptyMap(),
)

data class DictionaryReference(val term: String, val language: String)

data class ResolvedDefinition(
    val term: String,
    val senses: List<DictionarySense> = emptyList(),
    val problem: DefinitionProblem? = null,
)

enum class DefinitionProblem { MISSING, FAILED, LIMIT, UNRESOLVED }

/**
 * Wiktionary's definition endpoint answers with an object keyed by language
 * code, each holding the parts of speech for that language. We only keep the
 * languages the reader is likely to want, in the order asked for, and drop the
 * display markup while retaining explicit grammatical links.
 */
fun parseWiktionaryDefinitions(
    json: String,
    languages: List<String> = listOf("en"),
): List<DictionarySense> {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
    val senses = mutableListOf<DictionarySense>()
    for (language in languages) {
        val entries = root.optJSONArray(language) ?: continue
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            val fragments = entry.optJSONArray("definitions").definitionFragments()
            val definitions = fragments.map { it.stripHtml() }
            if (definitions.isEmpty()) continue
            senses += DictionarySense(
                partOfSpeech = entry.optString("partOfSpeech").orEmpty(),
                definitions = definitions,
                language = language,
                references = fragments.mapIndexedNotNull { index, html ->
                    dictionaryReference(Jsoup.parseBodyFragment(html).body(), "en", language,
                        entry.optString("partOfSpeech"))?.let { index to it }
                }.toMap(),
            )
        }
    }
    return senses
}

private fun JSONArray?.definitionFragments(): List<String> {
    val array = this ?: return emptyList()
    val out = mutableListOf<String>()
    for (i in 0 until array.length()) {
        val html = array.optJSONObject(i)?.optString("definition").orEmpty()
        if (html.stripHtml().isNotBlank()) out += html
    }
    return out
}

/**
 * Display text only. Grammatical references are extracted before these tags
 * are removed.
 */
internal fun String.stripHtml(): String =
    replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("\\s+"), " ")
        .trim()

/** Strips the punctuation a selection tends to catch alongside a word. */
fun normaliseLookupTerm(text: String): String =
    text.trim()
        .substringBefore('\n')
        .trim { !it.isLetterOrDigit() && it != '-' && it != '\'' && it != ' ' }
        .trim()
