package com.chmouel.liseur.data.calibre

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Fetches book lists from a calibre-web catalog. */
class CalibreCatalogClient(private val http: CalibreHttp = CalibreHttp()) {

    /**
     * Every book in the library, following the feed's `next` links.
     *
     * calibre-web serves 60 entries a page, so a large library means a
     * handful of requests; [onPage] reports each one so the library can
     * fill in as it arrives instead of after the last page.
     */
    suspend fun allBooks(
        baseUrl: String,
        credentials: CalibreCredentials,
        onPage: suspend (List<OpdsBook>) -> Unit = {},
    ): List<OpdsBook> = withContext(Dispatchers.IO) {
        val books = mutableListOf<OpdsBook>()
        val seenHrefs = mutableSetOf<String>()
        var href: String? = "/opds/books/letter/00"

        while (href != null) {
            coroutineContext.ensureActive()
            if (!seenHrefs.add(href)) break

            val page = fetchPage(baseUrl, credentials, href)
            books += page.books
            onPage(page.books)
            href = page.nextHref
        }
        books
    }

    /** calibre-web does not page search results, so this is one request. */
    suspend fun search(
        baseUrl: String,
        credentials: CalibreCredentials,
        query: String,
    ): List<OpdsBook> = withContext(Dispatchers.IO) {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        fetchPage(baseUrl, credentials, "/opds/search?query=$encoded").books
    }

    private fun fetchPage(
        baseUrl: String,
        credentials: CalibreCredentials,
        href: String,
    ): OpdsPage {
        val url = CalibreUrl.resolve(baseUrl, href)
        return http.get(url, credentials).use { response ->
            if (!response.isSuccessful) {
                throw IOException("Catalog request failed with ${response.code}")
            }
            OpdsParser.parse(response.body?.string().orEmpty())
        }
    }
}
