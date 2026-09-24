package com.chmouel.liseur.data.bookorbit

import android.util.Log
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookOrbitBindingDao
import com.chmouel.liseur.data.db.RemoteServerDao
import com.chmouel.liseur.data.remote.BookDeleter
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.RemoteUrl
import com.chmouel.liseur.data.remote.ServerDeleteResult
import com.chmouel.liseur.data.remote.ServerKind
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Deletes a book from a BookOrbit library.
 *
 * BookOrbit deletes books, not files: the request takes the book, and
 * every format of it, its cover and every reader's progress go with it.
 * The confirmation says so. `forgetReading` has nothing to add, because
 * no reading of a deleted book is kept.
 *
 * The id sent is the one in the entry's own scoped identity, and only
 * when that identity was issued for this account on this server. BookOrbit
 * numbers its books per installation, so the same integer on another
 * server, or under another login, is somebody else's book.
 */
class BookOrbitDeleteClient(
    private val http: BookOrbitHttp,
    private val serverDao: RemoteServerDao,
    private val bindings: BookOrbitBindingDao,
) : BookDeleter {

    override suspend fun delete(
        baseUrl: String,
        credentials: RemoteCredentials,
        book: Book,
        forgetReading: Boolean,
    ): ServerDeleteResult {
        val server = serverDao.get()?.takeIf {
            it.kind == ServerKind.BOOKORBIT && RemoteUrl.sameAddress(it.baseUrl, baseUrl)
        } ?: return ServerDeleteResult.Failed(null)
        val context = BookOrbitRequestContext.from(server) ?: return ServerDeleteResult.Failed(null)
        val accountId = server.accountId ?: return ServerDeleteResult.Failed(null)
        val bookId = bookIdOf(book.remoteUuid, context.baseUrl, accountId)
            ?: return ServerDeleteResult.Failed(null)
        // A binding that names a different book is a disagreement about
        // what this entry is; deleting either would be a guess.
        val bound = bindings.get(server.accountKey, book.url)
        if (bound != null && bound.bookId != bookId) return ServerDeleteResult.Failed(null)
        return try {
            val code = http.send(
                context,
                BookOrbitUrl.api(context.baseUrl, "books"),
                "DELETE",
                JSONObject().put("bookIds", JSONArray().put(bookId)),
                rejected = setOf(403, 404, 409),
            )
            when (code) {
                // Not there is what was asked for; a retry after a lost
                // answer lands here.
                204, 200, 404 -> ServerDeleteResult.Deleted
                403 -> ServerDeleteResult.NotAllowed
                else -> ServerDeleteResult.Failed("HTTP $code")
            }
        } catch (e: RemoteHttpFailure) {
            Log.w(TAG, "Could not delete the book on BookOrbit: ${e.reason}")
            ServerDeleteResult.Failed(null)
        } catch (e: IOException) {
            Log.w(TAG, "Could not delete the book on BookOrbit", e)
            ServerDeleteResult.Failed(e.message)
        }
    }

    /** The binding and everything that cascades from it go with the book. */
    override suspend fun forgetDeleted(bookUrl: String, accountKey: String) {
        bindings.delete(accountKey, bookUrl)
    }

    internal companion object {
        private const val TAG = "BookOrbitDelete"

        /** The BookOrbit id in [remoteUuid], if it was issued for this scope. */
        fun bookIdOf(remoteUuid: String?, baseUrl: String, accountId: String): Long? {
            val prefix = "bo_${BookOrbitScope.fingerprint(baseUrl, accountId)}_"
            if (remoteUuid == null || !remoteUuid.startsWith(prefix)) return null
            return remoteUuid.removePrefix(prefix).toLongOrNull()?.takeIf { it > 0 }
        }
    }
}
