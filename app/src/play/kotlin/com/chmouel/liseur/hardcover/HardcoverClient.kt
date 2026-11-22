package com.chmouel.liseur.hardcover

import com.chmouel.liseur.data.remote.RemoteHttp
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.RemoteResult
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.failureForCode
import com.chmouel.liseur.data.remote.remoteCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
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
    val usersCount: Int = 0,
    /** A cover on Hardcover's image host over https, or null: see [HardcoverImages.validCoverUrl]. */
    val coverUrl: String? = null,
    val authors: List<String> = emptyList(),
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
 * of queries reporting reading needs.
 *
 * Every call is one top-level field, because Hardcover counts each
 * top-level field against the rate limit (a burst of 10, refilled one a
 * second). Calls are spaced [spacingMillis] apart so a queue of books
 * never drains that burst.
 *
 * A refused token is 401 and a token missing a scope 403; a GraphQL
 * error comes back as HTTP 200 with an `errors` array, and an expired
 * session there is `invalid-jwt`. Both token cases read as
 * [SyncFailure.Unauthorised] or [SyncFailure.Forbidden] so the reader
 * is told to fix the token, and 429 is kept as itself so it is retried.
 *
 * Redirects are not followed: nothing behind the fixed endpoint
 * legitimately redirects, and a hop would carry the token somewhere the
 * reader never agreed to.
 */
