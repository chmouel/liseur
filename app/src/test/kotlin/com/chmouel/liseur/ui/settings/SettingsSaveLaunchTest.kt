package com.chmouel.liseur.ui.settings

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The resume gate in front of the destination picker.
 *
 * `repeatOnLifecycle` runs its block again on every transition back to RESUMED, and the
 * request is only re-read at recomposition, so the gate has to check the pending request
 * itself. These drive a real [LifecycleRegistry] through the transitions an activity makes
 * around a picker: the block must open that picker once, and never for a request that was
 * consumed or dropped.
 */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsSaveLaunchTest {

    private val owner = RegistryOwner()
    private lateinit var registry: LifecycleRegistry

    @Before
    fun createRegistry() {
        registry = LifecycleRegistry(owner)
        owner.registry = registry
    }

    @Test
    fun `a request prepared while stopped waits for the resumed screen`() = gateTest {
        val pending = Pending("liseur-backup-2026-01-01.zip")
        openGate(pending)

        moveTo(Lifecycle.State.CREATED)
        moveTo(Lifecycle.State.STARTED)
        assertTrue(pending.launched.isEmpty())

        moveTo(Lifecycle.State.RESUMED)
        assertEquals(listOf("liseur-backup-2026-01-01.zip"), pending.launched)
        assertNull(pending.name)
    }

    @Test
    fun `resuming again after the picker closed does not open it a second time`() = gateTest {
        val pending = Pending("liseur-backup-2026-01-01.zip")
        openGate(pending)

        moveTo(Lifecycle.State.RESUMED)
        assertEquals(listOf("liseur-backup-2026-01-01.zip"), pending.launched)

        // What closing the picker looks like: pause, stop, then resume again.
        moveTo(Lifecycle.State.STARTED)
        moveTo(Lifecycle.State.CREATED)
        moveTo(Lifecycle.State.RESUMED)

        assertEquals(1, pending.launched.size)
    }

    @Test
    fun `a request dropped before the screen resumes never opens the picker`() = gateTest {
        val pending = Pending("liseur-backup-2026-01-01.zip")
        openGate(pending)

        // close() drops the request while the screen is still in the background.
        pending.name = null
        moveTo(Lifecycle.State.RESUMED)

        assertTrue(pending.launched.isEmpty())
    }

    /** Starts the gate the way `LaunchedEffect` does, and keeps it running for the test. */
    private fun TestScope.openGate(pending: Pending) {
        backgroundScope.launch {
            registry.launchSaveRequest(request = { pending.name }, consume = pending.consume)
        }
        runCurrent()
    }

    /** Moves the activity through [state] and lets the gate react to it. */
    private fun TestScope.moveTo(state: Lifecycle.State) {
        registry.currentState = state
        runCurrent()
    }

    private fun gateTest(body: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            body()
        } finally {
            Dispatchers.resetMain()
        }
    }

    /** The screen's pending request: consuming it clears it, as `saveLaunched` does. */
    private class Pending(var name: String?) {
        val launched = mutableListOf<String>()
        val consume: (String) -> Unit = { requested ->
            launched += requested
            if (name == requested) name = null
        }
    }

    private class RegistryOwner : LifecycleOwner {
        lateinit var registry: LifecycleRegistry
        override val lifecycle: Lifecycle get() = registry
    }
}
