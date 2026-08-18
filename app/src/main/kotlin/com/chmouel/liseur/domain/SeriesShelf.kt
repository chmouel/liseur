package com.chmouel.liseur.domain

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.DownloadState

/** One volume of a series, and how far into it the reader has got. */
data class SeriesVolume(
    val book: Book,
    val progression: Double?,
) {
    val index: Double? get() = book.seriesIndex

    val finished: Boolean get() = book.finished

    /** Begun but not done: what "continue the series" is really asking after. */
    val inProgress: Boolean
        get() = !finished && (progression ?: 0.0) > 0.0

    val onDevice: Boolean
        get() = book.openableUrl != null || book.downloadState == DownloadState.DOWNLOADED
}

/**
 * A series as the library can see it: the books it holds, in order, and
 * what can be said about them together.
 *
 * Derived from the books rather than stored, so it is the same shape
 * whether the volumes came from calibre-web, from Komga, or from a
 * folder of files, and so it is still right with the server unreachable.
 */
data class SeriesShelf(
    val key: String,
    /** The series name as its books spell it, for showing. */
    val name: String,
    val author: String?,
    val volumes: List<SeriesVolume>,
    /**
     * The volume the reader would open next: the one they are part way
     * through, failing that the first they have not begun, and failing
     * that nothing, because the series is finished.
     *
     * Being part way through wins over being unread. "Continue" means
     * the book that is open, not the next one along, even when a later
     * volume was started first and abandoned.
     */
    val nextUp: SeriesVolume?,
    /**
     * Numbers the series skips between the volumes that are here: 3,
     * when 2 and 4 are on the shelf and nothing is between them.
     *
     * Only what is missing *between* what you have. A series read as far
     * as volume 4 of fourteen is not missing ten books; it is four books
     * in, and saying otherwise turns a shelf into a list of complaints.
     * What comes after the last volume is only knowable when a server
     * says how many there are, and that belongs to the screen, not here.
     */
    val gaps: List<Double>,
) {
    val finishedCount: Int get() = volumes.count { it.finished }

    val inProgressCount: Int get() = volumes.count { it.inProgress }

    /** Volumes named by a server that are not on the device yet. */
    val missing: List<SeriesVolume> get() = volumes.filter { !it.onDevice }

    /** The cover to show for the whole series. */
    val cover: Book get() = (nextUp ?: volumes.first()).book

    val complete: Boolean get() = volumes.isNotEmpty() && volumes.all { it.finished }
}

/**
 * The shelves worth putting on screen.
 *
 * A shelf of one is not a series, it is one book wearing a series card:
 * a tap that opens a screen showing exactly what the library already
 * showed. They arrive by the dozen — one for every standalone with a
 * series field filled in, one for every first volume bought before its
 * second.
 *
 * This narrows the *showing* only, which is why it is not folded into
 * [groupedIntoSeries]. A series created by hand on its first book has to
 * stay offerable to the second one, and a grouping that had already
 * dropped it could never be added to.
 *
 * A volume a server knows about but that is not downloaded is a row in
 * the shelf like any other and counts towards the two. One book of
 * twelve is a series; the missing volumes are the point of showing it.
 */
fun List<SeriesShelf>.worthShowing(): List<SeriesShelf> =
    filter { it.volumes.size >= MIN_SERIES_VOLUMES }

/** How many books it takes to make a series. */
const val MIN_SERIES_VOLUMES = 2

/**
 * Sorts the volumes of one series.
 *
 * Numbered volumes first and in their numbers, then anything the source
 * would not place, by title. An unplaced book is nearly always a
 * companion or a collection, and burying it under the numbered volumes
 * is both where it belongs and where it does the least harm.
 */
private val byVolume: Comparator<SeriesVolume> =
    compareBy<SeriesVolume> { it.index == null }
        .thenBy { it.index ?: 0.0 }
        .thenBy { sortKey(it.book.title) }

/**
 * Gathers books into the series they belong to.
 *
 * Only books that name a series take part; everything else is not a
 * series of one and is left off. [progressions] is the reading position
 * of each book by URL, which is what tells a volume being read from one
 * merely downloaded.
 */
