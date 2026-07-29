package com.chmouel.liseur.data.calibre

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.json.JSONException

/**
 * Why talking to calibre-web did not work.
 *
 * Telling these apart is what lets the app say something true. "You are
 * offline" and "this account may not download books" call for completely
 * different things from whoever is reading, and lumping them together as
 * a failure hides the one thing that would have explained it.
 *
 * Every value here is safe to show and safe to write to the log. None of
 * them carries a URL, because the Kobo sync token sits in the path of
 * every sync URL and is a standing key to the account.
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
     * Whether asking again later stands a chance. A refused sign-in will
     * be refused just as firmly in ten minutes, so retrying it only
     * spends battery.
     */
    val worthRetrying: Boolean
        get() = when (this) {
            Offline, Timeout, Malformed -> true
            is ServerError -> code >= 500
            Unauthorised, Forbidden, NotFound -> false
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
        }
}

/** Either what was asked for, or why it could not be had. */
sealed interface KoboResult<out T> {
    data class Ok<T>(val value: T) : KoboResult<T>
    data class Failed(val reason: SyncFailure) : KoboResult<Nothing>

    val failure: SyncFailure?
        get() = (this as? Failed)?.reason
}

/** What the value is, or null if the call failed. */
fun <T> KoboResult<T>.valueOrNull(): T? = (this as? KoboResult.Ok)?.value

/**
 * An answer that arrived and said no. Carried as an exception only so it
 * can travel out of the middle of a paged walk; it never escapes
 * [koboCall].
 */
internal class KoboHttpFailure(val reason: SyncFailure) : IOException()

/**
 * Turns the exceptions OkHttp and the JSON parser throw into the reasons
 * above. The exception itself is deliberately never logged or carried
 * forward: OkHttp puts the request URL in some of its messages, and that
 * URL contains the sync token.
 */
internal inline fun <T> koboCall(body: () -> T): KoboResult<T> = try {
    KoboResult.Ok(body())
} catch (e: KoboHttpFailure) {
    KoboResult.Failed(e.reason)
} catch (_: SocketTimeoutException) {
    KoboResult.Failed(SyncFailure.Timeout)
} catch (_: UnknownHostException) {
    KoboResult.Failed(SyncFailure.Offline)
} catch (_: ConnectException) {
    KoboResult.Failed(SyncFailure.Offline)
} catch (_: JSONException) {
    KoboResult.Failed(SyncFailure.Malformed)
} catch (_: IOException) {
    KoboResult.Failed(SyncFailure.Offline)
}

/** What an unsuccessful HTTP answer means. */
internal fun failureForCode(code: Int): SyncFailure = when (code) {
    401 -> SyncFailure.Unauthorised
    403 -> SyncFailure.Forbidden
    404 -> SyncFailure.NotFound
    408 -> SyncFailure.Timeout
    else -> SyncFailure.ServerError(code)
}
