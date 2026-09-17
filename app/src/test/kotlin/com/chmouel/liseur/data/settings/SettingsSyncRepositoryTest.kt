package com.chmouel.liseur.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The bookkeeping behind settings sync.
 *
 * Two records live in this one store and they answer to different
 * owners, which is the whole reason this file exists. What an account
 * agreed to is that account's, and means nothing to the next one — its
 * timestamps came off a different clock. What this device last changed
 * is this device's, and signing into another server does not un-change
 * a font.
 *
 * Get that split wrong in either direction and the failure is silent:
 * carry a baseline across and every key reads as neither newer nor older
 * than itself and goes quiet forever; drop the change record and the
 * settings already on the phone are never offered to the new account at
 * all.
 */
class SettingsSyncRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun repo() = SettingsSyncRepository(
        PreferenceDataStoreFactory.create { folder.newFile("settings_sync.preferences_pb") },
    )

    private fun entry(value: String, at: Long) = SettingsSyncRepository.SyncedEntry(value, at)

    // -- The baseline belongs to one account -------------------------------

    @Test
    fun `an agreement is remembered under the account that made it`() = runTest {
        val repo = repo()
        repo.recordSynced(A, mapOf("reader.font_size" to entry("1.4", 100)))

        assertEquals(entry("1.4", 100), repo.allLastSynced(A)["reader.font_size"])
        assertNull(repo.allLastSynced(B)["reader.font_size"])
    }

    @Test
    fun `two accounts keep separate answers about the same setting`() = runTest {
        val repo = repo()
        repo.recordSynced(A, mapOf("reader.font_size" to entry("1.4", 100)))
        repo.recordSynced(B, mapOf("reader.font_size" to entry("1.8", 50)))

        assertEquals(entry("1.4", 100), repo.allLastSynced(A)["reader.font_size"])
        assertEquals(entry("1.8", 50), repo.allLastSynced(B)["reader.font_size"])
    }

    @Test
    fun `an account key containing punctuation is still one key`() = runTest {
        val repo = repo()
        // The real shape, which carries both a colon and a pipe. Split
        // this on the wrong separator and one account reads another's.
        repo.recordSynced(A, mapOf("reader.font_size" to entry("1.4", 100)))

        assertEquals(1, repo.allLastSynced(A).size)
        assertEquals("reader.font_size", repo.allLastSynced(A).keys.single())
    }

    @Test
    fun `a whole pass is written down at once`() = runTest {
        val repo = repo()
        repo.recordSynced(
            A,
            mapOf(
                "reader.font_size" to entry("1.4", 100),
                "app.scroll_mode" to entry("true", 100),
            ),
        )
        assertEquals(2, repo.allLastSynced(A).size)
    }

    @Test
    fun `an entry with no timestamp beside it is not half an agreement`() = runTest {
        val repo = repo()
        repo.recordSynced(A, emptyMap())
        assertTrue(repo.allLastSynced(A).isEmpty())
        assertEquals(0, repo.countForPeer(A))
    }

    // -- Moving and leaving an account -------------------------------------

    @Test
    fun `a reconnect carries the baseline to the new spelling`() = runTest {
        val repo = repo()
        repo.recordSynced(A, mapOf("reader.font_size" to entry("1.4", 100)))

        repo.rekeyPeer(A, B)

        assertEquals(entry("1.4", 100), repo.allLastSynced(B)["reader.font_size"])
        assertTrue(repo.allLastSynced(A).isEmpty())
        assertEquals(0, repo.countForPeer(A))
        assertEquals(1, repo.countForPeer(B))
    }

    @Test
    fun `leaving an account drops what it agreed but not what this device did`() = runTest {
        val repo = repo()
        repo.recordSynced(A, mapOf("reader.font_size" to entry("1.4", 100)))
        repo.observeLocal(mapOf("reader.font_size" to "1.4"), 10)
        repo.observeLocal(mapOf("reader.font_size" to "1.8"), 20)

        repo.forgetPeer(A)

        assertTrue(repo.allLastSynced(A).isEmpty())
        // The edit still happened, and the next account has to hear
        // about it — with its own time, not the time it was told.
        assertEquals(20L, repo.localChanges()["reader.font_size"])
    }

    @Test
    fun `leaving one account leaves the other alone`() = runTest {
        val repo = repo()
        repo.recordSynced(A, mapOf("reader.font_size" to entry("1.4", 100)))
        repo.recordSynced(B, mapOf("reader.font_size" to entry("1.8", 50)))

        repo.forgetPeer(A)

        assertTrue(repo.allLastSynced(A).isEmpty())
        assertEquals(entry("1.8", 50), repo.allLastSynced(B)["reader.font_size"])
    }

    @Test
    fun `a rekey to the same key changes nothing`() = runTest {
        val repo = repo()
        repo.recordSynced(A, mapOf("reader.font_size" to entry("1.4", 100)))
        repo.rekeyPeer(A, A)
        assertEquals(entry("1.4", 100), repo.allLastSynced(A)["reader.font_size"])
    }

    // -- When the reader changed something ---------------------------------

    @Test
    fun `the first sight of a setting is not an edit`() = runTest {
        val repo = repo()
        // An install, or a key a new version added: nobody changed it,
        // and claiming they did would push it over another device's
        // real choice.
        repo.observeLocal(mapOf("reader.font_size" to "1.0"), 10)
        assertTrue(repo.localChanges().isEmpty())
    }

    @Test
    fun `a change is stamped when it is made`() = runTest {
        val repo = repo()
        repo.observeLocal(mapOf("reader.font_size" to "1.0"), 10)
        repo.observeLocal(mapOf("reader.font_size" to "1.4"), 20)
        assertEquals(20L, repo.localChanges()["reader.font_size"])
    }

    @Test
    fun `a setting looked at twice without changing is not restamped`() = runTest {
        val repo = repo()
        repo.observeLocal(mapOf("reader.font_size" to "1.0"), 10)
        repo.observeLocal(mapOf("reader.font_size" to "1.4"), 20)
        repo.observeLocal(mapOf("reader.font_size" to "1.4"), 30)
        repo.observeLocal(mapOf("reader.font_size" to "1.4"), 40)

        // Still 20: the edit happened once, and a later look at the same
        // value is not a second edit. Restamping here would let a device
        // that merely woke up outrank one that actually changed
        // something.
        assertEquals(20L, repo.localChanges()["reader.font_size"])
    }

    @Test
    fun `a value a server handed over is not counted as an edit made here`() = runTest {
        val repo = repo()
        repo.observeLocal(mapOf("reader.font_size" to "1.0"), 10)

        // The sync pass applies the account's value and says so. The
        // collector then sees that write, as it sees every write.
        repo.markApplied(mapOf("reader.font_size" to "1.4"))
        repo.observeLocal(mapOf("reader.font_size" to "1.4"), 20)

        assertNull(repo.localChanges()["reader.font_size"])
    }

    @Test
    fun `it does not matter which of the collector and the sync pass gets there first`() =
        runTest {
            val repo = repo()
            repo.observeLocal(mapOf("reader.font_size" to "1.0"), 10)

            // The collector wins the race and has already stamped the
            // applied value as an edit before the pass says otherwise.
            repo.observeLocal(mapOf("reader.font_size" to "1.4"), 20)
            repo.markApplied(mapOf("reader.font_size" to "1.4"))

            assertNull(repo.localChanges()["reader.font_size"])
        }

    @Test
    fun `an edit away from what a server handed over is still an edit`() = runTest {
        val repo = repo()
        repo.observeLocal(mapOf("reader.font_size" to "1.0"), 10)
        repo.markApplied(mapOf("reader.font_size" to "1.4"))
        repo.observeLocal(mapOf("reader.font_size" to "1.4"), 20)

        // The reader then picks something else, which is theirs.
        repo.observeLocal(mapOf("reader.font_size" to "1.8"), 30)

        assertEquals(30L, repo.localChanges()["reader.font_size"])
    }

    @Test
    fun `a setting changed back and forth keeps the latest time`() = runTest {
        val repo = repo()
        repo.observeLocal(mapOf("reader.font_size" to "1.0"), 10)
        repo.observeLocal(mapOf("reader.font_size" to "1.4"), 20)
        repo.observeLocal(mapOf("reader.font_size" to "1.0"), 30)
        assertEquals(30L, repo.localChanges()["reader.font_size"])
    }

    @Test
    fun `only the settings that moved are stamped`() = runTest {
        val repo = repo()
        repo.observeLocal(mapOf("a" to "1", "b" to "2"), 10)
        repo.observeLocal(mapOf("a" to "9", "b" to "2"), 20)

        assertEquals(mapOf("a" to 20L), repo.localChanges())
    }

    @Test
    fun `the change record survives an account switch`() = runTest {
        val repo = repo()
        repo.observeLocal(mapOf("reader.font_size" to "1.0"), 10)
        repo.observeLocal(mapOf("reader.font_size" to "1.4"), 20)

        repo.forgetPeer(A)
        repo.rekeyPeer(A, B)

        assertEquals(20L, repo.localChanges()["reader.font_size"])
    }

    private companion object {
        const val A = "liseursync|https://books.example.com|account-1"
        const val B = "liseursync|https://books.example.com|account-2"
    }
}
