package com.chmouel.liseur.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert
import com.chmouel.liseur.data.remote.CatalogContinuation
import com.chmouel.liseur.data.remote.CatalogStep
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where a starter OPDS catalog should resume discovery.
 *
 * Keyed by account and catalog address so a reconnect or a different Custom
 * catalog cannot inherit a queue that belonged to another shelf.
 */
@Entity(tableName = "starter_catalog_progress", primaryKeys = ["account_key", "catalog_url"])
data class StarterCatalogProgress(
    @ColumnInfo(name = "account_key") val accountKey: String,
    @ColumnInfo(name = "catalog_url") val catalogUrl: String,
    @ColumnInfo(name = "queue_json") val queueJson: String,
    @ColumnInfo(name = "seen_json") val seenJson: String,
    val exhausted: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    val continuation: CatalogContinuation
        get() = CatalogContinuation(
            queue = JSONArray(queueJson).let { array ->
                List(array.length()) { index ->
                    val item = array.getJSONObject(index)
                    CatalogStep(
                        url = item.getString("url"),
                        depth = item.getInt("depth"),
                    )
                }
            },
            seen = JSONArray(seenJson).let { array ->
                buildSet {
                    repeat(array.length()) { index -> add(array.getString(index)) }
                }
            },
        )

    companion object {
        fun of(
            accountKey: String,
            catalogUrl: String,
            continuation: CatalogContinuation,
            exhausted: Boolean,
            updatedAt: Long = System.currentTimeMillis(),
        ): StarterCatalogProgress = StarterCatalogProgress(
            accountKey = accountKey,
            catalogUrl = catalogUrl,
            queueJson = JSONArray().also { array ->
                continuation.queue.forEach { step ->
                    array.put(
                        JSONObject()
                            .put("url", step.url)
                            .put("depth", step.depth),
                    )
                }
            }.toString(),
            seenJson = JSONArray().also { array ->
                continuation.seen.forEach(array::put)
            }.toString(),
            exhausted = exhausted,
            updatedAt = updatedAt,
        )
    }
}

@Dao
interface StarterCatalogProgressDao {
    @Query(
        """
        SELECT * FROM starter_catalog_progress
        WHERE account_key = :accountKey AND catalog_url = :catalogUrl
        """,
    )
    suspend fun get(accountKey: String, catalogUrl: String): StarterCatalogProgress?

    @Query(
        """
        SELECT * FROM starter_catalog_progress
        WHERE account_key = :accountKey AND catalog_url = :catalogUrl
        """,
    )
    fun observe(accountKey: String, catalogUrl: String): Flow<StarterCatalogProgress?>

    @Upsert
    suspend fun upsert(progress: StarterCatalogProgress)

    @Query("DELETE FROM starter_catalog_progress WHERE account_key = :accountKey")
    suspend fun deleteForAccount(accountKey: String)

    @Query("DELETE FROM starter_catalog_progress")
    suspend fun deleteAll()
}
