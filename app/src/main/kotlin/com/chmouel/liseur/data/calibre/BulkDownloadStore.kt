package com.chmouel.liseur.data.calibre

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.bulkDownloadStore: DataStore<Preferences> by preferencesDataStore(
    name = "bulk_download",
)

/**
 * Remembers the one bulk download that is current, or was last.
 *
 * This is a second source of truth about work WorkManager also knows
 * about, and it earns its place by outliving it in both directions.
 * WorkManager forgets a batch's rows once it prunes them, which would
 * take the closing "N of M downloaded" with it; and a worker that stops
 * a batch cancels itself in the act, so its own output data cannot be
 * relied on to carry the reason why. Both survive here.
 *
 * Only what cannot be derived is kept. Which books are in the batch is
 * not: the tagged work items carry their own book URL, and while the
 * batch is live they are the authoritative membership.
 */
class BulkDownloadStore(private val context: Context) {

    private object Keys {
        val BATCH_ID = stringPreferencesKey("batch_id")
        val TOTAL = intPreferencesKey("total")
        val SETTLED = booleanPreferencesKey("settled")
        val DONE = intPreferencesKey("done")
        val FAILED = intPreferencesKey("failed")
        val STOP_REASON = stringPreferencesKey("stop_reason")
    }

    val batch: Flow<BulkBatch?> = context.bulkDownloadStore.data.map { p ->
        val id = p[Keys.BATCH_ID] ?: return@map null
        BulkBatch(
            id = id,
            total = p[Keys.TOTAL] ?: 0,
            settled = p[Keys.SETTLED] ?: false,
            done = p[Keys.DONE] ?: 0,
            failed = p[Keys.FAILED] ?: 0,
            stopReason = BulkStopReason.fromId(p[Keys.STOP_REASON]),
        )
    }

    suspend fun current(): BulkBatch? = batch.first()

    /** Opens a batch, clearing whatever the last one left behind. */
    suspend fun start(id: String, total: Int) {
        context.bulkDownloadStore.edit { p ->
            p[Keys.BATCH_ID] = id
            p[Keys.TOTAL] = total
            p[Keys.SETTLED] = false
            p[Keys.DONE] = 0
            p[Keys.FAILED] = 0
            p.remove(Keys.STOP_REASON)
        }
    }

    /**
     * Corrects the total once membership is known.
     *
     * The batch is opened before its work is enqueued — a worker that
     * starts before the record exists has no way to tell an unopened
     * batch from a finished one, and stands down — so the total it is
     * opened with is the selection, and this narrows it to what was
     * actually accepted.
     */
    suspend fun setTotal(batchId: String, total: Int) {
        context.bulkDownloadStore.edit { p ->
            if (p[Keys.BATCH_ID] != batchId || p[Keys.SETTLED] == true) return@edit
            p[Keys.TOTAL] = total
        }
    }

    /**
     * Records why a batch is stopping, before anything is cancelled.
     *
     * The order matters: the worker that notices trouble is about to be
     * cancelled along with its siblings, so the reason has to be durable
     * before the cancellation goes out. Written only for the batch that
     * is still current, and only once — the first reason is the real
     * one, and later workers noticing the same wall are echoes.
     */
    suspend fun recordStopReason(batchId: String, reason: BulkStopReason): Boolean {
        var recorded = false
        context.bulkDownloadStore.edit { p ->
            if (p[Keys.BATCH_ID] != batchId || p[Keys.SETTLED] == true) return@edit
            if (p[Keys.STOP_REASON] != null) return@edit
            p[Keys.STOP_REASON] = reason.id
            recorded = true
        }
        return recorded
    }

    /** Closes a batch, keeping the counts that outlive its work rows. */
    suspend fun settle(batchId: String, done: Int, failed: Int) {
        context.bulkDownloadStore.edit { p ->
            if (p[Keys.BATCH_ID] != batchId) return@edit
            p[Keys.SETTLED] = true
            p[Keys.DONE] = done
            p[Keys.FAILED] = failed
        }
    }

    /** Forgets the last batch, once the reader has seen how it went. */
    suspend fun clear() {
        context.bulkDownloadStore.edit { it.clear() }
    }
}
