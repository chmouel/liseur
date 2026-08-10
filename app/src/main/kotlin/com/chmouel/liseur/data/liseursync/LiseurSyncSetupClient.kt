package com.chmouel.liseur.data.liseursync

import android.util.Log
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.RemoteUrl
import com.chmouel.liseur.data.remote.SyncFailure
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * A liseur-sync server that answered, and the tokens to keep talking to
 * it with.
 *
 * The two tokens are separate because the server scopes them apart: one
 * that may sync cannot read statistics. [insightsToken] is null when the
 * reader did not ask for statistics, or when the server refused that
 * scope — which is not a reason to fail the connection, since syncing
 * positions is the point and statistics are the extra.
 */
data class SyncConnection(
    val baseUrl: String,
    val username: String,
    val token: String,
    val insightsToken: String?,
    val deviceName: String,
    /** The server's own name for this device, when it said one. */
    val deviceId: String? = null,
)

/** Why connecting to a sync server did not work. */
sealed interface SyncSetupFailure {
    /** The sign-in or the token was rejected. */
    data object BadCredentials : SyncSetupFailure

    /** Something answered, but it was not liseur-sync. */
    data object WrongServer : SyncSetupFailure

    /** The server will not take credentials over plain HTTP. */
    data object InsecureTransport : SyncSetupFailure

    /** Too many attempts; the server is asking us to wait. */
    data object RateLimited : SyncSetupFailure

    /** Nothing answered; [httpMayWork] when dropping to HTTP might. */
    data class Unreachable(val httpMayWork: Boolean) : SyncSetupFailure
}

sealed interface SyncSetupResult {
    data class Success(val connection: SyncConnection) : SyncSetupResult
    data class Failure(val reason: SyncSetupFailure) : SyncSetupResult
}

/**
 * The two ways onto a liseur-sync account, as a seam.
 *
 * Named apart from its one implementation so that everything above it —
 * the repository, and through it the settings screen — can be tested
 * for what it writes down rather than for how it speaks HTTP, which
 * [LiseurSyncSetupClient]'s own tests already cover.
 */
interface SyncSetup {

    suspend fun signIn(
        rawUrl: String,
        username: String,
        password: String,
        deviceName: String,
        wantInsights: Boolean,
        allowHttp: Boolean = false,
    ): SyncSetupResult

    suspend fun verifyToken(
        rawUrl: String,
        username: String,
        token: String,
        deviceName: String,
        allowHttp: Boolean = false,
    ): SyncSetupResult
}

/**
 * Getting this device onto a liseur-sync account.
 *
 * There are two ways in, and both end in the same place: a device token
 * that only this phone holds. Signing in mints one — the sign-in token
 * itself may only manage tokens and cannot sync, so it is used once and
 * dropped. Pasting a token skips the password entirely, which is the
 * better story for anyone who would rather their phone never saw it.
 *
 * The account name is asked for in both flows because reading is bound
 * to it: the same server signed into as two people is two partners, and
 * a device token does not say whose it is.
 */
class LiseurSyncSetupClient(private val http: LiseurSyncHttp = LiseurSyncHttp()) : SyncSetup {

    /**
     * Signs in, then mints a device token to keep.
     *
     * The password is never stored. It buys an hour-long token that can
     * do nothing but create the device tokens, and once those exist
     * neither it nor the password is needed again.
     */
    override suspend fun signIn(
        rawUrl: String,
        username: String,
        password: String,
        deviceName: String,
        wantInsights: Boolean,
        allowHttp: Boolean,
    ): SyncSetupResult = withContext(Dispatchers.IO) {
        attempt(rawUrl, allowHttp) { baseUrl ->
            val login = http.post(
                LiseurSyncApi.url(baseUrl, LiseurSyncApi.LOGIN),
                credentials = null,
                json = JSONObject()
                    .put("username", username)
                    .put("password", password),
            )
            val auth = login.optString("auth_token").takeIf { it.isNotEmpty() }
                ?: throw RemoteHttpFailure(SyncFailure.Malformed)
            val session = RemoteCredentials.Bearer(auth)

            val minted = mint(baseUrl, session, deviceName, LiseurSyncApi.SCOPE_SYNC)
            val token = minted?.optString("secret")?.takeIf { it.isNotEmpty() }
                ?: throw RemoteHttpFailure(SyncFailure.Malformed)

            // Statistics are the extra, not the point. A server that will
            // not grant the scope still syncs positions perfectly well,
            // so a refusal here is noted and stepped over.
            val insights = if (wantInsights) {
                try {
                    mint(baseUrl, session, "$deviceName (statistics)", LiseurSyncApi.SCOPE_INSIGHTS)
                        ?.optString("secret")?.takeIf { it.isNotEmpty() }
                } catch (_: IOException) {
                    Log.i(TAG, "No statistics token; positions will still sync")
                    null
                }
            } else {
                null
            }

            SyncConnection(
                baseUrl = baseUrl,
                username = username,
                token = token,
                insightsToken = insights,
                deviceName = deviceName,
                deviceId = minted.optString("device_id").takeIf { it.isNotEmpty() },
            )
        }
    }

