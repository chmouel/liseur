package com.chmouel.liseur.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * The calibre-web server the library is connected to. Only one row ever
 * exists ([SINGLE_ID]), but the table is keyed so support for several
 * servers can be added without another migration.
 *
 * The password is stored as Keystore-encrypted bytes because Basic auth
 * needs it back in the clear on every request; see `CredentialCipher`.
 */
@Entity(tableName = "calibre_server")
data class CalibreServer(
    @PrimaryKey val id: Long = SINGLE_ID,
    @ColumnInfo(name = "base_url") val baseUrl: String,
    val username: String,
    @ColumnInfo(name = "password_cipher") val passwordCipher: String,
    @ColumnInfo(name = "user_id") val userId: Int?,
    @ColumnInfo(name = "kobo_token") val koboToken: String?,
    @ColumnInfo(name = "can_download") val canDownload: Boolean,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "catalog_synced_at") val catalogSyncedAt: Long?,
    @ColumnInfo(name = "position_synced_at") val positionSyncedAt: Long?,
    @ColumnInfo(name = "sync_token") val syncToken: String?,
) {
    /** True once a Kobo sync token has been obtained for this account. */
    val canSync: Boolean get() = koboToken != null

    companion object {
        const val SINGLE_ID = 1L
    }
}

@Dao
interface CalibreServerDao {
    @Query("SELECT * FROM calibre_server WHERE id = :id")
    fun observe(id: Long = CalibreServer.SINGLE_ID): Flow<CalibreServer?>

    @Query("SELECT * FROM calibre_server WHERE id = :id")
    suspend fun get(id: Long = CalibreServer.SINGLE_ID): CalibreServer?

    @Upsert
    suspend fun upsert(server: CalibreServer)

    @Query("UPDATE calibre_server SET kobo_token = :token WHERE id = :id")
    suspend fun setKoboToken(token: String?, id: Long = CalibreServer.SINGLE_ID)

    @Query("UPDATE calibre_server SET can_download = :allowed WHERE id = :id")
    suspend fun setCanDownload(allowed: Boolean, id: Long = CalibreServer.SINGLE_ID)

    @Query("DELETE FROM calibre_server WHERE id = :id")
    suspend fun delete(id: Long = CalibreServer.SINGLE_ID)
}
