package com.chmouel.liseur.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.chmouel.liseur.reader.progress.PaceSample
import com.chmouel.liseur.reader.progress.ReadingPace
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReadingPaceRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store() =
        PreferenceDataStoreFactory.create { folder.newFile("pace.preferences_pb") }

    private fun sample(seconds: Double) = PaceSample(
        secondsPerPosition = seconds,
        positions = 1.0,
        elapsedMs = (seconds * 1_000).toLong(),
    )

    @Test
    fun `nothing is known before anything is read`() = runTest {
        assertEquals(ReadingPace.Unknown, ReadingPaceRepository(store()).pace())
    }

    @Test
    fun `a pace survives being written down`() = runTest {
        val repo = ReadingPaceRepository(store())
        repo.record(sample(40.0))
        repo.record(sample(40.0))
        val pace = repo.pace()
        assertEquals(40.0, pace.secondsPerPosition, 0.001)
        assertEquals(2, pace.samples)
        assertEquals(80_000L, pace.elapsedMs)
    }

    @Test
    fun `two pages recorded at once both count`() = runTest {
        // Read and write happen in one edit, so neither page can be
        // written over by the other — two books open at once must not
        // quietly lose one of them.
        val repo = ReadingPaceRepository(store())
        listOf(
            async { repo.record(sample(30.0)) },
            async { repo.record(sample(90.0)) },
        ).awaitAll()
        val pace = repo.pace()
        assertEquals(2, pace.samples)
        assertTrue("pace was ${pace.secondsPerPosition}", pace.secondsPerPosition > 0)
    }

    @Test
    fun `legacy speed keys do not poison v2 pace`() = runTest {
        val store = store()
        store.edit {
            it[doublePreferencesKey("speed")] = 8.0
            it[intPreferencesKey("samples")] = 100
        }

        assertEquals(ReadingPace.Unknown, ReadingPaceRepository(store).pace())
    }
}
