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
 * Deletes a book from a liseur-sync server.
 *
 * The server's rule (ADR-0025) mirrors the upload's: a book may be
 * deleted where a book may be written, which means a folder an
 * administrator marked `accepts_uploads` and no other. The file itself
 * goes, for every device, and there is no trash behind it.
 *
 * `forgetReading` is a second, separate question. The reading is per
 * reader, so it asks only about the caller's own — never anybody
 * else's — and the server keeps it by default so that another device
 * still holding the book keeps its position.
 */
class LiseurSyncDeleteClient(
    private val http: RemoteHttp = RemoteHttp(),
) : BookDeleter {

    override suspend fun delete(
        baseUrl: String,
        credentials: RemoteCredentials,
        book: Book,
        forgetReading: Boolean,
    ): ServerDeleteResult = withContext(Dispatchers.IO) {
        val bookId = book.remoteUuid ?: return@withContext ServerDeleteResult.Failed(null)
        val request = http
            .request(LiseurSyncApi.deleteBook(baseUrl, bookId, forgetReading), credentials)
            .delete()
            .build()
        try {
            http.client.newCall(request).execute().use { response ->
                when (response.code) {
                    // A book the server does not have is a book the
                    // reader no longer has to think about, so a 404 is
                    // the outcome they asked for rather than a failure
                    // to report. A retry after a lost answer lands here.
                    204, 404 -> ServerDeleteResult.Deleted
                    401, 403 -> ServerDeleteResult.NotAllowed
                    // 409 is worth showing: a busy Calibre library or a
                    // file that changed under the catalog are both
                    // things the reader can do something about.
                    409 -> ServerDeleteResult.Failed(reasonFrom(response.body.string()))
                    else -> ServerDeleteResult.Failed("HTTP ${response.code}")
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Could not delete the book on the server", e)
            ServerDeleteResult.Failed(e.message)
        }
    }

    /** The server's own sentence, which is written to be shown. */
    private fun reasonFrom(body: String?): String? =
        body?.let { runCatching { org.json.JSONObject(it).optString("error") }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }

    private companion object {
        const val TAG = "LiseurSyncDelete"
    }
}
