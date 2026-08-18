package com.chmouel.liseur.data.liseursync

import android.util.Log
import com.chmouel.liseur.data.remote.BookUploader
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteHttp
import com.chmouel.liseur.data.remote.RemoteUploadTarget
import com.chmouel.liseur.data.remote.ServerUploadResult
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
     * retries.
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
            val array = answer.optJSONArray("folders") ?: break
            for (index in 0 until array.length()) {
                val folder = array.optJSONObject(index) ?: continue
                if (!folder.optBoolean("accepts_uploads")) continue
                val id = folder.optString("folder_id").takeIf { it.isNotEmpty() } ?: continue
                targets += RemoteUploadTarget(
                    folderId = id,
                    name = folder.optString("name").ifEmpty { id },
                )
            }
            after = answer.optString("next_after").takeIf { it.isNotEmpty() } ?: break
        }
        return targets
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
                answerFor(response.code, response.body?.string().orEmpty())
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
            422 -> ServerUploadResult.Rejected
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

    private companion object {
        const val TAG = "LiseurSyncUpload"
        const val FOLDER_PAGE = 200
        const val MAX_PAGES = 50
        val EPUB = "application/epub+zip".toMediaType()
    }
}
