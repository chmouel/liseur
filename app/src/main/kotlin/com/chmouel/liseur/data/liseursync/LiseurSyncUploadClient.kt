package com.chmouel.liseur.data.liseursync

import android.util.Log
import com.chmouel.liseur.data.remote.BookUploader
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteHttp
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.RemoteUploadTarget
import com.chmouel.liseur.data.remote.ServerUploadResult
import com.chmouel.liseur.data.remote.SyncFailure
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONException
import org.json.JSONObject

/**
 * Adds a book to a liseur-sync folder that accepts one.
 *
 * The server's rule (ADR-0023) is that an upload is a file written into
 * a folder and nothing more: the catalog is still written by the folder
 * pass. Two things follow for this client. It has to pick a folder,
 * because there is no "the library" to post to — and only folders whose
 * `accepts_uploads` is set will take one. And the answer may legitimately
 * be "the bytes are safe but there is no book yet", which is
 * [ServerUploadResult.Pending] rather than a failure to retry.
 *
 * The file is streamed from disk rather than read into memory. A book is
 * routinely tens of megabytes and this runs on a phone.
 */
class LiseurSyncUploadClient(
    private val http: RemoteHttp = RemoteHttp(),
    private val json: LiseurSyncHttp = LiseurSyncHttp(http),
) : BookUploader {

    /**
     * The folders that said they would take a book.
     *
     * The permission is the server's to give and it gives it per folder,
     * so a token with the scope can still find nowhere to put a book.
     * That is a real answer and the caller shows it as one rather than
     * offering an action that cannot work.
     *
     * Which is why a failure to ask is not an answer of "none". The
     * caller treats an empty list as the server refusing and turns the
     * feature off until the reader signs in again; a dropped connection
     * must not be able to say that. It throws instead, and the worker
     * retries. A body that does not carry a folder list is the same
     * mistake wearing a 200, and is treated the same way.
     */
    override suspend fun targets(
        baseUrl: String,
        credentials: RemoteCredentials,
    ): List<RemoteUploadTarget> {
        val targets = mutableListOf<RemoteUploadTarget>()
        var after: String? = null
        var guard = MAX_PAGES
        while (guard-- > 0) {
            val answer = json.get(LiseurSyncApi.folders(baseUrl, after, FOLDER_PAGE), credentials)
            // A server with no folders sends an empty array, never an
            // absent one, so a missing key is a body this code does not
            // understand rather than an answer of none — a proxy's 200,
            // most likely. Reading it as "no folders" would spend the
            // reader's feature on somebody else's error page.
            val array = answer.optJSONArray("folders")
                ?: throw RemoteHttpFailure(SyncFailure.Malformed)
            for (index in 0 until array.length()) {
                val folder = array.optJSONObject(index) ?: continue
                if (!folder.optBoolean("accepts_uploads")) continue
                val id = folder.optString("folder_id").takeIf { it.isNotEmpty() } ?: continue
                targets += RemoteUploadTarget(
                    folderId = id,
                    name = folder.optString("name").ifEmpty { id },
                )
            }
            after = answer.optString("next_after").takeIf { it.isNotEmpty() } ?: return targets
        }
        // Ten thousand folders in, still being handed a cursor. Something
        // is wrong at the other end, and the truncated list this would
        // otherwise return could be missing every folder that accepts
        // uploads — which reads as a refusal.
        throw RemoteHttpFailure(SyncFailure.Malformed)
    }

    override suspend fun upload(
        baseUrl: String,
        credentials: RemoteCredentials,
        folderId: String,
        file: File,
        filename: String,
    ): ServerUploadResult = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", filename, file.asRequestBody(EPUB))
            .build()
        val request = Request.Builder()
            .url(LiseurSyncApi.uploadBook(baseUrl, folderId))
            .post(body)
            .also { credentials.signInto(it) }
            .build()
        try {
            http.client.newCall(request).execute().use { response ->
                // Bounded on the way in, not after: a server answering a
                // gigabyte of HTML is not a thing to hold in a phone's
                // memory before deciding it was not JSON.
                answerFor(response.code, response.peekBody(MAX_BODY).string())
            }
        } catch (e: IOException) {
            Log.i(TAG, "The book could not be sent to the server", e)
            ServerUploadResult.Failed(e.message)
        }
    }

    /**
     * What the server's answer means to a reader.
     *
     * The codes are the server's own contract and each maps to a
     * different thing to do next, which is why this is a `when` on the
     * code rather than a success check: a 200 and a 201 are both "the
     * server has it", a 202 is "it will have it", and a 403 is the only
     * one where offering the action again would be a lie.
     */
    private fun answerFor(code: Int, text: String): ServerUploadResult {
        val body = parseOrNull(text)
        return when (code) {
            200, 201 -> {
                val id = body?.optString("book_id")?.takeIf { it.isNotEmpty() }
                if (id == null) {
                    ServerUploadResult.Pending
                } else {
                    ServerUploadResult.Uploaded(id, alreadyThere = body.optBoolean("duplicate"))
                }
            }
            202 -> ServerUploadResult.Pending
            401, 403 -> ServerUploadResult.NotAllowed
            413 -> ServerUploadResult.TooLarge
            422 -> ServerUploadResult.Rejected(readable(body?.optString("error")))
            else -> ServerUploadResult.Failed(
                body?.optString("error")?.takeIf { it.isNotEmpty() } ?: "HTTP $code",
            )
        }
    }

    private fun parseOrNull(text: String): JSONObject? = try {
        if (text.isBlank()) null else JSONObject(text)
    } catch (e: JSONException) {
        Log.i(TAG, "The server did not answer with JSON", e)
        null
    }

    /**
     * A sentence from the server, fit to put in front of a reader.
     *
     * This is the one string in the app that a server chooses and the
     * reader sees, so nothing is taken on trust: it is trimmed to one
     * line, stripped of control characters that could forge one, and cut
     * to a length that fits a snackbar rather than filling the screen.
     * Null when there is nothing worth showing, and the caller — which
     * has resources and this does not — says something of its own.
     */
    private fun readable(raw: String?): String? = raw
        ?.replace(CONTROL, " ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { if (it.length > MAX_REASON) it.take(MAX_REASON).trimEnd() + "…" else it }

    private companion object {
        const val TAG = "LiseurSyncUpload"
        const val FOLDER_PAGE = 200
        const val MAX_PAGES = 50
        const val MAX_REASON = 160
        const val MAX_BODY = 64L * 1024
        val CONTROL = Regex("[\\p{Cntrl}\\p{Cf}]+")
        val EPUB = "application/epub+zip".toMediaType()
    }
}
