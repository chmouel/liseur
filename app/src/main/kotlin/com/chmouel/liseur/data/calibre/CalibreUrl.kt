package com.chmouel.liseur.data.calibre

import com.chmouel.liseur.data.remote.RemoteUrl

/**
 * URL handling for a calibre-web server. Pure functions, so the awkward
 * cases have unit tests rather than being discovered on a real server.
 */
object CalibreUrl {

    /**
     * Turns what a user typed into a base URL: adds a scheme when none
     * was given, drops a trailing slash, and keeps any path prefix,
     * because calibre-web is often reverse-proxied under one.
     *
     * Returns null when the input cannot be a URL at all.
     */
    fun normaliseBaseUrl(input: String, defaultScheme: String = "https"): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        val withScheme = when {
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.contains("://") -> return null
            else -> "$defaultScheme://$trimmed"
        }

        val schemeEnd = withScheme.indexOf("://") + 3
        val host = withScheme.substring(schemeEnd).substringBefore('/')
        if (host.isEmpty() || host.startsWith(":")) return null

        return withScheme.trimEnd('/')
    }

        /** Swaps `https` for `http`, used only after the user asks for it. */
    fun withHttp(baseUrl: String): String =
        if (baseUrl.startsWith("https://")) "http://" + baseUrl.removePrefix("https://") else baseUrl

    /**
     * Resolves a link from a feed against the server. Shared with the
     * other kinds of server, which have the same problem.
     */
    fun resolve(baseUrl: String, href: String): String =
        RemoteUrl.resolve(baseUrl, href)
}
