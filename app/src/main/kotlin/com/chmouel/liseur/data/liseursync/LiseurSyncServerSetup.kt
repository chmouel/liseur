package com.chmouel.liseur.data.liseursync

import android.util.Log
import com.chmouel.liseur.data.remote.RemoteCredentials
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
import org.json.JSONArray
import org.json.JSONObject

/**
 * Works out everything about a liseur-sync server that the user should
 * not have to type, and leaves the app holding a device token.
 *
 * Two ways in, both ending in the same row. A username and password
 * buys an hour-long login credential that can do nothing but mint
 * tokens, so it is used once to mint the device token — with every
 * scope the app needs — and neither it nor the password is kept. A
 * token minted elsewhere is pasted in and simply asked what it may do
 * (`GET /v1/token`), which is also how the app learns its scopes.
 */
class LiseurSyncServerSetup(
    private val http: LiseurSyncHttp = LiseurSyncHttp(),
    private val deviceName: suspend () -> String = { "Liseur" },
) : ServerSetup {

    override suspend fun connect(
        rawUrl: String,
        credentials: RemoteCredentials,
        allowHttp: Boolean,
    ): SetupResult = withContext(Dispatchers.IO) {
        attempt(rawUrl, allowHttp) { baseUrl ->
            when (credentials) {
                is RemoteCredentials.Basic -> signIn(baseUrl, credentials)
                is RemoteCredentials.Bearer -> introspect(baseUrl, credentials)
                // liseur-sync has no other way in; an API key is Komga's.
                else -> throw RemoteHttpFailure(SyncFailure.Malformed)
            }
        }
    }

    /**
     * Signs in and mints the device token to keep.
     *
     * One mint, with every scope the app has: reading and syncing is
     * all it does. Nothing writes to the catalog — books reach the
     * server by being put in a folder it watches (ADR-0017) — so there
     * is no scope here that a server might refuse.
     */
    private suspend fun signIn(baseUrl: String, credentials: RemoteCredentials.Basic): ServerCapabilities {
        val login = http.post(
            LiseurSyncApi.url(baseUrl, LiseurSyncApi.LOGIN),
            credentials = null,
            json = JSONObject()
                .put("username", credentials.username)
                .put("password", credentials.password),
        )
        val auth = login.optString("auth_token").takeIf { it.isNotEmpty() }
            ?: throw RemoteHttpFailure(SyncFailure.Malformed)
        val session = RemoteCredentials.Bearer(auth)
        val name = deviceName()

        val minted = mint(baseUrl, session, name, LiseurSyncApi.SCOPES_FULL)
        val token = minted.optString("secret").takeIf { it.isNotEmpty() }
            ?: throw RemoteHttpFailure(SyncFailure.Malformed)

        // The token is asked what it holds rather than trusting the mint
        // answer: one code path reads scopes, device and account id, and
        // what the server recorded is what governs.
        return introspect(baseUrl, RemoteCredentials.Bearer(token))
            .copy(displayName = credentials.username)
    }

    /**
     * Checks a token minted elsewhere by asking it about itself.
     *
     * A token without `sync` is no use at all, and one without
     * `library-read` cannot show its own catalog: both are told apart
     * from a rejected credential so the message can say what is actually
     * wrong.
     */
    private suspend fun introspect(
        baseUrl: String,
        credentials: RemoteCredentials.Bearer,
    ): ServerCapabilities {
        val answer = http.get(
            LiseurSyncApi.url(baseUrl, LiseurSyncApi.TOKEN),
            credentials,
        )
        val scopes = scopesOf(answer.optJSONArray("scopes"))
        if (LiseurSyncApi.SCOPE_SYNC !in scopes) {
            throw InsufficientScopes()
        }
        return ServerCapabilities(
            baseUrl = baseUrl,
            canDownload = LiseurSyncApi.SCOPE_LIBRARY_READ in scopes,
            accountId = answer.optString("device_id").takeIf { it.isNotEmpty() },
            displayName = answer.optString("name").ifEmpty { "liseur-sync" },
            liseurToken = credentials.token,
            // The stable account discriminator, present once the server
            // ships the ADR-0016 follow-up; null until then, and the
            // device id carries the duty.
            liseurAccountId = answer.optString("account_id").takeIf { it.isNotEmpty() },
        )
    }

    private suspend fun mint(
        baseUrl: String,
        session: RemoteCredentials,
        name: String,
        scopes: List<String>,
    ): JSONObject = http.post(
        LiseurSyncApi.url(baseUrl, LiseurSyncApi.TOKENS),
        session,
        JSONObject()
            .put("name", name)
            .put("scopes", JSONArray().apply { scopes.forEach(::put) }),
    )

    private fun scopesOf(array: JSONArray?): Set<String> =
        (0 until (array?.length() ?: 0)).mapNotNull { array?.optString(it) }.toSet()

    /**
     * Runs [connect] against the typed address, dropping to plain HTTP
     * only if nothing answered at all and the reader has said they are
     * willing.
     *
     * HTTPS is always tried first, even when HTTP is allowed: a server
     * that is briefly unreachable must not be quietly downgraded, since
     * that is the request that carries the credentials.
     */
    private inline fun attempt(
        rawUrl: String,
        allowHttp: Boolean,
        connect: (String) -> ServerCapabilities,
    ): SetupResult {
        val baseUrl = RemoteUrl.normaliseBase(rawUrl)
            ?: return SetupResult.Failure(SetupFailure.WrongServer)

        val reason = try {
            return SetupResult.Success(connect(baseUrl))
        } catch (e: IOException) {
            failureFor(e)
        }

        val unreachable = reason is SetupFailure.Unreachable
        if (!unreachable || !baseUrl.startsWith("https://")) {
            return SetupResult.Failure(reason)
        }
        if (!allowHttp) {
            return SetupResult.Failure(SetupFailure.Unreachable(reason.message, httpMayWork = true))
        }

        return try {
            SetupResult.Success(connect(RemoteUrl.withHttp(baseUrl)))
        } catch (e: IOException) {
            // If plain HTTP reached the server and it had something to
            // say — a refused login, an insistence on HTTPS — that is
            // the useful complaint. Only when nothing answered there
            // either do we fall back to why HTTPS failed.
            val retry = failureFor(e)
            SetupResult.Failure(
                if (retry is SetupFailure.Unreachable) reason else retry,
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
    /**
     * The token works but may not sync: told apart from a refused
     * credential so the message can ask for scopes rather than a
     * password.
     */
    private class InsufficientScopes : IOException()

    private fun failureFor(error: IOException): SetupFailure {
        if (error is InsufficientScopes) return SetupFailure.InsufficientScopes
        if (error is LiseurSyncRejection) return SetupFailure.WrongServer
        val reason = (error as? RemoteHttpFailure)?.reason
            ?: return SetupFailure.Unreachable(error.message ?: "No answer", httpMayWork = false)
        return when (reason) {
            SyncFailure.Unauthorised, SyncFailure.Forbidden -> SetupFailure.BadCredentials
            SyncFailure.InsecureTransport -> SetupFailure.InsecureTransport
            SyncFailure.NotFound, SyncFailure.Malformed -> SetupFailure.WrongServer
            SyncFailure.Offline, SyncFailure.Timeout ->
                SetupFailure.Unreachable("No answer", httpMayWork = false)

            is SyncFailure.ServerError ->
                if (reason.code == TOO_MANY) {
                    SetupFailure.RateLimited
                } else {
                    SetupFailure.WrongServer
                }
        }
    }

    private companion object {
        const val TAG = "liseur-sync-setup"
        const val TOO_MANY = 429
    }
}
