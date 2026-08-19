package com.chmouel.liseur.data.calibre

import android.util.Log
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.remote.BookDeleter
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.ServerDeleteResult
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody

/**
 * Deletes a book from calibre-web itself.
 *
 * Neither OPDS nor the Kobo API can do this — calibre-web only exposes it
 * through the web UI — so this posts to the same route the Delete button in
 * a browser does, with a session cookie and a CSRF token.
 */
class CalibreDeleteClient {

    suspend fun delete(
        baseUrl: String,
        username: String,
        password: String,
        remoteBookId: Int,
    ): ServerDeleteResult = withContext(Dispatchers.IO) {
        val session = CalibreWebSession.logIn(baseUrl, username, password)
            ?: return@withContext ServerDeleteResult.NotAllowed
        val csrf = session.csrfToken()
        try {
            val url = CalibreUrl.resolve(baseUrl, "/delete/$remoteBookId")
            val request = session.http.request(url, null)
                .apply { csrf?.let { header("X-CSRFToken", it) } }
                .post(FormBody.Builder().build())
                .build()
            session.http.client.newCall(request).execute().use { response ->
                when {
                    response.code == 401 || response.code == 403 -> ServerDeleteResult.NotAllowed
                    !response.isSuccessful -> ServerDeleteResult.Failed("HTTP ${response.code}")
                    // A 200 is not proof. calibre-web sends anyone without a
                    // session to the login page, which arrives as a perfectly
                    // successful response; taking it at its word would mean
                    // deleting the only remaining copy of the book.
                    CalibreParsing.isLoginPage(response.body?.string().orEmpty()) ->
                        ServerDeleteResult.NotAllowed
                    else -> ServerDeleteResult.Deleted
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Could not delete the book on the server", e)
            ServerDeleteResult.Failed(e.message)
        }
    }

    private companion object {
        const val TAG = "CalibreDelete"
    }
}

/**
 * calibre-web's [BookDeleter]: the web form's delete route, which is the
 * only way in — neither OPDS nor the Kobo API can remove a book.
 */
class CalibreBookDeleter(
    private val client: CalibreDeleteClient = CalibreDeleteClient(),
) : BookDeleter {
    override suspend fun delete(
        baseUrl: String,
        credentials: RemoteCredentials,
        book: Book,
        // Ignored, and honestly so: calibre-web holds no reading of its
        // own to forget. Positions there live in the Kobo sync layer,
        // which a deleted book takes with it.
        forgetReading: Boolean,
    ): ServerDeleteResult {
        val basic = credentials as? RemoteCredentials.Basic
            ?: return ServerDeleteResult.NotAllowed
        val remoteBookId = book.remoteBookId ?: return ServerDeleteResult.Failed(null)
        return client.delete(baseUrl, basic.username, basic.password, remoteBookId)
    }
}
