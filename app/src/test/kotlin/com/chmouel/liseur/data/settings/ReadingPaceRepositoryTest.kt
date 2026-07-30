package com.chmouel.liseur.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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

    private fun repository() = ReadingPaceRepository(
        PreferenceDataStoreFactory.create { folder.newFile("pace.preferences_pb") },
    )

    @Test
    fun `nothing is known before anything is read`() = runTest {
        assertEquals(ReadingPace.Unknown, repository().pace())
    }

    @Test
    fun `a pace survives being written down`() = runTest {
        val repo = repository()
        repo.record(2.0)
        repo.record(2.0)
        val pace = repo.pace()
        assertEquals(2.0, pace.speed, 0.001)
        assertEquals(2, pace.samples)
    }

    @Test
    fun `two pages recorded at once both count`() = runTest {
        // Read and write happen in one edit, so neither page can be
        // written over by the other — two books open at once must not
        // quietly lose one of them.
        val repo = repository()
        listOf(
            async { repo.record(1.0) },
            async { repo.record(3.0) },
        ).awaitAll()
        val pace = repo.pace()
        assertEquals(2, pace.samples)
        assertTrue("speed was ${pace.speed}", pace.speed > 0)
    }
}
