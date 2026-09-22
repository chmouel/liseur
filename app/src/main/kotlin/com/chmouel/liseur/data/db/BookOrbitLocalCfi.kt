package com.chmouel.liseur.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Query
import androidx.room.Upsert

/** A verified local CFI paired with exactly one saved Readium position. */
@Entity(
    tableName = "book_orbit_local_cfi",
    primaryKeys = ["account_key", "book_url"],
    indices = [Index(value = ["book_url"])],
    foreignKeys = [
        ForeignKey(
            entity = BookOrbitBinding::class,
            parentColumns = ["account_key", "book_url"],
            childColumns = ["account_key", "book_url"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ReadingProgress::class,
            parentColumns = ["book_url"],
            childColumns = ["book_url"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class BookOrbitLocalCfi(
    @ColumnInfo(name = "account_key") val accountKey: String,
    @ColumnInfo(name = "book_url") val bookUrl: String,
    @ColumnInfo(name = "book_id") val bookId: Long,
    @ColumnInfo(name = "file_id") val fileId: Long,
    @ColumnInfo(name = "binding_revision") val bindingRevision: Long,
    @ColumnInfo(name = "local_revision") val localRevision: Long,
    @ColumnInfo(name = "locator_json") val locatorJson: String,
    @ColumnInfo(name = "raw_cfi") val rawCfi: String,
)

@Dao
interface BookOrbitLocalCfiDao {
    @Query("SELECT * FROM book_orbit_local_cfi WHERE account_key = :accountKey AND book_url = :bookUrl")
    suspend fun get(accountKey: String, bookUrl: String): BookOrbitLocalCfi?

    @Upsert
    suspend fun write(record: BookOrbitLocalCfi)

    @Query("DELETE FROM book_orbit_local_cfi WHERE book_url = :bookUrl")
    suspend fun clearBook(bookUrl: String)

    @Query("DELETE FROM book_orbit_local_cfi WHERE account_key = :accountKey")
    suspend fun clearAccount(accountKey: String)
}
