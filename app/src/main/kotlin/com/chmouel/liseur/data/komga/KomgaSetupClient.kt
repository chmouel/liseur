package com.chmouel.liseur.data.komga

import android.util.Log
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.ServerCapabilities
import com.chmouel.liseur.data.remote.ServerSetup
import com.chmouel.liseur.data.remote.SetupFailure
import com.chmouel.liseur.data.remote.SetupResult
import com.chmouel.liseur.data.remote.SyncFailure
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/**
 * Works out everything about a Komga server that the user should not
 * have to type: where the API actually is, who the key belongs to, and
 * whether that account is allowed to download files.
 *
 * Komga is only ever signed into with an API key. It accepts a password
 * too, but a key can be revoked from the server without changing the
 * password, and the reader can see in Komga's own settings that Liseur
 * is the thing holding it.
 */
class KomgaSetupClient(private val http: KomgaHttp = KomgaHttp()) : ServerSetup {

    override suspend fun connect(
        rawUrl: String,
        credentials: RemoteCredentials,
        allowHttp: Boolean,
    ): SetupResult = withContext(Dispatchers.IO) {
        if (credentials !is RemoteCredentials.ApiKey) {
            return@withContext SetupResult.Failure(SetupFailure.WrongServer)
        }

        val candidates = KomgaUrl.baseUrlCandidates(rawUrl)
        if (candidates.isEmpty()) {
            return@withContext SetupResult.Failure(SetupFailure.WrongServer)
        }

        when (val found = probeAll(candidates, credentials, allowHttp)) {
            is Probe.Ok -> SetupResult.Success(capabilities(found.baseUrl, found.me))
            is Probe.Failed -> SetupResult.Failure(found.reason)
        }
    }

    /**
     * Tries each candidate base URL, and drops to plain HTTP only if
     * nothing answered at all and the user has said they are willing.
     *
     * HTTPS is always tried first, even when HTTP is allowed: a saved
     * account refreshes itself with [allowHttp] on, and an https server
     * must not be quietly downgraded just because it was briefly
     * unreachable.
     */
    private fun probeAll(
        candidates: List<String>,
        credentials: RemoteCredentials,
        allowHttp: Boolean,
    ): Probe {
        val overHttps = firstThatAnswers(candidates, credentials)
        if (overHttps is Probe.Ok) return overHttps

        val unreachable = (overHttps as Probe.Failed).reason is SetupFailure.Unreachable
        val wasHttps = candidates.first().startsWith("https://")
        if (!unreachable || !wasHttps) return overHttps

        if (!allowHttp) {
            return Probe.Failed(
                SetupFailure.Unreachable(
                    (overHttps.reason as SetupFailure.Unreachable).message,
                    httpMayWork = true,
                ),
            )
        }

        val retry = firstThatAnswers(candidates.map(KomgaUrl::withHttp), credentials)
        // Report why HTTPS failed; that is the more useful complaint.
        return if (retry is Probe.Ok) retry else overHttps
    }

    /**
     * The first candidate that answers like Komga.
     *
     * A rejected key is reported the moment it is seen rather than
     * carried on from: every candidate shares the same host, so the next
     * one down would reject it just as firmly, and "wrong address" is
     * the wrong thing to tell someone whose key is simply wrong.
     */
    private fun firstThatAnswers(
        candidates: List<String>,
        credentials: RemoteCredentials,
    ): Probe {
        var worst: Probe.Failed = Probe.Failed(SetupFailure.WrongServer)
        for (baseUrl in candidates) {
            when (val probe = probe(baseUrl, credentials)) {
                is Probe.Ok -> return probe
                is Probe.Failed -> {
                    if (probe.reason == SetupFailure.BadCredentials) return probe
                    if (probe.reason is SetupFailure.Unreachable) return probe
                    worst = probe
                }
            }
        }
        return worst
    }

    private fun probe(baseUrl: String, credentials: RemoteCredentials): Probe = try {
        val me = http.getObjectOrNull(KomgaUrl.api(baseUrl, ME), credentials)
        when {
            me == null -> Probe.Failed(SetupFailure.WrongServer)
            // Anything can answer 200 to anything; only Komga answers
            // this route with an account that has roles.
            me.optJSONArray("roles") == null -> Probe.Failed(SetupFailure.WrongServer)
            else -> Probe.Ok(baseUrl, me)
        }
    } catch (e: IOException) {
        val reason = e.komgaSetupFailure()
            ?: SetupFailure.Unreachable(e.message ?: "No answer", httpMayWork = false)
        Probe.Failed(reason)
    } catch (_: JSONException) {
        Probe.Failed(SetupFailure.WrongServer)
    }

    private fun capabilities(baseUrl: String, me: JSONObject): ServerCapabilities {
        val roles = me.optJSONArray("roles")
        val granted = buildSet {
            for (index in 0 until (roles?.length() ?: 0)) {
                roles?.optString(index)?.let(::add)
            }
        }
        val email = me.stringOrNull("email")
        return ServerCapabilities(
            baseUrl = baseUrl,
            // Komga refuses a file download outright without this role,
            // so finding out now means the app can say so plainly rather
            // than failing later, in the middle of a download.
            canDownload = FILE_DOWNLOAD in granted,
            accountId = me.stringOrNull("id"),
            displayName = email ?: "Komga",
        ).also {
            Log.i(TAG, "Connected to Komga; downloads ${if (it.canDownload) "on" else "off"}")
        }
    }

    private sealed interface Probe {
        data class Ok(val baseUrl: String, val me: JSONObject) : Probe
        data class Failed(val reason: SetupFailure) : Probe
    }

    private companion object {
        const val TAG = "KomgaSetup"
        const val ME = "/api/v2/users/me"
        const val FILE_DOWNLOAD = "FILE_DOWNLOAD"
    }
}

/**
 * The setup-time meaning of an answer that arrived and said no.
 *
 * A refused key has to be told apart from a wrong address, and only the
 * HTTP status says which it was. Null means it was not an answer at all.
 */
private fun IOException.komgaSetupFailure(): SetupFailure? =
    (this as? RemoteHttpFailure)?.let {
        when (it.reason) {
            SyncFailure.Unauthorised, SyncFailure.Forbidden -> SetupFailure.BadCredentials
            SyncFailure.Offline, SyncFailure.Timeout ->
                SetupFailure.Unreachable("No answer", httpMayWork = false)
            else -> SetupFailure.WrongServer
        }
    }
