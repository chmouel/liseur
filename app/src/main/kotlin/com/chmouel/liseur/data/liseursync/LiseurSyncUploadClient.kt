package com.chmouel.liseur.data.liseursync

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteHttp
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.failureForCode
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONException
import org.json.JSONObject

/** What an ingest job resolved to. */
sealed interface IngestOutcome {
    /** The book is in the catalog; [bookId] is its id there. */
    data class Promoted(val bookId: String) : IngestOutcome

    /** The server refused the file: not an EPUB, broken, over quota. */
    data class Refused(val reason: String) : IngestOutcome
}

/** Why an upload never reached the point of being judged. */
class UploadException(val reason: SyncFailure) : IOException()

/**
 * Pushing one EPUB up to a liseur-sync library.
 *
 * The upload is bound to a caller-chosen idempotency key, so a retry —
 * the worker rescheduled, the user asking twice — replays to the same
 * job instead of storing the file twice, whatever stage it reached.
 */
class LiseurSyncUploadClient(private val http: RemoteHttp = RemoteHttp()) {

    /** The libraries the token may write to, as `(id, name)` pairs. */
    suspend fun manageableLibraries(
        baseUrl: String,
        credentials: RemoteCredentials,
    ): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val response = get(LiseurSyncApi.url(baseUrl, LiseurSyncApi.LIBRARIES), credentials)
        val array = response.optJSONArray("libraries") ?: return@withContext emptyList()
        (0 until array.length()).mapNotNull { index ->
            val library = array.optJSONObject(index) ?: return@mapNotNull null
            if (library.optString("role") != "manage") return@mapNotNull null
            val id = library.optString("library_id").takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            id to library.optString("name")
        }
    }

    /**
     * Streams [file] into [library] and returns the ingest job's id.
     *
     * The body is read straight off the content resolver into the
     * socket; a several-hundred-megabyte EPUB never sits in memory.
     */
    suspend fun upload(
        baseUrl: String,
        credentials: RemoteCredentials,
        resolver: ContentResolver,
        library: String,
        file: Uri,
        filename: String,
        idempotencyKey: String,
    ): String = withContext(Dispatchers.IO) {
        val body = object : RequestBody() {
            override fun contentType() = EPUB

            // Known up front so the request is not chunked: some servers
            // answer a streaming POST less willingly than a measured one.
            override fun contentLength(): Long = resolver.query(
                file, arrayOf(OpenableColumns.SIZE), null, null, null,
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
            } ?: -1L

            override fun writeTo(sink: BufferedSink) {
                val stream = resolver.openInputStream(file)
                    ?: throw UploadException(SyncFailure.Malformed)
                stream.use { sink.writeAll(it.source()) }
            }
        }
        val multipart = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("file", filename, body)
            .build()
        val request = http
            .request(LiseurSyncApi.upload(baseUrl, library), credentials)
            .header("Idempotency-Key", idempotencyKey)
            .post(multipart)
            .build()
        val answer = execute(request)
        answer.optString("job_id").takeIf { it.isNotEmpty() }
            ?: throw UploadException(SyncFailure.Malformed)
    }

    /** The job's current state; null while it is still being worked on. */
    suspend fun job(
        baseUrl: String,
        credentials: RemoteCredentials,
        jobId: String,
    ): IngestOutcome? = withContext(Dispatchers.IO) {
        val answer = execute(
            http.request(LiseurSyncApi.ingestJob(baseUrl, jobId), credentials).get().build(),
        )
        when (answer.optString("state")) {
            "promoted" -> answer.optString("book_id").takeIf { it.isNotEmpty() }
                ?.let(IngestOutcome::Promoted)
                ?: throw UploadException(SyncFailure.Malformed)

            "failed", "quarantined" -> IngestOutcome.Refused(
                answer.optString("error").ifEmpty { answer.optString("state") },
            )

            else -> null
        }
    }

    private suspend fun get(url: String, credentials: RemoteCredentials): JSONObject =
        execute(http.request(url, credentials).get().build())

    private fun execute(request: Request): JSONObject {
        http.client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // Over-quota and too-large are the ordinary refusals an
                // uploader can be told about; the rest mean the account
                // or the address stopped making sense mid-way.
                val reason = when (response.code) {
                    413 -> SyncFailure.ServerError(413)
                    else -> failureForCode(response.code)
                }
                throw UploadException(reason)
            }
            return try {
                JSONObject(text)
            } catch (e: JSONException) {
                throw UploadException(SyncFailure.Malformed)
            }
        }
    }

    private companion object {
        val EPUB = "application/epub+zip".toMediaType()
    }
}
