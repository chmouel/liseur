package com.chmouel.liseur.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ServerSettingsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun repo() = AppSettingsRepository(PreferenceDataStoreFactory.create { folder.newFile("app.preferences_pb") })

    private fun legacy(url: String = KOKORO) = mutablePreferencesOf(
        LEGACY_URL to url,
        stringPreferencesKey("speech_server_model") to "kokoro",
        stringPreferencesKey("speech_server_voice") to "af_bella",
        stringSetPreferencesKey("speech_server_voices") to setOf("af_bella", "bf_emma"),
        stringPreferencesKey("speech_server_unsettled_url") to url,
        ServerSettings.PROVIDER to "openai",
    )

    private suspend fun AppSettingsRepository.remember(vararg preferences: VoicePreference) =
        editReadAloudVoice {
            preferences.forEach { remember(it) }
            true
        }

    private suspend fun AppSettingsRepository.add(name: String, url: String): String =
        (addServer(name, url) as ServerChange.Saved).id

    // -- Upgrade ----------------------------------------------------------

    @Test
    fun `the single server of an older version becomes a listed one read aloud still uses`() {
        val p = legacy()
        ServerSettings.migrateLegacy(p, ::defaultServerName)

        val server = ServerSettings.servers(p).readable!!.single()
        assertEquals(ServerConnection(KOKORO_ID, "192.168.1.10:8880", KOKORO), server)
        assertEquals(ServerSpeechState("kokoro", "af_bella", setOf("af_bella", "bf_emma")), ServerSettings.states(p)[KOKORO_ID])
        assertEquals(setOf(KOKORO_ID), p[ServerSettings.READ_ALOUD_UNSETTLED])
        assertEquals(server, ServerSettings.readAloudServer(p))
        assertFalse(ServerSettings.hasLegacy(p))
    }

    @Test
    fun `a server read aloud did not use is listed without being selected`() {
        val p = legacy().apply { this[ServerSettings.PROVIDER] = "device" }
        ServerSettings.migrateLegacy(p, ::defaultServerName)

        assertEquals(1, ServerSettings.servers(p).readable!!.size)
        assertNull(p[ServerSettings.READ_ALOUD_SERVER])
    }

    @Test
    fun `an upgraded server deleted later does not come back on the next start`() = runTest {
        val file = folder.newFile("app.preferences_pb")
        // The older version's settings, written by a store that is then closed.
        val seeding = Job()
        val seed = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + seeding)) { file }
        seed.edit { it += legacy() }
        seeding.cancelAndJoin()

        val first = Job()
        val upgraded = AppSettingsRepository(
            PreferenceDataStoreFactory.create(
                migrations = listOf(LegacySpeechServerMigration(::defaultServerName)),
                scope = CoroutineScope(Dispatchers.IO + first),
            ) { file },
        )
        assertEquals(KOKORO, upgraded.settings.first().speechServerUrl)
        assertTrue(upgraded.deleteServer(KOKORO_ID))
        first.cancelAndJoin()

        val restarted = AppSettingsRepository(
            PreferenceDataStoreFactory.create(migrations = listOf(LegacySpeechServerMigration(::defaultServerName))) { file },
        )
        val s = restarted.settings.first()
        assertEquals(ServerList.Empty, s.servers)
        assertNull(s.speechServerUrl)
    }

    // -- Saved servers that cannot be read ---------------------------------

    @Test
    fun `saved servers that cannot be read are kept as they are and nothing edits them`() = runTest {
        val store = PreferenceDataStoreFactory.create { folder.newFile("app.preferences_pb") }
        val repo = AppSettingsRepository(store)
        store.edit {
            it[ServerSettings.SERVERS] = "not json"
            it[ServerSettings.READ_ALOUD_SERVER] = KOKORO_ID
            it[ServerSettings.PROVIDER] = "openai"
        }

        assertEquals(ServerList.Unreadable, repo.settings.first().servers)
        assertNull(repo.settings.first().readAloudServerConnection)
        assertEquals(ServerChange.Unreadable, repo.addServer("Kokoro", KOKORO))
        assertFalse(repo.deleteServer(KOKORO_ID))
        assertFalse(repo.selectReadAloudServer(KOKORO_ID))
        // A migration does not write over them either.
        val p = store.data.first().toMutablePreferences().apply { this[LEGACY_URL] = OPENROUTER }
        ServerSettings.migrateLegacy(p, ::defaultServerName)
        assertEquals("not json", p[ServerSettings.SERVERS])

        val kept = store.data.first()
        assertEquals("not json", kept[ServerSettings.SERVERS])
        assertEquals(KOKORO_ID, kept[ServerSettings.READ_ALOUD_SERVER])
    }

    @Test
    fun `a listed server whose id is not its address cannot be read`() {
        val p = mutablePreferencesOf(
            ServerSettings.SERVERS to """[{"id":"$OPENROUTER_ID","name":"Kokoro","url":"$KOKORO"}]""",
        )
        assertEquals(ServerList.Unreadable, ServerSettings.servers(p))
    }

    @Test
    fun `state or a read-aloud choice for a server not listed cannot be read and is kept`() = runTest {
        val dangling = listOf(
            Pair(ServerSettings.READ_ALOUD_STATE, """{"$OPENROUTER_ID":{"model":"tts"}}"""),
            Pair(ServerSettings.READ_ALOUD_SERVER, OPENROUTER_ID),
        )
        for ((index, entry) in dangling.withIndex()) {
            val (key, value) = entry
            val store = PreferenceDataStoreFactory.create { folder.newFile("dangling$index.preferences_pb") }
            val repo = AppSettingsRepository(store)
            repo.add("Kokoro", KOKORO)
            store.edit { it[key] = value }

            assertEquals(ServerList.Unreadable, repo.settings.first().servers)
            assertEquals(ServerChange.Unreadable, repo.addServer("Moved", MOVED))
            assertFalse(repo.deleteServer(KOKORO_ID))
            assertEquals(value, store.data.first()[key])
        }
    }

    // -- Adding, editing and deleting ---------------------------------------

    @Test
    fun `the same server cannot be listed twice, however its address is written`() = runTest {
        val repo = repo()
        repo.add("Kokoro", KOKORO)
        val other = repo.add("OpenRouter", OPENROUTER)

        assertEquals(ServerChange.Duplicate, repo.addServer("Again", "$KOKORO/"))
        assertEquals(ServerChange.Duplicate, repo.updateServer(other, "OpenRouter", " $KOKORO "))
        assertEquals(listOf("Kokoro", "OpenRouter"), repo.settings.first().servers.readable!!.map { it.name })
    }

    @Test
    fun `a new address takes read aloud, its state and its language voices with it`() = runTest {
        val repo = repo()
        val id = repo.add("Kokoro", KOKORO)
        repo.selectReadAloudServer(id)
        repo.settleSpeechServer(KOKORO)
        repo.setSpeechServerModelAndVoice(KOKORO, "kokoro", "af_bella")
        repo.remember(
            VoicePreference("openai", KOKORO_ID, "kokoro", "fr", "ff_siwis"),
            VoicePreference("device", "engine", "", "en", "en-gb-x-a"),
        )

        val moved = repo.updateServer(id, "Kokoro", MOVED) as ServerChange.Saved

        val s = repo.settings.first()
        assertEquals(MOVED_ID, moved.id)
        assertEquals(MOVED, s.speechServerUrl)
        assertEquals("kokoro" to "af_bella", s.speechServerModel to s.speechServerVoice)
        // Its model and voice are to be checked against the new server's lists.
        assertEquals(MOVED, s.speechServerUnsettledUrl)
        assertEquals(
            listOf(MOVED_ID, "engine"),
            s.voicePreferences.map { it.context },
        )
        assertFalse(KOKORO_ID in s.readAloudServerStates)
    }

    @Test
    fun `a renamed server keeps its address and settles nothing`() = runTest {
        val repo = repo()
        val id = repo.add("", KOKORO)
        repo.settleSpeechServer(KOKORO)

        assertEquals(ServerChange.Saved(id), repo.updateServer(id, "At home", KOKORO))

        val s = repo.settings.first()
        assertEquals(listOf(ServerConnection(id, "At home", KOKORO)), s.servers.readable)
        assertTrue(s.unsettledReadAloudServers.isEmpty())
    }

    @Test
    fun `deleting the server read aloud uses sends it back to the device and forgets its state`() = runTest {
        val repo = repo()
        val id = repo.add("Kokoro", KOKORO)
        val other = repo.add("OpenRouter", OPENROUTER)
        repo.selectReadAloudServer(id)
        repo.setSpeechServerModelAndVoice(KOKORO, "kokoro", "af_bella")
        repo.setSpeechServerModelAndVoice(OPENROUTER, "gpt-4o-mini-tts", "alloy")
        repo.remember(VoicePreference("openai", KOKORO_ID, "kokoro", "fr", "ff_siwis"))

        assertTrue(repo.deleteServer(id))

        val s = repo.settings.first()
        assertNull(s.readAloudProvider)
        assertNull(s.readAloudServer)
        assertEquals(listOf(other), s.servers.readable!!.map { it.id })
        assertEquals(setOf(other), s.readAloudServerStates.keys)
        assertTrue(s.voicePreferences.isEmpty())
        assertTrue(s.unsettledReadAloudServers.none { it == id })
    }

    @Test
    fun `going back to a server finds the model and voices it had`() = runTest {
        val repo = repo()
        val a = repo.add("Kokoro", KOKORO)
        val b = repo.add("OpenRouter", OPENROUTER)
        repo.selectReadAloudServer(a)
        repo.setSpeechServerModelAndVoice(KOKORO, "kokoro", "af_bella")
        repo.setSpeechServerVoices(KOKORO, setOf("af_bella"))

        repo.selectReadAloudServer(b)
        repo.setSpeechServerModelAndVoice(OPENROUTER, "gpt-4o-mini-tts", "alloy")
        repo.selectReadAloudServer(a)

        val s = repo.settings.first()
        assertEquals("kokoro" to "af_bella", s.speechServerModel to s.speechServerVoice)
        assertEquals(setOf("af_bella"), s.speechServerVoices)
    }

    @Test
    fun `a model reply for an address the server no longer has is not saved`() = runTest {
        val repo = repo()
        val id = repo.add("Kokoro", KOKORO)
        repo.updateServer(id, "Kokoro", MOVED)

        repo.setSpeechServerModelAndVoice(KOKORO, "kokoro", "af_bella")

        assertTrue(repo.settings.first().readAloudServerStates.values.none { it.model != null })
    }

    // -- Backup and restore -------------------------------------------------

    @Test
    fun `an archive keeps each server's choices and never the unsettled mark`() = runTest {
        val source = repo()
        val id = source.add("Kokoro", KOKORO)
        source.selectReadAloudServer(id)
        source.setSpeechServerModelAndVoice(KOKORO, "kokoro", "af_bella")
        val archive = source.backupValues()
        assertFalse(archive.has("read_aloud_unsettled_servers"))

        val destination = AppSettingsRepository(PreferenceDataStoreFactory.create { folder.newFile("other.preferences_pb") })
        destination.restoreBackupValues(archive)

        val s = destination.settings.first()
        assertEquals(KOKORO, s.speechServerUrl)
        assertEquals("kokoro" to "af_bella", s.speechServerModel to s.speechServerVoice)
        assertNull(s.speechServerUnsettledUrl)
    }

    @Test
    fun `an older archive's server joins an empty list`() = runTest {
        val repo = repo()
        repo.restoreBackupValues(legacyArchive())

        val s = repo.settings.first()
        assertEquals(listOf(KOKORO_ID), s.servers.readable!!.map { it.id })
        assertEquals(KOKORO, s.speechServerUrl)
        assertEquals("kokoro" to "af_bella", s.speechServerModel to s.speechServerVoice)
        assertEquals(setOf("af_bella"), s.speechServerVoices)
        assertNull(s.speechServerUnsettledUrl)
    }

    @Test
    fun `an older archive's server takes over its own entry and leaves the others`() = runTest {
        val repo = repo()
        val kokoro = repo.add("At home", KOKORO)
        val other = repo.add("OpenRouter", OPENROUTER)
        repo.selectReadAloudServer(other)
        repo.setSpeechServerModelAndVoice(KOKORO, "older", "older-1")

        repo.restoreBackupValues(legacyArchive())

        val s = repo.settings.first()
        assertEquals(listOf("At home", "OpenRouter"), s.servers.readable!!.map { it.name })
        assertEquals(kokoro, s.readAloudServer)
        assertEquals("kokoro" to "af_bella", s.speechServerModel to s.speechServerVoice)
    }

    @Test
    fun `an archive with both kinds of keys restores its servers and ignores the older ones`() = runTest {
        val repo = repo()
        val archive = legacyArchive()
            .put(ServerSettings.SERVERS_NAME, ServerSettings.encodeServers(listOf(ServerConnection(OPENROUTER_ID, "OpenRouter", OPENROUTER))))
            .put(ServerSettings.READ_ALOUD_SERVER_NAME, OPENROUTER_ID)

        repo.restoreBackupValues(archive)

        val s = repo.settings.first()
        assertEquals(listOf(OPENROUTER_ID), s.servers.readable!!.map { it.id })
        assertEquals(OPENROUTER, s.speechServerUrl)
    }

    @Test
    fun `an archive without servers leaves them, and one with an empty list clears them`() = runTest {
        val repo = repo()
        val id = repo.add("Kokoro", KOKORO)
        repo.selectReadAloudServer(id)

        repo.restoreBackupValues(JSONObject().put("theme_mode", "dark"))
        assertEquals(KOKORO, repo.settings.first().speechServerUrl)

        repo.restoreBackupValues(JSONObject().put(ServerSettings.SERVERS_NAME, "[]"))
        val s = repo.settings.first()
        assertEquals(ServerList.Empty, s.servers)
        assertNull(s.readAloudProvider)
        assertNull(s.readAloudServer)
    }

    @Test
    fun `an archive whose servers do not hold together changes nothing`() = runTest {
        val repo = repo()
        val id = repo.add("Kokoro", KOKORO)
        repo.selectReadAloudServer(id)
        val before = repo.settings.first()

        val broken = listOf(
            JSONObject().put(ServerSettings.SERVERS_NAME, "{"),
            JSONObject().put(ServerSettings.SERVERS_NAME, "[]").put(ServerSettings.READ_ALOUD_SERVER_NAME, KOKORO_ID),
            JSONObject().put(ServerSettings.SERVERS_NAME, "[]")
                .put(ServerSettings.READ_ALOUD_STATE_NAME, """{"$KOKORO_ID":{"model":"kokoro"}}"""),
            JSONObject().put(ServerSettings.SERVERS_NAME, JSONArray()),
            JSONObject().put("speech_server_voices", JSONArray().put(3)),
        )
        for (archive in broken) {
            try {
                repo.restoreBackupValues(archive.put("theme_mode", "dark"))
                fail("Restored $archive")
            } catch (_: IllegalArgumentException) {
            }
        }

        assertEquals(before, repo.settings.first())
    }

    @Test
    fun `an archive without servers must refer to servers listed here`() = runTest {
        val repo = repo()
        repo.add("Kokoro", KOKORO)
        val before = repo.settings.first()

        val broken = listOf(
            JSONObject().put(ServerSettings.READ_ALOUD_SERVER_NAME, OPENROUTER_ID),
            JSONObject().put(ServerSettings.READ_ALOUD_SERVER_NAME, "$KOKORO/"),
            JSONObject().put(ServerSettings.READ_ALOUD_STATE_NAME, """{"$OPENROUTER_ID":{"model":"tts"}}"""),
        )
        for (archive in broken) {
            try {
                repo.restoreBackupValues(archive.put("theme_mode", "dark"))
                fail("Restored $archive")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertEquals(before, repo.settings.first())

        repo.restoreBackupValues(JSONObject().put(ServerSettings.READ_ALOUD_SERVER_NAME, KOKORO_ID).put("theme_mode", "dark"))
        assertEquals(KOKORO_ID, repo.settings.first().readAloudServer)
    }

    @Test
    fun `an archive with servers ignores whatever its older keys hold`() = runTest {
        val repo = repo()
        val archive = JSONObject()
            .put(ServerSettings.SERVERS_NAME, ServerSettings.encodeServers(listOf(ServerConnection(KOKORO_ID, "Kokoro", KOKORO))))
            .put("speech_server_url", 3)
            .put("speech_server_voices", JSONArray().put(3))

        repo.restoreBackupValues(archive)

        assertEquals(listOf(KOKORO_ID), repo.settings.first().servers.readable!!.map { it.id })
    }

    private fun legacyArchive() = JSONObject()
        .put("speech_server_url", KOKORO)
        .put("speech_server_model", "kokoro")
        .put("speech_server_voice", "af_bella")
        .put("speech_server_voices", JSONArray().put("af_bella"))
        .put("read_aloud_provider", "openai")

    private operator fun androidx.datastore.preferences.core.MutablePreferences.plusAssign(other: Preferences) {
        other.asMap().forEach { (key, value) ->
            @Suppress("UNCHECKED_CAST")
            this[key as Preferences.Key<Any>] = value
        }
    }

    private companion object {
        const val KOKORO = "http://192.168.1.10:8880/v1"
        const val KOKORO_ID = "http://192.168.1.10:8880/v1"
        const val MOVED = "http://192.168.1.11:8880/v1"
        const val MOVED_ID = "http://192.168.1.11:8880/v1"
        const val OPENROUTER = "https://openrouter.ai/api/v1"
        const val OPENROUTER_ID = "https://openrouter.ai/api/v1"
        val LEGACY_URL = stringPreferencesKey("speech_server_url")
    }
}
