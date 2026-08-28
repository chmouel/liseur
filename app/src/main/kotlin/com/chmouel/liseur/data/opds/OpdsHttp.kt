package com.chmouel.liseur.data.opds

import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.SyncFailure
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** A response, and the URL it finally came from. */
class OpdsFetch(val url: HttpUrl, val response: Response)

/**
 * Fetching from a catalog nobody has vouched for.
 *
 * Two things it does that the shared [com.chmouel.liseur.data.remote.RemoteHttp]
 * does not, both for the same reason — the address may have been chosen
 * by the feed rather than by the reader:
 *
 * 1. **It signs by [OpdsScope], not by request.** A link out of the
 *    catalog's origin is fetched unsigned.
 * 2. **It walks redirects by hand.** Checking where a redirect landed
 *    is too late: OkHttp re-sends the `Authorization` header on a
 *    same-host hop and has already delivered the password by the time
 *    anyone can object. Deciding per hop is the only place the question
 *    can be asked before the answer matters.
 *
 * An https catalog is never followed down to http either, whatever the
 * server says: a redirect is not the reader agreeing to send their
 * password in the clear.
 */
class OpdsHttp(private val client: OkHttpClient = default()) {

    fun get(
        start: HttpUrl,
        scope: OpdsScope,
        credentials: RemoteCredentials,
        range: String? = null,
    ): OpdsFetch {
        var url = start
        var hops = 0
        // The transport and stranger-address rules belong to the
        // connection, not to this call: a link that starts a fresh
        // request would otherwise be its own root and answer to nothing.
        if (!scope.mayFetch(url)) throw RemoteHttpFailure(SyncFailure.InsecureTransport)
        while (true) {
            val response = client.newCall(build(url, scope, credentials, range)).execute()
            val location = if (response.isRedirect) response.header("Location") else null
            if (location == null) return OpdsFetch(url, response)

            response.close()
            if (++hops > MAX_HOPS) throw RemoteHttpFailure(SyncFailure.Malformed)
            val next = url.resolve(location) ?: throw RemoteHttpFailure(SyncFailure.Malformed)
            // A secure catalog stays secure. Nothing the server can say
            // in a header is the reader agreeing to plain HTTP.
            if (!scope.mayFetch(next)) {
                throw RemoteHttpFailure(SyncFailure.InsecureTransport)
            }
            url = next
        }
    }

    /** The request as it would be sent, for the download worker to run. */
    fun request(
        url: HttpUrl,
        scope: OpdsScope,
        credentials: RemoteCredentials,
    ): Request.Builder = Request.Builder().url(url).apply {
        if (scope.signs(url)) credentials.signInto(this)
    }

    private fun build(
        url: HttpUrl,
        scope: OpdsScope,
        credentials: RemoteCredentials,
        range: String?,
    ): Request = request(url, scope, credentials)
        .apply { range?.let { header("Range", it) } }
        .header("Accept", ACCEPT)
        .build()

    companion object {
        private const val MAX_HOPS = 5

        /**
         * Both OPDS profiles, then anything. Servers that serve a web
         * page and a feed at the same address use this to decide, and
         * the ones that do not ignore it.
         */
        private const val ACCEPT =
            "application/atom+xml;profile=opds-catalog, application/atom+xml, */*;q=0.5"

        fun default(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
