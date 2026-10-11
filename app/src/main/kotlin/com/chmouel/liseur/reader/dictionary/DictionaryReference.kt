package com.chmouel.liseur.reader.dictionary

import java.net.URLDecoder
import org.jsoup.nodes.Element

/** Only grammatical links qualify; ordinary links inside a meaning are not lookup requests. */
internal fun dictionaryReference(
    item: Element,
    edition: String,
    language: String,
    partOfSpeech: String,
): DictionaryReference? {
    if (language.isBlank() || grammaticalPart(partOfSpeech) == null) return null
    val copy = item.clone()
    copy.select("ul, ol, dl, style, .references, .reference").remove()
    val link = when (edition) {
        "en" -> copy.select(".form-of-definition .form-of-definition-link a[href]").singleOrNull()
        "fr" -> {
            val note = copy.selectFirst("> i")?.text().orEmpty()
            if (!partOfSpeech.startsWith("Forme de ") ||
                !(note.startsWith("Pluriel de") ||
                    note.matches(Regex("(?i).*(personne|participe|féminin|masculin).* de")))) return null
            copy.select("> a[href]").singleOrNull()
        }
        "es" -> {
            val note = copy.selectFirst(".definicion-impropia") ?: return null
            if (!partOfSpeech.startsWith("Forma ") ||
                !note.text().contains(Regex("(?i)plural|persona|participio|gerundio"))) return null
            note.nextElementSibling()?.takeIf { it.tagName() == "a" && it.hasAttr("href") }
        }
        "nl" -> {
            if (!copy.text().matches(Regex(
                    "^(meervoud van het zelfstandig naamwoord|.*(?:tegenwoordige|verleden) tijd van|voltooid deelwoord van|onvoltooid deelwoord van)\\s+.+",
                ))) return null
            copy.select("> a[href]").singleOrNull()
        }
        else -> null
    } ?: return null
    val href = link.attr("href")
    val relative = when {
        href.startsWith("./") -> href.removePrefix("./")
        href.startsWith("/wiki/") -> href.removePrefix("/wiki/")
        else -> return null
    }
    val title = decodeWikiTitle(relative.substringBefore('#')) ?: return null
    if (title.isBlank() || title == "." || title == ".." ||
        title.any { it.isISOControl() || it in ":/?#\\" }) return null
    val fragment = relative.substringAfter('#', "")
    if (fragment.isNotBlank()) {
        val targetLanguage = dictionaryLanguage(decodeWikiTitle(fragment) ?: return null)
        if (targetLanguage != language) return null
    }
    val taggedLanguage = link.parents().firstOrNull { it.hasAttr("lang") }?.attr("lang")
    if (!taggedLanguage.isNullOrBlank() && taggedLanguage != language) return null
    return DictionaryReference(title, language)
}

private fun decodeWikiTitle(value: String): String? = try {
    URLDecoder.decode(value.replace("+", "%2B"), "UTF-8").replace('_', ' ')
} catch (_: IllegalArgumentException) {
    null
}

internal fun grammaticalPart(heading: String): String? {
    val text = heading.lowercase()
    return when {
        text.contains(Regex("\\b(noun|nom commun|nom|sustantiv[oa]|zelfstandig naamwoord)\\b")) -> "noun"
        text.contains(Regex("\\b(verb|verbe|verbal|verbo|werkwoord)\\b")) -> "verb"
        text.contains(Regex("\\b(adjective|adjectif|adjetiv[oa]|bijvoeglijk naamwoord)\\b")) -> "adjective"
        text.contains(Regex("\\b(adverb|adverbe|adverbio|bijwoord)\\b")) -> "adverb"
        else -> null
    }
}

internal fun dictionaryLanguage(heading: String): String? =
    when (heading.trim().lowercase()) {
        "en", "english", "anglais", "inglés", "engels" -> "en"
        "fr", "french", "français", "francés", "frans" -> "fr"
        "es", "spanish", "espagnol", "español", "spaans" -> "es"
        "nl", "dutch", "néerlandais", "neerlandés", "nederlands" -> "nl"
        else -> null
    }
