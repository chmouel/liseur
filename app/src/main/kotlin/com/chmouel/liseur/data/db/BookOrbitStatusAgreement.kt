package com.chmouel.liseur.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Query
import androidx.room.Upsert

/** BookOrbit's status baseline and one durable status-only request. */
@Entity(
    tableName = "book_orbit_status_agreement",
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
data class BookOrbitStatusAgreement(
    @ColumnInfo(name = "account_key") val accountKey: String,
    @ColumnInfo(name = "book_url") val bookUrl: String,
    @ColumnInfo(name = "book_id") val bookId: Long,
    @ColumnInfo(name = "binding_revision") val bindingRevision: Long,
    @ColumnInfo(name = "connection_epoch") val connectionEpoch: Long,
    @ColumnInfo(name = "base_url") val baseUrl: String,
    @ColumnInfo(name = "agreed_local_status_revision", defaultValue = "0")
    val agreedLocalStatusRevision: Long = 0,
    @ColumnInfo(name = "agreed_local_override", defaultValue = "0")
    val agreedLocalOverride: Int = 0,
    @ColumnInfo(name = "agreed_remote_status") val agreedRemoteStatus: String? = null,
    @ColumnInfo(name = "agreed_remote_source") val agreedRemoteSource: String? = null,
    @ColumnInfo(name = "outgoing_bytes") val outgoingBytes: ByteArray? = null,
    @ColumnInfo(name = "sent_status") val sentStatus: String? = null,
    @ColumnInfo(name = "sent_local_status_revision") val sentLocalStatusRevision: Long? = null,
    @ColumnInfo(name = "sent_local_override") val sentLocalOverride: Int? = null,
    @ColumnInfo(name = "preflight_remote_status") val preflightRemoteStatus: String? = null,
    @ColumnInfo(name = "preflight_remote_source") val preflightRemoteSource: String? = null,
    @ColumnInfo(name = "attempt_state") val attemptState: String? = null,
    @ColumnInfo(name = "attempt_generation", defaultValue = "0") val attemptGeneration: Long = 0,
)

@Dao
interface BookOrbitStatusAgreementDao {
    @Query("SELECT * FROM book_orbit_status_agreement WHERE account_key = :accountKey AND book_url = :bookUrl")
    suspend fun get(accountKey: String, bookUrl: String): BookOrbitStatusAgreement?

    @Upsert
    suspend fun write(row: BookOrbitStatusAgreement)

    @Query("UPDATE book_orbit_status_agreement SET connection_epoch = :epoch, base_url = :baseUrl WHERE account_key = :accountKey")
    suspend fun rebindConnection(accountKey: String, epoch: Long, baseUrl: String)

    @Query("DELETE FROM book_orbit_status_agreement WHERE account_key = :accountKey")
    suspend fun clearAccount(accountKey: String)
}
