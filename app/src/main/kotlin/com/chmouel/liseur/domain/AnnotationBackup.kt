package com.chmouel.liseur.domain

import com.chmouel.liseur.data.db.BookAnnotation
import org.json.JSONArray
import org.json.JSONObject

/**
 * Carrying highlights, notes and bookmarks off one device and onto
 * another.
 *
 * calibre-web's sync protocol has no room for them — it exchanges where
 * you are in a book and nothing else — so there is nowhere to put them
 * on the server. What there can be is a file: everything you have marked,
 * written out whole, and read back in somewhere else.
 *
 * The format is JSON rather than the Markdown the notebook already
 * offers, because this one has to come back in exactly as it went out.
 * Markdown is for reading; this is for keeping.
 */

/** The one book a set of marks belongs to, as the backup describes it. */
data class BackedUpBook(
    val bookId: String,
    val title: String?,
    val author: String?,
    val annotations: List<BookAnnotation>,
)

/** A book already in this library, for matching a backup against. */
data class KnownBook(val bookId: String, val title: String, val author: String?)

/** What a backup file turned out to contain. */
sealed interface BackupContents {
    data class Readable(val books: List<BackedUpBook>) : BackupContents

    /** The file is not one of ours, or is damaged. */
    data class Unreadable(val reason: String) : BackupContents
}

private const val FORMAT = 1

/**
 * Writes every mark out.
 *
 * The book's title and author travel with its marks so that the same
 * book can be recognised on a device where its file lives somewhere
 * else, and so that a person opening the file can tell what it holds.
 */
fun encodeAnnotationBackup(books: List<BackedUpBook>): String {
    val root = JSONObject()
    root.put("format", FORMAT)
    root.put("application", "liseur")
    val out = JSONArray()
    for (book in books.sortedBy { it.bookId }) {
        val entry = JSONObject()
        entry.put("book_id", book.bookId)
        book.title?.let { entry.put("title", it) }
        book.author?.let { entry.put("author", it) }
        val marks = JSONArray()
        for (a in book.annotations.sortedBy { it.id }) {
            marks.put(
                JSONObject().apply {
                    put("id", a.id)
                    put("kind", a.kind)
                    put("locator", a.locatorJson)
                    a.text?.let { put("text", it) }
                    a.note?.let { put("note", it) }
                    a.tint?.let { put("tint", it) }
                    a.chapter?.let { put("chapter", it) }
                    a.position?.let { put("position", it) }
                    a.totalProgression?.let { put("progression", it) }
                    put("created_at", a.createdAt)
                },
            )
        }
        entry.put("annotations", marks)
        out.put(entry)
    }
    root.put("books", out)
    return root.toString(2)
}

/**
 * Reads a backup back.
 *
 * Anything unrecognisable is reported rather than thrown, because the
 * person who picked the file is standing there waiting to be told what
 * happened to it, and "nothing" is not an answer.
 */
fun decodeAnnotationBackup(json: String): BackupContents {
    val root = try {
        JSONObject(json)
    } catch (e: org.json.JSONException) {
        return BackupContents.Unreadable(e.message ?: "not a backup file")
    }
    if (root.optInt("format", 0) > FORMAT) {
        return BackupContents.Unreadable("made by a newer version of Liseur")
    }
    val books = root.optJSONArray("books")
        ?: return BackupContents.Unreadable("no books in this file")

    val out = mutableListOf<BackedUpBook>()
    for (i in 0 until books.length()) {
        val entry = books.optJSONObject(i) ?: continue
        val bookId = entry.optString("book_id").takeIf { it.isNotEmpty() } ?: continue
        val marks = entry.optJSONArray("annotations") ?: JSONArray()
        val annotations = mutableListOf<BookAnnotation>()
        for (j in 0 until marks.length()) {
            val m = marks.optJSONObject(j) ?: continue
            val id = m.optString("id").takeIf { it.isNotEmpty() } ?: continue
            annotations += BookAnnotation(
                id = id,
                bookId = bookId,
                kind = m.optString("kind").ifEmpty { "HIGHLIGHT" },
                locatorJson = m.optString("locator"),
                text = m.optStringOrNull("text"),
                note = m.optStringOrNull("note"),
                tint = m.optStringOrNull("tint"),
                chapter = m.optStringOrNull("chapter"),
                position = if (m.has("position")) m.optInt("position") else null,
                totalProgression = if (m.has("progression")) m.optDouble("progression") else null,
                createdAt = m.optLong("created_at"),
            )
        }
        out += BackedUpBook(
            bookId = bookId,
            title = entry.optStringOrNull("title"),
            author = entry.optStringOrNull("author"),
            annotations = annotations,
        )
    }
    return BackupContents.Readable(out)
}

/**
 * Which book on this device a backed-up book's marks belong to.
 *
 * A book from calibre-web is known by the same identity everywhere, so
 * it matches outright. A file added by hand is not: the same book copied
 * onto another phone sits at a different path, so it is matched by what
 * it is called instead. Nothing matching means the marks are for a book
 * that is not here — they are kept as they are, and come back into view
 * if that book ever arrives.
 */
fun matchBackedUpBook(
    backedUp: BackedUpBook,
    known: List<KnownBook>,
): String {
    if (known.any { it.bookId == backedUp.bookId }) return backedUp.bookId
    val title = backedUp.title?.trim()?.lowercase() ?: return backedUp.bookId
    val author = backedUp.author?.trim()?.lowercase()
    val sameTitle = known.filter { it.title.trim().lowercase() == title }
    return when {
        sameTitle.isEmpty() -> backedUp.bookId
        sameTitle.size == 1 -> sameTitle.single().bookId
        // More than one book of that name: only an author settles it, and
        // guessing between them would attach a stranger's marks to a book.
        else -> sameTitle
            .singleOrNull { it.author?.trim()?.lowercase() == author }
            ?.bookId
            ?: backedUp.bookId
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

/**
 * How much of a backup would land on books that are actually here.
 *
 * The counting is the same `matchBackedUpBook` an import runs, so the
 * preview cannot promise what the import would not deliver. Pure so it
 * can be answered without a phone.
 */
fun previewBackupMatch(
    contents: BackupContents.Readable,
    known: List<KnownBook>,
): BackupMatch {
    var matchedBooks = 0
    var matchedMarks = 0
    for (book in contents.books) {
        val target = matchBackedUpBook(book, known)
        if (target != book.bookId || known.any { it.bookId == book.bookId }) {
            matchedBooks += 1
            matchedMarks += book.annotations.size
        }
    }
    return BackupMatch(
        books = contents.books.size,
        marks = contents.books.sumOf { it.annotations.size },
        matchedBooks = matchedBooks,
        matchedMarks = matchedMarks,
    )
}

/** What a backup would do here, counted. */
data class BackupMatch(
    val books: Int,
    val marks: Int,
    /** Books whose marks would land somewhere in this library. */
    val matchedBooks: Int,
    val matchedMarks: Int,
)
