package com.chmouel.liseur.data.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * How the last position sync went, wherever it went.
 *
 * There is one connected server, so there is one answer to "is it
 * syncing, and how did it go" — and the screen showing it should not
 * have to know which implementation produced it. Each [PositionSync]
 * writes here instead of keeping its own flows, so nothing downstream is
 * wired to a particular kind of server.
 */
class SyncReporting {
    private val _status = MutableStateFlow<PositionSyncStatus>(PositionSyncStatus.Idle)
    val status: StateFlow<PositionSyncStatus> = _status.asStateFlow()

    private val _report = MutableStateFlow(SyncReport())
    val report: StateFlow<SyncReport> = _report.asStateFlow()

    fun report(status: PositionSyncStatus) {
        _status.value = status
    }

    fun report(report: SyncReport) {
        _report.value = report
    }

    /** Corrects the count of unsettled disagreements on its own. */
    fun reportUnresolved(count: Int) {
        _report.update { it.copy(unresolved = count) }
    }
}
