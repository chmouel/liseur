package com.chmouel.liseur.reader.dictionary

import com.chmouel.liseur.domain.WiktionaryEditions
import com.chmouel.liseur.translate.TranslationLanguages
import java.util.Locale
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** One sense's translations: Wiktionary's short gloss for it, and the words in the wanted language. */
data class TranslationGroup(val gloss: String, val terms: List<String>)

/**
 * One translations table of an entry, under the part of speech that heads
 * it. A word with several etymologies has several nouns, each its own
 * section, told apart by their glosses. [moved] marks a table that only
 * points at the translations subpage.
 */
data class TranslationSection(
    val partOfSpeech: String,
    val groups: List<TranslationGroup>,
    val moved: Boolean = false,
)

/**
 * The translations an entry page lists into one language, in page order,
 * and the subpage they were moved to when the entry has too many to keep
 * inline (en.wiktionary's `house/translations`). A section whose table was
 * moved is kept empty and marked, as the place the subpage's tables go.
 */
data class EntryTranslations(
    val sections: List<TranslationSection> = emptyList(),
    val subpage: String? = null,
)

/**
 * Reads the translation tables of [language]'s section of a Parsoid entry
 * page, keeping the words in [target].
 *
 * Wiktionary has no translation API: an edition keeps tables only for
 * words in its own language, one box per sense, nested in that part of
 * speech's section. English writes them as `.translations[data-gloss]`
 * boxes of `<span lang="fr">`, French as `.boite` boxes of
 * `<bdi lang="en">` under "Traductions", German as `.uetabelle` tables
 * glossed by `.glosse`. The words
 * are found by their language tag; editions that do not tag them, such as
 * Spanish and Italian, give nothing.
 */
fun parseWiktionaryTranslations(html: String, language: String, target: String): EntryTranslations {
    val codes = wiktionaryCodes(target)
    if (codes.isEmpty()) return EntryTranslations()
    val document = runCatching { Jsoup.parse(html) }.getOrNull() ?: return EntryTranslations()
    val section = document.select("section:has(> h2)").firstOrNull { candidate ->
        headingLanguage(candidate.selectFirst("> h2")?.text().orEmpty(), language) == language
    } ?: return EntryTranslations()

    val sections = mutableListOf<TranslationSection>()
    var subpage: String? = null
    for (translations in section.select("section:has(> $SUBHEADINGS)")) {
        if (!isTranslationHeading(translations.selectFirst("> $SUBHEADINGS")?.text().orEmpty())) continue
        val link = translations.select("a[title]").map { it.attr("title") }
            .firstOrNull { it.endsWith(SUBPAGE_SUFFIX) }
        subpage = subpage ?: link
        val part = translations.parents().asSequence()
            .filter { it.tagName() == "section" }
            .mapNotNull { it.selectFirst("> $SUBHEADINGS")?.text() }
            .firstOrNull { !isTranslationHeading(it) }
            ?.let(::partOfSpeechHeading)
            ?: continue
        val groups = groupsOf(translations, codes, scriptOf(target))
        sections += TranslationSection(part, groups, moved = link != null && groups.isEmpty())
    }
    return EntryTranslations(sections, subpage)
}

/**
 * The entry's tables with the subpage's put where the entry pointed to
 * them: each moved section takes the next subpage section of its part of
 * speech, the last such placeholder takes all that remain, and any the
 * entry did not place come last. An inline table with no words in the
 * target language stays empty.
 */
internal fun EntryTranslations.plus(other: EntryTranslations): EntryTranslations {
    val unused = other.sections.toMutableList()
    val merged = sections.flatMapIndexed { index, section ->
        if (!section.moved) return@flatMapIndexed listOf(section)
        val part = section.partOfSpeech.lowercase()
        val matching = unused.filter { it.partOfSpeech.lowercase() == part }
        val laterPlaceholder = sections.drop(index + 1)
            .any { it.moved && it.partOfSpeech.lowercase() == part }
        (if (laterPlaceholder) matching.take(1) else matching).also { unused.removeAll(it) }
    }
    return EntryTranslations(merged + unused, subpage)
}

/**
 * The stored alternative language that turns translations off. Unset means
 * automatic. Four letters, so no language tag can be read as it.
 */
const val ALT_LANGUAGE_OFF = "none"

/**
 * The language a book's definitions offer translations into, from the
 * [setting]: none when it is [ALT_LANGUAGE_OFF], the chosen language
 * whatever the book is in, and when unset (automatic) the app's
 * [appLanguage], only for a book that declares a [bookLanguage] other
 * than it.
 */
fun altLanguageFor(setting: String?, bookLanguage: String?, appLanguage: String): String? = when {
    setting == ALT_LANGUAGE_OFF -> null
    setting != null -> TranslationLanguages.of(setting)
    else -> {
        val book = TranslationLanguages.of(bookLanguage)
        val app = TranslationLanguages.of(appLanguage)
        if (book == null || app == null || TranslationLanguages.same(book, app)) null else app
    }
}

/**
 * The language to offer translations into for these [senses], looked up
 * on [edition], or null when there is nothing to offer: no [altLanguage],
 * no sense in the edition's own language (the only words with tables), or
 * the alternative being that language.
 */
