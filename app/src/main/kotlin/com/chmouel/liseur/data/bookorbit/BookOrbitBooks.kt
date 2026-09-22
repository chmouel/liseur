package com.chmouel.liseur.data.bookorbit

import org.json.JSONObject
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.SyncFailure

/**
 * One file of a BookOrbit book.
 *
 * A book is not a file on that server: the same record carries the EPUB,
 * a JPEG cover and an OPF sidecar, and a book with two EPUB editions
 * carries both. Everything Liseur does with a book's bytes — download
 * it, record a position in it, send a session for it — names the file
 * rather than the book, so choosing one is a decision worth keeping
 * rather than making again on every refresh. See `BookOrbitBinding`.
 */
data class BookOrbitFile(
    val id: Long,
    /** Lower-case in practice, but compared case-insensitively anyway. */
    val format: String?,
    /** `primary`, `cover`, `metadata`, or something this app has not met. */
    val role: String?,
    val sizeBytes: Long?,
) {
    val isEpub: Boolean get() = format.equals("epub", ignoreCase = true)
}

/**
 * What BookOrbit says a reader has done with a book.
 *
 * The status is kept as the server spells it, all eight values of it,
 * rather than mapped down to Liseur's three here: `on_hold` and
 * `rereading` are things a reader chose, and flattening them is a
 * decision the merge makes, not a parser.
 */
data class BookOrbitStatus(
    val status: String,
    val source: String?,
    val startedAt: Long?,
    val finishedAt: Long?,
    val updatedAt: Long?,
)

/**
 * A book as the catalog lists it.
 *
 * Carries what the progress sync needs inline — the chosen file, the
 * aggregated percentage and the personal status — so a refresh that has
 * just walked the shelf does not have to ask about every book again.
 * The percentage is a summary across files, though, and is never
 * treated as the selected file's own position.
 */
data class BookOrbitCard(
    val bookId: Long,
    val title: String,
    val author: String?,
    val files: List<BookOrbitFile>,
    val percentage: Double?,
    val readStatus: BookOrbitStatus?,
    val seriesId: String?,
    val seriesName: String?,
    val seriesIndex: Double?,
    val updatedAt: Long?,
    val addedAt: Long?,
    val pageCount: Int?,
    val language: String?,
    val publisher: String?,
    val isbn13: String?,
    val hasCover: Boolean,
)

/** One page of `POST /books/query`. */
data class BookOrbitPage(
    val cards: List<BookOrbitCard>,
    val total: Int,
    val page: Int,
    val size: Int,
)

/**
 * Reads BookOrbit's book DTOs.
 *
 * Kept apart from the client so the shapes can be tested against
 * payloads captured from a real server rather than against a guess, and
 * so a field that moves in a minor release breaks one function.
 */
object BookOrbitBooks {

    fun parsePage(json: JSONObject): BookOrbitPage {
        val items = json.optJSONArray("items") ?: malformed()
        val total = json.intOrNull("total")?.takeIf { it >= 0 } ?: malformed()
        val page = json.intOrNull("page")?.takeIf { it >= 0 } ?: malformed()
        val size = json.intOrNull("size")?.takeIf { it > 0 } ?: malformed()
        if (items.length() == 0 && total != 0) malformed()
        if (items.length() > size || page.toLong() * size > total) malformed()
        val cards = (0 until items.length()).map { index ->
            parseCard(items.optJSONObject(index) ?: malformed()) ?: malformed()
        }
        return BookOrbitPage(cards, total, page, size)
    }

    private fun malformed(): Nothing =
        throw RemoteHttpFailure(SyncFailure.Malformed)

    fun parseCard(json: JSONObject): BookOrbitCard? {
        val id = json.longOrNull("id") ?: return null
        val authors = json.optJSONArray("authors")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotEmpty) }
        }.orEmpty()

        return BookOrbitCard(
            bookId = id,
            // BookOrbit lets a title be absent on a book it has not
            // finished cataloguing; the shelf still has to call it
            // something, and an id is at least true.
            title = json.stringOrNull("title") ?: "#$id",
            author = authors.takeIf { it.isNotEmpty() }?.joinToString(", "),
            files = json.objects("files").mapNotNull(::parseFile),
            percentage = json.doubleOrNull("readingProgress"),
            readStatus = json.optJSONObject("readStatus")?.let(::parseStatus),
            seriesId = json.longOrNull("seriesId")?.toString(),
            seriesName = json.stringOrNull("seriesName"),
            seriesIndex = seriesIndex(json),
            updatedAt = BookOrbitTime.parse(json.stringOrNull("updatedAt")),
            addedAt = BookOrbitTime.parse(json.stringOrNull("addedAt")),
            pageCount = json.intOrNull("pageCount")?.takeIf { it > 0 },
            language = json.stringOrNull("language"),
            publisher = json.stringOrNull("publisher"),
            isbn13 = json.stringOrNull("isbn13"),
            hasCover = json.booleanOrNull("hasCover") ?: false,
        )
    }

    fun parseFile(json: JSONObject): BookOrbitFile? {
        val id = json.longOrNull("id") ?: return null
        return BookOrbitFile(
            id = id,
            format = json.stringOrNull("format")?.lowercase(),
            role = json.stringOrNull("role"),
            sizeBytes = json.longOrNull("sizeBytes")?.takeIf { it > 0 },
        )
    }

    fun parseStatus(json: JSONObject): BookOrbitStatus? {
        val status = json.stringOrNull("status") ?: return null
        return BookOrbitStatus(
            status = status,
            source = json.stringOrNull("source"),
            startedAt = BookOrbitTime.parse(json.stringOrNull("startedAt")),
            finishedAt = BookOrbitTime.parse(json.stringOrNull("finishedAt")),
            updatedAt = BookOrbitTime.parse(json.stringOrNull("updatedAt")),
        )
    }

    /**
     * The EPUB this book is read as, or null when it holds none.
     *
     * A `primary` EPUB is what the server itself would open, so it wins;
     * failing that the first EPUB in the list is better than dropping a
     * book off the shelf. The cover JPEG and the OPF sidecar are never
     * candidates however they are ordered.
     */
    fun chosenFile(card: BookOrbitCard): BookOrbitFile? =
        card.files.firstOrNull { it.isEpub && it.role.equals("primary", ignoreCase = true) }
            ?: card.files.firstOrNull { it.isEpub }

    /**
     * Where in its series the book sits.
     *
     * BookOrbit has spelled this as both a number and a string at
     * different points, and its own detail DTO types it as a string, so
     * both are read. It is only ever advisory — the shelf falls back to
     * the file's own metadata — so an unparseable value is null rather
     * than an error.
     */
    private fun seriesIndex(json: JSONObject): Double? {
        json.doubleOrNull("seriesIndex")?.let { return it }
        val text = json.stringOrNull("seriesIndex") ?: return null
        return text.trim().toDoubleOrNull()
    }
}
