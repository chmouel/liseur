package com.chmouel.liseur.data.liseursync

import android.util.Log
import com.chmouel.liseur.data.remote.CatalogSource
import com.chmouel.liseur.data.remote.CatalogWalk
import com.chmouel.liseur.data.remote.RemoteBook
import com.chmouel.liseur.data.remote.RemoteCredentials
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fetches book lists from a liseur-sync server.
 *
 * The native API walks one library at a time, so the catalog is every
 * readable library's pages back to back. A page carries the book's
 * contributors, series and files inline (ADR-0015), which is what lets
 * a shelf row be drawn without a second request per book.
 */
class LiseurSyncCatalogClient(
    private val http: LiseurSyncHttp = LiseurSyncHttp(),
) : CatalogSource {

    override suspend fun allBooks(
        baseUrl: String,
        credentials: RemoteCredentials,
        onPage: suspend (List<RemoteBook>) -> Unit,
    ): CatalogWalk {
        val libraries = libraries(baseUrl, credentials)
        for (library in libraries) {
            var cursor: String? = null
            var guard = MAX_PAGES
            while (guard-- > 0) {
                coroutineContext.ensureActive()
                val page = http.get(
                    LiseurSyncApi.libraryBooks(baseUrl, library, cursor, PAGE),
                    credentials,
                )
                val books = page.optJSONArray("books").let(::books)
                onPage(books)
                cursor = page.optString("next_cursor").takeIf { it.isNotEmpty() }
                    ?: break
                if (books.isEmpty()) break
            }
            if (guard <= 0) {
                // A server that always says there is more would
                // otherwise keep this walk going forever, and a walk
                // that stopped short has not seen the whole library.
                Log.i(TAG, "Stopped after $MAX_PAGES pages of catalog")
                return CatalogWalk(complete = false)
            }
        }
        return CatalogWalk(complete = true)
    }

    override suspend fun search(
        baseUrl: String,
        credentials: RemoteCredentials,
        query: String,
    ): List<RemoteBook> =
        libraries(baseUrl, credentials).flatMap { library ->
            val answer = http.get(
                LiseurSyncApi.librarySearch(baseUrl, library, query),
                credentials,
            )
            answer.optJSONArray("books").let(::books)
        }

    /** The libraries this token may read, as ids. */
    private suspend fun libraries(
        baseUrl: String,
        credentials: RemoteCredentials,
    ): List<String> {
        val answer = http.get(LiseurSyncApi.url(baseUrl, LiseurSyncApi.LIBRARIES), credentials)
        val array = answer.optJSONArray("libraries") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.optString("library_id")?.takeIf { it.isNotEmpty() }
        }
    }

    internal companion object {
        const val TAG = "LiseurSyncCatalog"
        const val PAGE = 200

        /** A guard against a server that never says it is finished. */
        const val MAX_PAGES = 200
    }
}

/**
 * One catalog book as the server sends it, mapped to what the library
 * shows.
 *
 * The shape is the same on every route (ADR-0015): contributors carry a
 * normalized role, so the author is picked rather than guessed; a book
 * may claim several series, and the shelf shows the first, which is the
 * order the catalog stored them in.
 */
internal fun books(array: JSONArray?): List<RemoteBook> =
    (0 until (array?.length() ?: 0)).mapNotNull { index ->
        array?.optJSONObject(index)?.let(::book)
    }

private fun book(json: JSONObject): RemoteBook? {
    val id = json.optString("book_id").takeIf { it.isNotEmpty() } ?: return null
    val title = json.optString("title").takeIf { it.isNotEmpty() } ?: return null
    val series = json.optJSONArray("series")?.optJSONObject(0)
    return RemoteBook(
        remoteId = id,
        title = title,
        author = authors(json.optJSONArray("contributors")),
        coverHref = json.optString("cover_url").takeIf { it.isNotEmpty() },
        downloadHref = "/v1/books/$id/download",
        sizeBytes = firstFile(json.optJSONArray("files"))?.optLong("size_bytes")
            ?.takeIf { it > 0 },
        updatedAt = SyncOps.parseTime(json.optString("updated_at")),
        seriesName = series?.optString("name")?.takeIf { it.isNotEmpty() },
        seriesIndex = series?.takeIf { it.has("position") }?.optDouble("position"),
        seriesId = series?.optString("id")?.takeIf { it.isNotEmpty() },
    )
}

private fun authors(contributors: JSONArray?): String? {
    val names = (0 until (contributors?.length() ?: 0)).mapNotNull { index ->
        contributors?.optJSONObject(index)
            ?.takeIf { it.optString("role") == "author" }
            ?.optString("name")
            ?.takeIf { it.isNotEmpty() }
    }
    return names.joinToString(", ").ifBlank { null }
}

private fun firstFile(files: JSONArray?): JSONObject? =
    (0 until (files?.length() ?: 0))
        .mapNotNull { files?.optJSONObject(it) }
        .firstOrNull { it.optString("media_type") == "application/epub+zip" }
        ?: (0 until (files?.length() ?: 0)).mapNotNull { files?.optJSONObject(it) }.firstOrNull()
