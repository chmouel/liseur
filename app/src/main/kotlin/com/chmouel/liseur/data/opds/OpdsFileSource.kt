package com.chmouel.liseur.data.opds

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.remote.FileSource
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteHttp
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.SyncFailure
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Where a book from a Custom catalog is fetched from.
 *
 * The acquisition URL was made absolute when the catalog was walked, so
 * there is nothing to work out here — but there is still something to
 * decide. The stored URL came out of a document the server wrote, and
 * may name any host in the world, so it goes through the same origin
 * test the walk used: a file elsewhere is still downloaded, just not
 * with the reader's catalog password attached.
 */
class OpdsFileSource(private val http: OpdsHttp = OpdsHttp()) : FileSource {

    override fun downloadRequest(
        server: com.chmouel.liseur.data.db.RemoteServer,
        credentials: RemoteCredentials,
        book: Book,
    ): Request.Builder? {
        val baseUrl = server.baseUrl
        val scope = OpdsScope.of(baseUrl) ?: return null
        // Resolved against the catalog root, which changes nothing for
        // the absolute URLs the walk stores and rescues a row written
        // by some earlier or later version that stored a path.
        val url = book.downloadHref?.let { scope.root.resolve(it) ?: it.toHttpUrlOrNull() }
            ?: return null
        // The signing test is not the whole rule: an https catalog naming
        // a plaintext file, or a public one naming an address inside the
        // reader's network, is refused rather than downloaded unsigned.
        // The scope rides along as a tag so [downloadHttp] can ask the
        // same of every redirect.
        if (!scope.mayFetch(url)) return null
        return http.request(url, scope, credentials).tag(OpdsScope::class.java, scope)
    }

    override fun downloadHttp(): RemoteHttp = RemoteHttp(
        RemoteHttp.forDownloads().newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(ScopedRedirects())
            .build(),
    )
}

/**
 * Follows a download's redirects under its catalog's [OpdsScope], hop by
 * hop, the way [OpdsHttp.get] does for feeds. Left to OkHttp, a file
 * link on the catalog could send the download to plain HTTP or to an
 * address on the reader's own network. Every refusal, including a loop
 * or an unreadable `Location`, is reported as [SyncFailure.InsecureTransport]
 * so the download fails for good instead of being retried.
 */
internal class ScopedRedirects : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        val scope = request.tag(OpdsScope::class.java) ?: return chain.proceed(request)
        var hops = 0
        while (true) {
            val response = chain.proceed(request)
            val location = if (response.isRedirect) response.header("Location") else null
            if (location == null) return response
            response.close()
            if (++hops > OpdsHttp.MAX_HOPS) throw RemoteHttpFailure(SyncFailure.InsecureTransport)
            val next = request.url.resolve(location) ?: throw RemoteHttpFailure(SyncFailure.InsecureTransport)
            if (!scope.mayFetch(next)) throw RemoteHttpFailure(SyncFailure.InsecureTransport)
            request = request.newBuilder().url(next).apply {
                if (!scope.signs(next)) removeHeader("Authorization")
            }.build()
        }
    }
}
