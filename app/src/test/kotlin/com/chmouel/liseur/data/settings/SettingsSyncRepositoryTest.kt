package com.chmouel.liseur.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What this device last stored on each account it backs its settings up
 * to.
 *
 * The record belongs to one account: it says what that account's copy
 * holds, so it has to move with the account and go with it, and must
 * never answer for another.
 */
class SettingsSyncRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file by lazy { folder.newFile("settings_sync.preferences_pb") }

    private val store by lazy { PreferenceDataStoreFactory.create { file } }

    private fun repo() = SettingsSyncRepository(store)

    @Test
    fun `what was stored is remembered under its account`() = runTest {
        val repo = repo()
        repo.recordStored(A, mapOf("reader.font_size" to "1.4", "reader.theme" to "dark"))

        assertEquals(mapOf("reader.font_size" to "1.4", "reader.theme" to "dark"), repo.allStored(A))
        assertEquals(emptyMap<String, String>(), repo.allStored(B))
    }

    @Test
    fun `two accounts keep separate records about the same setting`() = runTest {
        val repo = repo()
        repo.recordStored(A, mapOf("reader.font_size" to "1.4"))
        repo.recordStored(B, mapOf("reader.font_size" to "1.8"))

        assertEquals("1.4", repo.allStored(A)["reader.font_size"])
        assertEquals("1.8", repo.allStored(B)["reader.font_size"])
    }

    @Test
    fun `an account key containing punctuation is still one key`() = runTest {
        val repo = repo()
        repo.recordStored(A, mapOf("reader.font_size" to "1.4"))
        // A is a prefix of this one up to its last character.
        repo.recordStored("$A-2", mapOf("reader.theme" to "dark"))

        assertEquals(mapOf("reader.font_size" to "1.4"), repo.allStored(A))
    }

    @Test
    fun `a reconnect carries the record to the new spelling`() = runTest {
        val repo = repo()
        repo.recordStored(A, mapOf("reader.font_size" to "1.4"))

        repo.rekeyPeer(A, B)

        assertEquals(mapOf("reader.font_size" to "1.4"), repo.allStored(B))
    }

    @Test
    fun `a rekey to the same key changes nothing`() = runTest {
        val repo = repo()
        repo.recordStored(A, mapOf("reader.font_size" to "1.4"))

        repo.rekeyPeer(A, A)

        assertEquals(mapOf("reader.font_size" to "1.4"), repo.allStored(A))
    }

    @Test
    fun `leaving one account leaves the other alone`() = runTest {
        val repo = repo()
        repo.recordStored(A, mapOf("reader.font_size" to "1.4"))
        repo.recordStored(B, mapOf("reader.font_size" to "1.8"))

        repo.forgetPeer(A)

        assertEquals(emptyMap<String, String>(), repo.allStored(A))
        assertEquals(mapOf("reader.font_size" to "1.8"), repo.allStored(B))
    }

    @Test
    fun `the cross-device bookkeeping is cleared on upgrade`() = runTest {
        // What the earlier version left: agreed values and their server
        // times, and change stamps this device made.
        store.edit {
            it[stringPreferencesKey("v:$A:reader.font_size")] = "1.4"
            it[longPreferencesKey("ts:$A:reader.font_size")] = 100L
            it[longPreferencesKey("ch:reader.font_size")] = 50L
        }

        assertEquals(emptyMap<String, String>(), repo().allStored(A))
        val left = store.data.first().asMap().keys.map { it.name }
        assertEquals(listOf("format"), left)
    }

    @Test
    fun `the clearing happens once`() = runTest {
        repo().recordStored(A, mapOf("reader.font_size" to "1.4"))

        // A later process starts with a fresh repository over the same
        // file, and must find what the first one wrote.
        val next = repo()
        assertEquals(mapOf("reader.font_size" to "1.4"), next.allStored(A))
        assertTrue(store.data.first().asMap().keys.any { it.name == "format" })
    }

    private companion object {
        const val A = "liseursync|https://books.example.com|account-1"
        const val B = "liseursync|https://books.example.com|account-2"
    }
}
