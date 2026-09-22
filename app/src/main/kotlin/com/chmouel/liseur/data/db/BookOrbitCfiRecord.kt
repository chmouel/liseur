package com.chmouel.liseur.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Query
import androidx.room.Upsert

/** Foreign source data only: never an acknowledged or locally restored position. */
@Entity(
    tableName = "book_orbit_cfi",
    primaryKeys = ["account_key", "book_url"],
    foreignKeys = [
        ForeignKey(
            entity = BookOrbitBinding::class,
            parentColumns = ["account_key", "book_url"],
            childColumns = ["account_key", "book_url"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
)
data class BookOrbitCfiRecord(
    @ColumnInfo(name = "account_key") val accountKey: String,
    @ColumnInfo(name = "book_url") val bookUrl: String,
    @ColumnInfo(name = "book_id") val bookId: Long,
    @ColumnInfo(name = "file_id") val fileId: Long,
    @ColumnInfo(name = "binding_revision") val bindingRevision: Long,
    @ColumnInfo(name = "raw_cfi") val rawCfi: String,
)

@Dao
interface BookOrbitCfiDao {
    @Query("SELECT * FROM book_orbit_cfi WHERE account_key = :accountKey AND book_url = :bookUrl")
    suspend fun get(accountKey: String, bookUrl: String): BookOrbitCfiRecord?

    @Upsert
    suspend fun write(record: BookOrbitCfiRecord)

    @Query("DELETE FROM book_orbit_cfi WHERE account_key = :accountKey")
    suspend fun clearAccount(accountKey: String)
}
