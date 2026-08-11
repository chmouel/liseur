package com.chmouel.liseur.domain

import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.SyncAccount

/** How long a completed full sync stands for before the app asks again. */
const val FOREGROUND_SYNC_FRESH_FOR_MS = 60L * 60L * 1000L

/**
 * Whether opening the app should ask the servers where the reader got to.
 *
 * Android keeps a process alive for days and then throws it away without
 * asking, so "have we synced yet" cannot be answered from memory: coming
 * back to a killed app looks exactly like a first start. The last
 * completed run is written down, so a new process can tell the
 * difference and a shelf full of books is not re-reconciled every time
 * the system decides to reclaim some memory.
 *
 * There are up to two peers — a catalog server and a sync server — and
 * a full sync runs against both, so it is due when either of them has
 * gone stale. Asking only the catalog would leave a liseur-sync-only
 * setup never syncing on the way in.
 *
 * The gesture that means "look again", and the hourly worker, do not ask
 * this. They are asking on purpose.
 */
fun shouldSyncOnForeground(
    server: RemoteServer?,
    syncAccount: SyncAccount?,
    now: Long,
    freshForMs: Long = FOREGROUND_SYNC_FRESH_FOR_MS,
): Boolean {
    val catalogDue = server != null && server.canSync &&
        due(server.positionSyncedAt, now, freshForMs)
    val peerDue = syncAccount != null &&
        due(syncAccount.syncedAt, now, freshForMs)
    return catalogDue || peerDue
}

private fun due(syncedAt: Long?, now: Long, freshForMs: Long): Boolean {
    if (syncedAt == null) return true
    // A clock put back — a timezone fixed, a phone that lost its
    // battery — leaves a stamp in the future. Waiting for it to come
    // round again would be waiting for as long as the clock was wrong.
    if (syncedAt > now) return true
    return now - syncedAt >= freshForMs
}
