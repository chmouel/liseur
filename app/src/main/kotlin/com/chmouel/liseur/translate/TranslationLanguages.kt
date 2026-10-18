package com.chmouel.liseur.translate

import com.chmouel.liseur.tts.SpeechLanguage
import java.text.Collator
import java.util.Locale

/**
 * Languages as translation compares and names them. A tag keeps what
 * changes the text: the script (`zh-Hans` and `zh-Hant`, `sr-Latn`) and,
 * for Portuguese, the region (`pt-BR`, `pt-PT`). Any other region is
 * dropped, as no service translates into `en-GB` differently from `en`.
 */
internal object TranslationLanguages {
    /** [tag] as translation keeps it, such as `zh-Hant` for `zh_TW`; null when it is not a language. */
    fun of(tag: String?): String? {
        val normal = SpeechLanguage.normalize(tag) ?: return null
        val primary = SpeechLanguage.primary(normal)
        val parts = normal.split('-').drop(1)
        val script = parts.firstOrNull { it.length == 4 && it.all(Char::isLetter) }
        val region = SpeechLanguage.region(normal)
        return when {
            primary == "zh" -> "zh-" + (script ?: if (region in TRADITIONAL) "Hant" else "Hans")
            script != null -> "$primary-$script"
            primary == "pt" && region in PORTUGUESE -> "pt-$region"
            else -> primary
        }
    }

    /**
     * The passage's language, from what the book declares: null when it
     * declares none, or several, as the passage could be in any of them.
     */
    fun source(declared: List<String>): String? = declared(declared).singleOrNull()

    /**
     * The languages a book declares, as translation keeps them, for the top
     * of the source list. A bare language beside a precise one of its own,
     * `pt` with `pt-BR`, says nothing more and is left out; two scripts or
     * Portuguese regions stay apart.
     */
    fun declared(declared: List<String>): List<String> {
        val normal = declared.mapNotNull(SpeechLanguage::normalize).distinct()
        return normal.filterNot { tag -> '-' !in tag && normal.any { it != tag && SpeechLanguage.primary(it) == tag } }
            .mapNotNull(::of)
            .distinct()
    }

    /** The language to translate into: the reader's pick, else the app's own language. */
    fun target(stored: String?, app: Locale): String = of(stored) ?: of(app.toLanguageTag()) ?: "en"

    /**
     * Whether [source] and [target] are one language, so there is nothing
     * to translate. A side without a script or region matches either.
     */
    fun same(source: String, target: String): Boolean {
        val a = source.split('-')
        val b = target.split('-')
        if (a.first().let { SAME[it] ?: it } != b.first().let { SAME[it] ?: it }) return false
        val subA = a.getOrNull(1)
        val subB = b.getOrNull(1)
        return subA == null || subB == null || subA == subB
    }

    // Codes services use for one written language: Bokmål as Norwegian, Filipino as Tagalog.
    private val SAME = mapOf("nb" to "no", "fil" to "tl")

    /** [tags] a passage in [source] can go into: all but [source] itself, or all when it is unknown. */
    fun targets(tags: Collection<String>, source: String?): List<String> =
        if (source == null) tags.toList() else tags.filterNot { same(source, it) }

    /** [tag]'s name in [ui], as a list or button shows it on its own: "Chinese (Traditional)". */
    fun name(tag: String, ui: Locale): String {
        val locale = Locale.forLanguageTag(tag)
        val name = locale.getDisplayName(ui).ifBlank { tag }
        return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(ui) else it.toString() }
    }

    /** [tag]'s name in English, for a prompt: "Portuguese (Brazil)". */
    fun englishName(tag: String): String = Locale.forLanguageTag(tag).getDisplayName(Locale.ENGLISH).ifBlank { tag }

    /**
     * A flag for [tag], as an emoji, when one country is plainly its home or
     * the tag names it (`pt-BR`, `zh-Hans`); null for a language many
     * countries share, such as English, Spanish or Arabic, or a script
     * written in several (`zh-Hant`), rather than pick one of them for
     * every reader.
     */
    fun flag(tag: String): String? {
        val region = FLAG_REGIONS[tag] ?: FLAG_REGIONS[tag.substringBefore('-')]?.takeIf { tag.substringBefore('-') !in BY_TAG_ONLY }
        return region?.let { code -> code.map { String(Character.toChars(REGIONAL_A + (it - 'A'))) }.joinToString("") }
    }

    /** [tag]'s [name] behind its [flag], for text that names it among other words. */
    fun flagged(tag: String, ui: Locale): String = flag(tag)?.let { "$it ${name(tag, ui)}" } ?: name(tag, ui)

    /** Every language the platform names, as translation keeps them. */
    fun all(): List<String> = (
        Locale.getAvailableLocales().mapNotNull { of(it.toLanguageTag()) } +
            listOf("zh-Hans", "zh-Hant", "pt-BR", "pt-PT")
        ).filter { it != "pt" }.distinct()

    /**
     * [tags] for a picker: [pinned] first in their order, then the rest
     * sorted by their names in [ui], as a reader of that language sorts.
     */
    fun ordered(tags: Collection<String>, pinned: List<String>, ui: Locale): List<String> {
        val collator = Collator.getInstance(ui)
        val first = pinned.filter { it in tags }.distinct()
        val rest = tags.filter { it !in first }.distinct().sortedWith { x, y -> collator.compare(name(x, ui), name(y, ui)) }
        return first + rest
    }

    private val TRADITIONAL = setOf("TW", "HK", "MO")
    private val PORTUGUESE = setOf("BR", "PT")

    private const val REGIONAL_A = 0x1F1E6

    // Languages flagged only by a tag that names the country: bare `pt` or `zh` is left without.
    private val BY_TAG_ONLY = setOf("pt", "zh")

    private val FLAG_REGIONS = mapOf(
        "zh-Hans" to "CN", "pt-BR" to "BR", "pt-PT" to "PT",
        "am" to "ET", "az" to "AZ", "be" to "BY", "bg" to "BG", "bn" to "BD", "bs" to "BA",
        "cs" to "CZ", "cy" to "GB", "da" to "DK", "de" to "DE", "el" to "GR", "et" to "EE",
        "fa" to "IR", "fi" to "FI", "fil" to "PH", "fr" to "FR", "ga" to "IE", "he" to "IL",
        "hi" to "IN", "hr" to "HR", "hu" to "HU", "hy" to "AM", "id" to "ID", "is" to "IS",
        "it" to "IT", "ja" to "JP", "ka" to "GE", "kk" to "KZ", "km" to "KH", "ko" to "KR",
        "lo" to "LA", "lt" to "LT", "lv" to "LV", "mk" to "MK", "mn" to "MN", "ms" to "MY",
        "mt" to "MT", "my" to "MM", "nb" to "NO", "ne" to "NP", "nl" to "NL", "no" to "NO",
        "pl" to "PL", "ro" to "RO", "ru" to "RU", "si" to "LK", "sk" to "SK", "sl" to "SI",
        "sq" to "AL", "sr" to "RS", "sv" to "SE", "th" to "TH", "tl" to "PH", "tr" to "TR",
        "uk" to "UA", "ur" to "PK", "uz" to "UZ", "vi" to "VN",
    )
}
