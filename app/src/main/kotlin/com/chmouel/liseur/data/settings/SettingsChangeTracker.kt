package com.chmouel.liseur.data.settings

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Watches the settings this device syncs and notes when one changes.
 *
 * Settings sync needs two different facts about a setting, and only one
 * of them can be worked out at sync time. *Whether* a value differs from
 * what the server agreed to is answered by comparing it against the
 * stored baseline, whenever anyone cares to ask. *When* the reader
 * changed it cannot be recovered after the fact — and it is the half
 * that decides conflicts, because last-writer-wins has to mean the last
 * writer and not the last device to find a network.
 *
 * Without this, a change made on a plane on Monday would go out stamped
 * Friday and beat another device's Thursday edit, which is exactly the
 * mistake `docs/adr/0032-imported-reading-keeps-its-own-time.md` forbids
 * for reading positions.
 *
 * One collector rather than twenty-odd instrumented setters: the two
 * DataStores already publish every write, so the change is visible
 * without asking each call site to remember to report it.
 *
 * The stamp it records is **advisory**. What gets pushed is still
 * decided by comparing values against the agreed baseline, so a stamp
 * left behind by the sync pass writing a pulled value — which this
 * collector sees like any other write — is never acted on, and the two
 * need no locking between them.
 */
class SettingsChangeTracker(
    private val syncState: SettingsSyncRepository,
    private val settings: List<SyncableSetting>,
    private val sources: List<Flow<*>>,
    private val now: () -> Long = System::currentTimeMillis,
) {

    fun start(scope: CoroutineScope) {
        scope.launch {
            // Conflated: a burst of writes only needs the settings as
            // they end up, and the stamp is the same either way.
            merge(*sources.toTypedArray()).conflate().collect {
                try {
                    note()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "Could not note a settings change: ${e.message}")
                }
            }
        }
    }

    /** Reads every syncable setting once and records what moved. */
    suspend fun note() {
        syncState.observeLocal(settings.associate { it.key to it.read() }, now())
    }

    private companion object {
        const val TAG = "SettingsTracker"
    }
}
