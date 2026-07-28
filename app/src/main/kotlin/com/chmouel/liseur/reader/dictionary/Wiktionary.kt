package com.chmouel.liseur.reader.dictionary

import org.json.JSONArray
import org.json.JSONObject

/** One sense of a word, as Wiktionary groups them under a part of speech. */
data class DictionarySense(
    val partOfSpeech: String,
    val definitions: List<String>,
)

/**
 * Wiktionary's definition endpoint answers with an object keyed by language
 * code, each holding the parts of speech for that language. We only keep the
 * languages the reader is likely to want, in the order asked for, and drop the
 * markup Wiktionary embeds in its definitions.
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
            val definitions = entry.optJSONArray("definitions").toDefinitions()
            if (definitions.isEmpty()) continue
            senses += DictionarySense(
                partOfSpeech = entry.optString("partOfSpeech").orEmpty(),
                definitions = definitions,
            )
        }
    }
    return senses
}

private fun JSONArray?.toDefinitions(): List<String> {
    val array = this ?: return emptyList()
    val out = mutableListOf<String>()
    for (i in 0 until array.length()) {
        val text = array.optJSONObject(i)?.optString("definition").orEmpty().stripHtml()
        if (text.isNotBlank()) out += text
    }
    return out
}

/**
 * Wiktionary definitions arrive as a fragment of HTML. Rendering it would mean
 * pulling in a parser for the sake of a few links, so the tags come out and the
 * handful of entities that actually show up are decoded.
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
