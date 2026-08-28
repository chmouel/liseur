package com.chmouel.liseur.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.kosync.KosyncCredentials
import kotlinx.coroutines.flow.Flow

/**
 * The kosync partner reading positions are also kept in step with.
 *
 * A row of its own rather than more columns on `remote_server`, because
 * the two connections have separate lives: kosync is configured
 * *alongside* whatever catalog server is connected — that is the whole
 * point for Grimmory, whose Komga shim carries no position — and
 * disconnecting one must not take the other with it.
 *
 * Only one row ever exists ([SINGLE_ID]). What is stored is the derived
 * auth key, never the password: the key is what travels on the wire, so
 * the password itself has no business being on disk. Sealed with the
 * Keystore like every other credential; see `CredentialCipher`.
 */
@Entity(tableName = "kosync_peer")
data class KosyncPeer(
    @PrimaryKey val id: Long = SINGLE_ID,
    /** The protocol's mount root, e.g. `https://host/api/koreader`. */
    @ColumnInfo(name = "base_url") val baseUrl: String,
    val username: String,
    /** The Keystore-sealed hex-MD5 auth key. */
    @ColumnInfo(name = "key_cipher") val keyCipher: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "position_synced_at") val positionSyncedAt: Long? = null,
) {
    /**
     * How to sign a kosync request, or null when the key cannot be read
     * back — a database restored onto another phone arrives with
     * ciphertext this Keystore cannot open.
     */
    @get:Ignore
    val credentials: KosyncCredentials?
        get() = CredentialCipher.decrypt(keyCipher)?.let { KosyncCredentials(username, it) }

    /**
     * Who this partner's agreements belong to.
     *
     * The key `sync_peer_state` rows are stored under. It names the
     * account rather than the protocol, so signing in as a different
     * kosync user strands the old agreements rather than adopting them —
     * the same rule every other kind of server follows.
     */
    @get:Ignore
    val accountKey: String
        get() = "kosync|$baseUrl|$username"

    companion object {
        const val SINGLE_ID = 1L

        /** Wraps a freshly derived key for storage. */
        fun seal(key: String): String = CredentialCipher.encrypt(key)
    }
}

@Dao
interface KosyncPeerDao {
    @Query("SELECT * FROM kosync_peer WHERE id = :id")
    fun observe(id: Long = KosyncPeer.SINGLE_ID): Flow<KosyncPeer?>

    @Query("SELECT * FROM kosync_peer WHERE id = :id")
    suspend fun get(id: Long = KosyncPeer.SINGLE_ID): KosyncPeer?

    @Upsert
    suspend fun upsert(peer: KosyncPeer)

    /**
     * Marks how far a run got, touching nothing else — writing the whole
     * row back would carry the copy of the credentials the run started
     * with over one stored in the meantime.
     */
    @Query("UPDATE kosync_peer SET position_synced_at = :at WHERE id = :id")
    suspend fun setPositionSyncedAt(at: Long, id: Long = KosyncPeer.SINGLE_ID)

    @Query("DELETE FROM kosync_peer WHERE id = :id")
    suspend fun delete(id: Long = KosyncPeer.SINGLE_ID)
}
