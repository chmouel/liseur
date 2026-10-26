package com.chmouel.liseur.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.chmouel.liseur.reader.dictionary.ALT_LANGUAGE_OFF
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppSettingsRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store(name: String = "app.preferences_pb") =
        PreferenceDataStoreFactory.create { folder.newFile(name) }

    @Test
    fun `automatic dictionary is opt in and survives backup without enabling online lookup`() = runTest {
        val repo = AppSettingsRepository(store("dictionary.preferences_pb"))
        assertFalse(repo.settings.first().dictionaryOnLongPress)
        repo.setDictionaryOnLongPress(true)
        assertTrue(repo.settings.first().dictionaryOnLongPress)
        assertFalse(repo.settings.first().dictionaryLookupEnabled)
        val restored = AppSettingsRepository(store("dictionary-restored.preferences_pb"))
        restored.restoreBackupValues(repo.backupValues())
        assertTrue(restored.settings.first().dictionaryOnLongPress)
        assertFalse(restored.settings.first().dictionaryLookupEnabled)
    }

    @Test
    fun `codex is available by default and disabling it survives settings backup`() = runTest {
        val repo = AppSettingsRepository(store("codex.preferences_pb"))
        assertTrue(repo.settings.first().codexEnabled)
        repo.setCodexEnabled(false)
        assertFalse(repo.settings.first().codexEnabled)
        val restored = AppSettingsRepository(store("codex-restored.preferences_pb"))
        restored.restoreBackupValues(repo.backupValues())
        assertFalse(restored.settings.first().codexEnabled)
        assertNull(restored.settings.first().companionProvider)
    }

    @Test
    fun `choosing Gemini for the companion stores the recommended model only when none was stored`() = runTest {
        val fresh = AppSettingsRepository(store("fresh.preferences_pb"))
        fresh.chooseCompanionGemini("gemini", "recommended")
        assertEquals("gemini", fresh.settings.first().companionProvider)
        assertEquals("recommended", fresh.settings.first().companionGeminiModel)

        val chosen = AppSettingsRepository(store("chosen.preferences_pb"))
        chosen.setCompanionGeminiModel("mine")
        chosen.chooseCompanionGemini("gemini", "recommended")
        assertEquals("mine", chosen.settings.first().companionGeminiModel)

        // Already on Gemini with the default: stays on the default.
        val using = AppSettingsRepository(store("using.preferences_pb"))
        using.setCompanionProvider("gemini")
        using.chooseCompanionGemini("gemini", "recommended")
        assertNull(using.settings.first().companionGeminiModel)
    }

    @Test
    fun `saving a Gemini key adopts Gemini for the companion and voice only when no server or provider is set`() = runTest {
        val fresh = AppSettingsRepository(store("gemini-fresh.preferences_pb"))
        fresh.adoptGeminiDefaults("gemini", "gemini-3.1-flash-lite")
        val adopted = fresh.settings.first()
        assertEquals("gemini", adopted.companionProvider)
        assertEquals("gemini-3.1-flash-lite", adopted.companionGeminiModel)
        assertEquals("gemini", adopted.readAloudProvider)

        // With an AI server already listed, neither provider is touched.
        val withServer = AppSettingsRepository(store("gemini-server.preferences_pb"))
        withServer.addServer("Local", "http://127.0.0.1:11434/v1")
        withServer.adoptGeminiDefaults("gemini", "gemini-3.1-flash-lite")
        val untouched = withServer.settings.first()
        assertNull(untouched.companionProvider)
        assertNull(untouched.companionGeminiModel)
        assertNull(untouched.readAloudProvider)

        // When one feature already has a provider chosen, only the unset one adopts Gemini.
        val voiceChosen = AppSettingsRepository(store("gemini-voice.preferences_pb"))
        voiceChosen.setReadAloudProvider("device")
        voiceChosen.setCompanionGeminiModel("custom-model")
        voiceChosen.adoptGeminiDefaults("gemini", "gemini-3.1-flash-lite")
        val partial = voiceChosen.settings.first()
        assertEquals("device", partial.readAloudProvider)
        assertEquals("gemini", partial.companionProvider)
        assertEquals("custom-model", partial.companionGeminiModel)

        val companionChosen = AppSettingsRepository(store("gemini-companion.preferences_pb"))
        companionChosen.setCompanionProvider("gemini")
        companionChosen.adoptGeminiDefaults("gemini", "gemini-3.1-flash-lite")
        val existing = companionChosen.settings.first()
        assertEquals("gemini", existing.companionProvider)
        assertNull(existing.companionGeminiModel)
        assertEquals("gemini", existing.readAloudProvider)
    }

    @Test
    fun `saving a Gemini key leaves unreadable server settings untouched`() = runTest {
        val store = store()
        store.edit { it[ServerSettings.SERVERS] = "[" }
        val repo = AppSettingsRepository(store)

        repo.adoptGeminiDefaults("gemini", "recommended")

        val settings = repo.settings.first()
        assertNull(settings.companionProvider)
        assertNull(settings.companionGeminiModel)
        assertNull(settings.readAloudProvider)
        assertEquals("[", store.data.first()[ServerSettings.SERVERS])
    }

    @Test
    fun `volume key mode is on by default`() = runTest {
        assertEquals(VolumeKeyMode.ON, AppSettings().volumeKeyMode)
        assertEquals(VolumeKeyMode.ON, AppSettingsRepository(store()).settings.first().volumeKeyMode)
    }

    @Test
    fun `legacy volume key preference maps to on or off`() = runTest {
        listOf(
            true to VolumeKeyMode.ON,
            false to VolumeKeyMode.OFF,
        ).forEach { (enabled, expected) ->
            val store = store("volume-keys-$enabled.preferences_pb")
            store.edit { it[booleanPreferencesKey("volume_keys_turn_pages")] = enabled }

            assertEquals(expected, AppSettingsRepository(store).settings.first().volumeKeyMode)
        }
    }

    @Test
    fun `volume key mode persists and removes the legacy preference`() = runTest {
        val store = store()
        val repo = AppSettingsRepository(store)

        VolumeKeyMode.entries.forEach { mode ->
            repo.setVolumeKeyMode(mode)

            assertEquals(mode, repo.settings.first().volumeKeyMode)
            assertEquals(mode.id, store.data.first()[stringPreferencesKey("volume_keys_mode")])
            assertNull(store.data.first()[booleanPreferencesKey("volume_keys_turn_pages")])
        }
    }

    @Test
    fun `legacy volume key preference is exported as a mode`() = runTest {
        val store = store()
        store.edit { it[booleanPreferencesKey("volume_keys_turn_pages")] = false }

        val backup = AppSettingsRepository(store).backupValues()

        assertEquals("off", backup.getString("volume_keys_mode"))
        assertFalse(backup.has("volume_keys_turn_pages"))
    }

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
    fun `the footer is locked unless the reader unlocked it`() = runTest {
        assertTrue(AppSettings().lockFooterOn)
        assertTrue(AppSettingsRepository(store()).settings.first().lockFooterOn)
    }

    @Test
    fun `a footer the reader unlocked stays unlocked`() = runTest {
        val store = store()
        store.edit { it[booleanPreferencesKey("lock_footer_on")] = false }

        assertFalse(AppSettingsRepository(store).settings.first().lockFooterOn)
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

    @Test
    fun `the dictionary alternative language is automatic until chosen, and can be turned off`() = runTest {
        val repo = AppSettingsRepository(store())
        assertNull(repo.settings.first().dictionaryAltLanguage)

        repo.setDictionaryAltLanguage("fr")
        assertEquals("fr", repo.settings.first().dictionaryAltLanguage)

        repo.setDictionaryAltLanguage(ALT_LANGUAGE_OFF)
        assertEquals(ALT_LANGUAGE_OFF, repo.settings.first().dictionaryAltLanguage)

        repo.setDictionaryAltLanguage(null)
        assertNull(repo.settings.first().dictionaryAltLanguage)
    }

    @Test
    fun `a stored alternative language that is not one is ignored`() = runTest {
        val store = store()
        store.edit { it[stringPreferencesKey("dictionary_alt_language")] = "not a language" }

        assertNull(AppSettingsRepository(store).settings.first().dictionaryAltLanguage)
    }
}
