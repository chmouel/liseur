package com.chmouel.liseur.domain

/**
 * A Wiktionary language edition the settings picker can offer.
 *
 * @param code The subdomain, which is also the wiki's language code.
 * @param nativeName The edition's name in its own language, because a
 *   reader picking the French dictionary looks for «Français», not for
 *   the English word for it.
 */
data class WiktionaryEdition(val code: String, val nativeName: String) {
    val baseUrl: String get() = "https://$code.wiktionary.org"
}

/**
 * The Wiktionary editions the settings screen offers by name.
 *
 * A reader used to type the edition's URL by hand, and a single typo'd
 * letter became a stored setting that failed later, in the reader, as a
 * bare HTTP error (fr.wiktionary misspelled was the bug report that
 * bought this list). Picking a name cannot be misspelled. The list is
 * the larger editions; anything else still fits through the custom URL
 * field.
 */
object WiktionaryEditions {

    val all: List<WiktionaryEdition> = listOf(
        WiktionaryEdition("en", "English"),
        WiktionaryEdition("fr", "Français"),
        WiktionaryEdition("de", "Deutsch"),
        WiktionaryEdition("es", "Español"),
        WiktionaryEdition("it", "Italiano"),
        WiktionaryEdition("pt", "Português"),
        WiktionaryEdition("nl", "Nederlands"),
        WiktionaryEdition("pl", "Polski"),
        WiktionaryEdition("sv", "Svenska"),
        WiktionaryEdition("da", "Dansk"),
        WiktionaryEdition("no", "Norsk"),
        WiktionaryEdition("fi", "Suomi"),
        WiktionaryEdition("cs", "Čeština"),
        WiktionaryEdition("hu", "Magyar"),
        WiktionaryEdition("ro", "Română"),
        WiktionaryEdition("ca", "Català"),
        WiktionaryEdition("el", "Ελληνικά"),
        WiktionaryEdition("ru", "Русский"),
        WiktionaryEdition("uk", "Українська"),
        WiktionaryEdition("tr", "Türkçe"),
        WiktionaryEdition("ar", "العربية"),
        WiktionaryEdition("fa", "فارسی"),
        WiktionaryEdition("hi", "हिन्दी"),
        WiktionaryEdition("ja", "日本語"),
        WiktionaryEdition("ko", "한국어"),
        WiktionaryEdition("zh", "中文"),
    )

    /**
     * The edition a stored URL points at, or null when it is something
     * else — a mirror, a proxy, a hand-typed address. The picker uses
     * this to show the right name for what is stored, and to know when
     * to fall back to the custom field instead.
     */
    fun editionOf(baseUrl: String?): WiktionaryEdition? {
        val host = baseUrl?.let(DictionaryUrl::hostOf) ?: return null
        val code = host.removeSuffix(".wiktionary.org")
        if (code == host || code.contains('.')) return null
        return all.firstOrNull { it.code.equals(code, ignoreCase = true) }
    }

    /**
     * Whether a URL points at a real Wikimedia-hosted Wiktionary — any
     * edition, not just the listed ones. Those are the hosts where the
     * REST API's shape is known: `page/definition` on the English one,
     * `page/html` everywhere.
     */
    fun isWiktionaryHost(baseUrl: String): Boolean {
        val host = DictionaryUrl.hostOf(baseUrl)
        val code = host.removeSuffix(".wiktionary.org")
        return code != host && code.isNotEmpty() && !code.contains('.')
    }
}
