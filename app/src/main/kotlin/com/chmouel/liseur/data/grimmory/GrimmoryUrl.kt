package com.chmouel.liseur.data.grimmory

import com.chmouel.liseur.data.remote.RemoteUrl

/**
 * URL handling for a Grimmory server. Pure functions, so the awkward
 * cases have unit tests rather than being discovered on a real server.
 */
object GrimmoryUrl {

    /** How many parent paths to try before giving up. */
    private const val MAX_CANDIDATES = 6

    /**
     * The path prefix Grimmory serves its Komga-compatible shim under.
     *
     * This is the whole reason Grimmory cannot simply reuse the Komga
     * client: `KomgaUrl.api` builds `/api/v1/…`, which on Grimmory is
     * its *own* API behind its *own* security chain, and answers 401 to
     * the credentials that work here.
     */
    private const val SHIM_PREFIX = "/komga"

    /**
     * An API path against the shim, e.g. `/komga/api/v1/books/42/file`.
     *
     * The stored base URL is the Grimmory root, not the shim, so the
     * prefix is added here rather than baked into what is persisted.
     * That keeps one server one address: the same root is what an OPDS
     * client or a browser would be given.
     */
    fun api(baseUrl: String, path: String): String =
        RemoteUrl.api(baseUrl, "$SHIM_PREFIX$path")

    /**
     * Every base URL worth trying for what the user typed, best guess
     * first.
     *
     * The same parent-path walk Komga uses, and for the same reason: a
     * reader pastes whatever was in the address bar, and a
     * reverse-proxied Grimmory at `example.com/books` is a real base URL
     * that has to be tried before `example.com`.
     *
     * The `/komga` segment is deliberately *not* added here. It belongs
     * to the protocol, not to the address, so it is [api]'s business.
     *
     * Nor is a pasted one stripped, though it is tempting: the parent
     * walk already covers it. A reader who pastes `example.com/komga`
     * tries `example.com/komga/komga/api/…` first, which answers 404
     * and costs one request, and then `example.com`, which works — so
     * both spellings still store one root and one account. Stripping it
     * instead would make a Grimmory genuinely reverse-proxied under
     * `/komga` unreachable, its shim really being at `/komga/komga`.
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

    /** Turns what a user typed into a base URL. */
    fun normaliseBaseUrl(input: String, defaultScheme: String = "https"): String? =
        RemoteUrl.normaliseBase(input, defaultScheme)

    /** Swaps `https` for `http`, used only after the user asks for it. */
    fun withHttp(baseUrl: String): String = RemoteUrl.withHttp(baseUrl)
}
