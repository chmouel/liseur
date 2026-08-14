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
/**
 * Where the reader put the book, when they have put it anywhere.
 *
 * A null [name] is an answer rather than a gap: it is "this book is in
 * no series", said out loud, which is why it is kept instead of falling
 * back to whatever the file or the server thought. Nothing said at all
 * is the absence of the whole override, not a null name inside one.
 */
data class SeriesOverride(val name: String?, val index: Double?)

/**
 * Which series a book is actually filed under, and where in it.
 *
 * The reader outranks both the server and the file, and stays
 * outranking them: a catalog refresh that arrives an hour later must
 * not quietly undo the filing. That is why the override is a layer of
 * its own rather than a write into [SeriesMetadata.name] — a write
 * would last until the next sync and no longer.
 *
 * The two halves are answered separately. [name] is the shelf the book
 * is on, [index] is where it sits on that shelf, and reordering a shelf
 * has to set the second without touching the first: overriding the name
 * as well would freeze it, so a series later renamed on the server
 * would keep its old name on the one book that had been dragged.
 *
 * Three rules fall out of that:
 *
 * - A null effective name forces a null effective index. An index
 *   belongs to nothing on its own and would file the book under an
 *   empty series.
 * - A name override with no index override yields **no** index. The
 *   source's number belongs to the series the source thinks the book is
 *   in, and a book filed somewhere else by hand is not in that series;
 *   volume 4 of *The Expanse* refiled into *Star Wars* is not volume 4
 *   of *Star Wars*. (Checking whether the source's series is the same
 *   one is not open to us: [mergeSeries] resolves name and index
 *   independently, so which source supplied the index is already gone,
 *   and the pair can be a catalog name beside a file index.)
 * - The server's id is left as it was, because it names a series on the
 *   server and the override has not moved anything there. Whether it is
 *   still the right thing to ask about is the shelf's business, not
 *   this function's.
 */
fun effectiveSeries(
    name: SeriesOverride?,
    index: Double?,
    indexOverridden: Boolean,
    source: SeriesMetadata,
): SeriesMetadata {
    val effectiveName = if (name != null) name.name else source.name
    val effectiveIndex = when {
        effectiveName == null -> null
        indexOverridden -> index
        // The source numbered the book for a series it may no longer be
        // in, and there is no way left to tell.
        name != null -> null
        else -> source.index
    }
    return SeriesMetadata(name = effectiveName, index = effectiveIndex, id = source.id)
}

fun mergeSeries(catalog: SeriesMetadata, file: SeriesMetadata): SeriesMetadata =
    SeriesMetadata(
        name = catalog.name ?: file.name,
        // An index without a name belongs to nothing and would file the
        // book under an empty series, so it is only kept alongside one.
        index = (catalog.index ?: file.index)
            .takeIf { catalog.name != null || file.name != null },
        id = catalog.id,
    )