fun List<Book>.groupedIntoSeries(
    progressions: Map<String, Double> = emptyMap(),
): List<SeriesShelf> =
    filter { !it.seriesName.isNullOrBlank() }
        .groupBy { seriesKey(it.seriesName) }
        .filterKeys { it.isNotEmpty() }
        .map { (key, books) ->
            val volumes = books
                .map { SeriesVolume(it, progressions[it.url]) }
                .sortedWith(byVolume)
            SeriesShelf(
                key = key,
                name = commonest(books.mapNotNull { it.seriesName }) ?: key,
                author = commonest(books.mapNotNull { it.displayAuthor }),
                volumes = volumes,
                nextUp = volumes.firstOrNull { it.inProgress }
                    ?: volumes.firstOrNull { !it.finished },
                gaps = gapsBetween(volumes),
            )
        }
        .sortedWith(compareBy({ sortKey(it.name) }, { it.key }))

/**
 * The spelling most of the volumes agree on.
 *
 * Two servers, or a server and a file, can disagree about capitals or a
 * subtitle, and one odd volume should not rename the shelf. Ties go to
 * whichever came first, which keeps the answer settled between launches.
 */
private fun commonest(values: List<String>): String? =
    values.groupingBy { it }.eachCount().entries
        .maxWithOrNull(compareBy({ it.value }, { -values.indexOf(it.key) }))
        ?.key

/**
 * The whole numbers missing between the first and last volume that are
 * here.
 *
 * Whole numbers only: a series that runs 1, 1.5, 2 is not missing
 * anything, and a novella between two volumes is not evidence that
 * something has been skipped.
 */
private fun gapsBetween(volumes: List<SeriesVolume>): List<Double> {
    val whole = volumes.mapNotNull { it.index }
        .filter { it == Math.floor(it) && it >= 0 }
        .map { it.toLong() }
        .toSet()
    if (whole.size < 2) return emptyList()
    val first = whole.min()
    val last = whole.max()
    // A series numbered into the thousands is a mis-parse, not a
    // library, and walking it would hang the shelf drawing it.
    if (last - first > MAX_GAP_SPAN) return emptyList()
    return (first..last).filter { it !in whole }.map { it.toDouble() }
}

private const val MAX_GAP_SPAN = 500L

/**
 * What the endpaper can say after a volume is finished: the next number
 * that is missing, if that can be proved, and the next book in the
 * library that can actually be opened, even when that book sits past
 * the hole.
 *
 * The two answers are independent. A missing #2 does not hide a #3
 * already on the shelf, and a #3 on the shelf does not invent a title
 * for the #2 that is not there.
 */
data class SeriesContinuation(
    val missingIndex: Double? = null,
    val next: Book? = null,
)

/**
 * The book to offer once one is finished: the next volume along that
 * is in the library and has not been read.
 *
 * A volume already begun is still the next volume — continuing a series
 * means picking up where that book was left, not skipping it. Finished
 * and archived volumes are stepped over. A hole in the numbering is
 * reported separately as [SeriesContinuation.missingIndex] rather than
 * blocking the offer: #3 is still the next book in the library when #2
 * is not there.
 *
 * Null for anything uncertain: a book with no series, one with no
 * number, or nothing later on the shelf that can be opened. Whether the
 * file is on the device is not this function's business.
 */
@Suppress("UNUSED_PARAMETER")
fun nextInSeries(
    finished: Book,
    library: List<Book>,
    progressions: Map<String, Double> = emptyMap(),
): Book? = seriesContinuation(finished, library).next

/**
 * The missing volume immediately after [finished], and the first later
 * volume in the library that can still be read.
 *
 * A later numbered book proves the hole: #1 then #3 means #2 is
 * missing. #1 then #1.5 is a novella, not a gap; #1.5 then #3 proves
 * #2. Only that immediate missing number is named. An authoritative
 * series total can prove the same hole when no later book is on the
 * shelf; without a total, the number is not guessed.
 */
