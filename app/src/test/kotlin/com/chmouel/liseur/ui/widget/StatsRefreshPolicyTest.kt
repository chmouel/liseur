package com.chmouel.liseur.ui.widget

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatsRefreshPolicyTest {

    @Test
    fun `nothing queued starts a refresh`() {
        assertEquals(ExistingWorkPolicy.KEEP, WidgetUpdater.statsRefreshPolicy(emptyList()))
        assertEquals(ExistingWorkPolicy.KEEP, WidgetUpdater.statsRefreshPolicy(listOf(State.SUCCEEDED)))
    }

    @Test
    fun `a refresh that has not fetched yet already covers the request`() {
        assertNull(WidgetUpdater.statsRefreshPolicy(listOf(State.ENQUEUED)))
    }

    @Test
    fun `a running refresh gets one trailing run`() {
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, WidgetUpdater.statsRefreshPolicy(listOf(State.RUNNING)))
    }

    @Test
    fun `a trailing run already waiting is not duplicated`() {
        assertNull(WidgetUpdater.statsRefreshPolicy(listOf(State.RUNNING, State.BLOCKED)))
    }
}
