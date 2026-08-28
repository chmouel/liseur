package com.chmouel.liseur.data.opds

import java.security.MessageDigest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The one catalog a Custom connection speaks to, and the rule about
 * where its password may go.
 *
 * A feed is a document written by somebody else. Every link in it —
 * the next page, a shelf, a cover, the book itself — is an address
 * chosen by the server, and OPDS is a federated format where pointing
 * at another server is not an attack but a feature. `RemoteHttp` signs
 * whatever URL it is handed, so following those links as written would
 * post the reader's catalog password to whatever host the feed named.
 *
 * So the credential is scoped to the origin the reader typed. Anything
 * elsewhere is still fetched — an open-access link to another archive
 * is a real and useful thing — but it is fetched as a stranger.
 *
 * Scoped by origin rather than by path prefix, which was the first
 * design: catalogs routinely serve their files from a path beside the
 * feed rather than beneath it, so a prefix rule breaks the ordinary
 * case to defend against another document on the reader's own server.
 * A browser scopes a Basic credential the same way.
 */
class OpdsScope private constructor(val root: HttpUrl) {

    /** Whether a request to [url] may carry the catalog's credential. */
    fun signs(url: HttpUrl): Boolean =
        url.scheme == root.scheme && url.host == root.host && url.port == root.port

    /**
     * A short, stable name for this catalog, for telling two of them
     * apart.
     *
     * OPDS entry ids are opaque and only unique within the catalog that
     * issued them: `1` is a perfectly legal id, and two unrelated
     * servers can both use it. A downloaded book keeps its URL when the
     * account changes, so without a namespace the second Custom server
     * connected would adopt the first one's rows — the wrong file, the
     * wrong metadata, and somebody else's reading history behind it.
     *
     * Hashed rather than spelled out because it goes into `books.url`,
     * where a raw address would be unreadable and fragile. Derived from
     * scheme, host, port and path, so the same catalog reached the same
     * way is always the same name.
     */
    val fingerprint: String by lazy {
        val canonical = "${root.scheme}://${root.host}:${root.port}${root.encodedPath.trimEnd('/')}"
        MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray())
            .take(6)
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * A book's identity in the library: the catalog it came from, then
     * the id that catalog gave it.
     *
     * Written into `books.url`, so this shape is schema and cannot be
     * changed later without orphaning every reading position and
     * highlight hanging off it.
     */
    fun remoteId(entryId: String): String = "$fingerprint:$entryId"

    companion object {
        fun of(catalogUrl: String): OpdsScope? =
            catalogUrl.trim().toHttpUrlOrNull()?.let(::OpdsScope)
    }
}
