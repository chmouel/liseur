package com.chmouel.liseur.data.bookorbit

import android.util.Log
import com.chmouel.liseur.data.remote.PriorConnection
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteHttp
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.RemoteUrl
import com.chmouel.liseur.data.remote.ServerCapabilities
import com.chmouel.liseur.data.remote.ServerSetup
import com.chmouel.liseur.data.remote.SetupFailure
import com.chmouel.liseur.data.remote.SetupResult
import com.chmouel.liseur.data.remote.SyncFailure
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * Proving an address is a BookOrbit server, and learning what the
 * account it answers for may do.
 *
 * The reader hands in a password, which is the only credential BookOrbit
 * offers a native client, and gets back a session: an access token that
 * lasts minutes and a refresh token that lasts a week. The password is
 * not kept. That is the same trade liseur-sync makes, and the reason the
 * server picker says so.
 *
 * Nothing here is signed in with until the probe has succeeded and the
 * account row has been written; that is `BookOrbitSession.adopt`'s job.
 * A candidate address that fails therefore cannot disturb the account
 * that is already connected.
 */
class BookOrbitSetupClient(
    private val http: RemoteHttp = RemoteHttp(RemoteHttp.forAuthentication()),
    private val deviceLabel: suspend () -> String = { "Liseur" },
    private val session: BookOrbitSession? = null,
) : ServerSetup {

    override suspend fun connect(
        rawUrl: String,
        credentials: RemoteCredentials,
        allowHttp: Boolean,
    ): SetupResult = withContext(Dispatchers.IO) {
        probe(rawUrl, credentials, allowHttp, prior = null)
    }

    override suspend fun reconnect(
        rawUrl: String,
        credentials: RemoteCredentials,
        allowHttp: Boolean,
        prior: PriorConnection,
    ): SetupResult = withContext(Dispatchers.IO) {
        probe(rawUrl, credentials, allowHttp, prior)
    }

    private suspend fun probe(
        rawUrl: String,
        credentials: RemoteCredentials,
        allowHttp: Boolean,
        prior: PriorConnection?,
    ): SetupResult {
        val candidates = BookOrbitUrl.baseUrlCandidates(rawUrl)
        if (candidates.isEmpty()) return SetupResult.Failure(SetupFailure.WrongServer)

        when (val found = probeAll(candidates, credentials, prior)) {
            is Probe.Ok -> return SetupResult.Success(found.capabilities)
            is Probe.Failed -> {
                // A home server is often reached over plain HTTP, so an
                // address that only failed to be reached on HTTPS is
                // offered the other scheme — but only when the reader
                // asked, because the password crosses it.
                if (!allowHttp ||
                    !candidates.first().startsWith("https://") ||
                    found.reason !is SetupFailure.Unreachable
                ) {
                    return SetupResult.Failure(found.reason)
                }
                val retry = probeAll(candidates.map(BookOrbitUrl::withHttp), credentials, prior)
                return when (retry) {
                    is Probe.Ok -> SetupResult.Success(retry.capabilities)
                    // Why HTTPS failed is the more useful complaint —
                    // unless the server did answer over HTTP and said
                    // something about the reader, which is then the
                    // thing to report.
                    is Probe.Failed ->
                        if (retry.reason is SetupFailure.Unreachable) {
                            SetupResult.Failure(found.reason)
                        } else {
                            SetupResult.Failure(retry.reason)
                        }
                }
            }
        }
    }

    private suspend fun probeAll(
        candidates: List<String>,
        credentials: RemoteCredentials,
        prior: PriorConnection?,
    ): Probe {
        var worst: Probe.Failed = Probe.Failed(SetupFailure.WrongServer)
        for (candidate in candidates) {
            when (val result = probeOne(candidate, credentials, prior)) {
                is Probe.Ok -> return result
                is Probe.Failed -> {
                    // Bad credentials, a throttled sign-in and an
                    // unreachable host are answers about the reader, not
                    // about the address, so trying another path would
                    // only ask the same question again.
                    if (result.reason is SetupFailure.BadCredentials ||
                        result.reason is SetupFailure.Unreachable ||
                        result.reason is SetupFailure.RateLimited
                    ) {
                        return result
                    }
                    worst = result
                }
            }
        }
        return worst
    }

    private suspend fun probeOne(
        baseUrl: String,
        credentials: RemoteCredentials,
        prior: PriorConnection?,
    ): Probe {
        return try {
            val found = when {
                credentials is RemoteCredentials.Basic -> signIn(baseUrl, credentials)

                // A stored refresh token that names this very address is
                // the way back in once the access token has expired.
                // Against another address it means nothing, so it is
                // checked rather than offered — and the stored spelling
                // may have been an address without a scheme, so both
                // sides are normalised before they are compared.
                prior?.refreshToken != null &&
                    RemoteUrl.sameAddress(normalise(prior.baseUrl), baseUrl) ->
                    renewThroughSessionOrDirect(baseUrl)

                credentials is RemoteCredentials.Bearer ->
                    Found(user = me(baseUrl, credentials.token), tokens = TokenSet.none)

                else -> return Probe.Failed(SetupFailure.BadCredentials)
            }
            if (!isBookOrbit(found.user)) return Probe.Failed(SetupFailure.WrongServer)
            Probe.Ok(capabilities(baseUrl, found.user, found.tokens))
        } catch (e: RemoteHttpFailure) {
            Log.i(TAG, "BookOrbit setup probe refused at $baseUrl: ${e.reason.label}")
            Probe.Failed(failureFor(e.reason))
        } catch (_: IOException) {
            // Nothing answered. Whether plain HTTP is worth offering is
            // a question about the scheme that failed, not about the
            // address.
            Probe.Failed(
                SetupFailure.Unreachable("No answer", httpMayWork = baseUrl.startsWith("https://")),
            )
        }
    }

    private suspend fun signIn(
        baseUrl: String,
        credentials: RemoteCredentials.Basic,
    ): Found {
        val result = BookOrbitAuth.login(
            baseUrl = baseUrl,
            username = credentials.username,
            password = credentials.password,
            deviceLabel = deviceLabel(),
            http = http,
        )
        return Found(result.user, result.tokens())
    }

    /**
     * Capability refresh must use the live session's mutex. A direct
     * refresh here would race a catalog/download renewal and could rotate
     * the database token while the session still holds the old one. The
     * session also persists the replacement before returning it, so a
     * later `/auth/me` failure cannot lose the only usable refresh token.
     */
    private suspend fun renewThroughSessionOrDirect(baseUrl: String): Found {
        val live = session
            ?.contextFor(baseUrl)
            ?: throw RemoteHttpFailure(SyncFailure.Unauthorised)
        return Found(
            user = session.authorized(live) { bearer -> me(baseUrl, bearer.token) },
            tokens = TokenSet.none,
        )
    }

    private suspend fun me(baseUrl: String, accessToken: String): JSONObject {
        val request = RemoteCredentials.Bearer(accessToken)
            .signInto(Request.Builder().url(BookOrbitUrl.api(baseUrl, "/auth/me")))
            .header("Accept", "application/json")
            .build()
        http.client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw RemoteHttpFailure(coded(response.code))
            return runCatching { JSONObject(text) }.getOrNull()
                ?: throw RemoteHttpFailure(SyncFailure.Malformed)
        }
    }

    /** The stored spelling of an address, given a scheme if it lacked one. */
    private fun normalise(address: String): String =
        BookOrbitUrl.normaliseBaseUrl(address) ?: address

    private fun coded(code: Int): SyncFailure = when (code) {
        401 -> SyncFailure.Unauthorised
        403 -> SyncFailure.Forbidden
        404 -> SyncFailure.NotFound
        else -> SyncFailure.ServerError(code)
    }

    /**
     * Whether a body is a BookOrbit account at all.
     *
     * Anything can answer 200 to anything, so the test is a field only
     * this server produces. The id matters most: it is what the book
     * identity is built from, so its absence is a refusal rather than a
     * detail.
     */
    private fun isBookOrbit(user: JSONObject): Boolean =
        user.longOrNull("id") != null && (user.has("username") || user.has("permissions"))

    private fun capabilities(baseUrl: String, user: JSONObject, tokens: TokenSet): ServerCapabilities {
        val granted = permissions(user)
        return ServerCapabilities(
            baseUrl = baseUrl,
            canDownload = granted.permits("library_download"),
            canUpload = granted.permits("library_upload"),
            canDelete = granted.permits("library_delete_books"),
            canManageLibrary = granted.permits("library_edit_metadata"),
            accountId = user.longOrNull("id")?.toString(),
            displayName = user.stringOrNull("name")
                ?: user.stringOrNull("username")
                ?: "BookOrbit",
            orbitAccessToken = tokens.accessToken,
            orbitRefreshToken = tokens.refreshToken,
            orbitAccessExpiresAt = tokens.accessExpiresAt,
            orbitSessionId = tokens.sessionId,
        )
    }

    private fun permissions(user: JSONObject): Set<String> {
        val array = user.optJSONArray("permissions") ?: return emptySet()
        return (0 until array.length())
            .mapNotNull { array.optString(it).takeIf(String::isNotEmpty) }
            .toSet()
    }

    /** A superuser's `*` stands for every permission there is. */
    private fun Set<String>.permits(permission: String): Boolean =
        contains("*") || contains(permission)

    private fun failureFor(reason: SyncFailure): SetupFailure = when (reason) {
        SyncFailure.Unauthorised, SyncFailure.Forbidden -> SetupFailure.BadCredentials
        SyncFailure.Offline, SyncFailure.Timeout ->
            SetupFailure.Unreachable("No answer", httpMayWork = false)

        is SyncFailure.ServerError ->
            if (reason.code == 429) SetupFailure.RateLimited else SetupFailure.WrongServer

        else -> SetupFailure.WrongServer
    }

    /** What a successful probe learned: who answered, and any new tokens. */
    private data class Found(val user: JSONObject, val tokens: TokenSet)

    /** The tokens setup minted, when it minted any. */
    private data class TokenSet(
        val accessToken: String? = null,
        val refreshToken: String? = null,
        val accessExpiresAt: Long = 0,
        val sessionId: Int? = null,
    ) {
        companion object {
            val none = TokenSet()
        }
    }

    private fun BookOrbitAuth.Result.tokens(): TokenSet = TokenSet(
        accessToken = accessToken,
        refreshToken = refreshToken,
        accessExpiresAt = accessExpiresAt,
        sessionId = sessionId,
    )

    private sealed interface Probe {
        data class Ok(val capabilities: ServerCapabilities) : Probe
        data class Failed(val reason: SetupFailure) : Probe
    }

    private companion object {
        const val TAG = "bookorbit-setup"
    }
}
