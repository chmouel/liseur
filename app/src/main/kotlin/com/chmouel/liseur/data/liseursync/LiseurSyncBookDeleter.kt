package com.chmouel.liseur.data.liseursync

import android.util.Log
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.remote.BookDeleter
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteHttp
import com.chmouel.liseur.data.remote.ServerDeleteResult
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Deleting a book from a liseur-sync library.
 *
 * Server-side the delete is a reversible trash, and this route needs
 * `library-manage` — the token introspection at connect time already
 * answered that, so the action is only offered when it can work. A
 * book already gone or already trashed counts as deleted: the end
 * state asked for is the end state that holds.
 */
class LiseurSyncBookDeleter(private val http: RemoteHttp = RemoteHttp()) : BookDeleter {

    override suspend fun delete(
        baseUrl: String,
        credentials: RemoteCredentials,
        book: Book,
    ): ServerDeleteResult = withContext(Dispatchers.IO) {
        val id = book.remoteUuid ?: return@withContext ServerDeleteResult.Failed(null)
        val request = http.request(LiseurSyncApi.book(baseUrl, id), credentials).delete().build()
        try {
            http.client.newCall(request).execute().use { response ->
                when (response.code) {
                    401, 403 -> ServerDeleteResult.NotAllowed
                    200, 404, 409 -> ServerDeleteResult.Deleted
                    else -> ServerDeleteResult.Failed("HTTP ${response.code}")
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Could not delete the book on the server", e)
            ServerDeleteResult.Failed(e.message)
        }
    }

    private companion object {
        const val TAG = "LiseurSyncDelete"
    }
}