    /**
     * Checks a token created elsewhere.
     *
     * Asked of `/v1/changes` rather than anything cheaper, because that
     * is the call this token exists to make: a token of the wrong scope
     * would pass a liveness probe and then fail at the first sync.
     */
    override suspend fun verifyToken(
        rawUrl: String,
        username: String,
        token: String,
        deviceName: String,
        allowHttp: Boolean,
    ): SyncSetupResult = withContext(Dispatchers.IO) {
        attempt(rawUrl, allowHttp) { baseUrl ->
            val answer = http.get(
                LiseurSyncApi.changes(baseUrl, since = 0, limit = 1),
                RemoteCredentials.Bearer(token),
            )
            // Only liseur-sync answers this route with a high-water mark.
            if (!answer.has("high_water")) throw RemoteHttpFailure(SyncFailure.Malformed)
            SyncConnection(baseUrl, username, token, insightsToken = null, deviceName)
        }
    }

    private fun mint(
        baseUrl: String,
        session: RemoteCredentials,
        name: String,
        scope: String,
    ): JSONObject? = http.post(
        LiseurSyncApi.url(baseUrl, LiseurSyncApi.TOKENS),
        session,
        JSONObject().put("name", name).put("scope", scope),
    )

    /**
     * Runs [connect] against the typed address, dropping to plain HTTP
     * only if nothing answered at all and the reader has said they are
     * willing.
     *
     * HTTPS is always tried first, even when HTTP is allowed: a server
     * that is briefly unreachable must not be quietly downgraded, since
     * that is the request that carries the password.
     */
    private inline fun attempt(
        rawUrl: String,
        allowHttp: Boolean,
        connect: (String) -> SyncConnection,
    ): SyncSetupResult {
        val baseUrl = RemoteUrl.normaliseBase(rawUrl)
            ?: return SyncSetupResult.Failure(SyncSetupFailure.WrongServer)

        val reason = try {
            return SyncSetupResult.Success(connect(baseUrl))
        } catch (e: IOException) {
            failureFor(e)
        }

        val unreachable = reason is SyncSetupFailure.Unreachable
        if (!unreachable || !baseUrl.startsWith("https://")) {
            return SyncSetupResult.Failure(reason)
        }
        if (!allowHttp) {
            return SyncSetupResult.Failure(SyncSetupFailure.Unreachable(httpMayWork = true))
        }

        return try {
            SyncSetupResult.Success(connect(RemoteUrl.withHttp(baseUrl)))
        } catch (e: IOException) {
            // If plain HTTP reached the server and it had something to
            // say — a refused password, an insistence on HTTPS — that is
            // the useful complaint. Only when nothing answered there
            // either do we fall back to why HTTPS failed.
            val retry = failureFor(e)
            SyncSetupResult.Failure(
                if (retry is SyncSetupFailure.Unreachable) reason else retry,
            )
        }
    }

    /**
     * What an exception on the way out means to somebody connecting.
     *
     * Nothing here carries the exception's own message forward: OkHttp
     * puts the request URL in some of them, and that request is the one
     * that carried the password.
     */
    private fun failureFor(error: IOException): SyncSetupFailure {
        val reason = (error as? RemoteHttpFailure)?.reason
            ?: return SyncSetupFailure.Unreachable(httpMayWork = false)
        return when (reason) {
            SyncFailure.Unauthorised, SyncFailure.Forbidden -> SyncSetupFailure.BadCredentials
            SyncFailure.InsecureTransport -> SyncSetupFailure.InsecureTransport
            SyncFailure.NotFound, SyncFailure.Malformed -> SyncSetupFailure.WrongServer
            SyncFailure.Offline, SyncFailure.Timeout ->
                SyncSetupFailure.Unreachable(httpMayWork = false)

            is SyncFailure.ServerError ->
                if (reason.code == TOO_MANY) {
                    SyncSetupFailure.RateLimited
                } else {
                    SyncSetupFailure.WrongServer
                }
        }
    }

    private companion object {
        const val TAG = "liseur-sync-setup"
        const val TOO_MANY = 429
    }
}
