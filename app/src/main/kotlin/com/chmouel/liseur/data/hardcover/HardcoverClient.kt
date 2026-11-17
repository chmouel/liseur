package com.chmouel.liseur.data.hardcover

import com.chmouel.liseur.data.remote.RemoteHttp
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.RemoteResult
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.failureForCode
import com.chmouel.liseur.data.remote.remoteCall
import com.chmouel.liseur.domain.HardcoverShelfState
import com.chmouel.liseur.domain.Isbn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Whose Hardcover library a token writes to. */
data class HardcoverUser(val id: Long, val username: String)

/** One edition of a book on Hardcover, as much of it as picking one needs. */
data class HardcoverEdition(
    val id: Long,
    val bookId: Long,
    val title: String,
    val bookTitle: String?,
    /** `hardcover`, `paperback`, `ebook`, `audiobook`, when Hardcover says. */
    val format: String?,
    val pages: Int?,
    val isbn13: String?,
    val isbn10: String?,
    val releaseYear: Int?,
    val publisher: String?,
)

/** A book the search turned up, before an edition of it is chosen. */
data class HardcoverBook(
    val id: Long,
    val title: String,
    val authors: List<String>,
    val releaseYear: Int?,
)

/**
 * Hardcover's GraphQL API: one endpoint, a bearer token, and the handful
 * of queries reading progress needs.
 *
 * The token is whatever Hardcover issued, a personal access token or an
 * OAuth access token; both sign a request the same way, so how it was
 * obtained is not this class's business.
 *
 * Every call here is one top-level field. Hardcover counts each
 * top-level field as a request against the rate limit (60 a minute, a
 * burst of 10), so a query that asked for two things at once would cost
 * two and save nothing.
 *
 * A GraphQL answer is HTTP 200 even when it refused: the refusal is an
 * `errors` array, or an `error` string inside a mutation's payload. Both
 * are read as [SyncFailure.Malformed], "it answered with something we
 * could not use", because neither is worth retrying unchanged.
 *
 * Redirects are not followed. The endpoint is fixed, nothing behind it
 * legitimately redirects, and a hop would be a place to carry the token
 * that the reader never agreed to.
 *
 * Blocking I/O moves to [Dispatchers.IO] here, not in callers.
 */
