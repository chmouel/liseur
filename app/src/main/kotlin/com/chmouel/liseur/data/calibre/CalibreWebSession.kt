package com.chmouel.liseur.data.calibre

import android.util.Log
import com.chmouel.liseur.data.remote.RemoteHttp
import java.io.IOException
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * A logged-in calibre-web browser session.
 *
 * Some of what the app needs is not in OPDS or the Kobo API and only exists
 * in calibre-web's web UI — generating a sync token, deleting a book. Those
 * routes want a session cookie and a CSRF token, so this logs in the way a
 * browser would and hands back both.
 */
class CalibreWebSession private constructor(
    val http: RemoteHttp,
    private val baseUrl: String,
) {

    /**
     * A CSRF token minted for the current session. calibre-web puts one in
     * every rendered page, so any page will do.
     */
    fun csrfToken(): String? = runCatching {
        http.get(CalibreUrl.resolve(baseUrl, "/"), null)
            .use { CalibreParsing.csrfToken(it.body?.string().orEmpty()) }
    }.getOrNull()

    companion object {
        private const val TAG = "CalibreWebSession"

        /** Logs in, or returns null when the server will not have us. */
        fun logIn(baseUrl: String, username: String, password: String): CalibreWebSession? = try {
            val http = RemoteHttp(clientFor(baseUrl))
            val loginUrl = CalibreUrl.resolve(baseUrl, "/login")
            val csrf = http.get(loginUrl, null)
                .use { CalibreParsing.csrfToken(it.body?.string().orEmpty()) }
            val form = FormBody.Builder()
                .add("username", username)
                .add("password", password)
                .add("submit", "")
                .add("next", "/")
                .apply { csrf?.let { add("csrf_token", it) } }
                .build()

            val accepted = http.client.newCall(http.request(loginUrl, null).post(form).build())
                .execute()
                .use { it.isSuccessful && !CalibreParsing.isLoginPage(it.body?.string().orEmpty()) }

            if (accepted) {
                CalibreWebSession(http, baseUrl)
            } else {
                // calibre-web answers a refused login by rendering the login
                // page again with a 200, so the status code proves nothing on
                // its own and every later request would look fine too.
                Log.i(TAG, "The server did not accept those credentials")
                null
            }
        } catch (e: IOException) {
            Log.i(TAG, "Could not open a session on this server", e)
            null
        }

        private fun clientFor(baseUrl: String): OkHttpClient {
            val host = baseUrl.toHttpUrlOrNull()?.host
            return OkHttpClient.Builder()
                .cookieJar(SessionCookieJar())
                .apply { host?.let { addNetworkInterceptor(SameHostRedirects(it)) } }
                .build()
        }
    }
}

/**
 * Refuses to follow a redirect that leaves the server we logged in to.
 *
 * calibre-web redirects plenty within itself, but nothing legitimate sends
 * a browser carrying the session cookie somewhere else. Dropping the header
 * is enough: OkHttp then hands the redirect back untouched, so the caller
 * sees an unsuccessful response instead of a stranger's reply.
 */
internal class SameHostRedirects(private val host: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val location = response.header("Location") ?: return response
        val target = response.request.url.resolve(location)
        if (target != null && target.host.equals(host, ignoreCase = true)) return response
        return response.newBuilder().removeHeader("Location").build()
    }
}

/**
 * Holds the login cookie for the few requests a session is needed for.
 *
 * A cookie is only handed back to a request it was actually meant for, so
 * a server that scopes its session to a path — calibre-web behind a reverse
 * proxy often does — is respected rather than second-guessed, and a cookie
 * marked secure never travels in the clear.
 */
internal class SessionCookieJar : CookieJar {
    private val stored = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { fresh ->
            stored.removeAll {
                it.name == fresh.name && it.domain == fresh.domain && it.path == fresh.path
            }
            stored += fresh
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        stored.removeAll { it.expiresAt < now }
        return stored.filter { it.matches(url) }
    }
}
