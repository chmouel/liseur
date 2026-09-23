package com.chmouel.liseur.sync

import androidx.work.ListenableWorker.Result
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.SyncOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

class PositionSyncWorkerTest {
    @Test
    fun `unresolved traversal does not cancel appended work but its final report fails`() {
        for (outcome in listOf(
            SyncOutcome.Failure(SyncFailure.PositionUnresolved, continuation = true),
            SyncOutcome.Partial(SyncFailure.PositionUnresolved, continuation = true),
            SyncOutcome.Incomplete,
        )) {
            assertEquals(Result.success(), PositionSyncWorker.completedResult(outcome))
        }
        for (outcome in listOf(
            SyncOutcome.Failure(SyncFailure.PositionUnresolved),
            SyncOutcome.Partial(SyncFailure.PositionUnresolved),
        )) {
            assertEquals(Result.failure(), PositionSyncWorker.completedResult(outcome))
        }
    }

    @Test
    fun `ordinary providers retain retry success and refusal behavior`() {
        assertEquals(Result.retry(),
            PositionSyncWorker.completedResult(SyncOutcome.Failure(SyncFailure.Offline)))
        assertEquals(Result.retry(),
            PositionSyncWorker.completedResult(SyncOutcome.Partial(SyncFailure.Timeout)))
        assertEquals(Result.failure(),
            PositionSyncWorker.completedResult(SyncOutcome.Failure(SyncFailure.Unauthorised)))
        assertEquals(Result.success(), PositionSyncWorker.completedResult(SyncOutcome.Success))
        assertEquals(Result.success(), PositionSyncWorker.completedResult(SyncOutcome.NotApplicable))
    }
}
