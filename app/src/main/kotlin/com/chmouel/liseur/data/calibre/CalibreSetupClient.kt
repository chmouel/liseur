package com.chmouel.liseur.data.calibre

import android.util.Log
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a calibre-web account turned out to be able to do. */
data class CalibreCapabilities(
    val baseUrl: String,
    val canDownload: Boolean,
    val userId: Int?,
    val koboToken: String?,
)

/** Why connecting to a server did not work, in terms a user can act on. */
sealed interface SetupFailure {
    /** The URL answered, but the credentials were rejected. */
    data object BadCredentials : SetupFailure

    /** Something answered, but it was not a calibre-web catalog. */
    data object NotCalibreWeb : SetupFailure

    /** Nothing answered over HTTPS; the user may want to allow plain HTTP. */
    data class Unreachable(val message: String, val httpMayWork: Boolean) : SetupFailure
}

sealed interface SetupResult {
    data class Success(val capabilities: CalibreCapabilities) : SetupResult
    data class Failure(val reason: SetupFailure) : SetupResult
}

/**
 * Works out everything about a calibre-web server that the user should
 * not have to type: whether the catalog is there, whether the account
 * may download books, and whether reading-position sync can be switched
 * on for it.
 */
class CalibreSetupClient(private val http: CalibreHttp = CalibreHttp()) {

    suspend fun connect(
        rawUrl: String,
        username: String,
        password: String,
        allowHttp: Boolean = false,
    ): SetupResult = withContext(Dispatchers.IO) {
        val requested = CalibreUrl.normaliseBaseUrl(rawUrl)
            ?: return@withContext SetupResult.Failure(SetupFailure.NotCalibreWeb)
        val credentials = CalibreCredentials(username, password)

        val (baseUrl, probe) = probeCatalog(requested, credentials, allowHttp)

        val feed = when (probe) {
            is Probe.Ok -> probe.body
            is Probe.Failed -> {
                val offerHttp = !allowHttp && requested.startsWith("https://") &&
                    probe.reason is SetupFailure.Unreachable
                return@withContext SetupResult.Failure(
                    if (offerHttp) {
                        SetupFailure.Unreachable(
                            (probe.reason as SetupFailure.Unreachable).message,
                            httpMayWork = true,
                        )
                    } else {
                        probe.reason
                    },
                )
            }
        }

        val canDownload = probeDownloadRights(baseUrl, credentials, feed)
        val kobo = provisionKoboToken(baseUrl, username, password)

        SetupResult.Success(
            CalibreCapabilities(
                baseUrl = baseUrl,
                canDownload = canDownload,
                userId = kobo?.first,
                koboToken = kobo?.second,
            ),
        )
    }

    /**
     * Looks for the catalog, and drops to plain HTTP if that is the only
     * way to reach it and the user has said they are willing.
     *
     * HTTPS is always tried first, even when HTTP is allowed: a saved
     * account refreshes itself with [allowHttp] on, and an https server
     * must not be quietly downgraded just because it was briefly
     * unreachable. Returns the URL that answered along with the result.
     */
    private fun probeCatalog(
        requested: String,
        credentials: CalibreCredentials,
        allowHttp: Boolean,
    ): Pair<String, Probe> {
        val first = fetchCatalog(requested, credentials)
        val worthRetrying = first is Probe.Failed &&
            first.reason is SetupFailure.Unreachable &&
            requested.startsWith("https://")
        if (!allowHttp || !worthRetrying) return requested to first

        val overHttp = CalibreUrl.withHttp(requested)
        return when (val retry = fetchCatalog(overHttp, credentials)) {
            is Probe.Ok -> overHttp to retry
            // Report why HTTPS failed; that is the more useful complaint.
            is Probe.Failed -> requested to first
        }
    }

    private sealed interface Probe {
        data class Ok(val body: String) : Probe
        data class Failed(val reason: SetupFailure) : Probe
    }

    private fun fetchCatalog(baseUrl: String, credentials: CalibreCredentials): Probe = try {
        http.get(CalibreUrl.resolve(baseUrl, "/opds"), credentials).use { response ->
            when {
                response.code == 401 -> Probe.Failed(SetupFailure.BadCredentials)
                !response.isSuccessful -> Probe.Failed(SetupFailure.NotCalibreWeb)
                else -> {
                    val body = response.body?.string().orEmpty()
                    if (CalibreParsing.isOpdsFeed(body)) {
                        Probe.Ok(body)
                    } else {
                        Probe.Failed(SetupFailure.NotCalibreWeb)
                    }
                }
            }
        }
    } catch (e: IOException) {
        Probe.Failed(SetupFailure.Unreachable(e.message ?: "No answer", httpMayWork = false))
    }

    /**
     * calibre-web answers 401 on a download when the account lacks the
     * "Allow Downloads" permission, even though browsing works. Finding
     * that out now means the app can say so plainly instead of failing
     * later, in the middle of a download.
     */
    private fun probeDownloadRights(
        baseUrl: String,
        credentials: CalibreCredentials,
        catalogFeed: String,
    ): Boolean {
        val href = firstBookLink(baseUrl, credentials, catalogFeed) ?: return true
        return try {
            http.client.newCall(
                http.request(href, credentials).header("Range", "bytes=0-0").build(),
            ).execute().use { it.code != 401 && it.code != 403 }
        } catch (e: IOException) {
            Log.w(TAG, "Could not probe download rights", e)
            true
        }
    }

    private fun firstBookLink(
        baseUrl: String,
        credentials: CalibreCredentials,
        catalogFeed: String,
    ): String? {
        CalibreParsing.firstAcquisitionHref(catalogFeed)
            ?.let { return CalibreUrl.resolve(baseUrl, it) }

        // The root feed is navigation only, so look inside a book feed.
        return try {
            http.get(CalibreUrl.resolve(baseUrl, "/opds/new"), credentials).use { response ->
                if (!response.isSuccessful) return null
                CalibreParsing.firstAcquisitionHref(response.body?.string().orEmpty())
                    ?.let { CalibreUrl.resolve(baseUrl, it) }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Could not read a book feed", e)
            null
        }
    }

    /**
     * Turns on reading-position sync without the user hunting through
     * calibre-web's settings: log in the way a browser does, read the
     * user id off the profile page, then ask for the Kobo token.
     *
     * The token route is idempotent — it hands back the existing token
     * and never rotates it — so this cannot disturb another device.
     * Anything unexpected simply leaves sync switched off.
     */
    private fun provisionKoboToken(
        baseUrl: String,
        username: String,
        password: String,
    ): Pair<Int?, String>? = try {
        val session = CalibreWebSession.logIn(baseUrl, username, password) ?: return null

        val userId = session.http.get(CalibreUrl.resolve(baseUrl, "/me"), null)
            .use { CalibreParsing.userId(it.body?.string().orEmpty()) }
            ?: return null

        val token = session.http
            .get(CalibreUrl.resolve(baseUrl, "/kobo_auth/generate_auth_token/$userId"), null)
            .use { CalibreParsing.koboToken(it.body?.string().orEmpty()) }
            ?: return null

        userId to token
    } catch (e: IOException) {
        Log.i(TAG, "Position sync is not available on this server", e)
        null
    }

    private companion object {
        const val TAG = "CalibreSetup"
    }
}