fun seriesContinuation(
    finished: Book,
    library: List<Book>,
    extras: SeriesExtras? = null,
): SeriesContinuation {
    val key = seriesKey(finished.seriesName)
    if (key.isEmpty()) return SeriesContinuation()
    val index = finished.seriesIndex ?: return SeriesContinuation()

    val later = library
        .asSequence()
        .filter { it.url != finished.url }
        .filter { seriesKey(it.seriesName) == key }
        .mapNotNull { book -> book.seriesIndex?.let { it to book } }
        .filter { (candidate, _) -> candidate > index }
        .sortedBy { (candidate, _) -> candidate }
        .toList()

    val expected = Math.floor(index) + 1.0
    val nextPresent = later.firstOrNull()?.first
    val missing = when {
        nextPresent != null -> expected.takeIf { nextPresent > expected }
        else -> extras?.totalBookCount
            ?.toDouble()
            ?.let { total -> expected.takeIf { it <= total } }
    }
    val next = later.firstOrNull { (_, book) -> !book.archived && !book.finished }?.second
    return SeriesContinuation(missingIndex = missing, next = next)
}

/**
 * The server id to ask for series extras.
 *
 * A local file filed with downloaded volumes has none of its own. The
 * shelf still might: another volume that came from the server carries
 * it, and that is the one the series screen uses.
 */
fun seriesIdForExtras(book: Book, library: List<Book>): String? {
    val key = seriesKey(book.seriesName)
    if (key.isEmpty()) return null
    return library.groupedIntoSeries()
        .firstOrNull { it.key == key }
        ?.volumes
        ?.firstNotNullOfOrNull { it.book.shelfSeriesId }
}

/**
 * Puts the series shelves in the order the library is set to.
 *
 * A series is ordered by the volume that speaks for it: the best-ranked
 * volume for Recent, or the one added most recently for Added. The
 * ranking stays the same as the ordinary shelf — reading first, then
 * books on the device, then books still on the server.
 */
fun List<SeriesShelf>.arrangedBy(
    sort: LibrarySort,
    reversed: Boolean = false,
    readAt: Map<String, Long> = emptyMap(),
): List<SeriesShelf> {
    val byName = compareBy<SeriesShelf>({ sortKey(it.name) }, { it.key })

    val comparator = when (sort) {
        LibrarySort.RECENT -> {
            val recent = compareBy<SeriesShelf> { shelf -> shelf.recentRank(readAt) }
                .thenByDescending { shelf -> shelf.recentAt(readAt) }
            (if (reversed) recent.reversed() else recent).then(byName)
        }

        LibrarySort.ADDED -> {
            val added = compareByDescending<SeriesShelf> { shelf ->
                shelf.volumes.maxOfOrNull { it.book.addedAt } ?: 0L
            }
            (if (reversed) added.reversed() else added).then(byName)
        }

        LibrarySort.AUTHOR -> {
            val named = compareBy<SeriesShelf> { sortKey(it.author) }
            val ordered = if (reversed) named.reversed() else named
            compareBy<SeriesShelf> { if (it.author.isNullOrBlank()) 1 else 0 }
                .then(ordered)
                .then(byName)
        }

        LibrarySort.TITLE, LibrarySort.SERIES ->
            if (reversed) byName.reversed() else byName
    }

    return sortedWith(comparator)
}

/** Best availability/reading rank held by any volume on the shelf. */
private fun SeriesShelf.recentRank(readAt: Map<String, Long>): Int =
    volumes.minOfOrNull { volume -> volume.book.recentRank(readAt[volume.book.url]) } ?: 2

/** Most recent timestamp among the volumes in the shelf's best rank. */
private fun SeriesShelf.recentAt(readAt: Map<String, Long>): Long {
    val rank = recentRank(readAt)
    return volumes.asSequence()
        .filter { volume -> volume.book.recentRank(readAt[volume.book.url]) == rank }
        .maxOfOrNull { volume -> volume.book.recentAt(readAt[volume.book.url]) }
        ?: 0L
}
