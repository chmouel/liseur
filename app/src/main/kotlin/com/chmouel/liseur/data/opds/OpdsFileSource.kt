package com.chmouel.liseur.data.opds

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.remote.FileSource
import com.chmouel.liseur.data.remote.RemoteCredentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

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
        baseUrl: String,
        credentials: RemoteCredentials,
        book: Book,
    ): Request.Builder? {
        val scope = OpdsScope.of(baseUrl) ?: return null
        // Resolved against the catalog root, which changes nothing for
        // the absolute URLs the walk stores and rescues a row written
        // by some earlier or later version that stored a path.
        val url = book.downloadHref?.let { scope.root.resolve(it) ?: it.toHttpUrlOrNull() }
            ?: return null
        // The signing test is not the whole rule. This request goes to
        // the generic downloader, which follows its own redirects and
        // never sees `OpdsScope`, so the transport and reachability
        // questions are asked here or nowhere: an https catalog naming a
        // plaintext file, or a public one naming an address inside the
        // reader's network, is refused rather than downloaded unsigned.
        if (!scope.mayFetch(url)) return null
        return http.request(url, scope, credentials)
    }
}
