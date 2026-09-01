package com.chmouel.liseur.data.remote

import com.chmouel.liseur.data.db.Book
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
    ): CatalogWalk

    suspend fun search(
        baseUrl: String,
        credentials: RemoteCredentials,
        query: String,
    ): List<RemoteBook>
}

/**
 * How a walk of the whole catalog ended.
 *
 * [complete] is the difference between "the server has no more books"
 * and "we stopped asking": both servers have a guard against a catalog
 * that never ends, and a walk cut short by one of them has not seen the
 * whole library. Anything that would remove what was not seen, or trust
 * this as the current state of the server, must not act on it.
 */
data class CatalogWalk(
    val complete: Boolean,
    /** What the provider kept of the walk, for reusing within this run. */
    val snapshot: CatalogSnapshot? = null,
)

/**
 * A provider's own record of a catalog walk it has just done.
 *
 * Opaque on purpose: Komga's answer already carries every book's reading
 * progress, so the position sync that follows a refresh need not fetch
 * the same listing again, but what is in it is Komga's business. Nothing
 * outside the provider that made one ever looks inside.
 */
interface CatalogSnapshot

/**
 * A catalog walk offered to a position sync, with whose it is.
 *
 * Reading progress is per-account on both servers. A snapshot taken
 * before a sign-out says nothing true about whoever is signed in now,
 * so it travels with the account it was read for and is refused if that
 * is no longer the connected one.
 */
data class SyncSnapshot(val accountKey: String, val catalog: CatalogSnapshot)

/**
 * Fetching the file itself.
 *
 * Only the request is built here, not the transfer: the download worker
 * owns resuming, progress and the partial file, and none of that differs
 * between servers.
 */
interface FileSource {
    /**
     * The signed request that fetches [book]'s file, or null when there
     * is no way to ask for it.
     *
     * The whole book is passed rather than a URL because the servers do
     * not agree on what identifies a file: calibre-web wants the integer
     * id from its own database, Komga the id it gave the book. Working
     * that out is the provider's business, not the download worker's.
     */
    fun downloadRequest(
        baseUrl: String,
        credentials: RemoteCredentials,
        book: Book,
    ): Request.Builder?
}

/** What an account turned out to be able to do on its server. */
data class ServerCapabilities(
    /** The URL that actually answered, which may not be the one typed. */
    val baseUrl: String,
    /** Whether this account may fetch book files at all. */
    val canDownload: Boolean,
    /** Whether the account can write liseur-sync personal series claims. */
    val canManageLibrary: Boolean = false,
    /**
     * Whether the account may add a book to the server's library.
     *
     * A separate permission from [canManageLibrary] on purpose: putting
     * a file in somebody's library is a bigger thing than claiming a
     * series for it, and the server gives it its own scope. It is also
     * only half the answer — the server decides per folder as well — so
     * this says "worth offering", not "will succeed".
     */
    val canUpload: Boolean = false,
    /**
     * Whether the account may delete a book from the server's library
     * (ADR-0025).
     *
     * Separate from [canUpload] because the server keeps them separate:
     * adding your own book and destroying everyone's are different
     * questions. Like [canUpload] it is only half the answer — the
     * server decides per folder too — so this says "worth offering".
     */
    val canDelete: Boolean = false,
    /** Whether the account can also write the shared catalog layer. */
    val canAdmin: Boolean = false,
    /** Who the server says we are, for telling two logins apart. */
    val accountId: String?,
    /** The display name to show for the account. */
    val displayName: String,
    /**
     * The liseur-sync device token minted or verified during setup.
     *
     * Setup signs in with a password and comes back holding a different
     * secret — the device token — which is the one stored. Null for the
     * kinds whose sign-in secret is the stored one.
     */
    val liseurToken: String? = null,
    /**
     * liseur-sync's stable account id, when the server reports it
     * (ADR-0016 follow-up). It survives a token rotation, which is what
     * tells a re-pasted token apart from a different account signing in.
     */
    val liseurAccountId: String? = null,
    /**
     * calibre-web's Kobo sync token, obtained during setup. Nothing else
     * has one, and nothing but the Kobo sync uses it.
     */
    val koboToken: String? = null,
    /** calibre-web's integer user id, kept for the same reason. */
    val calibreUserId: Int? = null,
    /**
     * Where this account's catalog is, or null when it has none.
     *
     * Defaults to [baseUrl], which is what every kind of server but one
     * means by it. A Custom connection is the exception in both
     * directions: its catalog may be a different address, and it may
     * have no catalog at all — a kosync address on its own is a whole
     * connection. Null is that answer, and it is why this is not simply
     * read off [baseUrl] downstream.
     */
    val catalogUrl: String? = baseUrl,
)

