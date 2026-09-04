package com.chmouel.liseur.data.liseursync

import android.util.Log
import com.chmouel.liseur.data.remote.AnsweredFailure
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteHttp
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.failureForCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject

/**
 * An answer from liseur-sync that said no in a way the caller has a move
 * for.
 *
 * The protocol has a few of these, and they are ordinary parts of
 * syncing rather than errors: a cursor that has fallen behind the
 * server's compaction horizon, identifiers that name two different
 * books. They carry their body because that body *is* the answer — the
 * works to choose between, the seq to resync from.
 */
class LiseurSyncRejection(
    val code: Int,
    val body: JSONObject?,
) : java.io.IOException("liseur-sync refused with $code"), AnsweredFailure {

    /** The server's own word for what was wrong, if it gave one. */
    val error: String? get() = body?.optString("error")?.takeIf { it.isNotEmpty() }

    /** The machine-readable refusal kind, when the server gave one. */
    val errorCode: String? get() = body?.optString("code")?.takeIf { it.isNotEmpty() }

    /** The work the server no longer holds, on an [UNKNOWN_WORK] refusal. */
    val workId: String? get() = body?.optString("work_id")?.takeIf { it.isNotEmpty() }

    /** The op that named the unknown work. */
    val opId: String? get() = body?.optString("op_id")?.takeIf { it.isNotEmpty() }

    /** The session that named the unknown work. */
    val sessionId: String? get() = body?.optString("session_id")?.takeIf { it.isNotEmpty() }

    /**
     * The one refusal a client recovers from: the server no longer
     * holds a work this device had a cached name for.
     *
     * Only this exact shape enters stale-identity recovery — a missing
     * or malformed code is an ordinary 400 and invalidates nothing.
     */
    val isUnknownWork: Boolean
        get() = code == LiseurSyncHttp.BAD_REQUEST && errorCode == UNKNOWN_WORK

    companion object {
        /** A batch item named a work the server no longer holds. */
        const val UNKNOWN_WORK = "unknown_work"

        /** A mint asked to keep a device id no token of the account carries. */
        const val UNKNOWN_DEVICE = "unknown_device"
    }
}

/**
 * The few HTTP shapes every liseur-sync call needs.
 *
 * The server answers JSON everywhere and states its errors as
 * `{"error": ...}` with a precise 4xx, so the awkward parts are the same
 * each time: sign the request, tell a refusal that means something apart
 * from one that does not, and never let a proxy's HTML error page reach
 * a JSON parser unannounced.
 *
 * Both calls suspend and move to the IO dispatcher here rather than
 * leaving it to each caller. One of them forgot, and since a `suspend`
 * signature reads like a promise that the thread is safe, the mistake
 * was invisible until a statistics screen brought the app down from
 * `viewModelScope`. Blocking where the blocking happens costs nothing
 * and removes the way to get it wrong.
 */
class LiseurSyncHttp(private val http: RemoteHttp = RemoteHttp()) {

    suspend fun get(
        url: String,
        credentials: RemoteCredentials?,
        expected: Set<Int> = emptySet(),
    ): JSONObject = send(Request.Builder().url(url).get(), credentials, expected)

    suspend fun post(
        url: String,
        credentials: RemoteCredentials?,
        json: JSONObject,
        expected: Set<Int> = emptySet(),
    ): JSONObject = send(
        Request.Builder().url(url).post(json.toString().toRequestBody(JSON)),
        credentials,
        expected,
    )

    suspend fun put(
        url: String,
        credentials: RemoteCredentials?,
        json: JSONObject,
        expected: Set<Int> = emptySet(),
    ): JSONObject = send(
        Request.Builder().url(url).put(json.toString().toRequestBody(JSON)),
        credentials,
        expected,
    )

