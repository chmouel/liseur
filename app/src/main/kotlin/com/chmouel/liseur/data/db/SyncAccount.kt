package com.chmouel.liseur.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.remote.RemoteCredentials
import kotlinx.coroutines.flow.Flow

/**
 * A liseur-sync server: somewhere to keep reading positions that is not
 * where the books came from.
 *
 * Deliberately its own row rather than another [RemoteServer]. A catalog
 * server is browsed and downloaded from and syncs as a side effect; this
 * one has no books at all, is connected to *as well as* a catalog server
 * rather than instead of one, and is the only partner a book that came
 * off an SD card can have.
 *
 * Two secrets are kept because the server scopes them apart: a token
 * that may sync cannot read statistics, and it is better to hold two
 * narrow tokens than to ask for one that can do everything. Both are
 * Keystore-encrypted at rest and are needed back in the clear on every
 * request; see `CredentialCipher`.
 */
@Entity(tableName = "sync_account")
data class SyncAccount(
    @PrimaryKey val id: Long = SINGLE_ID,
    @ColumnInfo(name = "base_url") val baseUrl: String,
    val username: String,
    /** The device token that pushes and pulls positions. */
    @ColumnInfo(name = "token_cipher") val tokenCipher: String,
    /** A second, narrower token that may only read statistics. */
    @ColumnInfo(name = "insights_token_cipher") val insightsTokenCipher: String? = null,
    /** What this device is called on the server's own device list. */
    @ColumnInfo(name = "device_name") val deviceName: String,
    /**
     * The server's own name for this device, when it told us.
     *
     * Only used to recognise this device's own ops coming back around.
     * Null for a token pasted in from elsewhere, which says nothing
     * about whose device it is — and that is survivable, because an op
     * of ours replayed back describes the position we last pushed, which
     * is the baseline, so nothing follows from it.
     */
    @ColumnInfo(name = "device_id") val deviceId: String? = null,
    /**
     * This device, as this device knows itself.
     *
     * Part of every op id, so that two phones sitting at the same
     * revision of the same book do not name the same op and silence each
     * other. It is the local identity rather than the server's precisely
     * so that it exists before the server has said anything.
     */
    @ColumnInfo(name = "device_key", defaultValue = "") val deviceKey: String = "",
    /**
     * How far through the op log this device has reconciled.
     *
     * Zero means "everything", which is what a freshly connected account
     * wants: the server replays its whole history and this device works
     * out what to do with it.
     */
    @ColumnInfo(name = "cursor_seq", defaultValue = "0") val cursorSeq: Long = 0,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "synced_at") val syncedAt: Long? = null,
) {
    /**
     * How to sign a sync request, or null when the secret cannot be read
     * back — a database restored onto another phone arrives with
     * ciphertext this Keystore cannot open.
     */
    @get:Ignore
    val credentials: RemoteCredentials?
        get() = CredentialCipher.decrypt(tokenCipher)?.let(RemoteCredentials::Bearer)

    /** How to sign a request for statistics, if that was ever granted. */
    @get:Ignore
    val insightsCredentials: RemoteCredentials?
        get() = insightsTokenCipher
            ?.let(CredentialCipher::decrypt)
            ?.let(RemoteCredentials::Bearer)

    /**
     * Who reading agreed with this server belongs to.
     *
     * The same host signed into as two people is two partners, so this
     * carries the login as well as the address. It is written into
     * `sync_peer_state`, which makes it as good as schema: changing the
     * spelling would make one person's own reading look like a
     * stranger's and quietly stop syncing it.
     */
    @get:Ignore
    val peerId: String get() = "liseursync|$baseUrl|$username"

    companion object {
        const val SINGLE_ID = 1L
    }
}

@Dao
interface SyncAccountDao {

    @Query("SELECT * FROM sync_account WHERE id = :id")
    fun observe(id: Long = SyncAccount.SINGLE_ID): Flow<SyncAccount?>

    @Query("SELECT * FROM sync_account WHERE id = :id")
    suspend fun get(id: Long = SyncAccount.SINGLE_ID): SyncAccount?

    @Upsert
    suspend fun upsert(account: SyncAccount)

    /**
     * Moves the cursor, touching nothing else.
     *
     * Writing the whole row back would carry with it the copy of the
     * token the run started with, which is how a token replaced in the
     * meantime gets quietly reverted to the old one.
     */
    @Query("UPDATE sync_account SET cursor_seq = :seq WHERE id = :id")
    suspend fun setCursor(seq: Long, id: Long = SyncAccount.SINGLE_ID)

    @Query("UPDATE sync_account SET synced_at = :at WHERE id = :id")
    suspend fun setSyncedAt(at: Long, id: Long = SyncAccount.SINGLE_ID)

    @Query("DELETE FROM sync_account WHERE id = :id")
    suspend fun delete(id: Long = SyncAccount.SINGLE_ID)
}