fun translationTarget(senses: List<DictionarySense>, edition: String, altLanguage: String?): String? {
    val tag = TranslationLanguages.of(altLanguage) ?: return null
    if (TranslationLanguages.same(edition, tag) || senses.none { it.language == edition }) return null
    return tag
}

/**
 * The Wiktionary written in [target], where its words are defined in that
 * language, or null when [baseUrl] is not a Wikimedia Wiktionary (a mirror
 * was chosen so as not to talk to Wikimedia) or the language has no edition
 * the app knows.
 */
fun translationSite(baseUrl: String, target: String): String? {
    if (!WiktionaryEditions.isWiktionaryHost(baseUrl)) return null
    val code = Locale.forLanguageTag(target).language
    // same() also pairs codes for one written language, so Bokmål finds no.wiktionary.org.
    return WiktionaryEditions.all.firstOrNull { TranslationLanguages.same(it.code, code) }?.baseUrl
}

private fun groupsOf(translations: Element, codes: Set<String>, script: String?): List<TranslationGroup> {
    val boxes = translations.select("[data-gloss]").ifEmpty { translations.select(".boite, .uetabelle") }
    if (boxes.isEmpty()) {
        return listOfNotNull(TranslationGroup("", termsOf(translations, codes, script)).takeIf { it.terms.isNotEmpty() })
    }
    return boxes.mapNotNull { box ->
        val gloss = box.attr("data-gloss").ifBlank {
            (box.selectFirst(".nav-head-title, .glosse") ?: box.selectFirst(".NavHead"))?.text().orEmpty()
        }.trim()
        TranslationGroup(gloss, termsOf(box, codes, script)).takeIf { it.terms.isNotEmpty() }
    }
}

private fun termsOf(box: Element, codes: Set<String>, script: String?): List<String> =
    box.select("[lang]")
        .filter { it.attr("lang") in codes && writtenIn(it, script) }
        .filter { term -> term.parents().takeWhile { it != box }.none { it.attr("lang") in codes } }
        .map { it.text().trim() }
        .filter { it.isNotBlank() }
        .distinct()

/**
 * Whether [term] can be in [script]: Wiktionary marks each word's script
 * as a class (`Latn`, `Cyrl`, `Hans`…) or in its tag (`sh-Latn`), and a
 * word marked with none is taken as it comes. Han covers both Chinese
 * scripts.
 */
private fun writtenIn(term: Element, script: String?): Boolean {
    if (script == null) return true
    val tagged = term.attr("lang").split('-').getOrNull(1)?.takeIf { it.length == 4 }
        ?.lowercase()?.replaceFirstChar(Char::uppercaseChar)
    val marked = term.classNames().filter { it in SCRIPTS } + listOfNotNull(tagged)
    return marked.isEmpty() || marked.any { it == script || (it == "Hani" && script in HAN) }
}

/**
 * The `lang` values Wiktionary tags [target]'s words with. English
 * files Chinese under its spoken varieties, Mandarin as `cmn`, Norwegian
 * under Bokmål `nb` beside the macrolanguage `no`, and Serbian, Croatian
 * and Bosnian together as Serbo-Croatian `sh`.
 */
internal fun wiktionaryCodes(target: String): Set<String> {
    val tag = TranslationLanguages.of(target) ?: return emptySet()
    val primary = tag.substringBefore('-')
    return when (primary) {
        "zh" -> setOf("zh", "cmn", tag, "cmn-" + tag.substringAfter('-'))
        "nb", "no" -> setOf("nb", "no")
        "sr", "hr", "bs", "sh" -> setOf(primary, tag, "sh")
        else -> setOf(primary, tag)
    }
}

/** The script [target] is written in, when it says, or when Serbo-Croatian's other scripts must be left out. */
private fun scriptOf(target: String): String? {
    val tag = TranslationLanguages.of(target) ?: return null
    return tag.split('-').getOrNull(1)?.takeIf { it.length == 4 }
        ?: if (tag == "hr" || tag == "bs") "Latn" else null
}

private fun isTranslationHeading(heading: String): Boolean {
    val text = heading.trim().lowercase()
    return TRANSLATION_HEADINGS.any { text.startsWith(it) }
}

private const val SUBHEADINGS = "h3, > h4, > h5, > h6"
internal const val SUBPAGE_SUFFIX = "/translations"

private val HAN = setOf("Hans", "Hant")
private val SCRIPTS = setOf(
    "Latn", "Cyrl", "Grek", "Arab", "Hebr", "Armn", "Geor", "Deva", "Beng", "Taml", "Telu",
    "Thai", "Ethi", "Hani", "Hans", "Hant", "Jpan", "Hira", "Kana", "Kore", "Hang", "Mong",
)

/** "Translations", "Traductions", "Traducciones", "Übersetzungen", … as editions head them. */
private val TRANSLATION_HEADINGS = listOf(
    "translation", "tradu", "übersetzung", "vertaling", "перевод", "tłumaczeni",
    "översättning", "oversettelse", "oversættelse", "käännökset",
)
