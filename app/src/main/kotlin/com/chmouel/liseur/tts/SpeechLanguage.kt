package com.chmouel.liseur.tts

import java.util.Locale
import java.util.MissingResourceException

/** What a book says it is written in, as reading aloud takes it. */
internal sealed interface BookLanguage {
    /** One language; [tag] is the most precise tag declared for it, such as `fr-CA`. */
    data class Known(val tag: String) : BookLanguage

    /** No language declared, or only `und`. */
    data object Missing : BookLanguage

    /** Several different languages, one tag for each, in the order declared. */
    data class Ambiguous(val tags: List<String>) : BookLanguage
}

/**
 * Language tags as reading aloud compares them: books, engines and
 * servers write the same language in different ways (`eng`, `en_us`,
 * `fre-FR`), so each is brought to one BCP 47 form first. Voices are
 * remembered per primary language, so `en-US` and `en-GB` share a choice,
 * while the region is kept for matching an accent.
 */
internal object SpeechLanguage {
    /** [tag] in canonical form, such as `en-US` for `eng_us`; null for none, `und`, or one that is not a language. */
    fun normalize(tag: String?): String? {
        val parts = tag?.trim()?.split('-', '_')?.filter { it.isNotEmpty() } ?: return null
        val first = parts.firstOrNull()?.lowercase(Locale.ROOT) ?: return null
        if (first.length !in 2..3 || first.any { it !in 'a'..'z' }) return null
        val language = canonical(first) ?: return null
        val rest = parts.drop(1).map { sub ->
            when {
                sub.length == 4 && sub.all(Char::isLetter) ->
                    sub.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
                sub.length == 2 && sub.all(Char::isLetter) -> sub.uppercase(Locale.ROOT)
                sub.length == 3 && sub.all(Char::isDigit) -> sub
                sub.length == 3 && sub.all(Char::isLetter) ->
                    countries[sub.uppercase(Locale.ROOT)] ?: sub.lowercase(Locale.ROOT)
                else -> sub.lowercase(Locale.ROOT)
            }
        }
        return (listOf(language) + rest).joinToString("-")
    }

    /** The language a normalized [tag] names, without script or region: `en` for `en-US`. */
    fun primary(tag: String): String = tag.substringBefore('-')

    /** The region a normalized [tag] names, such as `US`, or null. */
    fun region(tag: String): String? = tag.split('-').drop(1)
        .firstOrNull { (it.length == 2 && it.all(Char::isUpperCase)) || (it.length == 3 && it.all(Char::isDigit)) }

    /** The language of a book declaring [declared] tags, in the order its metadata lists them. */
    fun ofBook(declared: List<String>): BookLanguage {
        val byLanguage = declared.mapNotNull(::normalize).groupBy(::primary)
        // The most precise tag of each language: `en-GB` says more than `en`.
        val tags = byLanguage.values.map { same -> same.maxBy { it.count { c -> c == '-' } } }
        return when (tags.size) {
            0 -> BookLanguage.Missing
            1 -> BookLanguage.Known(tags.single())
            else -> BookLanguage.Ambiguous(tags)
        }
    }

    private fun canonical(code: String): String? {
        if (code in NONE) return null
        val two = if (code.length == 3) MACRO[code] ?: BIBLIOGRAPHIC[code] ?: iso3[code] ?: code else code
        return DEPRECATED[two] ?: two
    }

    private val NONE = setOf("und", "mul", "zxx", "mis")

    /** Codes Android or the JDK may still use for these. */
    private val DEPRECATED = mapOf("iw" to "he", "in" to "id", "ji" to "yi", "jw" to "jv", "mo" to "ro")

    /** ISO 639-2/B codes, which libraries print and the JDK does not map. */
    private val BIBLIOGRAPHIC = mapOf(
        "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "chi" to "zh", "cze" to "cs",
        "dut" to "nl", "fre" to "fr", "geo" to "ka", "ger" to "de", "gre" to "el", "ice" to "is",
        "mac" to "mk", "mao" to "mi", "may" to "ms", "per" to "fa", "rum" to "ro", "slo" to "sk",
        "tib" to "bo", "wel" to "cy",
    )

    /** Individual languages of a macrolanguage that books and services name for the macrolanguage. */
    private val MACRO = mapOf(
        "cmn" to "zh", "arb" to "ar", "zsm" to "ms", "pes" to "fa", "prs" to "fa", "npi" to "ne",
        "swh" to "sw", "als" to "sq", "lvs" to "lv", "ekk" to "et", "khk" to "mn", "uzn" to "uz",
        "azb" to "az", "azj" to "az", "ory" to "or", "pbt" to "ps", "fuv" to "ff", "ydd" to "yi",
    )

    /** ISO 639-2/T codes to their two letters, as the JDK knows them. */
    private val iso3: Map<String, String> by lazy {
        Locale.getISOLanguages().mapNotNull { two ->
            try {
                Locale.forLanguageTag(two).isO3Language.takeIf { it.length == 3 }?.let { it to two }
            } catch (_: MissingResourceException) {
                null
            }
        }.toMap()
    }

    /** ISO 3166 alpha-3 countries to their two letters, which some engines report. */
    private val countries: Map<String, String> by lazy {
        Locale.getISOCountries().mapNotNull { two ->
            try {
                Locale.Builder().setRegion(two).build().isO3Country.takeIf { it.length == 3 }?.let { it to two }
            } catch (_: MissingResourceException) {
                null
            }
        }.toMap()
    }
}
