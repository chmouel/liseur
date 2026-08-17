package com.chmouel.liseur.data.remote

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.json.JSONException

/**
 * Why talking to a server did not work.
 *
 * Telling these apart is what lets the app say something true. "You are
 * offline" and "this account may not download books" call for completely
 * different things from whoever is reading, and lumping them together as
 * a failure hides the one thing that would have explained it.
 *
 * Every value here is safe to show and safe to write to the log. None of
 * them carries a URL, because calibre-web's Kobo sync token sits in the
 * path of every sync URL and is a standing key to the account.
 */
sealed interface SyncFailure {

    /** The server could not be reached at all. */
    data object Offline : SyncFailure

    /** It answered too slowly to wait for. */
    data object Timeout : SyncFailure

    /** The sign-in is no longer accepted. */
    data object Unauthorised : SyncFailure

    /** Signed in, but this account is not allowed to do that. */
    data object Forbidden : SyncFailure

    /** The server has never heard of that book. */
    data object NotFound : SyncFailure

    /** The server broke. */
    data class ServerError(val code: Int) : SyncFailure

    /** It answered with something that was not what it claimed to be. */
    data object Malformed : SyncFailure

    /**
     * The server will not take credentials over plain HTTP.
     *
     * Worth its own name because the server says it with a 403, which
     * otherwise reads as "this account may not" and sends the reader
     * looking for a permission when what is wrong is the address.
     */
    data object InsecureTransport : SyncFailure

    /**
     * The server no longer knew a book by the name this device had
     * cached, twice in one run.
     *
     * Recovery re-resolves and retries once per book per run; this is
     * what is left when even the fresh answer was already stale, so the
     * run gives the book back to the next scheduled sync rather than
     * asking the server a third time.
     */
    data object StaleIdentity : SyncFailure

    /**
     * Whether asking again later stands a chance. A refused sign-in will
     * be refused just as firmly in ten minutes, so retrying it only
     * spends battery.
     */
    val worthRetrying: Boolean
        get() = when (this) {
            Offline, Timeout, Malformed, StaleIdentity -> true
            is ServerError -> code >= 500
            Unauthorised, Forbidden, NotFound, InsecureTransport -> false
        }

    /** A short tag for the log. Never contains a URL or a token. */
    val label: String
        get() = when (this) {
            Offline -> "offline"
            Timeout -> "timeout"
            Unauthorised -> "unauthorised"
            Forbidden -> "forbidden"
            NotFound -> "not found"
            is ServerError -> "server error $code"
            Malformed -> "malformed response"
            InsecureTransport -> "https required"
            StaleIdentity -> "stale identity"
        }
}

/** Either what was asked for, or why it could not be had. */
sealed interface RemoteResult<out T> {
    data class Ok<T>(val value: T) : RemoteResult<T>
    data class Failed(val reason: SyncFailure) : RemoteResult<Nothing>

    val failure: SyncFailure?
        get() = (this as? Failed)?.reason
}

/** What the value is, or null if the call failed. */
fun <T> RemoteResult<T>.valueOrNull(): T? = (this as? RemoteResult.Ok)?.value

/**
 * An answer that arrived and said no. Carried as an exception only so it
 * can travel out of the middle of a paged walk; it never escapes
 * [remoteCall].
 */
internal class RemoteHttpFailure(val reason: SyncFailure) : IOException()

/**
 * Turns the exceptions OkHttp and the JSON parser throw into the reasons
 * above. The exception itself is deliberately never logged or carried
 * forward: OkHttp puts the request URL in some of its messages, and that
 * URL contains the sync token.
 */
internal inline fun <T> remoteCall(body: () -> T): RemoteResult<T> = try {
    RemoteResult.Ok(body())
} catch (e: RemoteHttpFailure) {
    RemoteResult.Failed(e.reason)
} catch (_: SocketTimeoutException) {
    RemoteResult.Failed(SyncFailure.Timeout)
} catch (_: UnknownHostException) {
    RemoteResult.Failed(SyncFailure.Offline)
} catch (_: ConnectException) {
    RemoteResult.Failed(SyncFailure.Offline)
} catch (_: JSONException) {
    RemoteResult.Failed(SyncFailure.Malformed)
} catch (_: IOException) {
    RemoteResult.Failed(SyncFailure.Offline)
}

/** What an unsuccessful HTTP answer means. */
internal fun failureForCode(code: Int): SyncFailure = when (code) {
    401 -> SyncFailure.Unauthorised
    403 -> SyncFailure.Forbidden
    404 -> SyncFailure.NotFound
    408 -> SyncFailure.Timeout
    else -> SyncFailure.ServerError(code)
}
