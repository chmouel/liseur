package com.chmouel.liseur.data.calibre

import java.util.concurrent.TimeUnit
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Username and password for HTTP Basic auth against calibre-web. */
data class CalibreCredentials(val username: String, val password: String) {
    val basic: String get() = Credentials.basic(username, password)
}

/**
 * Shared OkHttp client. Redirects are followed because calibre-web
 * 308-redirects download links that are missing their trailing slash,
 * but the Basic auth header is re-attached per request rather than by an
 * interceptor so it is never sent to a host we did not mean to talk to.
 */
class CalibreHttp(val client: OkHttpClient = default()) {

    fun request(url: String, credentials: CalibreCredentials?): Request.Builder =
        Request.Builder().url(url).apply {
            credentials?.let { header("Authorization", it.basic) }
        }

    fun get(url: String, credentials: CalibreCredentials?): Response =
        client.newCall(request(url, credentials).build()).execute()

    companion object {
        fun default(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
