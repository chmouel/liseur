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
        if (a.first() != b.first()) return false
        val subA = a.getOrNull(1)
        val subB = b.getOrNull(1)
        return subA == null || subB == null || subA == subB
    }

    /** [tag]'s name in [ui], as a list or button shows it on its own: "Chinese (Traditional)". */
    fun name(tag: String, ui: Locale): String {
        val locale = Locale.forLanguageTag(tag)
        val name = locale.getDisplayName(ui).ifBlank { tag }
        return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(ui) else it.toString() }
    }

    /** [tag]'s name in English, for a prompt: "Portuguese (Brazil)". */
    fun englishName(tag: String): String = Locale.forLanguageTag(tag).getDisplayName(Locale.ENGLISH).ifBlank { tag }

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
}
