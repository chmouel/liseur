package com.chmouel.liseur.data.grimmory

import android.util.Log
import com.chmouel.liseur.data.komga.KomgaHttp
import com.chmouel.liseur.data.komga.stringOrNull
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
 * Works out where a Grimmory server's Komga-compatible shim is and
 * whether the credentials given open it.
 *
 * The username and password are those of a Grimmory *OPDS user*. That is
 * not the login used in a browser, and it is the single most likely
 * thing for a reader to get wrong, because the browser login is right
 * there and answers to nothing here.
 */
class GrimmorySetupClient(private val http: KomgaHttp = KomgaHttp()) : ServerSetup {

    override suspend fun connect(
        rawUrl: String,
        credentials: RemoteCredentials,
        allowHttp: Boolean,
    ): SetupResult = withContext(Dispatchers.IO) {
        // Grimmory has no API key mechanism at all, so a key is not a
        // credential that is merely wrong here — it is the wrong kind of
        // server for it.
        if (credentials !is RemoteCredentials.Basic) {
            return@withContext SetupResult.Failure(SetupFailure.WrongServer)
        }

        val candidates = GrimmoryUrl.baseUrlCandidates(rawUrl)
        if (candidates.isEmpty()) {
            return@withContext SetupResult.Failure(SetupFailure.WrongServer)
        }

        when (val found = probeAll(candidates, credentials, allowHttp)) {
            is Probe.Ok -> SetupResult.Success(
                capabilities(found.baseUrl, found.me, credentials.username),
            )

            is Probe.Failed -> SetupResult.Failure(found.reason)
        }
    }

    /**
     * Tries each candidate base URL, and drops to plain HTTP only if
     * nothing answered at all and the user has said they are willing.
     *
     * HTTPS is always tried first, even when HTTP is allowed, so a
     * reachable server is never reached in the clear. The downgrade
     * itself is guarded a step up: [allowHttp] is only ever true for an
     * address the reader typed, or for a refresh of an account already
     * stored as `http://`. A saved https account refreshes with it off,
     * so it cannot be quietly downgraded on a bad afternoon.
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

        val retry = firstThatAnswers(candidates.map(GrimmoryUrl::withHttp), credentials)
        // Whatever plain HTTP had to say, as long as it said anything.
        // Only when nothing answered there either is the HTTPS
        // complaint the more useful one -- and reaching for it sooner
        // hides the answer that matters: a reader who typed a bare
        // address and got their password wrong is told the server did
        // not answer securely, which is true, useless, and not why they
        // are stuck.
        if (retry is Probe.Ok) return retry
        val silent = (retry as Probe.Failed).reason is SetupFailure.Unreachable
        return if (silent) overHttps else retry
    }

    /**
     * The first candidate that answers like Grimmory's shim.
     *
     * Rejected credentials are remembered and the walk carries on, which
     * is where this parts company with the Komga client it is otherwise
     * modelled on. A pasted address is walked up its parents, and a
     * deeper path can be something else entirely — a reverse proxy with
     * its own password on `example.com/private` — whose 401 says nothing
     * about the Grimmory one parent up. Stopping there blames the
     * password for an address that had not been tried yet. A rejection
     * is still the most useful complaint if nothing answers, so it is
     * what gets reported.
     *
     * An unreachable host does stop the walk: every candidate shares one
     * host, so the rest would time out identically, and six timeouts is
     * a minute of nothing.
     *
     * This is also why every probe goes through [GrimmoryUrl.api], which
     * adds the `/komga` prefix. The bare `{root}/api/v2/users/me` is
     * Grimmory's *own* API behind its own security chain: it answers 401
     * to a perfectly good OPDS user, and reading that as a bad password
     * blames the reader for what is really an address.
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
                    if (probe.reason is SetupFailure.Unreachable) return probe
                    // A rejection outranks "not the server I was looking
                    // for", being the more useful of the two to report
                    // if nothing ends up answering.
                    if (worst.reason != SetupFailure.BadCredentials) worst = probe
                }
            }
        }
        return worst
    }

    private fun probe(baseUrl: String, credentials: RemoteCredentials): Probe = try {
        val me = http.getObjectOrNull(GrimmoryUrl.api(baseUrl, ME), credentials)
        when {
            me == null -> Probe.Failed(SetupFailure.WrongServer)
            // Anything can answer 200 to anything; only the shim answers
            // this route with an account that has roles.
            me.optJSONArray("roles") == null -> Probe.Failed(SetupFailure.WrongServer)
            else -> Probe.Ok(baseUrl, me)
        }
    } catch (e: IOException) {
        val reason = e.grimmorySetupFailure()
            ?: SetupFailure.Unreachable(e.message ?: "No answer", httpMayWork = false)
        Probe.Failed(reason)
    } catch (_: JSONException) {
        Probe.Failed(SetupFailure.WrongServer)
    }

    private fun capabilities(
        baseUrl: String,
        me: JSONObject,
        username: String,
    ): ServerCapabilities = ServerCapabilities(
        baseUrl = baseUrl,
        // Unconditionally true, and deliberately not read off `roles`.
        //
        // The shim hardcodes every account's roles to `["USER"]` — it
        // has no `FILE_DOWNLOAD` to report and never will. Gating on one
        // would refuse every download on a server that in fact serves
        // them to any authenticated OPDS user the library is shared
        // with, which is exactly who has got this far.
        canDownload = true,
        accountId = me.stringOrNull("id"),
        // The typed username, not the DTO's `email`: Grimmory
        // manufactures that as `username@grimmory.local`, an address
        // that exists nowhere and would only puzzle the reader.
        displayName = username.takeIf(String::isNotBlank) ?: "Grimmory",
    ).also {
        Log.i(TAG, "Connected to Grimmory's Komga API")
    }

    private sealed interface Probe {
        data class Ok(val baseUrl: String, val me: JSONObject) : Probe
        data class Failed(val reason: SetupFailure) : Probe
    }

    private companion object {
        const val TAG = "GrimmorySetup"
        const val ME = "/api/v2/users/me"
    }
}

/**
 * The setup-time meaning of an answer that arrived and said no.
 *
 * The 403 here is not only a refused password. Grimmory's Komga shim is
 * off by default, and while it is off every request to it is refused
 * with a 403 whose body names no cause. There is no way to tell that
 * apart from a bad password on the wire, so both arrive as
 * [SetupFailure.BadCredentials] and the message shown to the reader has
 * to offer both explanations.
 */
private fun IOException.grimmorySetupFailure(): SetupFailure? =
    (this as? RemoteHttpFailure)?.let {
        when (it.reason) {
            SyncFailure.Unauthorised, SyncFailure.Forbidden -> SetupFailure.BadCredentials
            SyncFailure.Offline, SyncFailure.Timeout ->
                SetupFailure.Unreachable("No answer", httpMayWork = false)

            else -> SetupFailure.WrongServer
        }
    }
