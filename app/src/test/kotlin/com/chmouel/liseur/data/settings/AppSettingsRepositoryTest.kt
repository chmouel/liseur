package com.chmouel.liseur.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppSettingsRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store() =
        PreferenceDataStoreFactory.create { folder.newFile("app.preferences_pb") }

    @Test
    fun `pinch to resize is on by default in app settings`() {
        assertTrue(AppSettings().pinchToResize)
    }

    @Test
    fun `an unconfigured store defaults pinch to resize to on`() = runTest {
        val repo = AppSettingsRepository(store())
        assertTrue(repo.settings.first().pinchToResize)
    }

    @Test
    fun `an existing disabled setting is preserved without migration`() = runTest {
        val store = store()
        store.edit { it[booleanPreferencesKey("pinch_to_resize")] = false }

        val repo = AppSettingsRepository(store)
        assertFalse(repo.settings.first().pinchToResize)
    }

    @Test
    fun `an existing enabled setting is preserved`() = runTest {
        val store = store()
        store.edit { it[booleanPreferencesKey("pinch_to_resize")] = true }

        val repo = AppSettingsRepository(store)
        assertTrue(repo.settings.first().pinchToResize)
    }

    @Test
    fun `pinch to resize survives being changed`() = runTest {
        val repo = AppSettingsRepository(store())
        repo.setPinchToResize(false)
        assertFalse(repo.settings.first().pinchToResize)

        repo.setPinchToResize(true)
        assertTrue(repo.settings.first().pinchToResize)
    }
}
