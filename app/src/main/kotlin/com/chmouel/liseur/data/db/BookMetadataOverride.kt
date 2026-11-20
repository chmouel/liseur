package com.chmouel.liseur.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

object MetadataOverrideFields {
    const val TITLE = "title"
    const val AUTHOR = "author"
    const val DESCRIPTION = "description"
    const val COVER = "cover"

    val TEXT_FIELDS = listOf(TITLE, AUTHOR, DESCRIPTION)
    val ALL_FIELDS = listOf(TITLE, AUTHOR, DESCRIPTION, COVER)
}

@Entity(tableName = "metadata_override_revision", primaryKeys = ["book_url", "field"])
data class MetadataOverrideRevision(
    @ColumnInfo(name = "book_url") val bookUrl: String,
    val field: String,
    val revision: Long = 0,
    @ColumnInfo(name = "retired_revision") val retiredRevision: Long = 0,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0,
) {
    fun owed(state: MetadataOverrideSync?): Boolean =
        revision > maxOf(retiredRevision, state?.ackedRevision ?: 0)
}

@Entity(tableName = "metadata_override_sync", primaryKeys = ["peer_id", "book_url", "field"])
data class MetadataOverrideSync(
    @ColumnInfo(name = "peer_id") val peerId: String,
    @ColumnInfo(name = "book_url") val bookUrl: String,
    val field: String,
    @ColumnInfo(name = "work_id") val workId: String,
    @ColumnInfo(name = "acked_revision") val ackedRevision: Long = 0,
    @ColumnInfo(name = "sent_json") val sentJson: String? = null,
    @ColumnInfo(name = "sent_revision") val sentRevision: Long? = null,
    @ColumnInfo(name = "polled_at", defaultValue = "0") val polledAt: Long = 0,
    @ColumnInfo(name = "remote_cover_sha256") val remoteCoverSha256: String? = null,
    @ColumnInfo(name = "remote_cover_updated_at", defaultValue = "0") val remoteCoverUpdatedAt: Long = 0,
)

@Entity(tableName = "metadata_override_poll")
data class MetadataOverridePoll(
    @PrimaryKey @ColumnInfo(name = "peer_id") val peerId: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
)

@Dao
interface BookMetadataOverrideDao {
    @Query("SELECT * FROM metadata_override_revision WHERE book_url = :bookUrl ORDER BY field")
    suspend fun revisions(bookUrl: String): List<MetadataOverrideRevision>

    @Query("SELECT * FROM metadata_override_revision WHERE book_url = :bookUrl AND field = :field")
    suspend fun revision(bookUrl: String, field: String): MetadataOverrideRevision?

    @Query("SELECT * FROM metadata_override_revision ORDER BY book_url, field")
    suspend fun allRevisions(): List<MetadataOverrideRevision>

    @Upsert
    suspend fun upsertRevision(row: MetadataOverrideRevision)

    @Query("SELECT * FROM metadata_override_sync WHERE peer_id = :peerId")
    suspend fun states(peerId: String): List<MetadataOverrideSync>

    @Query("SELECT * FROM metadata_override_sync WHERE peer_id = :peerId AND book_url = :bookUrl AND field = :field")
    suspend fun state(peerId: String, bookUrl: String, field: String): MetadataOverrideSync?

    @Upsert
    suspend fun upsertState(row: MetadataOverrideSync)

    @Query("UPDATE metadata_override_sync SET work_id = '' WHERE peer_id = :peerId AND book_url = :bookUrl AND work_id = :workId")
    suspend fun invalidateWork(peerId: String, bookUrl: String, workId: String)

    @Query("SELECT * FROM metadata_override_poll WHERE peer_id = :peerId")
    suspend fun poll(peerId: String): MetadataOverridePoll?

    @Upsert
    suspend fun upsertPoll(poll: MetadataOverridePoll)

    @Query("SELECT COALESCE(MAX(polled_at), 0) FROM metadata_override_sync WHERE peer_id = :peerId")
    suspend fun latestPoll(peerId: String): Long

    @Query("DELETE FROM metadata_override_poll WHERE peer_id = :peerId")
    suspend fun deletePoll(peerId: String)

    @Query(
        """
        SELECT (SELECT COUNT(*) FROM metadata_override_sync WHERE peer_id = :peerId) +
               (SELECT COUNT(*) FROM metadata_override_poll WHERE peer_id = :peerId)
        """,
    )
    suspend fun countForPeer(peerId: String): Int

    @Query("UPDATE metadata_override_sync SET peer_id = :to WHERE peer_id = :from")
    suspend fun rekeyStates(from: String, to: String)

    @Query("UPDATE metadata_override_poll SET peer_id = :to WHERE peer_id = :from")
    suspend fun rekeyPoll(from: String, to: String)

    @Transaction
    suspend fun rekeyPeer(from: String, to: String) {
        rekeyStates(from, to)
        rekeyPoll(from, to)
    }

    @Query("UPDATE metadata_override_revision SET retired_revision = revision")
    suspend fun retireEdits()

    @Query("DELETE FROM metadata_override_sync WHERE peer_id = :peerId")
    suspend fun deletePeer(peerId: String)

    @Transaction
    suspend fun forgetPeer(peerId: String) {
        retireEdits()
        deletePeer(peerId)
        deletePoll(peerId)
    }

    @Query("DELETE FROM metadata_override_revision WHERE book_url = :bookUrl")
    suspend fun deleteRevisionsForBook(bookUrl: String)

    @Query("DELETE FROM metadata_override_sync WHERE book_url = :bookUrl")
    suspend fun deleteStatesForBook(bookUrl: String)

    @Transaction
    suspend fun deleteForBook(bookUrl: String) {
        deleteRevisionsForBook(bookUrl)
        deleteStatesForBook(bookUrl)
    }
}
