package com.chmouel.liseur.data.calibre

import android.util.Log
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

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
        val baseUrl = CalibreUrl.normaliseBaseUrl(rawUrl)
            ?: return@withContext SetupResult.Failure(SetupFailure.NotCalibreWeb)
        val credentials = CalibreCredentials(username, password)

        val feed = when (val probe = fetchCatalog(baseUrl, credentials)) {
            is Probe.Ok -> probe.body
            is Probe.Failed -> {
                val overHttp = !allowHttp && baseUrl.startsWith("https://") &&
                    probe.reason is SetupFailure.Unreachable
                return@withContext SetupResult.Failure(
                    if (overHttp) {
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
        val session = OkHttpClient.Builder()
            .cookieJar(SessionCookieJar())
            .build()
        val withSession = CalibreHttp(session)

        val loginUrl = CalibreUrl.resolve(baseUrl, "/login")
        val csrf = withSession.get(loginUrl, null)
            .use { CalibreParsing.csrfToken(it.body?.string().orEmpty()) }

        val form = FormBody.Builder()
            .add("username", username)
            .add("password", password)
            .add("submit", "")
            .add("next", "/")
            .apply { csrf?.let { add("csrf_token", it) } }
            .build()
        withSession.client.newCall(
            withSession.request(loginUrl, null).post(form).build(),
        ).execute().close()

        val userId = withSession.get(CalibreUrl.resolve(baseUrl, "/me"), null)
            .use { CalibreParsing.userId(it.body?.string().orEmpty()) }
            ?: return null

        val token = withSession
            .get(CalibreUrl.resolve(baseUrl, "/kobo_auth/generate_auth_token/$userId"), null)
            .use { CalibreParsing.koboToken(it.body?.string().orEmpty()) }
            ?: return null

        userId to token
    } catch (e: IOException) {
        Log.i(TAG, "Position sync is not available on this server", e)
        null
    }

    /** Holds the login session for the few requests it takes to read the token. */
    private class SessionCookieJar : CookieJar {
        private val cookies = mutableMapOf<String, Cookie>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { this.cookies[it.name] = it }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.values.toList()
    }

    private companion object {
        const val TAG = "CalibreSetup"
    }
}
