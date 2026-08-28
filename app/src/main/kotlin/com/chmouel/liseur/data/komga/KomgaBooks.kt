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
    /**
     * The book's exact media type, e.g. `application/epub+zip`.
     *
     * Komga's own client has no use for this, since a Komga library is
     * asked to return only what it can already read. Grimmory's
     * compatibility shim has no such filter, and its `mediaProfile` —
     * the field that looks like the one to ask — reports MOBI and AZW3
     * as `"EPUB"`. This is the only field that says what the file
     * actually is.
     */
    val mediaType: String?,
)

/**
 * One page of a book listing, and what it said about being the last.
 *
 * [last] is the answer; [pagingWasExplicit] is whether the server
 * actually said so or the parser assumed it. That distinction is what
 * lets a caller who cannot afford to guess — because guessing wrong
 * deletes books — refuse to treat an assumption as the end of a catalog.
 */
data class KomgaPage(
    val books: List<KomgaBook>,
    val last: Boolean,
    /** Which page this is, when the server said. */
    val number: Int? = null,
    /** How many pages there are in total, when the server said. */
    val totalPages: Int? = null,
    /** Whether `last`, `number` and `totalPages` were all present. */
    val pagingWasExplicit: Boolean = false,
    /**
     * How many entries the page carried, before any were dropped.
     *
     * A caller compares this with `books.size` to find out whether the
     * server sent something it could not read. That is a different fact
     * from an empty page, and for a catalog walk a much more dangerous
     * one: a page whose entries were all unparseable looks exactly like
     * a page of no books, and "no books" is how a library gets deleted.
     *
     * This is the length of the `content` array itself, so an entry
     * that was not even a JSON object still counts. Anything else would
     * let the array's own shape hide a book.
     */
    val entriesOnPage: Int = 0,
    /**
     * Whether `content` was actually there and actually an array.
     *
     * Absent, null, or some other type all parse to no books, which is
     * indistinguishable from an empty page unless this is checked. For a
     * catalog walk they are opposites: one is a library that emptied,
     * the other is a body this client did not understand.
     */
    val contentWasArray: Boolean = false,
)

/**
 * How a book's cover and file are addressed on the server it came from.
 *
 * Komga and Grimmory answer with the same book DTO but serve it from
 * different paths, and neither includes a usable link in the payload, so
 * the address has to be built. Getting this wrong on a download is loud;
 * getting it wrong on a cover is a silent grey box, which is why it is a
 * parameter rather than a constant.
 */
fun interface KomgaHrefs {
    /** The path for [what] — `thumbnail` or `file` — of book [id]. */
    fun of(id: String, what: String): String
}

/**
 * Reads Komga's book DTOs.
 *
 * Kept apart from the client so the shapes can be tested against
 * payloads captured from a real server rather than against a guess.
 */
object KomgaBooks {

    /** Where Komga itself serves a book's parts from. */
    val KOMGA_HREFS = KomgaHrefs { id, what -> "/api/v1/books/$id/$what" }

    /**
     * Accepts any id, which is what Komga has always done.
     *
     * Komga's ids are opaque strings with no shape to check against, and
     * they are only ever used as a path segment against a server the
     * reader chose. A caller whose ids *do* have a shape passes its own.
     */
    val ANY_ID: (String) -> String? = { it }

    fun parsePage(
        json: JSONObject,
        hrefs: KomgaHrefs = KOMGA_HREFS,
        validateId: (String) -> String? = ANY_ID,
    ): KomgaPage {
        val content = json.optJSONArray("content")
        val entries = json.objects("content")
        // Read by type rather than by presence. A field that is there
        // and null passes `has()` while `optInt` answers 0 and
        // `optBoolean` answers the default, which together spell "page
        // 0 of 0, and the last one" -- a body that says nothing,
        // believed as one that says the catalog ends here.
        val number = json.intOrNull("number")
        val totalPages = json.intOrNull("totalPages")
        val last = json.booleanOrNull("last")
        return KomgaPage(
            books = entries.mapNotNull { parseBook(it, hrefs, validateId) },
            // `last` is absent on the one-shot routes that return a bare
            // list; treating that as the end is right for them, and
            // `pagingWasExplicit` is how a caller tells the two apart.
            last = last ?: true,
            number = number,
            totalPages = totalPages,
            pagingWasExplicit = number != null && totalPages != null && last != null,
            // The array's own length, not the number of entries that
            // turned out to be objects. A caller checking for entries it
            // could not read has to be able to see one that was not even
            // the right kind of thing.
            entriesOnPage = content?.length() ?: 0,
            contentWasArray = content != null,
        )
    }

    /**
     * One book, or null if its id is not one this caller can address.
     *
     * A null is not "no such book" — it is "this entry was not
     * understood", and a caller walking a catalog has to treat the two
     * very differently.
     */
    fun parseBook(
        json: JSONObject,
        hrefs: KomgaHrefs = KOMGA_HREFS,
        validateId: (String) -> String? = ANY_ID,
    ): KomgaBook? {
        val metadata = json.optJSONObject("metadata")
        val media = json.optJSONObject("media")
        val id = validateId(json.optString("id")) ?: return null

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
                coverHref = hrefs.of(id, "thumbnail"),
                downloadHref = hrefs.of(id, "file"),
                sizeBytes = json.optLong("sizeBytes").takeIf { it > 0 },
                updatedAt = KomgaTime.parse(json.stringOrNull("lastModified")),
                pageCount = media?.optInt("pagesCount")?.takeIf { it > 0 },
                // A one-shot is a book Komga holds on its own. It is
                // given a series of its own name so the API has
                // something to answer with, and putting that on the
                // shelf would fill it with series of one book.
                seriesName = json.stringOrNull("seriesTitle")
                    ?.takeIf { !json.optBoolean("oneshot", false) },
                seriesIndex = metadata?.seriesIndex()
                    ?.takeIf { !json.optBoolean("oneshot", false) },
                seriesId = json.stringOrNull("seriesId")
                    ?.takeIf { !json.optBoolean("oneshot", false) },
            ),
            progress = json.optJSONObject("readProgress")?.let(::parseProgress),
            mediaType = media?.stringOrNull("mediaType"),
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

    /**
     * Where in its series Komga puts the book.
     *
     * `numberSort` is the one to ask: it is what Komga itself orders a
     * series by, and it is already a number. `number` beside it is what
     * gets shown and need not be one at all — "Annual 2023" is a
     * perfectly ordinary value there — so it is only a fallback for the
     * rare book whose sort number is missing.
     *
     * The book's other `number`, on the DTO rather than its metadata, is
     * the file's place in the folder and means nothing here.
     */
    private fun JSONObject.seriesIndex(): Double? {
        if (has("numberSort") && !isNull("numberSort")) {
            optDouble("numberSort").takeIf { !it.isNaN() }?.let { return it }
        }
        return stringOrNull("number")?.trim()?.toDoubleOrNull()
    }
}
