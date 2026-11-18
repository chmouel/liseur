package com.chmouel.liseur.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SessionStateRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `a cold start keeps the previous screen while settings load`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            folder.newFile("session.preferences_pb")
        }
        val session = SessionStateRepository(store)
        session.setLeftFromReader(true)
        val settings = CompletableDeferred<Boolean>()
        var resume: Boolean? = null
        val startup = launch {
            val leftFromReader = session.enterLibrary()
            resume = settings.await() && leftFromReader
        }
        runCurrent()

        assertNull(resume)
        assertFalse(session.enterLibrary())
        settings.complete(true)
        startup.join()
        assertTrue(resume == true)

        session.setLeftFromReader(true)
        assertTrue(session.enterLibrary())
        assertFalse(session.enterLibrary())
    }

    @Test
    fun `a first launch or a return from the library stays on the library`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            folder.newFile("session.preferences_pb")
        }
        val session = SessionStateRepository(store)

        assertFalse(session.enterLibrary())
        session.setLeftFromReader(false)
        assertFalse(session.enterLibrary())
    }
}
