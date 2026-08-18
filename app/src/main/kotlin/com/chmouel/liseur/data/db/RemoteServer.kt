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
import com.chmouel.liseur.data.remote.ServerKind
import kotlinx.coroutines.flow.Flow

/**
 * The server the library is connected to. Only one row ever exists
 * ([SINGLE_ID]), but the table is keyed so support for several servers
 * can be added without another migration.
 *
 * [kind] decides which half of this row is meaningful. calibre-web signs
 * in with [username] and [passwordCipher] and syncs over the Kobo
 * protocol, so it is the only kind that has a [koboTokenCipher], a
 * [userId] or a [syncToken]. Komga signs in with [apiKeyCipher] alone.
 * liseur-sync signs in with a device token it minted at setup, kept in
 * [liseurTokenCipher], and its sync cursor is [syncCursorSeq].
 *
 * Secrets are stored as Keystore-encrypted bytes. All of them are needed
 * back in the clear on every request — one for Basic auth, one for a
 * header, one because it is part of the sync URL — so this protects them
 * at rest only; see `CredentialCipher`.
 */
@Entity(tableName = "remote_server")
data class RemoteServer(
    @PrimaryKey val id: Long = SINGLE_ID,
    val kind: ServerKind,
    @ColumnInfo(name = "base_url") val baseUrl: String,
    /** The login for calibre-web, or the account's name for Komga. */
    val username: String?,
    @ColumnInfo(name = "password_cipher") val passwordCipher: String?,
    @ColumnInfo(name = "api_key_cipher") val apiKeyCipher: String?,
    /** Who the server says we are, when it says so as a string. */
    @ColumnInfo(name = "account_id") val accountId: String?,
    @ColumnInfo(name = "user_id") val userId: Int?,
    @ColumnInfo(name = "kobo_token") val koboTokenCipher: String?,
    @ColumnInfo(name = "can_download") val canDownload: Boolean,
    @ColumnInfo(name = "can_manage_library") val canManageLibrary: Boolean = false,
    @ColumnInfo(name = "can_upload") val canUpload: Boolean = false,
    @ColumnInfo(name = "can_admin") val canAdmin: Boolean = false,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "catalog_synced_at") val catalogSyncedAt: Long?,
    @ColumnInfo(name = "position_synced_at") val positionSyncedAt: Long?,
    @ColumnInfo(name = "sync_token") val syncToken: String?,
    /**
     * The liseur-sync device token; null for every other kind.
     */
    @ColumnInfo(name = "liseur_token_cipher") val liseurTokenCipher: String? = null,
    /**
     * liseur-sync's stable account id (ADR-0016 follow-up), when the
     * server said it.
     *
     * Unlike [accountId] — the *device* id, which a replacement token
     * changes — this survives a credential rotation, which is what lets
     * a re-pasted token be told apart from a different person signing
     * in. Null until the server reports it; the older spellings in
     * [accountKey] cover rows from before.
     */
    @ColumnInfo(name = "liseur_account_id") val liseurAccountId: String? = null,
    /**
     * How far through the liseur-sync op log this device has reconciled.
     *
     * The only irreplaceable sync state: advance it in the same
     * transaction that writes the page it covers, never before.
     */
    @ColumnInfo(name = "sync_cursor_seq", defaultValue = "0") val syncCursorSeq: Long = 0,
) {
    /** The Kobo sync token in the clear, or null if there is none to read. */
    @get:Ignore
    val koboToken: String? get() = koboTokenCipher?.let(CredentialCipher::decrypt)

    /**
     * How to sign a request to this server, or null when the secret
     * cannot be read back — a database restored onto another phone
     * arrives with ciphertext this Keystore cannot open.
     */
    @get:Ignore
    val credentials: RemoteCredentials?
        get() = when (kind) {
            ServerKind.CALIBRE ->
                passwordCipher?.let(CredentialCipher::decrypt)
                    ?.let { RemoteCredentials.Basic(username.orEmpty(), it) }

            ServerKind.KOMGA ->
                apiKeyCipher?.let(CredentialCipher::decrypt)?.let(RemoteCredentials::ApiKey)

            ServerKind.LISEUR_SYNC ->
                liseurTokenCipher?.let(CredentialCipher::decrypt)?.let(RemoteCredentials::Bearer)
        }

    /**
     * True once positions can be exchanged with this server.
     *
     * calibre-web needs a Kobo token first, and getting one can fail
     * without the account being any less usable for reading. Komga and
     * liseur-sync sync over the same API they do everything else with,
     * so there is nothing extra to obtain.
     */
    @get:Ignore
    val canSync: Boolean
        get() = when (kind) {
            ServerKind.CALIBRE -> koboTokenCipher != null
            ServerKind.KOMGA, ServerKind.LISEUR_SYNC -> true
        }

    /**
     * Who this account is, for stamping reading positions with.
     *
     * Reading state is per-user on both servers, so the same server
     * signed into as two people is two separate worlds. Anything derived
     * from a server is tagged with this, and a tag from someone else is
     * never treated as our own.
     *
     * The calibre-web spelling is fixed: it is already written into
     * every `owner_account` on every phone that has synced, and changing
     * it would make everyone's own reading look like a stranger's.
     */
    @get:Ignore
    val accountKey: String
        get() = when (kind) {
            ServerKind.CALIBRE -> "$baseUrl|$username|${userId ?: -1}"
            ServerKind.KOMGA -> "$baseUrl|$username|${accountId ?: "-1"}"
            // The liseur-sync spelling is the one the old sync-only
            // account already wrote into `sync_peer_state` and
            // `work_alias`, and the migration carries it across: the
            // token's device id when known, the login when not. The
            // stable account id outranks both once the server reports
            // it, so a rotated token no longer reads as a new account.
            ServerKind.LISEUR_SYNC ->
                "liseursync|$baseUrl|${liseurAccountId ?: accountId ?: username}"
        }

    companion object {
        const val SINGLE_ID = 1L

        /** Wraps a freshly fetched secret for storage. */
        fun seal(secret: String?): String? = secret?.let(CredentialCipher::encrypt)
    }
}

