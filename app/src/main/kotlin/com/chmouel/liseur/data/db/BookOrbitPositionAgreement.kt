package com.chmouel.liseur.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Query
import androidx.room.Upsert

/** BookOrbit-only position baseline and one durable, exact request; never status agreement. */
@Entity(
    tableName = "book_orbit_position_agreement",
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
data class BookOrbitPositionAgreement(
    @ColumnInfo(name = "account_key") val accountKey: String,
    @ColumnInfo(name = "book_url") val bookUrl: String,
    @ColumnInfo(name = "book_id") val bookId: Long,
    @ColumnInfo(name = "file_id") val fileId: Long,
    @ColumnInfo(name = "binding_revision") val bindingRevision: Long,
    @ColumnInfo(name = "connection_epoch") val connectionEpoch: Long,
    @ColumnInfo(name = "base_url") val baseUrl: String,
    @ColumnInfo(name = "agreed_local_revision") val agreedLocalRevision: Long? = null,
    @ColumnInfo(name = "agreed_locator_json") val agreedLocatorJson: String? = null,
    @ColumnInfo(name = "agreed_remote_cfi") val agreedRemoteCfi: String? = null,
    @ColumnInfo(name = "agreed_remote_percentage") val agreedRemotePercentage: Double? = null,
    @ColumnInfo(name = "agreed_remote_saved") val agreedRemoteSaved: Boolean? = null,
    @ColumnInfo(name = "candidate_cfi") val candidateCfi: String? = null,
    @ColumnInfo(name = "candidate_percentage") val candidatePercentage: Double? = null,
    @ColumnInfo(name = "candidate_saved") val candidateSaved: Boolean? = null,
    @ColumnInfo(name = "candidate_updated_at") val candidateUpdatedAt: String? = null,
    @ColumnInfo(name = "outgoing_bytes") val outgoingBytes: ByteArray? = null,
    @ColumnInfo(name = "sent_local_revision") val sentLocalRevision: Long? = null,
    @ColumnInfo(name = "sent_locator_json") val sentLocatorJson: String? = null,
    @ColumnInfo(name = "preflight_cfi") val preflightCfi: String? = null,
    @ColumnInfo(name = "preflight_percentage") val preflightPercentage: Double? = null,
    @ColumnInfo(name = "preflight_saved") val preflightSaved: Boolean? = null,
    @ColumnInfo(name = "attempt_state") val attemptState: String? = null,
    @ColumnInfo(name = "attempt_generation", defaultValue = "0") val attemptGeneration: Long = 0,
)

@Dao
interface BookOrbitPositionAgreementDao {
    @Query("SELECT * FROM book_orbit_position_agreement WHERE account_key = :accountKey AND book_url = :bookUrl")
    suspend fun get(accountKey: String, bookUrl: String): BookOrbitPositionAgreement?

    @Upsert
    suspend fun write(row: BookOrbitPositionAgreement)

    @Query("UPDATE book_orbit_position_agreement SET connection_epoch = :epoch, base_url = :baseUrl WHERE account_key = :accountKey")
    suspend fun rebindConnection(accountKey: String, epoch: Long, baseUrl: String)

    @Query("DELETE FROM book_orbit_position_agreement WHERE account_key = :accountKey")
    suspend fun clearAccount(accountKey: String)
}
