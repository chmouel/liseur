package com.chmouel.liseur.reader.dictionary

import java.util.Locale
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Reads definitions out of a Wiktionary page as Parsoid HTML
 * (`/api/rest_v1/page/html/{term}`).
 *
 * The tidy JSON endpoint (`page/definition`) only exists on
 * en.wiktionary.org — every other edition answers it with a 501 — so the
 * other editions get their entry page parsed instead. The page is one
 * `<section>` per language with an `<h2>` naming it, part-of-speech
 * subsections under `<h3>`, and the senses in an `<ol>` (most editions),
 * a `<dl>` of `[1]`-numbered `<dd>`s (German style) or a `<dl>` of
 * numbered `<dt>`s (Spanish style).
 *
 * Pure function on a string, like [parseWiktionaryDefinitions], so the
 * awkward pages become fixtures rather than field reports.
 */
fun parseWiktionaryEntryHtml(
    html: String,
    languages: List<String> = emptyList(),
): List<DictionarySense> {
    val document = runCatching { Jsoup.parse(html) }.getOrNull() ?: return emptyList()
    val sections = document.select("section:has(> h2)")
    if (sections.isEmpty()) return emptyList()

    val wantedNames = languages.flatMap(::displayNamesOf).toSet()
    val ordered = sections.sortedByDescending { section ->
        val heading = section.selectFirst("> h2")?.text().orEmpty().lowercase()
        wantedNames.count { heading.contains(it) }
    }

    for (section in ordered) {
        val senses = sensesOf(section)
        if (senses.isNotEmpty()) return senses
    }
    return emptyList()
}

/**
 * A language's name as each Wiktionary edition would write it in a
 * section heading: «Français» at home, "French" in English, «Francés»
 * in Spanish. The page does not say which edition it came from, so the
 * heading is matched against all the spellings at once.
 */
private fun displayNamesOf(languageCode: String): List<String> {
    val language = Locale.forLanguageTag(languageCode)
    if (language.language.isEmpty()) return emptyList()
    return HEADING_LOCALES
        .map { language.getDisplayLanguage(Locale.forLanguageTag(it)).lowercase() }
        .filter { it.isNotBlank() && it != language.language }
        .distinct()
}

private val HEADING_LOCALES = listOf(
    "en", "fr", "de", "es", "it", "pt", "nl", "pl", "sv", "da", "no", "fi",
    "cs", "hu", "ro", "ca", "el", "ru", "uk", "tr", "ar", "fa", "hi", "ja",
    "ko", "zh",
)

private fun sensesOf(languageSection: Element): List<DictionarySense> {
    val senses = mutableListOf<DictionarySense>()
    val subsections = languageSection.select("section:has(> h3), section:has(> h4)")
    for (subsection in subsections) {
        val definitions = definitionsOf(subsection)
        if (definitions.isEmpty()) continue
        val heading = subsection.selectFirst("> h3, > h4")?.text().orEmpty()
        senses += DictionarySense(
            partOfSpeech = heading.replace(Regex("\\s*\\d+$"), "").trim(),
            definitions = definitions,
        )
    }
    return senses
}

private fun definitionsOf(subsection: Element): List<String> {
    val fromLists = subsection.select("> ol, > p ~ ol")
        .asSequence()
        .filterNot { it.hasClass("references") || it.attr("typeof").contains("mw:Extension/references") }
        .flatMap { it.select("> li") }
        .filterNot { it.id().startsWith("cite_note") }
        .map(::definitionText)
        .filter { it.isNotBlank() }
        .toList()
    if (fromLists.isNotEmpty()) return fromLists

    // German-style <dd>[1] …</dd> and Spanish-style <dt>1 …</dt><dd>…</dd>.
    val fromGlossaries = mutableListOf<String>()
    for (glossary in subsection.select("dl")) {
        for (item in glossary.select("> dd")) {
            val text = definitionText(item)
            if (text.matches(NUMBERED_DEFINITION)) fromGlossaries += text
        }
        if (fromGlossaries.isNotEmpty()) continue
        for (term in glossary.select("> dt")) {
            if (!term.text().trim().matches(NUMBERED_TERM)) continue
            val text = definitionText(term.nextElementSibling()?.takeIf { it.tagName() == "dd" } ?: continue)
            if (text.isNotBlank()) fromGlossaries += text
        }
    }
    return fromGlossaries.map { it.replace(LEADING_NUMBER, "") }
}

/**
 * The item's own words: quotations, sub-senses and usage examples nest
 * inside the item as further lists, and belong to the full entry, not
 * to a card.
 */
private fun definitionText(item: Element): String {
    val copy = item.clone()
    copy.select("ul, ol, dl, style, .references").forEach(Element::remove)
    return copy.text().trim()
}

private val NUMBERED_DEFINITION = Regex("^\\[\\d+[a-z]?].*", RegexOption.DOT_MATCHES_ALL)
private val NUMBERED_TERM = Regex("^\\d+[a-z]?\\b.*", RegexOption.DOT_MATCHES_ALL)
private val LEADING_NUMBER = Regex("^\\[\\d+[a-z]?]\\s*")
