package com.chmouel.liseur.data.calibre

import android.util.Log
import java.io.IOException
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/**
 * A logged-in calibre-web browser session.
 *
 * Some of what the app needs is not in OPDS or the Kobo API and only exists
 * in calibre-web's web UI — generating a sync token, deleting a book. Those
 * routes want a session cookie and a CSRF token, so this logs in the way a
 * browser would and hands back both.
 */
class CalibreWebSession private constructor(
    val http: CalibreHttp,
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
            val client = OkHttpClient.Builder().cookieJar(SessionCookieJar()).build()
            val http = CalibreHttp(client)
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
            http.client.newCall(http.request(loginUrl, null).post(form).build())
                .execute()
                .close()
            CalibreWebSession(http, baseUrl)
        } catch (e: IOException) {
            Log.i(TAG, "Could not open a session on this server", e)
            null
        }
    }
}

/** Holds the login cookie for the few requests a session is needed for. */
internal class SessionCookieJar : CookieJar {
    private val cookies = mutableMapOf<String, Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { this.cookies[it.name] = it }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.values.toList()
}