@Dao
interface RemoteServerDao {
    @Query("SELECT * FROM remote_server WHERE id = :id")
    fun observe(id: Long = RemoteServer.SINGLE_ID): Flow<RemoteServer?>

    @Query("SELECT * FROM remote_server WHERE id = :id")
    suspend fun get(id: Long = RemoteServer.SINGLE_ID): RemoteServer?

    @Upsert
    suspend fun upsert(server: RemoteServer)

    @Query("UPDATE remote_server SET kobo_token = :cipher WHERE id = :id")
    suspend fun setKoboTokenCipher(cipher: String?, id: Long = RemoteServer.SINGLE_ID)

    /**
     * Marks how far a run got, touching nothing else.
     *
     * Writing the whole row back would carry with it the copy of the
     * credentials the run started with, which is how a password changed
     * in the meantime gets quietly replaced by the old one.
     */
    @Query("UPDATE remote_server SET catalog_synced_at = :at WHERE id = :id")
    suspend fun setCatalogSyncedAt(at: Long, id: Long = RemoteServer.SINGLE_ID)

    @Query("UPDATE remote_server SET position_synced_at = :at WHERE id = :id")
    suspend fun setPositionSyncedAt(at: Long, id: Long = RemoteServer.SINGLE_ID)

    @Query("UPDATE remote_server SET sync_token = :token WHERE id = :id")
    suspend fun setSyncToken(token: String?, id: Long = RemoteServer.SINGLE_ID)

    @Query("UPDATE remote_server SET can_download = :allowed WHERE id = :id")
    suspend fun setCanDownload(allowed: Boolean, id: Long = RemoteServer.SINGLE_ID)

    @Query("UPDATE remote_server SET can_upload = :allowed WHERE id = :id")
    suspend fun setCanUpload(allowed: Boolean, id: Long = RemoteServer.SINGLE_ID)

    /**
     * Moves the liseur-sync cursor, touching nothing else.
     *
     * The cursor only ever advances alongside the page it covers, inside
     * the caller's transaction; a bare row write would risk carrying a
     * stale copy of the credentials over a freshly stored one.
     */
    @Query("UPDATE remote_server SET sync_cursor_seq = :seq WHERE id = :id")
    suspend fun setSyncCursor(seq: Long, id: Long = RemoteServer.SINGLE_ID)

    @Query("DELETE FROM remote_server WHERE id = :id")
    suspend fun delete(id: Long = RemoteServer.SINGLE_ID)
}
