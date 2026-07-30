package com.chmouel.liseur.data.komga

import com.chmouel.liseur.data.remote.RemoteBook
import org.json.JSONObject

/**
 * How far Komga thinks a book has been read.
 *
 * Every book in the catalog carries this inline, which is what makes a
 * catalog refresh double as the sync's change detector: nothing extra
 * has to be asked for to find out which books moved.
 */
data class KomgaReadProgress(
    val page: Int,
    val completed: Boolean,
    /**
     * When the reading happened.
     *
     * This is the only timestamp Komga reports for a position that can
     * be believed, and it is the one the server itself orders writes by
     * — a push whose `modified` is not strictly after it comes back 409.
     * The `modified` field of `GET /progression` is not the same number
     * and must not be used in its place.
     */
    val readDate: Long?,
    val deviceId: String?,
)

/** A Komga book, and what the sync needs that the library does not. */
data class KomgaBook(
    val book: RemoteBook,
    val progress: KomgaReadProgress?,
)

/** One page of a `books/list` answer, and whether it was the last. */
data class KomgaPage(
    val books: List<KomgaBook>,
    val last: Boolean,
)

/**
 * Reads Komga's book DTOs.
 *
 * Kept apart from the client so the shapes can be tested against
 * payloads captured from a real server rather than against a guess.
 */
object KomgaBooks {

    fun parsePage(json: JSONObject): KomgaPage = KomgaPage(
        books = json.objects("content").map(::parseBook),
        // `last` is absent on the one-shot routes that return a bare
        // list; treating that as the end is right for them.
        last = json.optBoolean("last", true),
    )

    fun parseBook(json: JSONObject): KomgaBook {
        val metadata = json.optJSONObject("metadata")
        val media = json.optJSONObject("media")
        val id = json.optString("id")

        return KomgaBook(
            book = RemoteBook(
                remoteId = id,
                // The metadata title is the one Komga shows and the one
                // the reader has had the chance to correct; the file name
                // is only a fallback for a book it never catalogued.
                title = metadata?.stringOrNull("title")
                    ?: json.stringOrNull("name")
                    ?: id,
                author = metadata?.author(),
                coverHref = "/api/v1/books/$id/thumbnail",
                downloadHref = "/api/v1/books/$id/file",
                sizeBytes = json.optLong("sizeBytes").takeIf { it > 0 },
                updatedAt = KomgaTime.parse(json.stringOrNull("lastModified")),
                pageCount = media?.optInt("pagesCount")?.takeIf { it > 0 },
            ),
            progress = json.optJSONObject("readProgress")?.let(::parseProgress),
        )
    }

    fun parseProgress(json: JSONObject): KomgaReadProgress = KomgaReadProgress(
        page = json.optInt("page"),
        completed = json.optBoolean("completed"),
        readDate = KomgaTime.parse(json.stringOrNull("readDate")),
        deviceId = json.stringOrNull("deviceId"),
    )

    /**
     * Whoever wrote the book, preferred over whoever else worked on it.
     *
     * Komga lists everyone involved with a role each, so a book can
     * arrive credited to its translator or its cover artist unless the
     * writer is asked for by name.
     */
    private fun JSONObject.author(): String? {
        val authors = objects("authors")
        val writer = authors.firstOrNull { it.optString("role").equals("writer", true) }
        return (writer ?: authors.firstOrNull())?.stringOrNull("name")
    }
}
