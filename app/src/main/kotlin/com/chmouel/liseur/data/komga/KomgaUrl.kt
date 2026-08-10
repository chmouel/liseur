package com.chmouel.liseur.data.komga

import com.chmouel.liseur.data.remote.RemoteUrl

/**
 * URL handling for a Komga server. Pure functions, so the awkward cases
 * have unit tests rather than being discovered on a real server.
 */
object KomgaUrl {

    /** How many parent paths to try before giving up. */
    private const val MAX_CANDIDATES = 6

    /**
     * Every base URL worth trying for what the user typed, best guess
     * first.
     *
     * Komga's own settings page tells the reader to copy an API key from
     * `/account/api-keys`, and copying the address bar along with it is
     * the obvious thing to do — so the pasted URL is very often a page
     * in the web interface rather than the server root. Rather than keep
     * a list of Komga's page addresses, which would rot, each parent
     * path is offered in turn down to the bare origin, and setup takes
     * the first one that answers like Komga.
     *
     * Walking downwards rather than jumping straight to the origin is
     * what keeps a reverse-proxied server working: `example.com/komga`
     * is a real base URL and has to be tried before `example.com`.
     */
    fun baseUrlCandidates(input: String, defaultScheme: String = "https"): List<String> {
        val normalised = normaliseBaseUrl(input, defaultScheme) ?: return emptyList()
        val schemeEnd = normalised.indexOf("://") + 3
        val origin = normalised.substring(0, schemeEnd) +
            normalised.substring(schemeEnd).substringBefore('/')

        val segments = normalised.substring(schemeEnd)
            .substringAfter('/', "")
            .split('/')
            .filter { it.isNotEmpty() }

        val candidates = mutableListOf<String>()
        for (depth in segments.size downTo 0) {
            candidates += (listOf(origin) + segments.take(depth)).joinToString("/")
        }
        return candidates.distinct().take(MAX_CANDIDATES)
    }

    /**
     * Turns what a user typed into a base URL. Shared with every other
     * kind of server, since the awkward cases — a missing scheme, a
     * trailing slash, a reverse proxy's path prefix — are not Komga's.
     */
    fun normaliseBaseUrl(input: String, defaultScheme: String = "https"): String? =
        RemoteUrl.normaliseBase(input, defaultScheme)

    /** Swaps `https` for `http`, used only after the user asks for it. */
    fun withHttp(baseUrl: String): String = RemoteUrl.withHttp(baseUrl)

    /** An API path against the server, e.g. `/api/v1/books/42/file`. */
    fun api(baseUrl: String, path: String): String = RemoteUrl.api(baseUrl, path)
}
