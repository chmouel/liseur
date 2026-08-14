package com.chmouel.liseur.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * What a server had to say about a series, kept so it can be said again
 * offline.
 *
 * Decoration, all of it. Nothing here decides which books belong to a
 * series or what order they are in — that is worked out from the books
 * themselves, so that calibre-web, Komga and a folder of files all get
 * the same screen. This only fills in what one of them happens to know.
 *
 * Keyed by the server's own id for the series, because that is the only
 * name the request can be made with. The shelf is still gathered by
 * name.
 */
@Entity(tableName = "series_extra")
data class SeriesExtra(
    @PrimaryKey @ColumnInfo(name = "series_id") val seriesId: String,
    val summary: String?,
    val status: String?,
    @ColumnInfo(name = "total_book_count") val totalBookCount: Int?,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
)

@Dao
interface SeriesExtraDao {
    @Query("SELECT * FROM series_extra WHERE series_id = :seriesId")
    suspend fun get(seriesId: String): SeriesExtra?

    @Upsert
    suspend fun upsert(extra: SeriesExtra)

    /** Dropped along with the account whose server named these series. */
    @Query("DELETE FROM series_extra")
    suspend fun clear()
}
