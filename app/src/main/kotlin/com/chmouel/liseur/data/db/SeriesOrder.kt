package com.chmouel.liseur.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.chmouel.liseur.domain.seriesKey

/**
 * The writes that put a series in the order the reader wants it.
 *
 * Its own DAO, and an abstract class rather than an interface, because
 * the two interesting methods have bodies: they check that the shelf
 * still holds the books they were asked to number *inside* the same
 * transaction as the numbering, which is not something a `@Query` can
 * express.
 */
@Dao
abstract class SeriesOrderDao {

    /**
     * Every book on the shelf.
     *
     * All of them, deliberately. Which books make up a series is decided
     * by [seriesKey] — accent-folded, case-folded, leading article
     * stripped — and no SQL comparison reproduces that: `=` and
     * `COLLATE NOCASE` would both call *L'Épée* and *Epee* different
     * series and renumber half a shelf. Narrowing by raw name first is
     * the same wrong comparison made earlier, dropping exactly the rows
     * the folding exists to catch. The library already holds every book
     * in memory to group them, so reading them here costs nothing new.
     */
    @Query("SELECT url, series_name FROM books WHERE archived_at IS NULL")
    protected abstract suspend fun shelvedSeriesNames(): List<ShelvedSeries>

    /**
     * Sets where a book sits in its series, saying nothing about which
     * series that is.
     *
     * The name is left to whoever was already deciding it, so a series
     * renamed on the server is still renamed here on a book that has
     * been dragged.
     */
    @Query(
        """
        UPDATE books
        SET user_series_index = :index,
            series_index_override = 1,
            user_series_updated_at = :updatedAt,
            series_index = CASE WHEN series_name IS NULL THEN NULL ELSE :index END
        WHERE url = :url
        """,
    )
    protected abstract suspend fun setIndexOverride(url: String, index: Double?, updatedAt: Long)

    /**
     * Gives the numbering of these books back to their sources.
     *
     * A name override survives and takes the number down with it: the
     * catalog's index belongs to the series the catalog thinks the book
     * is in, and a book filed here by hand is not in that series, so it
     * comes back unnumbered rather than carrying a number from
     * somewhere else.
     *
     * `series_index` is rewritten in the same statement because it is
     * what the shelf reads. Clearing the flag alone would change nothing
     * on screen until some unrelated refresh happened to rewrite the
     * row.
     */
    @Query(
        """
        UPDATE books
        SET user_series_index = NULL,
            series_index_override = 0,
            series_index = CASE
                WHEN series_override = 1 THEN NULL
                WHEN COALESCE(catalog_series_name, file_series_name) IS NULL THEN NULL
                ELSE COALESCE(catalog_series_index, file_series_index)
            END
        WHERE url IN (:urls)
        """,
    )
    protected abstract suspend fun clearIndexOverrides(urls: List<String>)

    /**
     * Numbers a series 1…n in the order given.
     *
     * Every volume is written, not only the ones whose number changed.
     * A row left alone keeps no override, which means its source still
     * owns it, which means the next catalog refresh can move it back out
     * of the order that was just set — the shelf would rearrange itself
     * hours later for no visible reason. Committing the whole sequence
     * is what makes the order hold.
     *
     * Returns false, having written nothing, when the shelf no longer
     * holds exactly the books it was asked to number. The alternative is
     * guessing: numbering a book the reader never saw, or numbering
     * around the hole where one they placed used to be.
     */
    @Transaction
    open suspend fun renumber(key: String, urlsInOrder: List<String>): Boolean {
        if (!stillHolds(key, urlsInOrder)) return false
        val now = System.currentTimeMillis()
        urlsInOrder.forEachIndexed { i, url -> setIndexOverride(url, (i + 1).toDouble(), now) }
        return true
    }

    /** Clears the whole shelf's numbering, under the same guard. */
    @Transaction
    open suspend fun clearOrder(key: String, urls: List<String>): Boolean {
        if (!stillHolds(key, urls)) return false
        clearIndexOverrides(urls)
        return true
    }

    private suspend fun stillHolds(key: String, urls: List<String>): Boolean {
        val onTheShelf = shelvedSeriesNames()
            .filter { seriesKey(it.seriesName) == key }
            .mapTo(mutableSetOf()) { it.url }
        return onTheShelf == urls.toSet() && urls.size == onTheShelf.size
    }
}

/** Just enough of a book to work out which series it is on. */
data class ShelvedSeries(
    val url: String,
    @androidx.room.ColumnInfo(name = "series_name") val seriesName: String?,
)
