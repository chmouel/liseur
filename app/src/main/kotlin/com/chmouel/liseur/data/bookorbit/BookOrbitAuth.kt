package com.chmouel.liseur.data.bookorbit

import com.chmouel.liseur.data.remote.RemoteHttp
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.failureForCode
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject

/**
 * BookOrbit's sign-in routes, on their own.
 *
 * Kept apart from the session that holds the tokens because one of them
 * must be usable with credentials nobody is signed in with yet: setup
 * probes a candidate address with the password the reader just typed,
 * and that attempt must not touch the session the connected account is
 * already using.
 *
 * Every function throws [RemoteHttpFailure], the same as the rest of the
 * remote layer, so a caller already inside `remoteCall` reads the
 * failures it was going to read anyway.
 */
internal object BookOrbitAuth {

    /** What BookOrbit answers a sign-in or a renewal with. */
    data class Result(
        val accessToken: String,
        val accessExpiresAt: Long,
        val refreshToken: String,
        val refreshExpiresAt: Long,
        val sessionId: Int?,
        /** The account, as `/auth/me` would report it. */
        val user: JSONObject,
    )

    /**
     * Exchanges a password for a session.
     *
     * `clientKind: "native"` is what makes the reply carry a refresh
     * token rather than setting cookies: the web client gets a thinner
     * body and a browser jar to keep it in.
     */
    fun login(
        baseUrl: String,
        username: String,
        password: String,
        deviceLabel: String,
        http: RemoteHttp,
    ): Result {
        val body = JSONObject()
            .put("username", username)
            .put("password", password)
            .put("clientKind", "native")
            .put("deviceLabel", deviceLabel.take(100))
        return parse(send(baseUrl, "/auth/login", body, http))
    }

    /**
     * Trades a refresh token for a new pair.
     *
     * BookOrbit replaces the refresh token on every use, so the reply is
     * the only place the next one exists. Losing it costs a re-login,
     * not the account.
     */
    fun refresh(baseUrl: String, refreshToken: String, http: RemoteHttp): Result {
        val body = JSONObject().put("refreshToken", refreshToken)
        return parse(send(baseUrl, "/auth/refresh", body, http))
    }

    /**
     * Ends a session, best effort.
     *
     * A failure here is not worth reporting: the tokens are being thrown
     * away either way, and the server expires them on its own. The one
     * thing it must not do is fail the caller.
     */
    fun logout(baseUrl: String, refreshToken: String, http: RemoteHttp) {
        runCatching {
            val body = JSONObject().put("refreshToken", refreshToken)
            send(baseUrl, "/auth/logout", body, http)
        }
    }

    private fun send(
        baseUrl: String,
        path: String,
        body: JSONObject,
        http: RemoteHttp,
    ): JSONObject {
        val request = Request.Builder()
            .url(BookOrbitUrl.api(baseUrl, path))
            .post(body.toString().toRequestBody(JSON))
            .header("Accept", "application/json")
            .build()
        http.client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw RemoteHttpFailure(failureForCode(response.code))
            if (text.isBlank()) throw RemoteHttpFailure(SyncFailure.Malformed)
            return try {
                JSONObject(text)
            } catch (_: JSONException) {
                // A proxy's HTML page, or something that is not BookOrbit
                // at all. Never echoed: a sign-in body can carry the
                // password back.
                throw RemoteHttpFailure(SyncFailure.Malformed)
            }
        }
    }

    private fun parse(json: JSONObject): Result {
        val access = json.optString("accessToken").takeIf { it.isNotEmpty() }
            ?: throw RemoteHttpFailure(SyncFailure.Malformed)
        val refresh = json.optString("refreshToken").takeIf { it.isNotEmpty() }
            ?: throw RemoteHttpFailure(SyncFailure.Malformed)
        val accessExpires = BookOrbitTime.parse(json.optString("accessTokenExpiresAt"))
            ?: throw RemoteHttpFailure(SyncFailure.Malformed)
        val refreshExpires = BookOrbitTime.parse(json.optString("refreshTokenExpiresAt"))
            ?: throw RemoteHttpFailure(SyncFailure.Malformed)
        return Result(
            accessToken = access,
            accessExpiresAt = accessExpires,
            refreshToken = refresh,
            refreshExpiresAt = refreshExpires,
            sessionId = when (val id = json.opt("sessionId")) {
                is Number -> id.toInt()
                is String -> id.toIntOrNull()
                else -> null
            },
            user = json.optJSONObject("user") ?: JSONObject(),
        )
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()
}
