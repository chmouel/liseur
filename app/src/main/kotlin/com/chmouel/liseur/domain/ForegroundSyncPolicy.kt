package com.chmouel.liseur.domain

import com.chmouel.liseur.data.db.KosyncPeer
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
 * The kosync partner is asked about on its own terms: it lives alongside
 * a Custom catalog. It runs only where the connected server is one the
 * pairing belongs to
 * ([com.chmouel.liseur.data.remote.ServerKind.hostsKosyncPeer]) — a peer
 * left behind by an account switch or restored with a backup would
 * otherwise wake a sync against a server nobody paired it with. With no
 * server connected the same question is put to
 * [externalLibrarySyncActive] (ADR-0078): switching to Drive retires the
 * Custom row and keeps the pairing, which is still the reader's.
 *
 * The gesture that means "look again", and the hourly worker, do not ask
 * this. They are asking on purpose.
 */
fun shouldSyncOnForeground(
    server: RemoteServer?,
    now: Long,
    freshForMs: Long = FOREGROUND_SYNC_FRESH_FOR_MS,
    kosync: KosyncPeer? = null,
    externalLibrarySyncActive: Boolean = false,
): Boolean {
    val catalogDue = server != null && server.canSync &&
        due(server.positionSyncedAt, now, freshForMs)
    val kosyncDue = kosync != null &&
        hostsKosyncPairing(server, externalLibrarySyncActive) &&
        due(kosync.positionSyncedAt, now, freshForMs)
    return catalogDue || kosyncDue
}

/**
 * Whether anything could carry reading positions: the connected server
 * itself, or a kosync pairing on a server — or a backend — that hosts
 * one. Without either, asking for a sync has nothing to talk to.
 */
fun syncsPositions(
    server: RemoteServer?,
    kosync: KosyncPeer?,
    externalLibrarySyncActive: Boolean = false,
): Boolean = server?.canSync == true ||
    (kosync != null && hostsKosyncPairing(server, externalLibrarySyncActive))

/**
 * Whether what is connected may host a kosync pairing. The same question
 * [com.chmouel.liseur.data.kosync.KosyncPositionSync] opens every run
 * with, so a pairing the sync would use is a pairing the app asks to run.
 */
private fun hostsKosyncPairing(server: RemoteServer?, externalLibrarySyncActive: Boolean): Boolean =
    if (server == null) externalLibrarySyncActive else server.kind.hostsKosyncPeer

private fun due(syncedAt: Long?, now: Long, freshForMs: Long): Boolean {
    if (syncedAt == null) return true
    // A clock put back — a timezone fixed, a phone that lost its
    // battery — leaves a stamp in the future. Waiting for it to come
    // round again would be waiting for as long as the clock was wrong.
    if (syncedAt > now) return true
    return now - syncedAt >= freshForMs
}
