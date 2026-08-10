package com.chmouel.liseur.data.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How the last position sync went, wherever it went.
 *
 * Each partner reports here instead of keeping its own flows, so nothing
 * downstream is wired to a particular kind of server. With more than one
 * partner there is more than one answer, so each is kept under its own
 * name and the screen is given the one summary true of all of them:
 * still going if any is, broken if any is, and settled only when nobody
 * has anything to complain about.
 */
class SyncReporting {

    private data class PeerReport(
        val status: PositionSyncStatus = PositionSyncStatus.Idle,
        val report: SyncReport = SyncReport(),
    )

    private var peers: Map<String, PeerReport> = emptyMap()

    private val _status = MutableStateFlow<PositionSyncStatus>(PositionSyncStatus.Idle)
    val status: StateFlow<PositionSyncStatus> = _status.asStateFlow()

    private val _report = MutableStateFlow(SyncReport())
    val report: StateFlow<SyncReport> = _report.asStateFlow()

    /** What one partner alone last did, for a screen that names them. */
    fun statusOf(peerId: String): PositionSyncStatus =
        peers[peerId]?.status ?: PositionSyncStatus.Idle

    fun report(status: PositionSyncStatus, peerId: String = PeerPositionSync.CATALOG) {
        update(peerId) { it.copy(status = status) }
    }

    fun report(report: SyncReport, peerId: String = PeerPositionSync.CATALOG) {
        update(peerId) { it.copy(report = report) }
    }

    /** Corrects the count of unsettled disagreements on its own. */
    fun reportUnresolved(count: Int, peerId: String = PeerPositionSync.CATALOG) {
        update(peerId) { it.copy(report = it.report.copy(unresolved = count)) }
    }

    /** Drops a partner that is no longer connected. */
    @Synchronized
    fun forget(peerId: String) {
        peers = peers - peerId
        publish()
    }

    @Synchronized
    private fun update(peerId: String, change: (PeerReport) -> PeerReport) {
        peers = peers + (peerId to change(peers[peerId] ?: PeerReport()))
        publish()
    }

    private fun publish() {
        val all = peers.values
        _status.value = summarise(all.map { it.status })
        _report.value = all.map { it.report }.fold(SyncReport()) { total, one ->
            SyncReport(
                at = listOfNotNull(total.at, one.at).maxOrNull(),
                pulled = total.pulled + one.pulled,
                pushed = total.pushed + one.pushed,
                unresolved = total.unresolved + one.unresolved,
            )
        }
    }

    /**
     * What several partners' states add up to.
     *
     * A failure is never hidden by somebody else's success: the point of
     * showing this is to explain why a book is not where it should be,
     * and "synced" while one server is refusing the sign-in explains
     * nothing. "Nothing to sync here" is only claimed when that is true
     * of every partner.
     */
    private fun summarise(statuses: List<PositionSyncStatus>): PositionSyncStatus = when {
        statuses.isEmpty() -> PositionSyncStatus.Idle
        statuses.any { it is PositionSyncStatus.Syncing } -> PositionSyncStatus.Syncing
        statuses.any { it is PositionSyncStatus.Failed } ->
            statuses.filterIsInstance<PositionSyncStatus.Failed>().first()

        statuses.any { it is PositionSyncStatus.Synced } ->
            PositionSyncStatus.Synced(
                statuses.filterIsInstance<PositionSyncStatus.Synced>().maxOf { it.at },
            )

        statuses.all { it is PositionSyncStatus.Unavailable } -> PositionSyncStatus.Unavailable
        else -> PositionSyncStatus.Idle
    }
}
