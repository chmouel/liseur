package com.chmouel.liseur.data.remote

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The part of a server's address that decides whether a request is
 * really going to that server.
 *
 * This is the boundary the account's password and API key are kept
 * inside, so it is drawn deliberately rather than by comparing strings.
 * A prefix match is not an origin check: with a base of
 * `https://books.example`, the string `https://books.example.evil.test/`
 * starts with it, and so does `https://books.example:8443/` once a port
 * is involved. Cover URLs come from the server's own catalog, so getting
 * this wrong hands the credentials to whoever that server names.
 *
 * Scheme, host and port must match exactly; the path must match segment
 * by segment, so a base of `/api` covers `/api/books` but not `/apifoo`.
 */
class RemoteOrigin private constructor(
    private val scheme: String,
    private val host: String,
    private val port: Int,
    private val segments: List<String>,
) {

    /** True when [url] belongs to this server and may carry its credentials. */
    fun covers(url: String): Boolean = covers(url.toHttpUrlOrNull())

    fun covers(url: HttpUrl?): Boolean {
        if (url == null) return false
        if (url.scheme != scheme || url.host != host || url.port != port) return false
        val requested = url.pathSegments.withoutTrailingBlanks()
        if (requested.size < segments.size) return false
        return segments.indices.all { segments[it] == requested[it] }
    }

    companion object {
        /** Reads a server's base URL, or null if it is not one. */
        fun of(baseUrl: String): RemoteOrigin? {
            val url = baseUrl.toHttpUrlOrNull() ?: return null
            return RemoteOrigin(
                scheme = url.scheme,
                host = url.host,
                port = url.port,
                segments = url.pathSegments.withoutTrailingBlanks(),
            )
        }

        /**
         * A trailing slash is not a path segment. OkHttp reports one as a
         * trailing empty string, so `/api` and `/api/` would otherwise be
         * different origins.
         */
        private fun List<String>.withoutTrailingBlanks(): List<String> =
            dropLastWhile { it.isEmpty() }
    }
}