class HardcoverClient(
    private val endpoint: String = ENDPOINT,
    private val http: RemoteHttp = RemoteHttp(
        RemoteHttp.default().newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build(),
    ),
    private val spacingMillis: Long = SPACING_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val pacing = Mutex()
    private var lastSentAt = 0L

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
        // An absent ISBN-10 is asked for as the ISBN-13 again: `_eq: null` is an error in Hasura.
        val variables = JSONObject().put("isbn13", isbn.isbn13).put("isbn10", isbn.isbn10 ?: isbn.isbn13)
        return call(token, EDITIONS_BY_ISBN, variables) { editions(it) }
    }

    /** The editions of these books, in their order and most read first, for choosing one by hand. */
    suspend fun editionsOfBooks(token: String, bookIds: List<Long>): RemoteResult<List<HardcoverEdition>> =
        call(token, EDITIONS_OF_BOOKS, JSONObject().put("bookIds", JSONArray(bookIds))) { data ->
            editions(data).sortedBy { bookIds.indexOf(it.bookId) }
        }

    /** The id of the book a Hardcover book id or slug names, as a list that is empty when there is none. */
    suspend fun booksByRef(token: String, ref: HardcoverRef): RemoteResult<List<Long>> {
        val (query, variables) = when (ref) {
            is HardcoverRef.BookId -> BOOK_BY_ID to JSONObject().put("id", ref.id)
            is HardcoverRef.Slug -> BOOK_BY_SLUG to JSONObject().put("slug", ref.slug)
            else -> return RemoteResult.Ok(emptyList())
        }
        return call(token, query, variables) { data ->
            val rows = data.optJSONArray("books") ?: JSONArray()
            (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.optLong("id", 0)?.takeIf { id -> id > 0 } }
        }
    }

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

    /** Where this book stands in the reader's library, with its latest read. */
    suspend fun shelfState(token: String, userId: Long, bookId: Long): RemoteResult<HardcoverShelfState> {
        val variables = JSONObject().put("userId", userId).put("bookId", bookId)
        return call(token, SHELF_STATE, variables) { data ->
            val entry = data.optJSONArray("user_books")?.optJSONObject(0)
            val read = entry?.optJSONArray("user_book_reads")?.optJSONObject(0)
            HardcoverShelfState(
                userBookId = entry?.getLong("id"),
                statusId = entry?.takeUnless { it.isNull("status_id") }?.getInt("status_id"),
                rating = entry?.takeUnless { it.isNull("rating") }?.getDouble("rating"),
                read = read?.let {
                    HardcoverRead(
                        id = it.getLong("id"),
                        pages = it.takeUnless { r -> r.isNull("progress_pages") }?.getInt("progress_pages"),
                        startedAt = it.text("started_at")?.take(DATE_LENGTH),
                        finishedAt = it.text("finished_at")?.take(DATE_LENGTH),
                        editionId = it.takeUnless { r -> r.isNull("edition_id") }?.optLong("edition_id"),
                        editionPages = it.optJSONObject("edition")?.optInt("pages", 0)?.takeIf { p -> p > 0 },
                    )
                },
                editionId = entry?.takeUnless { it.isNull("edition_id") }?.optLong("edition_id"),
            )
        }
    }

    /** Adds a book to the library; answers the new entry's id. */
    suspend fun addToLibrary(
        token: String,
        bookId: Long,
        editionId: Long,
        statusId: Int,
        rating: Double?,
    ): RemoteResult<Long> {
        val entry = JSONObject().put("book_id", bookId).put("edition_id", editionId).put("status_id", statusId)
        rating?.let { entry.put("rating", it) }
        return call(token, INSERT_USER_BOOK, JSONObject().put("object", entry)) { data ->
            payload(data, "insert_user_book").optJSONObject("user_book")?.getLong("id")
                ?: throw RemoteHttpFailure(SyncFailure.Malformed)
        }
    }

    /** Changes a library entry; the server starts or finishes the read a status implies. */
    suspend fun updateEntry(token: String, userBookId: Long, step: HardcoverEntryStep.Update): RemoteResult<Unit> {
        val entry = JSONObject()
        step.statusId?.let { entry.put("status_id", it) }
        when {
            step.rating != null -> entry.put("rating", step.rating)
            step.clearRating -> entry.put("rating", JSONObject.NULL)
        }
        step.editionId?.let { entry.put("edition_id", it) }
        val variables = JSONObject().put("id", userBookId).put("object", entry)
        return call(token, UPDATE_USER_BOOK, variables) { payload(it, "update_user_book") }
    }

    /** Corrects one read: pages and dates, only the ones given. */
    suspend fun updateRead(token: String, readId: Long, step: HardcoverReadStep): RemoteResult<Unit> {
        val read = JSONObject()
        step.pages?.let { read.put("progress_pages", it) }
        step.startedAt?.let { read.put("started_at", it) }
        step.finishedAt?.let { read.put("finished_at", it) }
        step.editionId?.let { read.put("edition_id", it) }
        val variables = JSONObject().put("id", readId).put("object", read)
        return call(token, UPDATE_USER_BOOK_READ, variables) { payload(it, "update_user_book_read") }
    }

    /** A mutation's payload, refused when it carries an `error` or `errors`. */
    private fun payload(data: JSONObject, field: String): JSONObject {
        val payload = data.optJSONObject(field) ?: throw RemoteHttpFailure(SyncFailure.Malformed)
        val error = payload.text("error")
        val errors = payload.optJSONArray("errors")?.length() ?: 0
        if (error != null || errors > 0) throw RemoteHttpFailure(SyncFailure.Malformed)
        return payload
    }

    private fun editions(data: JSONObject): List<HardcoverEdition> {
        val rows = data.optJSONArray("editions") ?: JSONArray()
        return (0 until rows.length()).map { index -> edition(rows.getJSONObject(index)) }
    }

    private fun edition(row: JSONObject): HardcoverEdition = HardcoverEdition(
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
        usersCount = row.optInt("users_count", 0).coerceAtLeast(0),
        coverUrl = HardcoverImages.validCoverUrl(
            row.optJSONObject("image")?.text("url") ?: row.optJSONObject("book")?.optJSONObject("cached_image")?.text("url"),
        ),
        authors = authorsOf(row.optJSONObject("book")?.opt("cached_contributors")),
    )

    /** Contributors credited as authors, or every contributor when none is. */
    private fun authorsOf(contributors: Any?): List<String> {
        val rows = when (contributors) {
            is JSONArray -> contributors
            is String -> runCatching { JSONArray(contributors) }.getOrNull()
            else -> null
        } ?: return emptyList()
        val people = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.mapNotNull { row ->
            val name = row.optJSONObject("author")?.text("name") ?: return@mapNotNull null
            name to row.text("contribution")
        }
        val authors = people.filter { it.second == null || it.second.equals("Author", ignoreCase = true) }
        return (authors.ifEmpty { people }).map { it.first }.distinct()
    }

    private suspend fun awaitTurn() = pacing.withLock {
        val wait = lastSentAt + spacingMillis - clock()
        if (wait > 0) delay(wait)
        lastSentAt = clock()
    }

    private suspend fun <T> call(
        token: String,
        query: String,
        variables: JSONObject = JSONObject(),
        read: (JSONObject) -> T,
    ): RemoteResult<T> {
        val bare = bearer(token)
        // OkHttp throws on a header holding anything else, as a pasted token might.
        if (bare.isEmpty() || bare.any { it !in '!'..'~' }) return RemoteResult.Failed(SyncFailure.Unauthorised)
        awaitTurn()
        return withContext(Dispatchers.IO) {
            remoteCall {
                val body = JSONObject().put("query", query).put("variables", variables)
                    .toString()
                    .toRequestBody(JSON)
                val request = Request.Builder()
                    .url(endpoint)
                    .header("Authorization", "Bearer $bare")
                    .header("User-Agent", USER_AGENT)
                    .post(body)
                    .build()
                http.client.newCall(request).await().use { response ->
                    currentCoroutineContext().ensureActive()
                    if (!response.isSuccessful) throw RemoteHttpFailure(failureFor(response.code))
                    val answer = JSONObject(response.body.string())
                    answer.optJSONArray("errors")?.takeIf { it.length() > 0 }?.let {
                        throw RemoteHttpFailure(graphQlFailure(it))
                    }
                    read(answer.optJSONObject("data") ?: throw RemoteHttpFailure(SyncFailure.Malformed))
                }
            }
        }
    }

    /** Cancelling the coroutine cancels the HTTP call, so a lookup nobody waits for stops. */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { response.close() }
                }
            },
        )
    }

    private fun JSONObject.text(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

    companion object {
        const val ENDPOINT = "https://api.hardcover.app/v1/graphql"

        /** Where a reader makes a token with exactly the scopes reporting needs. */
        const val TOKEN_PAGE =
            "https://hardcover.app/account/api/keys/new?scope=read:me+read:catalog+read:library+write:library"

        /** One request a second, Hardcover's refill rate. */
        private const val SPACING_MILLIS = 1_000L
        private const val DATE_LENGTH = 10

        private const val USER_AGENT = "Liseur (Android EPUB reader; github.com/chmouel/liseur)"

        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Tokens get pasted with the `Bearer ` they were shown with. */
        internal fun bearer(token: String): String =
            token.trim().removePrefix("Bearer ").removePrefix("bearer ").trim()

        /** With redirects off a 3xx is the answer itself, and it is never a right one. */
        internal fun failureFor(code: Int): SyncFailure =
            if (code in 300..399) SyncFailure.Malformed else failureForCode(code)

        internal fun graphQlFailure(errors: JSONArray): SyncFailure {
            val codes = (0 until errors.length()).mapNotNull {
                (errors.opt(it) as? JSONObject)?.optJSONObject("extensions")?.optString("code")
            }
            return when {
                codes.any { it == "invalid-jwt" || it == "invalid-headers" } -> SyncFailure.Unauthorised
                else -> SyncFailure.Malformed
            }
        }

        private const val EDITION_FIELDS =
            "id book_id title edition_format pages isbn_13 isbn_10 release_year users_count " +
                "image { url } book { title cached_image cached_contributors } publisher { name }"

        private const val ME = "query { me { id username } }"

        private const val EDITIONS_BY_ISBN =
            "query (\$isbn13: String!, \$isbn10: String!) { " +
                "editions(where: {_or: [{isbn_13: {_eq: \$isbn13}}, {isbn_10: {_eq: \$isbn10}}]}, " +
                "order_by: {users_count: desc}, limit: 10) { $EDITION_FIELDS } }"

        private const val EDITIONS_OF_BOOKS =
            "query (\$bookIds: [Int!]!) { " +
                "editions(where: {book_id: {_in: \$bookIds}}, " +
                "order_by: {users_count: desc}, limit: 50) { $EDITION_FIELDS } }"

        private const val DEFAULT_EDITIONS =
            "query (\$bookId: Int!) { books(where: {id: {_eq: \$bookId}}) { " +
                "default_ebook_edition { $EDITION_FIELDS } " +
                "default_physical_edition { $EDITION_FIELDS } " +
                "editions(order_by: {users_count: desc}, limit: 1) { $EDITION_FIELDS } } }"

        private const val BOOK_BY_ID =
            "query (\$id: Int!) { books(where: {id: {_eq: \$id}}, limit: 1) { id } }"

        private const val BOOK_BY_SLUG =
            "query (\$slug: String!) { books(where: {slug: {_eq: \$slug}}, limit: 1) { id } }"

        private const val SEARCH =
            "query (\$query: String!) { " +
                "search(query: \$query, query_type: \"Book\", per_page: 10, page: 1) { results } }"

        private const val SHELF_STATE =
            "query (\$userId: Int!, \$bookId: Int!) { " +
                "user_books(where: {user_id: {_eq: \$userId}, book_id: {_eq: \$bookId}}) { " +
                "id status_id rating edition_id " +
                "user_book_reads(order_by: {id: desc}, limit: 1) { " +
                "id progress_pages started_at finished_at edition_id edition { pages } } } }"

        private const val INSERT_USER_BOOK =
            "mutation (\$object: UserBookCreateInput!) { " +
                "insert_user_book(object: \$object) { error user_book { id } } }"

        private const val UPDATE_USER_BOOK =
            "mutation (\$id: Int!, \$object: UserBookUpdateInput!) { " +
                "update_user_book(id: \$id, object: \$object) { error user_book { id } } }"

        private const val UPDATE_USER_BOOK_READ =
            "mutation (\$id: Int!, \$object: DatesReadInput!) { " +
                "update_user_book_read(id: \$id, object: \$object) { error user_book_read { id } } }"
    }
}
