package com.chmouel.liseur.data.komga

import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteHttp
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.failureForCode
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/**
 * The few HTTP shapes every Komga call needs.
 *
 * Komga speaks JSON everywhere, so the awkward parts are the same each
 * time: sign the request, refuse anything that is not a success, and
 * cope with the answers that are deliberately empty. Keeping them here
 * means the clients above read as the requests they are.
 *
 * Each call throws [RemoteHttpFailure] rather than returning a result,
 * because most of them happen inside a loop; the caller wraps the whole
 * walk in `remoteCall` once.
 */
class KomgaHttp(private val http: RemoteHttp = RemoteHttp()) {

    fun getObject(url: String, credentials: RemoteCredentials): JSONObject =
        JSONObject(body(get(url, credentials)))

    /**
     * A GET whose answer may legitimately be nothing at all.
     *
     * Komga says "no reading position yet" with 204 and an empty body.
     * A book it has never heard of is a 404, and stays a failure: the
     * two mean quite different things and only one of them is fine.
     */
    fun getObjectOrNull(url: String, credentials: RemoteCredentials): JSONObject? {
        http.client.newCall(http.request(url, credentials).build()).execute().use { response ->
            if (!response.isSuccessful) throw RemoteHttpFailure(failureForCode(response.code))
            val body = response.body?.string().orEmpty()
            return if (body.isBlank()) null else JSONObject(body)
        }
    }

    fun postObject(url: String, credentials: RemoteCredentials, json: JSONObject): JSONObject {
        val request = credentials.signInto(okhttp3.Request.Builder().url(url))
            .post(json.toString().toRequestBody(JSON))
            .build()
        return JSONObject(body(http.client.newCall(request).execute()))
    }

    /**
     * A write whose answer we do not need, only its verdict.
     *
     * [rejected] is how a refusal that is not really an error gets back
     * out: Komga answers 400 when a position does not fit the book it
     * was sent for, and 409 when what it already holds is newer. Both
     * are things the caller has a sensible next move for, so neither is
     * worth throwing over.
     */
    fun send(
        url: String,
        credentials: RemoteCredentials,
        method: String,
        json: JSONObject?,
        rejected: Set<Int> = emptySet(),
    ): Int {
        val body = json?.toString()?.toRequestBody(JSON)
            ?: ByteArray(0).toRequestBody(null)
        val request = credentials.signInto(okhttp3.Request.Builder().url(url))
            .method(method, if (method == "DELETE" && json == null) null else body)
            .build()
        http.client.newCall(request).execute().use { response ->
            if (response.isSuccessful || response.code in rejected) return response.code
            throw RemoteHttpFailure(failureForCode(response.code))
        }
    }

    private fun get(url: String, credentials: RemoteCredentials): Response =
        http.client.newCall(http.request(url, credentials).build()).execute()

    private fun body(response: Response): String = response.use {
        if (!it.isSuccessful) throw RemoteHttpFailure(failureForCode(it.code))
        val text = it.body?.string().orEmpty()
        if (text.isBlank()) throw RemoteHttpFailure(SyncFailure.Malformed)
        text
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** The objects in [name], or none when the field is absent or null. */
internal fun JSONObject.objects(name: String): List<JSONObject> {
    val array = optJSONArray(name) ?: return emptyList()
    return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}

/** A string field, or null when it is absent, null, or JSON's `"null"`. */
internal fun JSONObject.stringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

internal fun jsonArrayOf(vararg values: JSONObject): JSONArray =
    JSONArray().apply { values.forEach { put(it) } }