class HardcoverClient(
    private val endpoint: String = ENDPOINT,
    private val http: RemoteHttp = RemoteHttp(
        RemoteHttp.default().newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build(),
    ),
) {

    /** Who the token belongs to; also the proof that it works. */
    suspend fun me(token: String): RemoteResult<HardcoverUser> = call(token, ME) { data ->
        // `me` is a function returning users, so Hasura answers with a list.
        val user = when (val me = data.opt("me")) {
            is JSONArray -> me.optJSONObject(0)
            is JSONObject -> me
            else -> null
        } ?: throw RemoteHttpFailure(SyncFailure.Malformed)
        HardcoverUser(user.getLong("id"), user.optString("username"))
    }

    /** Every edition carrying this ISBN. Usually one; reprints can share a number. */
    suspend fun editionsByIsbn(token: String, isbn: Isbn): RemoteResult<List<HardcoverEdition>> {
        val variables = JSONObject().put("isbn13", isbn.isbn13)
        // An ISBN-10 that is absent is asked for as the ISBN-13 again
        // rather than as null: `_eq: null` is an error in Hasura.
        variables.put("isbn10", isbn.isbn10 ?: isbn.isbn13)
        return call(token, EDITIONS_BY_ISBN, variables) { editions(it) }
    }

    /** A book's editions, most read first, for choosing one by hand. */
    suspend fun editionsOfBook(token: String, bookId: Long): RemoteResult<List<HardcoverEdition>> =
        call(token, EDITIONS_OF_BOOK, JSONObject().put("bookId", bookId)) { editions(it) }

    /** The editions Hardcover shows for a book by default, one per kind. */
    suspend fun defaultEditions(token: String, bookId: Long): RemoteResult<HardcoverDefaultEditions> =
        call(token, DEFAULT_EDITIONS, JSONObject().put("bookId", bookId)) { data ->
            val book = data.optJSONArray("books")?.optJSONObject(0)
            HardcoverDefaultEditions(
                ebook = book?.optJSONObject("default_ebook_edition")?.let(::edition),
                physical = book?.optJSONObject("default_physical_edition")?.let(::edition),
                mostRead = book?.optJSONArray("editions")?.optJSONObject(0)?.let(::edition),
            )
        }

    /** Books matching a title and author, best match first. */
    suspend fun searchBooks(token: String, query: String): RemoteResult<List<HardcoverBook>> =
        call(token, SEARCH, JSONObject().put("query", query)) { data ->
            val results = data.optJSONObject("search")?.optJSONObject("results")
            val hits = results?.optJSONArray("hits") ?: JSONArray()
            (0 until hits.length()).mapNotNull { index ->
                val document = hits.optJSONObject(index)?.optJSONObject("document")
                    ?: return@mapNotNull null
                // Typesense hands ids back as strings.
                val id = document.optString("id").toLongOrNull() ?: return@mapNotNull null
                val authors = document.optJSONArray("author_names") ?: JSONArray()
                HardcoverBook(
                    id = id,
                    title = document.optString("title"),
                    authors = (0 until authors.length()).map { authors.optString(it) },
                    releaseYear = document.optInt("release_year", 0).takeIf { it > 0 },
                )
            }
        }

    /** Where this book stands in the reader's library. */
    suspend fun shelfState(
        token: String,
        userId: Long,
        bookId: Long,
    ): RemoteResult<HardcoverShelfState> {
        val variables = JSONObject().put("userId", userId).put("bookId", bookId)
        return call(token, SHELF_STATE, variables) { data ->
            val entry = data.optJSONArray("user_books")?.optJSONObject(0)
            val read = entry?.optJSONArray("user_book_reads")?.optJSONObject(0)
            HardcoverShelfState(
                userBookId = entry?.getLong("id"),
                statusId = entry?.getInt("status_id"),
                openReadId = read?.getLong("id"),
                openReadPages = read?.takeUnless { it.isNull("progress_pages") }
                    ?.getInt("progress_pages"),
            )
        }
    }

    /** Adds a book to the library with a status; answers the new entry's id. */
    suspend fun addToLibrary(
        token: String,
        bookId: Long,
        editionId: Long,
        statusId: Int,
    ): RemoteResult<Long> {
        val variables = JSONObject()
            .put("bookId", bookId)
            .put("editionId", editionId)
            .put("statusId", statusId)
        return call(token, INSERT_USER_BOOK, variables) { data ->
            val payload = payload(data, "insert_user_book")
            payload.optJSONObject("user_book")?.getLong("id")
                ?: throw RemoteHttpFailure(SyncFailure.Malformed)
        }
    }

    /** Changes a library entry's status; the server starts or finishes the read. */
    suspend fun setStatus(token: String, userBookId: Long, statusId: Int): RemoteResult<Unit> {
        val variables = JSONObject().put("id", userBookId).put("statusId", statusId)
        return call(token, UPDATE_USER_BOOK, variables) { payload(it, "update_user_book") }
    }

    /** Logs how many pages in the reader is, on one read. */
    suspend fun setProgress(token: String, readId: Long, pages: Int): RemoteResult<Unit> {
        val variables = JSONObject().put("id", readId).put("pages", pages)
        return call(token, UPDATE_USER_BOOK_READ, variables) {
            payload(it, "update_user_book_read")
        }
    }

    /** A mutation's payload, refused when it carries an `error`. */
    private fun payload(data: JSONObject, field: String): JSONObject {
        val payload = data.optJSONObject(field) ?: throw RemoteHttpFailure(SyncFailure.Malformed)
        if (!payload.isNull("error") && payload.optString("error").isNotBlank()) {
            throw RemoteHttpFailure(SyncFailure.Malformed)
        }
        return payload
    }

    private fun editions(data: JSONObject): List<HardcoverEdition> {
        val rows = data.optJSONArray("editions") ?: JSONArray()
        return (0 until rows.length()).map { index -> edition(rows.getJSONObject(index)) }
    }

    private fun edition(row: JSONObject): HardcoverEdition =
            HardcoverEdition(
                id = row.getLong("id"),
                bookId = row.getLong("book_id"),
                title = row.text("title").orEmpty(),
                bookTitle = row.optJSONObject("book")?.text("title"),
                format = row.text("edition_format"),
                pages = row.optInt("pages", 0).takeIf { it > 0 },
                isbn13 = row.text("isbn_13"),
                isbn10 = row.text("isbn_10"),
                releaseYear = row.optInt("release_year", 0).takeIf { it > 0 },
                publisher = row.optJSONObject("publisher")?.text("name"),
            )

    private suspend fun <T> call(
        token: String,
        query: String,
        variables: JSONObject = JSONObject(),
        read: (JSONObject) -> T,
    ): RemoteResult<T> = withContext(Dispatchers.IO) {
        remoteCall {
            val body = JSONObject().put("query", query).put("variables", variables)
                .toString()
                .toRequestBody(JSON)
            val request = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer " + bearer(token))
                .header("User-Agent", USER_AGENT)
                .post(body)
                .build()
            http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw RemoteHttpFailure(failureFor(response.code))
                val answer = JSONObject(response.body?.string().orEmpty())
                if ((answer.optJSONArray("errors")?.length() ?: 0) > 0) {
                    throw RemoteHttpFailure(SyncFailure.Malformed)
                }
                read(answer.optJSONObject("data") ?: throw RemoteHttpFailure(SyncFailure.Malformed))
            }
        }
    }

    /** With redirects off a 3xx is the answer itself, and it is never a right one. */
    private fun failureFor(code: Int): SyncFailure =
        if (code in 300..399) SyncFailure.Malformed else failureForCode(code)

    private fun JSONObject.text(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

    companion object {
        const val ENDPOINT = "https://api.hardcover.app/v1/graphql"

        /** Hardcover asks API clients to say who they are. */
        private const val USER_AGENT = "Liseur (Android EPUB reader; github.com/chmouel/liseur)"

        private val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * The token alone. Hardcover's settings page has handed tokens
         * out with the `Bearer ` already in front, and a reader pastes
         * what they copied.
         */
        internal fun bearer(token: String): String =
            token.trim().removePrefix("Bearer ").removePrefix("bearer ").trim()

        private const val EDITION_FIELDS =
            "id book_id title edition_format pages isbn_13 isbn_10 release_year " +
                "book { title } publisher { name }"

        private const val ME = "query { me { id username } }"

        private const val EDITIONS_BY_ISBN =
            "query (\$isbn13: String!, \$isbn10: String!) { " +
                "editions(where: {_or: [{isbn_13: {_eq: \$isbn13}}, {isbn_10: {_eq: \$isbn10}}]}, " +
                "order_by: {users_count: desc}, limit: 10) { $EDITION_FIELDS } }"

        private const val EDITIONS_OF_BOOK =
            "query (\$bookId: Int!) { " +
                "editions(where: {book_id: {_eq: \$bookId}}, " +
                "order_by: {users_count: desc}, limit: 25) { $EDITION_FIELDS } }"

        private const val DEFAULT_EDITIONS =
            "query (\$bookId: Int!) { books(where: {id: {_eq: \$bookId}}) { " +
                "default_ebook_edition { $EDITION_FIELDS } " +
                "default_physical_edition { $EDITION_FIELDS } " +
                "editions(order_by: {users_count: desc}, limit: 1) { $EDITION_FIELDS } } }"

        private const val SEARCH =
            "query (\$query: String!) { " +
                "search(query: \$query, query_type: \"Book\", per_page: 10, page: 1) { results } }"

        private const val SHELF_STATE =
            "query (\$userId: Int!, \$bookId: Int!) { " +
                "user_books(where: {user_id: {_eq: \$userId}, book_id: {_eq: \$bookId}}) { " +
                "id status_id " +
                "user_book_reads(where: {finished_at: {_is_null: true}}, " +
                "order_by: {id: desc}, limit: 1) { id progress_pages } } }"

        private const val INSERT_USER_BOOK =
            "mutation (\$bookId: Int!, \$editionId: Int!, \$statusId: Int!) { " +
                "insert_user_book(object: {book_id: \$bookId, edition_id: \$editionId, " +
                "status_id: \$statusId}) { error user_book { id status_id } } }"

        private const val UPDATE_USER_BOOK =
            "mutation (\$id: Int!, \$statusId: Int!) { " +
                "update_user_book(id: \$id, object: {status_id: \$statusId}) { " +
                "error user_book { id status_id } } }"

        private const val UPDATE_USER_BOOK_READ =
            "mutation (\$id: Int!, \$pages: Int!) { " +
                "update_user_book_read(id: \$id, object: {progress_pages: \$pages}) { " +
                "error user_book_read { id progress_pages } } }"
    }
}
