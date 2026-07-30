package com.chmouel.liseur.data.komga

import android.util.Log
import com.chmouel.liseur.data.remote.CatalogSource
import com.chmouel.liseur.data.remote.RemoteBook
import com.chmouel.liseur.data.remote.RemoteCredentials
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Fetches book lists from a Komga server.
 *
 * Only EPUBs Komga has finished analysing are asked for, and the filter
 * is applied server-side: a mixed library of comics and books would
 * otherwise be paged through in full only to have most of it discarded
 * on the phone.
 */
class KomgaCatalogClient(private val http: KomgaHttp = KomgaHttp()) : CatalogSource {

    override suspend fun allBooks(
        baseUrl: String,
        credentials: RemoteCredentials,
        onPage: suspend (List<RemoteBook>) -> Unit,
    ): List<RemoteBook> = allKomgaBooks(baseUrl, credentials) { page ->
        onPage(page.map(KomgaBook::book))
    }.map(KomgaBook::book)

    /**
     * The same walk, keeping what only the sync cares about.
     *
     * Each book carries its reading progress inline, so this one pass
     * tells the sync which books moved without a request per book.
     */
    suspend fun allKomgaBooks(
        baseUrl: String,
        credentials: RemoteCredentials,
        onPage: suspend (List<KomgaBook>) -> Unit = {},
    ): List<KomgaBook> = withContext(Dispatchers.IO) {
        val books = mutableListOf<KomgaBook>()
        var page = 0

        while (page < MAX_PAGES) {
            coroutineContext.ensureActive()
            val answer = KomgaBooks.parsePage(fetchPage(baseUrl, credentials, page))
            books += answer.books
            onPage(answer.books)
            if (answer.last || answer.books.isEmpty()) return@withContext books
            page++
        }
        Log.i(TAG, "Stopped after $MAX_PAGES pages of catalog")
        books
    }

    /**
     * One book, for when only one book is being asked about.
     *
     * Syncing a single book after it is opened would otherwise page
     * through the whole catalog to find one row of reading progress.
     */
    suspend fun book(
        baseUrl: String,
        credentials: RemoteCredentials,
        bookId: String,
    ): KomgaBook = withContext(Dispatchers.IO) {
        KomgaBooks.parseBook(
            http.getObject(KomgaUrl.api(baseUrl, "/api/v1/books/$bookId"), credentials),
        )
    }

    override suspend fun search(
        baseUrl: String,
        credentials: RemoteCredentials,
        query: String,
    ): List<RemoteBook> = withContext(Dispatchers.IO) {
        val url = KomgaUrl.api(baseUrl, LIST) +
            "?page=0&size=$PAGE_SIZE&sort=metadata.titleSort,asc"
        KomgaBooks.parsePage(http.postObject(url, credentials, searchBody(query)))
            .books
            .map(KomgaBook::book)
    }

    private fun fetchPage(baseUrl: String, credentials: RemoteCredentials, page: Int): JSONObject {
        val url = KomgaUrl.api(baseUrl, LIST) +
            "?page=$page&size=$PAGE_SIZE&sort=metadata.titleSort,asc"
        return http.postObject(url, credentials, JSONObject().put("condition", epubsThatAreReady()))
    }

    private fun searchBody(query: String): JSONObject = JSONObject()
        .put("condition", epubsThatAreReady())
        .put("fullTextSearch", query)

    /**
     * Books this app can actually open.
     *
     * `READY` matters as much as the format: a book Komga is still
     * unpacking, or gave up on, has no page list, so its position could
     * never be synced even though it would show up in the library.
     */
    private fun epubsThatAreReady(): JSONObject = JSONObject().put(
        "allOf",
        jsonArrayOf(
            JSONObject().put("mediaProfile", isExactly("EPUB")),
            JSONObject().put("mediaStatus", isExactly("READY")),
        ),
    )

    private fun isExactly(value: String) = JSONObject()
        .put("operator", "is")
        .put("value", value)

    private companion object {
        const val TAG = "KomgaCatalog"
        const val LIST = "/api/v1/books/list"
        const val PAGE_SIZE = 200

        /** A guard against a server that never says it is finished. */
        const val MAX_PAGES = 200
    }
}
