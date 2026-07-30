package com.chmouel.liseur.data.calibre

import android.util.Log
import com.chmouel.liseur.data.remote.CatalogSource
import com.chmouel.liseur.data.remote.RemoteBook
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteHttp
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.failureForCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import org.xml.sax.SAXException

/** Fetches book lists from a calibre-web catalog. */
class CalibreCatalogClient(private val http: RemoteHttp = RemoteHttp()) : CatalogSource {

    /**
     * Every book in the library, following the feed's `next` links.
     *
     * calibre-web serves 60 entries a page, so a large library means a
     * handful of requests; [onPage] reports each one so the library can
     * fill in as it arrives instead of after the last page.
     */
    override suspend fun allBooks(
        baseUrl: String,
        credentials: RemoteCredentials,
        onPage: suspend (List<RemoteBook>) -> Unit,
    ): List<RemoteBook> = withContext(Dispatchers.IO) {
        val books = mutableListOf<RemoteBook>()
        val seenHrefs = mutableSetOf<String>()
        var href: String? = "/opds/books/letter/00"

        while (href != null) {
            coroutineContext.ensureActive()
            if (!seenHrefs.add(href)) break

            val page = fetchPage(baseUrl, credentials, href)
            val entries = page.books.map(OpdsBook::toRemote)
            books += entries
            onPage(entries)
            href = page.nextHref
        }
        books
    }

    /** calibre-web does not page search results, so this is one request. */
    override suspend fun search(
        baseUrl: String,
        credentials: RemoteCredentials,
        query: String,
    ): List<RemoteBook> = withContext(Dispatchers.IO) {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        fetchPage(baseUrl, credentials, "/opds/search?query=$encoded").books.map(OpdsBook::toRemote)
    }

    /**
     * A page, with every way of failing already given its meaning.
     *
     * Both translations here matter upstream. A bare `IOException` for a
     * rejected sign-in would reach the library as "could not reach the
     * server", which sends someone to check their wifi over a password
     * the server told us it does not like. And a feed that is not XML at
     * all throws a `SAXException`, which is not an `IOException`, so
     * nothing above catches it -- it escapes the refresh entirely and
     * leaves the library spinning for good.
     */
    private fun fetchPage(
        baseUrl: String,
        credentials: RemoteCredentials,
        href: String,
    ): OpdsPage {
        val url = CalibreUrl.resolve(baseUrl, href)
        return http.get(url, credentials).use { response ->
            if (!response.isSuccessful) {
                throw RemoteHttpFailure(failureForCode(response.code))
            }
            val body = response.body.string()
            try {
                OpdsParser.parse(body)
            } catch (e: SAXException) {
                Log.i(TAG, "The catalog feed was not readable XML", e)
                throw RemoteHttpFailure(SyncFailure.Malformed)
            }
        }
    }

    private companion object {
        const val TAG = "calibre-catalog"
    }
}
