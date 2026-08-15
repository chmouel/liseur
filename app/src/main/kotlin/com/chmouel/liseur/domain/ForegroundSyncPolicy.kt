package com.chmouel.liseur.domain

import com.chmouel.liseur.data.db.RemoteServer

/** How long a completed full sync stands for before the app asks again. */
const val FOREGROUND_SYNC_FRESH_FOR_MS = 60L * 60L * 1000L

/**
 * Whether opening the app should ask the server where the reader got to.
 *
 * Android keeps a process alive for days and then throws it away without
 * asking, so "have we synced yet" cannot be answered from memory: coming
 * back to a killed app looks exactly like a first start. The last
 * completed run is written down, so a new process can tell the
 * difference and a shelf full of books is not re-reconciled every time
 * the system decides to reclaim some memory.
 *
 * The gesture that means "look again", and the hourly worker, do not ask
 * this. They are asking on purpose.
 */
fun shouldSyncOnForeground(
    server: RemoteServer?,
    now: Long,
    freshForMs: Long = FOREGROUND_SYNC_FRESH_FOR_MS,
): Boolean {
    return server != null && server.canSync &&
        due(server.positionSyncedAt, now, freshForMs)
}

private fun due(syncedAt: Long?, now: Long, freshForMs: Long): Boolean {
    if (syncedAt == null) return true
    // A clock put back — a timezone fixed, a phone that lost its
    // battery — leaves a stamp in the future. Waiting for it to come
    // round again would be waiting for as long as the clock was wrong.
    if (syncedAt > now) return true
    return now - syncedAt >= freshForMs
}
