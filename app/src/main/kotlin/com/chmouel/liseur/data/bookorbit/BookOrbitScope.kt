package com.chmouel.liseur.data.bookorbit

import com.chmouel.liseur.data.remote.RemoteUrl
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * The name a BookOrbit book carries in the library.
 *
 * BookOrbit numbers its books per installation, starting again at one.
 * The same integer therefore names a different book on somebody else's
 * server, and — because a downloaded book keeps its `books.url` when a
 * server is disconnected — a bare id would let one server's book adopt
 * the row, the file and the reading history of another server's. This
 * is the same hazard `OpdsScope` exists for, and it is answered the
 * same way: a short digest of where the book came from, in front of the
 * id.
 *
 * The account is part of the scope as well as the address. Two people
 * sharing one BookOrbit installation are two libraries, and their
 * highlights and personal status must not be adopted by each other on a
 * login change. Reading that is genuinely the reader's own can be moved
 * across by an explicit action; it is not inferred from a matching
 * title.
 *
 * The digest is hex, and the whole name is `[a-z0-9_]`, because
 * `books.remote_uuid` is also spelled into a filename by
 * `BookDownloadRepository.fileFor`. Nothing here is secret: an address
 * is not a credential.
 */
object BookOrbitScope {

    /** Marks a Liseur-generated BookOrbit identity apart from a raw id. */
    private const val PREFIX = "bo"

    /** How much of the digest is kept. Enough that collisions are not a worry. */
    private const val DIGEST_HEX = 16

    /**
     * A stable, filename-safe name for one account on one installation.
     *
     * Both halves are length-delimited so that two spellings cannot be
     * concatenated into the same material: without it, the address
     * `https://a.example/1` with account `2` and the address
     * `https://a.example/12` with account `` would digest the same
     * string.
     */
    fun fingerprint(baseUrl: String, accountId: String): String {
        val base = canonicalBase(baseUrl)
        val material = "${base.length}:$base|${accountId.length}:$accountId"
        return digest(material).take(DIGEST_HEX)
    }

    /**
     * The identity written into `books.remote_uuid` and, behind
     * [com.chmouel.liseur.data.remote.ServerKind.remoteUrl], into
     * `books.url`.
     */
    fun remoteId(baseUrl: String, accountId: String, bookId: Long): String =
        "${PREFIX}_${fingerprint(baseUrl, accountId)}_$bookId"

    /**
     * The address as it identifies a library, rather than as it was
     * typed.
     *
     * The scheme and host are lower-cased, the path keeps whatever
     * reverse-proxy prefix the reader is actually reaching the server
     * under, and the trailing slash is dropped so that the two ways of
     * spelling one server name one scope.
     */
    fun canonicalBase(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd < 0) return trimmed.lowercase()
        val scheme = trimmed.substring(0, schemeEnd).lowercase()
        val rest = trimmed.substring(schemeEnd + 3)
        val host = rest.substringBefore('/').lowercase()
        val path = rest.substringAfter('/', "")
        return if (path.isEmpty()) "$scheme://$host" else "$scheme://$host/$path"
    }

    private fun digest(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(StandardCharsets.UTF_8))
        return bytes.take(DIGEST_HEX / 2).joinToString("") { "%02x".format(it) }
    }

    /**
     * Whether [baseUrl] names a server this scope could have come from.
     *
     * Only used to tell a move that kept the account apart from a
     * different server entirely. A server reached under a new address is
     * a new scope on purpose; nothing here silently re-homes reading.
     */
    fun sameServer(first: String, second: String): Boolean =
        RemoteUrl.sameAddress(canonicalBase(first), canonicalBase(second))
}
