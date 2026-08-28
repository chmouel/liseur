package com.chmouel.liseur.data.komga

import android.util.Log
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteHttp
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.failureForCode
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
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
        asObject(body(get(url, credentials)))

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
            return if (body.isBlank()) null else asObject(body)
        }
    }

    fun postObject(url: String, credentials: RemoteCredentials, json: JSONObject): JSONObject {
        val request = credentials.signInto(okhttp3.Request.Builder().url(url))
            .post(json.toString().toRequestBody(JSON))
            .build()
        return asObject(body(http.client.newCall(request).execute()))
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
        val text = it.body.string()
        if (text.isBlank()) throw RemoteHttpFailure(SyncFailure.Malformed)
        text
    }

    /**
     * Something that answered, but not with JSON.
     *
     * A `JSONException` is unchecked and is not an `IOException`, so
     * left alone it goes straight past every catch between here and the
     * screen. That is how a proxy's HTML error page -- or an address
     * that reaches something else entirely -- ends up killing a catalog
     * refresh outright instead of being reported as a bad answer.
     */
    private fun asObject(text: String): JSONObject = try {
        JSONObject(text)
    } catch (e: JSONException) {
        Log.i(TAG, "The server did not answer with JSON", e)
        throw RemoteHttpFailure(SyncFailure.Malformed)
    }

    companion object {
        private const val TAG = "komga-http"
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

/**
 * A whole-number field, or null when it is absent, null, or not one.
 *
 * Deliberately narrower than [JSONObject.optLong], which answers 0 for
 * all three. A caller checking a count against what arrived needs a
 * missing count to read as missing, not as zero.
 */
internal fun JSONObject.longOrNull(name: String): Long? = when (val value = opt(name)) {
    is Int -> value.toLong()
    is Long -> value
    is String -> value.toLongOrNull()
    else -> null
}

/** As [longOrNull], for a field that has to fit an `Int`. */
internal fun JSONObject.intOrNull(name: String): Int? =
    longOrNull(name)?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()

/**
 * A boolean field, or null when it is absent, null, or something else.
 *
 * The strings are accepted because that is what [JSONObject.optBoolean]
 * does and a server spelling `"true"` means it; anything further from a
 * boolean is treated as not having answered at all.
 */
internal fun JSONObject.booleanOrNull(name: String): Boolean? = when (val value = opt(name)) {
    is Boolean -> value
    is String -> value.lowercase().let { if (it == "true") true else if (it == "false") false else null }
    else -> null
}

internal fun jsonArrayOf(vararg values: JSONObject): JSONArray =
    JSONArray().apply { values.forEach { put(it) } }
