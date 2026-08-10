package com.chmouel.liseur.data.library

import android.os.SystemClock
import com.chmouel.liseur.data.db.ReadingSessionDao
import com.chmouel.liseur.domain.ReadingSessionClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * App-lifetime owner of reading-session persistence.
 *
 * Recovery runs once before any new recorder can write. Recorders also use
 * the app scope, so the final pause queued by an Activity is not cancelled
 * merely because its ViewModel is cleared a moment later.
 */
class ReadingSessionManager(
    private val dao: ReadingSessionDao,
    private val scope: CoroutineScope,
    private val clockFactory: () -> ReadingSessionClock = ::ReadingSessionClock,
    private val wallNow: () -> Long = System::currentTimeMillis,
    private val elapsedNow: () -> Long = SystemClock::elapsedRealtime,
    private val checkpointIntervalMs: Long = ReadingSessionRecorder.CHECKPOINT_INTERVAL_MS,
) {
    private val recovery: Deferred<Unit> = scope.async {
        dao.closeInterruptedSessions()
    }

    fun recorder(bookUrl: String): ReadingSessionRecorder = ReadingSessionRecorder(
        dao = dao,
        bookUrl = bookUrl,
        scope = scope,
        awaitRecovery = { recovery.await() },
        clock = clockFactory(),
        wallNow = wallNow,
        elapsedNow = elapsedNow,
        checkpointIntervalMs = checkpointIntervalMs,
    )
}
