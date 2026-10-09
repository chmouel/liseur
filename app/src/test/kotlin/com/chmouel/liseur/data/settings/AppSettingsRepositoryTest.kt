package com.chmouel.liseur.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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

    @Test
    fun `read aloud asks for one sentence at a time until changed`() = runTest {
        val repo = AppSettingsRepository(store())
        assertEquals(1, repo.settings.first().readAloudSentencesPerRequest)

        repo.setReadAloudSentencesPerRequest(3)
        assertEquals(3, repo.settings.first().readAloudSentencesPerRequest)
    }

    @Test
    fun `sentences per request out of range are brought within it`() = runTest {
        val store = store()
        val repo = AppSettingsRepository(store)
        store.edit { it[intPreferencesKey("read_aloud_sentences_per_request")] = 0 }
        assertEquals(1, repo.settings.first().readAloudSentencesPerRequest)
        store.edit { it[intPreferencesKey("read_aloud_sentences_per_request")] = 99 }
        assertEquals(5, repo.settings.first().readAloudSentencesPerRequest)

        repo.setReadAloudSentencesPerRequest(9)
        assertEquals(5, store.data.first()[intPreferencesKey("read_aloud_sentences_per_request")])
    }

    @Test
    fun `a speech model and its voice are saved together`() = runTest {
        val repo = AppSettingsRepository(store())
        val url = "http://192.168.1.10:8880/v1"
        repo.selectReadAloudServer((repo.addServer("Kokoro", url) as ServerChange.Saved).id)
        repo.setSpeechServerModelAndVoice(url, " hexgrad/Kokoro-82M ", "af_bella")
        val saved = repo.settings.first()
        assertEquals("hexgrad/Kokoro-82M", saved.speechServerModel)
        assertEquals("af_bella", saved.speechServerVoice)

        repo.setSpeechServerModelAndVoice(url, "Qwen/Qwen3-TTS-VoiceDesign", " ")
        val typed = repo.settings.first()
        assertEquals("Qwen/Qwen3-TTS-VoiceDesign", typed.speechServerModel)
        assertEquals(null, typed.speechServerVoice)
    }

    @Test
    fun `a voice and the language it is remembered for are written together`() = runTest {
        val store = store()
        val repo = AppSettingsRepository(store)
        val english = VoicePreference("device", "engine", "", "en", "en-gb-x-a")
        val french = VoicePreference("device", "engine", "", "fr", "fr-fr-x-b")

        assertTrue(repo.editReadAloudVoice { setDeviceVoice(english.voice); remember(english); true })
        assertTrue(repo.editReadAloudVoice { setDeviceVoice(french.voice); remember(french); true })

        // As after the app restarts: each language keeps its own voice.
        val s = AppSettingsRepository(store).settings.first()
        assertEquals("fr-fr-x-b", s.deviceVoice)
        assertEquals(setOf(english, french), s.voicePreferences.toSet())
    }

    @Test
    fun `an edit that gives up writes nothing`() = runTest {
        val repo = AppSettingsRepository(store())
        repo.setDeviceVoice("kept")

        val pref = VoicePreference("device", "engine", "", "en", "dropped")
        assertFalse(repo.editReadAloudVoice { setDeviceVoice("dropped"); remember(pref); false })

        val s = repo.settings.first()
        assertEquals("kept", s.deviceVoice)
        assertTrue(s.voicePreferences.isEmpty())
    }
}