/** Why connecting to a server did not work, in terms a user can act on. */
sealed interface SetupFailure {
    /** The URL answered, but the credentials were rejected. */
    data object BadCredentials : SetupFailure

    /**
     * The credentials work but do not grant what the app needs — a
     * liseur-sync device token without the `sync` scope, say.
     */
    data object InsufficientScopes : SetupFailure

    /** Something answered, but it was not the kind of server we asked for. */
    data object WrongServer : SetupFailure

    /** The server insists on HTTPS and the address given was plain HTTP. */
    data object InsecureTransport : SetupFailure

    /** Too many sign-in attempts; the server is asking us to wait. */
    data object RateLimited : SetupFailure

    /** Nothing answered over HTTPS; the user may want to allow plain HTTP. */
    data class Unreachable(val message: String, val httpMayWork: Boolean) : SetupFailure
}

sealed interface SetupResult {
    data class Success(val capabilities: ServerCapabilities) : SetupResult
    data class Failure(val reason: SetupFailure) : SetupResult
}

/**
 * What came of connecting a Custom server, address by address.
 *
 * Two failures rather than one, because the form has two fields and a
 * reader who mistyped the sync address should be told about the sync
 * address. Both null means it worked; a Custom connection is published
 * only when both halves have answered, so there is no half-success to
 * report.
 */
