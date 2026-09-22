package com.chmouel.liseur.data.bookorbit

import com.chmouel.liseur.data.remote.RemoteUrl

/**
 * URL handling for a BookOrbit server.
 *
 * BookOrbit hangs everything off an `/api/v1` prefix, and a reader
 * pasting an address has usually copied it out of a browser rather than
 * off a settings page, so the typed value is as likely to be
 * `https://orbit.example.com/api/v1/auth/login` as the origin. Rather
 * than keep a list of the routes BookOrbit has this week, the known
 * suffixes are stripped and each parent path is offered in turn, the
 * same way `KomgaUrl` does it.
 *
 * Pure functions, so the awkward cases are unit tests rather than
 * discoveries on a real server.
 */
object BookOrbitUrl {

    /** How many parent paths to try before giving up. */
    private const val MAX_CANDIDATES = 6

    /** Where BookOrbit mounts its REST API. */
    const val API_PREFIX = "api/v1"

    /**
     * Every base URL worth trying for what the reader typed, best guess
     * first.
     *
     * The suffix strip happens before the parent walk so that a pasted
     * `/api/v1/...` page does not become an API base of its own: the
     * candidate list always describes servers, and [api] is what adds
     * the prefix back on.
     */
    fun baseUrlCandidates(input: String, defaultScheme: String = "https"): List<String> {
        val normalised = normaliseBaseUrl(input, defaultScheme) ?: return emptyList()
        val withoutApi = stripApiSuffix(normalised)

        val schemeEnd = withoutApi.indexOf("://") + 3
        val origin = withoutApi.substring(0, schemeEnd) +
            withoutApi.substring(schemeEnd).substringBefore('/')
        val segments = withoutApi.substring(schemeEnd)
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
     * Removes a trailing `/api/v1`, `/api/v1/`, `/api` or `/api/`, and
     * any deeper path under it.
     *
     * A pasted route is not a server, so everything from the known mount
     * point onwards is dropped rather than walked past: the candidates
     * then describe the origins that could actually answer.
     */
    private fun stripApiSuffix(url: String): String {
        val withoutQuery = url.substringBefore('?')
        val marker = "/$API_PREFIX"
        val at = withoutQuery.indexOf(marker)
        if (at >= 0) return withoutQuery.substring(0, at).trimEnd('/')
        val apiMarker = "/api"
        val apiAt = withoutQuery.indexOf(apiMarker)
        if (apiAt >= 0 &&
            (withoutQuery.length == apiAt + apiMarker.length ||
                withoutQuery.getOrNull(apiAt + apiMarker.length) == '/')
        ) {
            return withoutQuery.substring(0, apiAt).trimEnd('/')
        }
        return withoutQuery
    }

    /** Turns what a reader typed into a base URL. */
    fun normaliseBaseUrl(input: String, defaultScheme: String = "https"): String? =
        RemoteUrl.normaliseBase(input, defaultScheme)

    /** Swaps `https` for `http`, used only after the reader asks for it. */
    fun withHttp(baseUrl: String): String = RemoteUrl.withHttp(baseUrl)

    /**
     * A BookOrbit route against the server, with the API prefix added.
     *
     * Every client goes through this, so the prefix is spelled once and
     * a server behind a reverse proxy keeps its path prefix.
     */
    fun api(baseUrl: String, path: String): String =
        RemoteUrl.api(RemoteUrl.api(baseUrl, API_PREFIX), path)

    /**
     * The file id inside a download link this app built.
     *
     * The link is Liseur's own (`/api/v1/books/files/{id}/download`), so
     * this is reading back something written a moment ago rather than
     * parsing a server's guess. Anything that does not match returns
     * null, which the caller treats as "no file to fetch" instead of
     * fetching whatever the string happened to contain.
     */
    fun fileIdOf(downloadHref: String?): Long? {
        val text = downloadHref?.trim().orEmpty()
        val match = FILE_ID.find(text) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    /** The download link a book's primary EPUB is fetched from. */
    fun downloadHref(fileId: Long): String = "/api/v1/books/files/$fileId/download"

    /**
     * The cover link, tagged with the account-scoped library identity.
     *
     * A URI fragment is not sent in an HTTP request, but Coil keeps it in
     * the data/cache key. It therefore does two jobs without exposing
     * anything to the server: one account cannot see another account's
     * cached cover at the same numeric book id, and the image
     * interceptor can refuse an old queued request after an account
     * switch instead of signing it with the new account's token.
     */
    fun coverHref(bookId: Long, remoteId: String): String =
        "/api/v1/books/$bookId/thumbnail#$COVER_SCOPE=$remoteId"

    /** The account-scoped id carried by [coverHref], if any. */
    fun coverRemoteId(url: String): String? =
        url.substringAfter('#', "")
            .split('&')
            .firstOrNull { it.startsWith("$COVER_SCOPE=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotEmpty() }

    fun isScopedCover(url: String): Boolean = coverRemoteId(url) != null

    private val FILE_ID = Regex("/books/files/(\\d+)(?:/|$)")
    private const val COVER_SCOPE = "liseur-bookorbit"
}
