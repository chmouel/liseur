package com.chmouel.liseur.data.remote

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Shared OkHttp client.
 *
 * Redirects are followed because calibre-web 308-redirects download
 * links that are missing their trailing slash, but the credentials are
 * re-attached per request rather than by an interceptor so they are
 * never sent to a host we did not mean to talk to.
 */
class RemoteHttp(val client: OkHttpClient = default()) {

    fun request(url: String, credentials: RemoteCredentials?): Request.Builder =
        Request.Builder().url(url).apply { credentials?.signInto(this) }

    fun get(url: String, credentials: RemoteCredentials?): Response =
        client.newCall(request(url, credentials).build()).execute()

    companion object {
        fun default(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
