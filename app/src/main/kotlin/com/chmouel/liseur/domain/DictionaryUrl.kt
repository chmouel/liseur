package com.chmouel.liseur.domain

/**
 * URL handling for the dictionary server.
 *
 * The site root is what gets stored, not the API path, so one setting
 * drives both the definition request and the "open the full entry" link
 * to the browser.
 *
 * Pure functions, so the awkward inputs have unit tests rather than being
 * discovered when a definition quietly fails to arrive.
 */
object DictionaryUrl {

    const val DEFAULT_BASE_URL = "https://en.wiktionary.org"

    /**
     * Turns what a user typed into a site root: adds `https` when no
     * scheme was given, drops the query, fragment and trailing slash.
     *
     * Plain HTTP is refused. The book server gets a cleartext exemption
     * because it is often a machine on the reader's own network with no
     * certificate; a dictionary is a public site on the internet and has
     * no such excuse, and `network_security_config.xml` is written on
     * that assumption.
     *
     * Returns null when the input cannot be a dictionary site at all.
     */
    fun normalise(input: String): String? {
        val trimmed = input.trim().substringBefore('?').substringBefore('#')
        if (trimmed.isEmpty()) return null

        val withScheme = when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.contains("://") -> return null
            else -> "https://$trimmed"
        }

        val schemeEnd = withScheme.indexOf("://") + 3
        val host = withScheme.substring(schemeEnd).substringBefore('/')
        if (host.isEmpty() || host.startsWith(":") || !host.contains('.')) return null

        return withScheme.trimEnd('/')
    }

    /**
     * The stored value, or the default when nothing usable is stored.
     *
     * Validation happens once, on the way in — [normalise] guards the
     * settings field and the repository setter. Everything downstream
     * only tidies, so that a test can point the client at a local socket
     * without the rule about https getting in the way.
     */
    fun orDefault(stored: String?): String =
        stored?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: DEFAULT_BASE_URL

    /** Where the definition of [term] is fetched from. */
    fun definitionApi(baseUrl: String, term: String): String =
        orDefault(baseUrl) + "/api/rest_v1/page/definition/" + encode(term)

    /**
     * The entry as Parsoid HTML. The tidy [definitionApi] only exists on
     * the English edition — every other edition answers it with a 501 —
     * so this is where their definitions are read from.
     */
    fun pageHtmlApi(baseUrl: String, term: String): String =
        orDefault(baseUrl) + "/api/rest_v1/page/html/" + encode(term)

    /** The full entry, for reading in a browser. */
    fun entryPage(baseUrl: String, term: String): String =
        orDefault(baseUrl) + "/wiki/" + encode(term)

    /**
     * Just the host, for telling the reader who they are about to talk to.
     * A bare hostname says more in a sentence than a full URL does.
     */
    fun hostOf(baseUrl: String): String =
        orDefault(baseUrl).substringAfter("://").substringBefore('/')

    private fun encode(term: String): String =
        java.net.URLEncoder.encode(term, "UTF-8").replace("+", "%20")
}
