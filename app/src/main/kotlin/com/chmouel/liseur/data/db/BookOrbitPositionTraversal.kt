package com.chmouel.liseur.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Query
import androidx.room.Upsert

/** A finite account observation, including its report after the last page. */
@Entity(tableName = "book_orbit_position_traversal", primaryKeys = ["account_key"])
data class BookOrbitPositionTraversal(
    @ColumnInfo(name = "account_key") val accountKey: String,
    @ColumnInfo(name = "connection_epoch") val connectionEpoch: Long,
    @ColumnInfo(name = "base_url") val baseUrl: String,
    @ColumnInfo(name = "after_url") val afterUrl: String? = null,
    @ColumnInfo(name = "succeeded") val succeeded: Boolean = false,
    @ColumnInfo(name = "failure") val failure: String? = null,
    @ColumnInfo(name = "finished") val finished: Boolean = false,
)

/** Frozen membership and binding identity; catalog additions wait for the next traversal. */
@Entity(
    tableName = "book_orbit_position_traversal_item",
    primaryKeys = ["account_key", "book_url"],
    foreignKeys = [ForeignKey(
        entity = BookOrbitPositionTraversal::class,
        parentColumns = ["account_key"],
        childColumns = ["account_key"],
        onDelete = ForeignKey.CASCADE,
        onUpdate = ForeignKey.CASCADE,
    )],
)
data class BookOrbitPositionTraversalItem(
    @ColumnInfo(name = "account_key") val accountKey: String,
    @ColumnInfo(name = "book_url") val bookUrl: String,
    @ColumnInfo(name = "book_id") val bookId: Long,
    @ColumnInfo(name = "file_id") val fileId: Long,
    @ColumnInfo(name = "binding_revision") val bindingRevision: Long,
)

@Dao
interface BookOrbitPositionTraversalDao {
    @Query("SELECT * FROM book_orbit_position_traversal WHERE account_key = :accountKey")
    suspend fun get(accountKey: String): BookOrbitPositionTraversal?

    @Upsert
    suspend fun write(traversal: BookOrbitPositionTraversal)

    @Query("DELETE FROM book_orbit_position_traversal WHERE account_key = :accountKey")
    suspend fun clearAccount(accountKey: String)

    /**
     * Bindings outlive a book that left the catalog, so only those whose
     * book is still linked to the server are asked about.
     */
    @Query(
        "INSERT INTO book_orbit_position_traversal_item " +
            "(account_key, book_url, book_id, file_id, binding_revision) " +
            "SELECT account_key, book_url, book_id, file_id, revision FROM book_orbit_binding " +
            "WHERE account_key = :accountKey AND file_id IS NOT NULL " +
            "AND LOWER(file_format) = 'epub' AND state IN ('SELECTED', 'DOWNLOADED') " +
            "AND EXISTS (SELECT 1 FROM books WHERE books.url = book_orbit_binding.book_url " +
            "AND books.remote_uuid IS NOT NULL)",
    )
    suspend fun capture(accountKey: String)

    @Query(
        "SELECT * FROM book_orbit_position_traversal_item WHERE account_key = :accountKey " +
            "AND (:afterUrl IS NULL OR book_url > :afterUrl) ORDER BY book_url LIMIT :limit",
    )
    suspend fun page(accountKey: String, afterUrl: String?, limit: Int): List<BookOrbitPositionTraversalItem>
}
