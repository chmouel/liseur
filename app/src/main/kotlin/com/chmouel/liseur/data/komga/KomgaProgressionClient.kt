package com.chmouel.liseur.data.komga

import android.util.Log
import com.chmouel.liseur.data.remote.DeviceIdentity
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteResult
import com.chmouel.liseur.data.remote.remoteCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** What became of a position we tried to save on the server. */
sealed interface PushOutcome {
    /** The server took it. */
    data object Accepted : PushOutcome

    /**
     * The server already holds something at least as new.
     *
     * Not a failure: it means another device got there first, and the
     * right response is to leave ours unsent and read theirs on the next
     * pass rather than to overwrite it.
     */
    data object Stale : PushOutcome

    /**
     * The server would not place this position in this book, even after
     * being asked where it thinks the pages are.
     */
    data object Unplaceable : PushOutcome
}

/**
 * Reading positions on a Komga server.
 *
 * Komga is strict about where in a book a position may be: it keeps its
 * own index of the pages in every EPUB and refuses anything it cannot
 * find in it. That is stricter than Readium, which will happily describe
 * a spot between two of Komga's pages, so a position saved by the reader
 * is not always one the server will take.
 *
 * There is no cruder fallback to drop to, either: sending a page number
 * instead is refused for reflowable EPUBs, which is all of them here.
 * So when a position is turned down, the server is asked for its index
 * and the nearest place it does know about is sent instead.
 */
class KomgaProgressionClient(private val http: KomgaHttp = KomgaHttp()) {

    /**
     * Where the server thinks the reader is, or null if it has never
     * been told.
     *
     * Komga says "nowhere yet" with an empty answer rather than a 404,
     * so that has to be told apart from a book it has never heard of.
     *
     * Only the locator in the answer can be trusted. The `modified`
     * beside it comes back with the server's offset applied on top of an
     * instant that was already absolute, so it reads hours away from the
     * time it was sent — send `09:00Z` and it returns `11:00+01:00`.
     * When a position was saved is read from the catalog's
     * `readProgress.readDate` instead, which round-trips exactly.
     */
    suspend fun read(
        baseUrl: String,
        credentials: RemoteCredentials,
        bookId: String,
    ): RemoteResult<JSONObject?> = withContext(Dispatchers.IO) {
        remoteCall {
            http.getObjectOrNull(KomgaUrl.api(baseUrl, progression(bookId)), credentials)
        }
    }

    /**
     * Saves a position, retrying at the nearest place the server admits
     * to if it will not take the one we have.
     */
    suspend fun push(
        baseUrl: String,
        credentials: RemoteCredentials,
        bookId: String,
        locator: JSONObject,
        modifiedAt: Long,
        device: DeviceIdentity,
    ): RemoteResult<PushOutcome> = withContext(Dispatchers.IO) {
        remoteCall {
            // A locator with nowhere to point cannot be repaired by
            // asking the server where the pages are, so it never gets
            // as far as trying.
            if (KomgaLocator.toKomga(locator) == null) {
                PushOutcome.Unplaceable
            } else {
                when (send(baseUrl, credentials, bookId, locator, modifiedAt, device)) {
                    CONFLICT -> PushOutcome.Stale
                    REJECTED ->
                        retryNearby(baseUrl, credentials, bookId, locator, modifiedAt, device)
                    else -> PushOutcome.Accepted
                }
            }
        }
    }

    private fun retryNearby(
        baseUrl: String,
        credentials: RemoteCredentials,
        bookId: String,
        locator: JSONObject,
        modifiedAt: Long,
        device: DeviceIdentity,
    ): PushOutcome {
        // The index is large — over a thousand entries for an ordinary
        // book — which is exactly why it is only fetched once a position
        // has actually been refused.
        val index = http.getObjectOrNull(KomgaUrl.api(baseUrl, POSITIONS.format(bookId)), credentials)
            ?: return PushOutcome.Unplaceable
        val nearest = KomgaLocator.snap(KomgaLocator.positionsOf(index), locator)
            ?: return PushOutcome.Unplaceable

        Log.i(TAG, "Position refused; moving to the nearest one the server knows")
        return when (send(baseUrl, credentials, bookId, nearest, modifiedAt, device)) {
            CONFLICT -> PushOutcome.Stale
            REJECTED -> PushOutcome.Unplaceable
            else -> PushOutcome.Accepted
        }
    }

    private fun send(
        baseUrl: String,
        credentials: RemoteCredentials,
        bookId: String,
        locator: JSONObject,
        modifiedAt: Long,
        device: DeviceIdentity,
    ): Int {
        val body = JSONObject()
            .put(
                "device",
                JSONObject().put("id", device.id).put("name", device.name),
            )
            .put("locator", KomgaLocator.toKomga(locator) ?: return REJECTED)
            .put("modified", KomgaTime.format(modifiedAt))

        return http.send(
            url = KomgaUrl.api(baseUrl, progression(bookId)),
            credentials = credentials,
            method = "PUT",
            json = body,
            // Both mean something the caller can act on, so neither is
            // worth treating as the server having broken.
            rejected = setOf(REJECTED, CONFLICT),
        )
    }

    /**
     * Marks a book read.
     *
     * This is the one thing that can be said about a reflowable EPUB
     * without a locator, and it is said separately because finishing a
     * book is not the same as being on its last page.
     */
    suspend fun markCompleted(
        baseUrl: String,
        credentials: RemoteCredentials,
        bookId: String,
    ): RemoteResult<Unit> = withContext(Dispatchers.IO) {
        remoteCall {
            http.send(
                url = KomgaUrl.api(baseUrl, READ_PROGRESS.format(bookId)),
                credentials = credentials,
                method = "PATCH",
                json = JSONObject().put("completed", true),
            )
            Unit
        }
    }

    /** Forgets a book's position on the server, marking it unread. */
    suspend fun clear(
        baseUrl: String,
        credentials: RemoteCredentials,
        bookId: String,
    ): RemoteResult<Unit> = withContext(Dispatchers.IO) {
        remoteCall {
            http.send(
                url = KomgaUrl.api(baseUrl, READ_PROGRESS.format(bookId)),
                credentials = credentials,
                method = "DELETE",
                json = null,
            )
            Unit
        }
    }

    private fun progression(bookId: String) = PROGRESSION.format(bookId)

    private companion object {
        const val TAG = "KomgaProgression"
        const val PROGRESSION = "/api/v1/books/%s/progression"
        const val READ_PROGRESS = "/api/v1/books/%s/read-progress"
        const val POSITIONS = "/api/v1/books/%s/positions"

        const val REJECTED = 400
        const val CONFLICT = 409
    }
}
