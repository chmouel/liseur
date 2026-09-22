package com.chmouel.liseur.data.bookorbit

import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteHttp
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.failureForCode
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The few HTTP shapes every BookOrbit call needs.
 *
 * BookOrbit speaks JSON everywhere and answers 201 to a POST that
 * created nothing, so the awkward parts are the same each time: get a
 * live token from the session, refuse anything that is not a success,
 * and cope with an answer that is deliberately empty.
 *
 * Repeatable calls use [BookOrbitSession.authorized] to renew a refused
 * token. Progress mutations use [postProgress] instead: a response lost
 * after delivery must not cause an automatic second POST.
 */
class BookOrbitHttp(
    private val session: BookOrbitSession,
    private val http: RemoteHttp = RemoteHttp(),
) {
    /** A progress POST must not be replayed merely because its response was lost. */
    sealed interface MutationResult {
        data object ReadBackRequired : MutationResult
        data class Rejected(val status: Int) : MutationResult
        data object Uncertain : MutationResult
    }

    internal suspend fun postProgress(
        context: BookOrbitRequestContext,
        url: String,
        json: JSONObject,
    ): MutationResult {
        val token = session.token(context)
        val bearer = RemoteCredentials.Bearer(token)
        val request = signed(context, url, bearer)
            .post(json.toString().toRequestBody(JSON)).build()
        return try {
            http.client.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build().newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> MutationResult.ReadBackRequired
                    response.code == 401 -> {
                        session.noteRejection(token)
                        MutationResult.Rejected(response.code)
                    }
                    response.code == 403 -> MutationResult.Rejected(response.code)
                    else -> MutationResult.Uncertain
                }
            }
        } catch (_: IOException) {
            MutationResult.Uncertain
        }
    }

    suspend fun getObject(
        context: BookOrbitRequestContext,
        url: String,
    ): JSONObject = asObject(get(context, url))

    /**
     * A GET whose answer may legitimately be nothing at all.
     *
     * BookOrbit answers a file the reader has never opened with a
     * default body rather than a 404, so an empty body here is not the
     * "nothing" case; it is a malformed answer, because a route that
     * exists always says something. Kept for the routes that really do
     * answer 204.
     */
    suspend fun getObjectOrNull(
        context: BookOrbitRequestContext,
        url: String,
    ): JSONObject? =
        session.authorized(context) { bearer ->
            http.client.newCall(signed(context, url, bearer).build()).execute().use { response ->
                if (!response.isSuccessful) throw RemoteHttpFailure(failureForCode(response.code))
                val text = response.body?.string().orEmpty()
                if (text.isBlank()) null else asObject(text)
            }
        }

    suspend fun postObject(
        context: BookOrbitRequestContext,
        url: String,
        json: JSONObject,
        rejected: Set<Int> = emptySet(),
    ): JSONObject? = session.authorized(context) { bearer ->
        val request = signed(context, url, bearer).post(json.toString().toRequestBody(JSON)).build()
        http.client.newCall(request).execute().use { response ->
            if (response.code in rejected) return@authorized null
            asObject(body(response))
        }
    }

    /**
     * A write whose answer we do not need, only its verdict.
     *
     * [rejected] is how a refusal that is not really an error gets back
     * out, the way Komga's 400 and 409 do; BookOrbit has fewer of them,
     * but a rate limit or a validation failure still deserves a caller
     * that can tell it apart from a transport failure.
     */
    suspend fun send(
        context: BookOrbitRequestContext,
        url: String,
        method: String,
        json: JSONObject? = null,
        rejected: Set<Int> = emptySet(),
    ): Int = session.authorized(context) { bearer ->
        val body = json?.toString()?.toRequestBody(JSON) ?: EMPTY
        val request = signed(context, url, bearer)
            .method(method, if (method == "DELETE" && json == null) null else body)
            .build()
        http.client.newCall(request).execute().use { response ->
            if (response.isSuccessful || response.code in rejected) return@authorized response.code
            throw RemoteHttpFailure(failureForCode(response.code))
        }
    }

    private suspend fun get(
        context: BookOrbitRequestContext,
        url: String,
    ): String = session.authorized(context) { bearer ->
        body(http.client.newCall(signed(context, url, bearer).build()).execute())
    }

    private fun signed(
        context: BookOrbitRequestContext,
        url: String,
        bearer: RemoteCredentials.Bearer,
    ): Request.Builder {
        if (!context.covers(url)) throw RemoteHttpFailure(SyncFailure.Unauthorised)
        return bearer.signInto(Request.Builder().url(url).header("Accept", "application/json"))
    }

    private fun body(response: Response): String = response.use {
        if (!it.isSuccessful) throw RemoteHttpFailure(failureForCode(it.code))
        val text = it.body?.string().orEmpty()
        if (text.isBlank()) throw RemoteHttpFailure(SyncFailure.Malformed)
        text
    }

    /**
     * Something that answered, but not with JSON.
     *
     * A `JSONException` is unchecked and is not an `IOException`, so left
     * alone it goes past every catch between here and the screen. That is
     * how a proxy's HTML error page ends up killing a catalog refresh
     * instead of being reported as a bad answer.
     */
    private fun asObject(text: String): JSONObject = try {
        JSONObject(text)
    } catch (_: JSONException) {
        throw RemoteHttpFailure(SyncFailure.Malformed)
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val EMPTY = ByteArray(0).toRequestBody(null)
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

/** A whole-number field, or null when it is absent, null, or not one. */
internal fun JSONObject.longOrNull(name: String): Long? = when (val value = opt(name)) {
    is Int -> value.toLong()
    is Long -> value
    is String -> value.toLongOrNull()
    else -> null
}

/** As [longOrNull], for a field that has to fit an `Int`. */
internal fun JSONObject.intOrNull(name: String): Int? =
    longOrNull(name)?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()

/** A double field, or null when it is absent, null, or not a number. */
internal fun JSONObject.doubleOrNull(name: String): Double? = when (val value = opt(name)) {
    is Number -> value.toDouble().takeIf { it.isFinite() }
    is String -> value.toDoubleOrNull()?.takeIf { it.isFinite() }
    else -> null
}

/** A boolean field, or null when it is absent, null, or something else. */
internal fun JSONObject.booleanOrNull(name: String): Boolean? = when (val value = opt(name)) {
    is Boolean -> value
    is String -> value.lowercase().let { if (it == "true") true else if (it == "false") false else null }
    else -> null
}

internal fun jsonArrayOf(strings: Collection<String>): JSONArray =
    JSONArray().apply { strings.forEach { put(it) } }