data class CustomSetupResult(
    val catalog: SetupFailure? = null,
    val kosync: SetupFailure? = null,
) {
    val connected: Boolean get() = catalog == null && kosync == null

    /** Whichever address failed, for the paths that show one message. */
    val failure: SetupFailure? get() = catalog ?: kosync
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

/** How deleting a book from the server went. */
sealed interface ServerDeleteResult {
    data object Deleted : ServerDeleteResult

    /** The account cannot delete books, or the server said no. */
    data object NotAllowed : ServerDeleteResult

    data class Failed(val message: String?) : ServerDeleteResult
}

/**
 * Deleting a book off the server, for the kinds that allow it at all.
 *
 * Komga has no entry: deleting a file there is an administrator's job,
 * and the action stays hidden rather than offered and failed.
 *
 * [forgetReading] asks the server to forget the caller's own reading of
 * the book as well. It is about the *server's* copy of that reading:
 * the phone's goes either way, because the book is gone and hours with
 * nothing behind them are a ghost entry. An implementation whose server
 * keeps no reading ignores it.
 */
interface BookDeleter {
    suspend fun delete(
        baseUrl: String,
        credentials: RemoteCredentials,
        book: Book,
        forgetReading: Boolean = false,
    ): ServerDeleteResult
}

/** A folder on the server that a book could be uploaded into. */
data class RemoteUploadTarget(
    val folderId: String,
    val name: String,
)

/** How uploading a book to the server went. */
sealed interface ServerUploadResult {
    /**
     * The server has the book, and this is its id there.
     *
     * [alreadyThere] means the server recognised the bytes and no
     * transfer was needed. Both are successes and the caller treats
     * them the same; they differ only in what it is honest to say.
     */
    data class Uploaded(val remoteBookId: String, val alreadyThere: Boolean) : ServerUploadResult

    /**
     * The bytes arrived and are safe, but the server has not catalogued
     * them yet, so there is no id to adopt. Not a failure and not worth
     * retrying the transfer for: the server will get there.
     */
    data object Pending : ServerUploadResult

    /** No folder accepts uploads, or the account may not. */
    data object NotAllowed : ServerUploadResult

    /** The book is bigger than the server will take. */
    data object TooLarge : ServerUploadResult

    /** The server would not read it as an EPUB. */
    data class Rejected(val reason: String?) : ServerUploadResult

    /** Worth trying again: a network failure, or a server that was busy. */
    data class Failed(val message: String?) : ServerUploadResult
}

/**
 * Sending a book the reader added on the device up to the server.
 *
 * Only the kinds that can accept one have an entry. Absent from the
 * router's map means the action is never offered, which is the same rule
 * [BookDeleter] follows and for the same reason: an action that is
 * offered and then always fails is worse than one that is not there.
 */
interface BookUploader {
    /**
     * The folders this server will accept a book into, which may be
     * none even for an account that holds the permission.
     *
     * An empty list means the server was asked and said none, and the
     * caller acts on that by turning the offer off. So an implementation
     * that could not ask must throw rather than return empty: silence
     * and refusal are different answers, and only one of them should
     * cost the reader the feature.
     */
    suspend fun targets(
        baseUrl: String,
        credentials: RemoteCredentials,
    ): List<RemoteUploadTarget>

    suspend fun upload(
        baseUrl: String,
        credentials: RemoteCredentials,
        folderId: String,
        file: java.io.File,
        filename: String,
    ): ServerUploadResult
}

data class SeriesLayers(
    val bookId: String,
    val source: String?,
    val series: List<RemoteSeriesMembership>,
    val folder: List<RemoteSeriesMembership>,
    val shared: List<RemoteSeriesMembership>?,
    val personal: List<RemoteSeriesMembership>?,
    val sharedUpdatedAt: Long?,
    val personalUpdatedAt: Long?,
    val outcome: String?,
)

/**
 * What a series is called, after a rename or a revert (ADR-0020).
 *
 * A rename is a display layer: the server keeps the name its scan
 * observed as the key it folds by, and shows this one over it. So
 * [scannedName] is what the shelf would be called again, and [source]
 * says whether it is currently being called something else.
 */
data class SeriesName(
    val name: String,
    val scannedName: String?,
    /** `folder`, `shared` or `personal`. */
    val source: String?,
) {
    /** Whether the shown name is somebody's rename rather than the scan's. */
    val renamed: Boolean get() = source == "shared" || source == "personal"
}

/**
 * Refused because another shelf already answers to that name.
 *
 * Not an error to retry: giving two shelves one name is a merge, and
 * the server does not merge (ADR-0020). It is an [java.io.IOException]
 * so that a caller with nothing better to do than log it still catches
 * it.
 */
class SeriesNameTaken : java.io.IOException("that series name is already taken")

interface SeriesClaimSync {
    suspend fun setPersonalSeries(
        baseUrl: String,
        credentials: RemoteCredentials,
        book: Book,
        name: String?,
        index: Double?,
    ): SeriesLayers?

    suspend fun resetPersonalSeries(
        baseUrl: String,
        credentials: RemoteCredentials,
        book: Book,
    ): SeriesLayers?

    suspend fun resetSharedSeries(
        baseUrl: String,
        credentials: RemoteCredentials,
        book: Book,
    ): SeriesLayers?

    suspend fun reorderPersonalSeries(
        baseUrl: String,
        credentials: RemoteCredentials,
        booksInOrder: List<Book>,
    ): Boolean

    /**
     * Calls a series something else, for this reader alone.
     *
     * Throws [SeriesNameTaken] when a shelf already has that name.
     */
    suspend fun renameSeries(
        baseUrl: String,
        credentials: RemoteCredentials,
        seriesId: String,
        name: String,
    ): SeriesName?

    /** Gives a series back the name the last scan gave it. */
    suspend fun resetSeriesName(
        baseUrl: String,
        credentials: RemoteCredentials,
        seriesId: String,
    ): SeriesName?
}
