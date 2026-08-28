package com.chmouel.liseur.data.opds

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
import org.xml.sax.SAXException

/**
 * Checks that an address is an OPDS catalog, and reports what it is.
 *
 * There is no shim to recognise and no capability route to ask, which
 * is the whole appeal of a standard: the test is that the address
 * answers with a feed. Anything can answer 200 with HTML, so parsing is
 * the check — a login page is not a catalog however cheerfully it
 * arrives.
 *
 * What comes back as [ServerCapabilities.catalogUrl] is the URL that
 * *answered*, not the one that was typed. A root that redirects, or
 * that the reader spelled with a trailing slash, is stored as it
 * resolved, so the walk and the origin rule both work from the address
 * the server actually uses.
 */
class OpdsSetupClient(private val http: OpdsHttp = OpdsHttp()) : ServerSetup {

    override suspend fun connect(
        rawUrl: String,
        credentials: RemoteCredentials,
        allowHttp: Boolean,
    ): SetupResult = withContext(Dispatchers.IO) {
        val base = RemoteUrl.normaliseBase(rawUrl)
            ?: return@withContext SetupResult.Failure(SetupFailure.WrongServer)

        when (val probed = probe(base, credentials)) {
            is Probe.Ok -> SetupResult.Success(probed.capabilities)
            is Probe.Failed -> {
                // HTTPS first even when plain HTTP is allowed, and only
                // when nothing answered at all: a catalog that is simply
                // unreachable for a moment must not be quietly retried
                // in the clear with the reader's password on it.
                val worthRetrying = probed.reason is SetupFailure.Unreachable &&
                    base.startsWith("https://", ignoreCase = true)
                when {
                    !worthRetrying -> SetupResult.Failure(probed.reason)
                    !allowHttp -> SetupResult.Failure(
                        SetupFailure.Unreachable(
                            (probed.reason as SetupFailure.Unreachable).message,
                            httpMayWork = true,
                        ),
                    )
                    else -> when (val retried = probe(RemoteUrl.withHttp(base), credentials)) {
                        is Probe.Ok -> SetupResult.Success(retried.capabilities)
                        // Report why HTTPS failed; that is the more
                        // useful complaint.
                        is Probe.Failed -> SetupResult.Failure(probed.reason)
                    }
                }
            }
        }
    }

    private fun probe(base: String, credentials: RemoteCredentials): Probe {
        val scope = OpdsScope.of(base) ?: return Probe.Failed(SetupFailure.WrongServer)
        return try {
            val fetched = http.get(scope.root, scope, credentials)
            val page = fetched.response.use { response ->
                when {
                    response.code == 401 || response.code == 403 ->
                        return Probe.Failed(SetupFailure.BadCredentials)
                    !response.isSuccessful ->
                        return Probe.Failed(SetupFailure.WrongServer)
                    else -> OpdsParser.parse(response.body.string())
                }
            }
            Probe.Ok(
                ServerCapabilities(
                    // Where the walk landed, which is where the catalog
                    // is. A root that redirected once will redirect on
                    // every refresh otherwise, and the origin rule would
                    // be reasoning about an address nothing uses.
                    baseUrl = fetched.url.toString().trimEnd('/'),
                    // A root that only lists shelves is downloadable:
                    // the books are a walk away, and saying otherwise
                    // would hide the download button on every one of
                    // them. Whether a particular book has a file Liseur
                    // can open is answered per entry, in the parser.
                    canDownload = true,
                    accountId = null,
                    // What the catalog calls itself, falling back to
                    // the host: a Custom account has no user record to
                    // read a name off, and "opds.example.org" is at
                    // least true.
                    displayName = page.title?.takeIf { it.isNotBlank() } ?: fetched.url.host,
                ),
            ).also { Log.i(TAG, "Connected to an OPDS catalog at ${fetched.url.host}") }
        } catch (e: SAXException) {
            // Something answered, and it was not a feed.
            Log.i(TAG, "That address did not answer with an OPDS feed", e)
            Probe.Failed(SetupFailure.WrongServer)
        } catch (e: IOException) {
            Probe.Failed(e.opdsSetupFailure())
        }
    }

    private sealed interface Probe {
        data class Ok(val capabilities: ServerCapabilities) : Probe
        data class Failed(val reason: SetupFailure) : Probe
    }

    private companion object {
        const val TAG = "OpdsSetup"
    }
}

/** What a failure during the probe means to someone filling in a form. */
private fun IOException.opdsSetupFailure(): SetupFailure = when {
    this is RemoteHttpFailure -> when (reason) {
        SyncFailure.Unauthorised, SyncFailure.Forbidden -> SetupFailure.BadCredentials
        SyncFailure.InsecureTransport -> SetupFailure.InsecureTransport
        SyncFailure.Offline, SyncFailure.Timeout ->
            SetupFailure.Unreachable("No answer", httpMayWork = false)
        else -> SetupFailure.WrongServer
    }
    else -> SetupFailure.Unreachable(message ?: "No answer", httpMayWork = false)
}
