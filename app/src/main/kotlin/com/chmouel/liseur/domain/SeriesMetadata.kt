package com.chmouel.liseur.domain

/**
 * What a source managed to say about a book's series.
 *
 * The two halves are separately absent on purpose: a calibre-web feed
 * that names the series without an index, and an EPUB that carries an
 * index for a series it never names, are both real and both worth half
 * an answer.
 */
data class SeriesMetadata(
    val name: String? = null,
    val index: Double? = null,
    /** The server's own id, which only Komga has. */
    val id: String? = null,
) {
    val isEmpty: Boolean get() = name == null && index == null && id == null

    companion object {
        val None = SeriesMetadata()
    }
}

/**
 * Which answer to keep when a book is described twice.
 *
 * A downloaded calibre-web book has a series from the feed and another
 * from its own OPF, and they need not agree. The rule lives here, once,
 * for the same reason [ReadingStateMerge] does: written per provider it
 * would be written differently per provider, and a book would belong to
 * a different series depending on which server it came from.
 *
 * [catalog] is the copy the reader curates on the server, so it wins
 * where it has anything to say. It wins field by field rather than
 * wholesale: a feed that named the series but not the index takes the
 * index from the file rather than throwing it away.
 *
 * A book with no catalog entry left — one unlinked because it vanished
 * from the server, or one that never came from a server at all — keeps
 * what it has. Unlinking takes the server's id with it, because that is
 * the one thing that is only true while the link is; it must not take
 * the series itself.
 */
fun mergeSeries(catalog: SeriesMetadata, file: SeriesMetadata): SeriesMetadata =
    SeriesMetadata(
        name = catalog.name ?: file.name,
        // An index without a name belongs to nothing and would file the
        // book under an empty series, so it is only kept alongside one.
        index = (catalog.index ?: file.index)
            .takeIf { catalog.name != null || file.name != null },
        id = catalog.id,
    )
