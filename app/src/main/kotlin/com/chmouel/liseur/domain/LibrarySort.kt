package com.chmouel.liseur.domain

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.DownloadState

/** The orders the library grid can be arranged in. */
enum class LibrarySort(val id: String) {
    /** Reading, then on the device, then everything else. */
    RECENT("recent"),
    TITLE("title"),
    AUTHOR("author"),

    /** When the book joined the library. */
    ADDED("added"),
    ;

    companion object {
        val Default = RECENT

        fun fromId(id: String?): LibrarySort = entries.firstOrNull { it.id == id } ?: Default
    }
}

/**
 * Articles that should not decide where a book files.
 *
 * English and French, which is what the app is translated into; a book
 * called *The Whale* belongs under W, the way it would on a shelf.
 */
private val ARTICLES = setOf(
    "the", "a", "an",
    "le", "la", "les", "un", "une", "des", "du", "de",
)

private val ELIDED = listOf("l'", "l\u2019", "d'", "d\u2019")

/**
 * The string a title or an author actually files under: lower case,
 * trimmed, and without a leading article.
 *
 * Only a *leading* article goes, and only when something follows it, so
 * a book called *The* still files under T rather than vanishing to the
 * top of the list.
 */
fun sortKey(text: String?): String {
    val trimmed = text?.trim()?.lowercase().orEmpty()
    if (trimmed.isEmpty()) return ""

    ELIDED.firstOrNull { trimmed.startsWith(it) }?.let { elision ->
        val rest = trimmed.removePrefix(elision).trimStart()
        return rest.ifEmpty { trimmed }
    }

    val space = trimmed.indexOf(' ')
    if (space <= 0) return trimmed
    val first = trimmed.substring(0, space)
    if (first !in ARTICLES) return trimmed
    val rest = trimmed.substring(space + 1).trimStart()
    return rest.ifEmpty { trimmed }
}

/**
 * How near the top of the shelf a book sits in [LibrarySort.RECENT]:
 * something you are part way through, then something you could open right
 * now, then everything else.
 *
 * A book with a reading position counts as started even if it has never
 * been opened on this device, which is how a book picked up on another
 * phone finds its way to the front of this one's shelf.
 */
private fun Book.recentRank(readAt: Long?): Int = when {
    lastOpenedAt != null || readAt != null -> 0
    downloadState == DownloadState.DOWNLOADED -> 1
    else -> 2
}

/**
 * The moment a book was last worth looking at, for [LibrarySort.RECENT].
 *
 * Opening a book here and reading it elsewhere are both reasons for it
 * to rise, so the later of the two wins. Opening without reading still
 * counts, and so does reading without ever having opened it here.
 */
private fun Book.recentAt(readAt: Long?): Long {
    val opened = lastOpenedAt
    val read = readAt
    return when {
        opened != null && read != null -> maxOf(opened, read)
        else -> opened ?: read ?: downloadedAt ?: addedAt
    }
}

/**
 * Puts the library in the asked-for order.
 *
 * [reversed] flips whichever direction the order reads in naturally:
 * newest first for dates, A to Z for names. Every order finishes on the
 * title, so two books that are otherwise equal keep the same places
 * between launches instead of swapping about.
 *
 * A book with no author files last whichever way round the list is: an
 * unknown author is not a name, and pretending it sorts before A only
 * buries the books that do have one.
 */
fun List<Book>.arrangedBy(
    sort: LibrarySort,
    reversed: Boolean = false,
    readAt: Map<String, Long> = emptyMap(),
): List<Book> {
    val byTitle = compareBy<Book> { sortKey(it.title) }

    val comparator = when (sort) {
        LibrarySort.RECENT -> compareBy<Book> { it.recentRank(readAt[it.url]) }
            .thenByDescending { it.recentAt(readAt[it.url]) }
            .let { if (reversed) it.reversed() else it }
            .then(byTitle)

        LibrarySort.TITLE ->
            (if (reversed) byTitle.reversed() else byTitle)

        LibrarySort.AUTHOR -> {
            val named = compareBy<Book> { sortKey(it.author) }
            val ordered = if (reversed) named.reversed() else named
            compareBy<Book> { if (it.author.isNullOrBlank()) 1 else 0 }
                .then(ordered)
                .then(byTitle)
        }

        LibrarySort.ADDED -> compareByDescending<Book> { it.addedAt }
            .let { if (reversed) it.reversed() else it }
            .then(byTitle)
    }

    return sortedWith(comparator)
}
