package com.chmouel.liseur.data.remote

import okhttp3.Request

/**
 * Browsing a server's books.
 *
 * Both servers hand out their whole catalog a page at a time, so that is
 * what the contract is shaped around: [onPage] is called as each page
 * arrives, which lets the library fill in while the walk is still going
 * rather than sitting empty until the last page lands.
 */
interface CatalogSource {
    suspend fun allBooks(
        baseUrl: String,
        credentials: RemoteCredentials,
        onPage: suspend (List<RemoteBook>) -> Unit = {},
    ): List<RemoteBook>

    suspend fun search(
        baseUrl: String,
        credentials: RemoteCredentials,
        query: String,
    ): List<RemoteBook>
}

/**
 * Fetching the file itself.
 *
 * Only the request is built here, not the transfer: the download worker
 * owns resuming, progress and the partial file, and none of that differs
 * between servers.
 */
interface FileSource {
    /**
     * The signed request that fetches a book's file.
     *
     * [downloadHref] is whatever the catalog recorded, absolute or
     * relative; [remoteId] is there for servers that would rather build
     * the URL themselves than trust a stored one.
     */
    fun downloadRequest(
        baseUrl: String,
        credentials: RemoteCredentials,
        remoteId: String,
        downloadHref: String?,
    ): Request.Builder?

    /** Where a book's cover is, or null when the server offers none. */
    fun coverUrl(baseUrl: String, remoteId: String, coverHref: String?): String?
}

/** What an account turned out to be able to do on its server. */
data class ServerCapabilities(
    /** The URL that actually answered, which may not be the one typed. */
    val baseUrl: String,
    /** Whether this account may fetch book files at all. */
    val canDownload: Boolean,
    /** Who the server says we are, for telling two logins apart. */
    val accountId: String?,
    /** The display name to show for the account. */
    val displayName: String,
    /**
     * calibre-web's Kobo sync token, obtained during setup. Nothing else
     * has one, and nothing but the Kobo sync uses it.
     */
    val koboToken: String? = null,
    /** calibre-web's integer user id, kept for the same reason. */
    val calibreUserId: Int? = null,
)

/** Why connecting to a server did not work, in terms a user can act on. */
sealed interface SetupFailure {
    /** The URL answered, but the credentials were rejected. */
    data object BadCredentials : SetupFailure

    /** Something answered, but it was not the kind of server we asked for. */
    data object WrongServer : SetupFailure

    /** Nothing answered over HTTPS; the user may want to allow plain HTTP. */
    data class Unreachable(val message: String, val httpMayWork: Boolean) : SetupFailure
}

sealed interface SetupResult {
    data class Success(val capabilities: ServerCapabilities) : SetupResult
    data class Failure(val reason: SetupFailure) : SetupResult
}

/**
 * Working out everything about a server that the user should not have to
 * type, and confirming it is the kind of server they said it was.
 */
interface ServerSetup {
    suspend fun connect(
        rawUrl: String,
        credentials: RemoteCredentials,
        allowHttp: Boolean = false,
    ): SetupResult
}