    /**
     * Posts a body exactly as given, without parsing it.
     *
     * Annotation pushes need this. The server answers a repeat only when
     * the payload matches to the byte, and going through [JSONObject]
     * would not survive that: its keys come out of a hash map, so
     * reserialising the same request can order them differently. So the
     * bytes are settled once, stored, and sent from storage — first
     * attempt and retry alike, since a retry that goes down a different
     * path is only a retry by intention.
     */
    suspend fun postRaw(
        url: String,
        credentials: RemoteCredentials?,
        body: String,
        expected: Set<Int> = emptySet(),
    ): JSONObject = send(
        Request.Builder().url(url).post(body.toRequestBody(JSON)),
        credentials,
        expected,
    )

    suspend fun delete(
        url: String,
        credentials: RemoteCredentials?,
        expected: Set<Int> = emptySet(),
    ): JSONObject = send(Request.Builder().url(url).delete(), credentials, expected)

    suspend fun putNoContent(
        url: String,
        credentials: RemoteCredentials?,
        json: JSONObject,
        expected: Set<Int> = emptySet(),
    ) {
        sendMaybeEmpty(
            Request.Builder().url(url).put(json.toString().toRequestBody(JSON)),
            credentials,
            expected,
        )
    }

    /**
     * Runs a request and insists on JSON back.
     *
     * [expected] names the refusals the caller has a move for; they come
     * back as [LiseurSyncRejection] with their body intact. Everything
     * else becomes the same [RemoteHttpFailure] the rest of the app
     * already knows how to phrase.
     */
    private suspend fun send(
        builder: Request.Builder,
        credentials: RemoteCredentials?,
        expected: Set<Int>,
    ): JSONObject = withContext(Dispatchers.IO) {
        val request = builder.also { credentials?.signInto(it) }.build()
        http.client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code in expected) {
                throw LiseurSyncRejection(response.code, parseOrNull(text))
            }
            if (!response.isSuccessful) throw failureFor(response, text)
            parseOrNull(text) ?: throw RemoteHttpFailure(SyncFailure.Malformed)
        }
    }

    private suspend fun sendMaybeEmpty(
        builder: Request.Builder,
        credentials: RemoteCredentials?,
        expected: Set<Int>,
    ) = withContext(Dispatchers.IO) {
        val request = builder.also { credentials?.signInto(it) }.build()
        http.client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code in expected) {
                throw LiseurSyncRejection(response.code, parseOrNull(text))
            }
            if (!response.isSuccessful) throw failureFor(response, text)
        }
    }

    /**
     * Why a refusal happened, in the app's own vocabulary.
     *
     * The one worth separating out is an instance that will not take
     * credentials over plain HTTP: it says so with a 403, which
     * otherwise reads as "this account may not", and sends the reader
     * looking for a permission when what is wrong is the address.
     */
    private fun failureFor(response: Response, body: String): RemoteHttpFailure {
        val reason = parseOrNull(body)?.optString("error").orEmpty()
        if (response.code == 403 && reason.contains("http", ignoreCase = true)) {
            return RemoteHttpFailure(SyncFailure.InsecureTransport)
        }
        return RemoteHttpFailure(failureForCode(response.code))
    }

    /**
     * JSON, or nothing.
     *
     * A `JSONException` is unchecked and is not an `IOException`, so left
     * alone it goes straight past every catch between here and the
     * screen — which is how an address that reaches something other than
     * liseur-sync kills a run outright instead of being reported as a bad
     * answer.
     */
    private fun parseOrNull(text: String): JSONObject? = try {
        if (text.isBlank()) null else JSONObject(text)
    } catch (e: JSONException) {
        Log.i(TAG, "The server did not answer with JSON", e)
        null
    }

    companion object {
        private const val TAG = "liseur-sync-http"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** A malformed batch — or, with a `code`, a recoverable refusal. */
        const val BAD_REQUEST = 400

        /** Identifiers that name two different books. */
        const val CONFLICT = 409

        /** A request bigger than the server was configured to take. */
        const val TOO_LARGE = 413

        /** An annotation this server has no record of, swept or never made. */
        const val NOT_FOUND = 404

        /** A cursor that fell behind the server's compaction horizon. */
        const val GONE = 410
    }
}
