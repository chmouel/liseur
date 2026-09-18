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
        // Slash and all: a catalog address is fetched rather than built
        // onto, so `…/search.opds/` and `…/search.opds` are two
        // resources and only the reader knows which one they were given.
        val base = RemoteUrl.normaliseBase(rawUrl, keepQuery = true, keepTrailingSlash = true)
            ?: return@withContext SetupResult.Failure(SetupFailure.WrongServer)

        when (val probed = probeEitherSpelling(base, credentials)) {
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
                            probed.reason.message,
                            httpMayWork = true,
                        ),
                    )
                    else -> when (
                        val retried =
                            probeEitherSpelling(RemoteUrl.withHttp(base), credentials)
                    ) {
                        is Probe.Ok -> SetupResult.Success(retried.capabilities)
                        // Report why HTTPS failed; that is the more
                        // useful complaint.
                        is Probe.Failed -> SetupResult.Failure(probed.reason)
                    }
                }
            }
        }
    }

    /**
     * The address as typed, and then the same address spelled with the
     * other trailing slash.
     *
     * Catalogs publish both forms and answer to one. Project Gutenberg
     * links `/ebooks.opds` in its own pages and answers 403 to it,
     * serving only `/ebooks.opds/`; others do the reverse. A reader
     * copying an address they were given cannot be expected to know
     * which, and a second guess costs one request on a connection that
     * has already failed.
     *
     * Only when something answered and said no. An address nothing
     * answered at all is not a spelling mistake, and retrying it would
     * double the wait before the offer to try plain HTTP — which owns
     * that case. A refusal of the transport is not about the path
     * either.
     *
     * The first failure is what gets reported when both spellings fail:
     * it is the one about the address the reader actually typed.
     */
    private fun probeEitherSpelling(base: String, credentials: RemoteCredentials): Probe {
        val first = probe(base, credentials)
        if (first !is Probe.Failed || !first.worthAnotherSpelling()) return first
        val flipped = RemoteUrl.flipTrailingSlash(base) ?: return first
        return when (val second = probe(flipped, credentials)) {
            is Probe.Ok -> second
            is Probe.Failed -> first
        }
    }

    private fun probe(base: String, credentials: RemoteCredentials): Probe {
        val scope = OpdsScope.of(base) ?: return Probe.Failed(SetupFailure.WrongServer)
        return try {
            val fetched = http.get(scope.root, scope, credentials)
            // Storing where the redirect landed is right for a path
            // correction and wrong across origins: the stored address
            // becomes the credential origin on every later refresh, so a
            // catalog that answers setup with a redirect elsewhere would
            // be choosing where the reader's password goes from then on.
            // The first request was safe — the scope refused to sign a
            // stranger — but the second would not be.
            //
            // Nothing is lost by refusing. The reader is one address away
            // from the same connection, typed themselves, and being told
            // so is better than being moved silently.
            if (credentials !is RemoteCredentials.Anonymous && !scope.signs(fetched.url)) {
                fetched.response.close()
                return Probe.Failed(SetupFailure.WrongServer)
            }
            val page = fetched.response.use { response ->
                when {
                    response.code == 401 || response.code == 403 ->
                        return Probe.Failed(refusal(credentials))
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
                    // be reasoning about an address nothing uses. Kept
                    // exactly as it answered, trailing slash included,
                    // or every later refresh would ask for the spelling
                    // that did not work.
                    baseUrl = fetched.url.toString(),
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
                    hasBooks = page.books.isNotEmpty() || page.navigation.isNotEmpty(),
                ),
            ).also { Log.i(TAG, "Connected to an OPDS catalog at ${fetched.url.host}") }
        } catch (e: SAXException) {
            // Something answered, and it was not a feed.
            Log.i(TAG, "That address did not answer with an OPDS feed", e)
            Probe.Failed(SetupFailure.WrongServer)
        } catch (e: IOException) {
            Probe.Failed(e.opdsSetupFailure(credentials))
        }
    }

    private sealed interface Probe {
        data class Ok(val capabilities: ServerCapabilities) : Probe
        data class Failed(val reason: SetupFailure) : Probe {
            fun worthAnotherSpelling(): Boolean = when (reason) {
                SetupFailure.WrongServer,
                SetupFailure.BadCredentials,
                SetupFailure.SignInRequired,
                -> true
                else -> false
            }
        }
    }

    private companion object {
        const val TAG = "OpdsSetup"
    }
}

/**
 * What a refused request means, which depends on whether anything was
 * offered for it to refuse.
 *
 * An open catalog is connected to with both fields empty, so a 401 or
 * 403 there is not a password being wrong. It may be a catalog that
 * wants a sign-in, or an address that is not the catalog — the message
 * for [SetupFailure.SignInRequired] says both.
 */
private fun refusal(credentials: RemoteCredentials): SetupFailure =
    if (credentials is RemoteCredentials.Anonymous) {
        SetupFailure.SignInRequired
    } else {
        SetupFailure.BadCredentials
    }

/** What a failure during the probe means to someone filling in a form. */
private fun IOException.opdsSetupFailure(credentials: RemoteCredentials): SetupFailure = when {
    this is RemoteHttpFailure -> when (reason) {
        SyncFailure.Unauthorised, SyncFailure.Forbidden -> refusal(credentials)
        SyncFailure.InsecureTransport -> SetupFailure.InsecureTransport
        SyncFailure.Offline, SyncFailure.Timeout ->
            SetupFailure.Unreachable("No answer", httpMayWork = false)
        else -> SetupFailure.WrongServer
    }
    else -> SetupFailure.Unreachable(message ?: "No answer", httpMayWork = false)
}
