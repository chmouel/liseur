package com.chmouel.liseur.data.settings

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.chmouel.liseur.tts.OpenAiTts
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * An OpenAI-compatible server the reader added on the Services page.
 *
 * [id] is the server's normalised base address, so the same server can
 * never be listed twice and nothing random is ever stored. Editing the
 * address changes the id, and every reference to it is rewritten in the
 * same write.
 */
data class ServerConnection(val id: String, val name: String, val url: String) {
    /** The host, with its port when it is not the scheme's own, for a row's supporting line. */
    val host: String
        get() = OpenAiTts.baseUrl(url)?.let { base ->
            if (base.port == HttpUrl.defaultPort(base.scheme)) base.host else "${base.host}:${base.port}"
        } ?: url

    companion object {
        /** The id of the server at [url], or null when it is not a server address. */
        fun idOf(url: String): String? = OpenAiTts.baseUrl(url)?.toString()
    }
}

/** The saved servers, or that what is saved could not be read and must be left untouched. */
sealed interface ServerList {
    data class Readable(val servers: List<ServerConnection>) : ServerList

    /** The stored bytes are kept as they are; nothing edits them until a restore replaces them. */
    data object Unreadable : ServerList

    val readable: List<ServerConnection>? get() = (this as? Readable)?.servers

    companion object {
        val Empty: ServerList = Readable(emptyList())
    }
}

/** What read aloud uses on one server: its model, its voice and the voices it offers (empty offers all). */
data class ServerSpeechState(
    val model: String? = null,
    val voice: String? = null,
    val voices: Set<String> = emptySet(),
)

/** What an add or edit of a server came to. */
sealed interface ServerChange {
    data class Saved(val id: String) : ServerChange
    data object Duplicate : ServerChange
    data object Invalid : ServerChange
    data object Unreadable : ServerChange
}

/**
 * The saved servers and read aloud's state on each, stored as JSON in
 * the app settings. Backed up with the settings archive, never synced:
 * which servers a device reaches is that device's business.
 */
internal object ServerSettings {
    const val SERVERS_NAME = "ai_servers"
    const val READ_ALOUD_SERVER_NAME = "read_aloud_server"
    const val READ_ALOUD_STATE_NAME = "read_aloud_server_state"

    val SERVERS = stringPreferencesKey(SERVERS_NAME)
    val READ_ALOUD_SERVER = stringPreferencesKey(READ_ALOUD_SERVER_NAME)
    val READ_ALOUD_STATE = stringPreferencesKey(READ_ALOUD_STATE_NAME)

    /** Servers whose read-aloud model and voice are still to be chosen from their lists; never backed up. */
    val READ_ALOUD_UNSETTLED = stringSetPreferencesKey("read_aloud_unsettled_servers")

    val PROVIDER = stringPreferencesKey("read_aloud_provider")
    val VOICE_PREFERENCES = stringPreferencesKey("read_aloud_voice_preferences")

    /** The read-aloud provider id of a listed server. */
    const val SERVER_PROVIDER = "openai"

    // The single server read aloud had before servers were listed.
    private const val LEGACY_URL_NAME = "speech_server_url"
    private const val LEGACY_VOICE_NAME = "speech_server_voice"
    private const val LEGACY_MODEL_NAME = "speech_server_model"
    private const val LEGACY_VOICES_NAME = "speech_server_voices"
    private val LEGACY_URL = stringPreferencesKey(LEGACY_URL_NAME)
    private val LEGACY_VOICE = stringPreferencesKey(LEGACY_VOICE_NAME)
    private val LEGACY_MODEL = stringPreferencesKey(LEGACY_MODEL_NAME)
    private val LEGACY_VOICES = stringSetPreferencesKey(LEGACY_VOICES_NAME)
    private val LEGACY_UNSETTLED = stringPreferencesKey("speech_server_unsettled_url")
    private val LEGACY_KEYS = listOf(LEGACY_URL, LEGACY_VOICE, LEGACY_MODEL, LEGACY_VOICES, LEGACY_UNSETTLED)

    /** The saved servers; unreadable when they or anything referring to them do not hold together. */
    fun servers(p: Preferences): ServerList = try {
        val servers = decodeServers(p[SERVERS])
        requireListed(servers.map { it.id }.toSet(), decodeStates(p[READ_ALOUD_STATE]).keys, p[READ_ALOUD_SERVER])
        ServerList.Readable(servers)
    } catch (_: IllegalArgumentException) {
        ServerList.Unreadable
    }

    private fun requireListed(ids: Set<String>, states: Set<String>, readAloud: String?) {
        if (!ids.containsAll(states)) invalid("State for an unlisted server")
        if (readAloud != null && readAloud !in ids) invalid("Read aloud uses an unlisted server")
    }

    /** Read aloud's state per server; empty when the servers cannot be read. */
    fun states(p: Preferences): Map<String, ServerSpeechState> = try {
        decodeStates(p[READ_ALOUD_STATE])
    } catch (_: IllegalArgumentException) {
        emptyMap()
    }

    /** The servers in [json]; throws [IllegalArgumentException] for anything but a valid list. */
    fun decodeServers(json: String?): List<ServerConnection> {
        if (json == null) return emptyList()
        val array = try {
            JSONArray(json)
        } catch (e: JSONException) {
            throw IllegalArgumentException("Malformed server list", e)
        }
        val servers = (0 until array.length()).map { i ->
            val entry = array.opt(i) as? JSONObject ?: invalid("Malformed server")
            val url = entry.opt("url") as? String ?: invalid("Malformed server address")
            val name = entry.opt("name") as? String ?: invalid("Malformed server name")
            val id = entry.opt("id") as? String ?: invalid("Malformed server id")
            if (name.isBlank() || ServerConnection.idOf(url) != id) invalid("Invalid server")
            ServerConnection(id, name, url)
        }
        if (servers.map { it.id }.toSet().size != servers.size) invalid("Server listed twice")
        return servers
    }

    fun encodeServers(servers: List<ServerConnection>): String = JSONArray().apply {
        servers.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url)) }
    }.toString()

    /** Read aloud's state per server in [json]; throws [IllegalArgumentException] for anything malformed. */
    fun decodeStates(json: String?): Map<String, ServerSpeechState> {
        if (json == null) return emptyMap()
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw IllegalArgumentException("Malformed server state", e)
        }
        return buildMap {
            root.keys().forEach { id ->
                if (ServerConnection.idOf(id) != id) invalid("Invalid server state id")
                val entry = root.opt(id) as? JSONObject ?: invalid("Malformed server state")
                val voices = when (val raw = entry.opt("voices")) {
                    null -> emptySet()
                    is JSONArray -> (0 until raw.length()).map { raw.opt(it) as? String ?: invalid("Malformed voices") }.toSet()
                    else -> invalid("Malformed voices")
                }
                put(id, ServerSpeechState(entry.optionalString("model"), entry.optionalString("voice"), voices))
            }
        }
    }

    fun encodeStates(states: Map<String, ServerSpeechState>): String = JSONObject().apply {
        states.toSortedMap().forEach { (id, state) ->
            put(
                id,
                JSONObject().apply {
                    state.model?.let { put("model", it) }
                    state.voice?.let { put("voice", it) }
                    if (state.voices.isNotEmpty()) put("voices", JSONArray(state.voices.sorted()))
                },
            )
        }
    }.toString()

    private fun JSONObject.optionalString(name: String): String? = when (val raw = opt(name)) {
        null -> null
        is String -> raw.takeIf { it.isNotBlank() }
        else -> invalid("Malformed $name")
    }

    private fun invalid(message: String): Nothing = throw IllegalArgumentException(message)

    /** The server read aloud is set to use, when it is listed. */
    fun readAloudServer(p: Preferences): ServerConnection? =
        servers(p).readable?.firstOrNull { it.id == p[READ_ALOUD_SERVER] }

    fun hasLegacy(p: Preferences): Boolean = LEGACY_KEYS.any { it in p }

    /**
     * Turns the single server of older versions into a listed one, with
     * its model, voices and unsettled mark, selected for read aloud when
     * read aloud used it, and removes the old keys in the same write so a
     * server deleted later never comes back. Does nothing when the saved
     * servers cannot be read.
     */
    fun migrateLegacy(p: MutablePreferences, name: (String) -> String) {
        val edit = ServerEdit.of(p) ?: return
        val legacy = LegacyServer(
            url = p[LEGACY_URL],
            model = p[LEGACY_MODEL],
            voice = p[LEGACY_VOICE],
            voices = p[LEGACY_VOICES].orEmpty(),
            unsettled = p[LEGACY_UNSETTLED]?.trim()?.takeIf { it == p[LEGACY_URL]?.trim() } != null,
        )
        edit.adopt(legacy, selected = p[PROVIDER] == SERVER_PROVIDER, name)
        edit.save()
        LEGACY_KEYS.forEach { p.remove(it) }
    }

    /**
     * Throws [IllegalArgumentException] unless the archive's servers and
     * their references hold together. References in an archive without a
     * server list are checked against the device's list by [settleRestore].
     */
    fun validateBackup(values: JSONObject) {
        val servers = if (values.has(SERVERS_NAME)) decodeServers(values.get(SERVERS_NAME) as? String ?: invalid("Malformed servers")) else null
        val ids = servers?.map { it.id }?.toSet()
        if (values.has(READ_ALOUD_STATE_NAME)) {
            val states = decodeStates(values.get(READ_ALOUD_STATE_NAME) as? String ?: invalid("Malformed server state"))
            if (ids != null && !ids.containsAll(states.keys)) invalid("State for an unlisted server")
        }
        if (values.has(READ_ALOUD_SERVER_NAME)) {
            val id = values.get(READ_ALOUD_SERVER_NAME) as? String ?: invalid("Malformed read-aloud server")
            if (ServerConnection.idOf(id) != id) invalid("Invalid read-aloud server")
            if (ids != null && id !in ids) invalid("Read aloud uses an unlisted server")
        }
        // An archive with a server list ignores the older single server's keys.
        if (servers != null) return
        for (key in listOf(LEGACY_URL_NAME, LEGACY_MODEL_NAME, LEGACY_VOICE_NAME)) {
            if (values.has(key) && values.get(key) !is String) invalid("Malformed $key")
        }
        if (values.has(LEGACY_VOICES_NAME)) {
            val voices = values.get(LEGACY_VOICES_NAME) as? JSONArray ?: invalid("Malformed voices")
            for (i in 0 until voices.length()) if (voices.get(i) !is String) invalid("Malformed voices")
        }
    }

    /**
     * Brings an archive of an older version's single server into [p]: the
     * server joins the list (or replaces its own entry), takes the
     * archive's model and voices, and is selected when the archive's read
     * aloud used it. An archive with servers of its own ignores these keys.
     * Throws [IllegalArgumentException] when the saved servers cannot be
     * read, as merging into them would overwrite what is kept.
     */
    fun restoreLegacy(p: MutablePreferences, values: JSONObject, name: (String) -> String) {
        if (values.has(SERVERS_NAME) || !values.has(LEGACY_URL_NAME)) return
        val edit = ServerEdit.of(p) ?: invalid("Saved servers cannot be read")
        val voices = values.optJSONArray(LEGACY_VOICES_NAME)
        val legacy = LegacyServer(
            url = values.optString(LEGACY_URL_NAME),
            model = values.optString(LEGACY_MODEL_NAME).takeIf { values.has(LEGACY_MODEL_NAME) },
            voice = values.optString(LEGACY_VOICE_NAME).takeIf { values.has(LEGACY_VOICE_NAME) },
            voices = voices?.let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }.orEmpty(),
            unsettled = false,
        )
        edit.adopt(legacy, selected = values.optString(PROVIDER.name) == SERVER_PROVIDER, name)
        edit.save()
    }

    /** Whether restoring [values] can change the servers, what each keeps, or which one read aloud uses. */
    fun touchesServers(values: JSONObject): Boolean =
        (SERVER_KEYS + PROVIDER.name).any(values::has)

    private val SERVER_KEYS = listOf(SERVERS_NAME, READ_ALOUD_STATE_NAME, READ_ALOUD_SERVER_NAME, LEGACY_URL_NAME)

    /**
     * Run in the restore's write once the archive is applied. An archive
     * with its own server list replaces the device's: what the device kept
     * for servers no longer listed goes, and read aloud goes back to the
     * device if its server went. An archive without one must refer only
     * to servers the device lists. Throws [IllegalArgumentException] when
     * that fails or the device's servers cannot be read, so nothing of the
     * restore is written.
     */
    fun settleRestore(p: MutablePreferences, values: JSONObject) {
        if (SERVER_KEYS.none(values::has)) return
        val ids = decodeServers(p[SERVERS]).map { it.id }.toSet()
        val replaced = values.has(SERVERS_NAME)
        val states = try {
            decodeStates(p[READ_ALOUD_STATE])
        } catch (e: IllegalArgumentException) {
            // Unreadable state the device kept goes with the list it belonged to.
            if (replaced) emptyMap() else throw e
        }.toMutableMap()
        if (replaced) {
            states.keys.retainAll(ids)
            if (p[READ_ALOUD_SERVER]?.let { it !in ids } == true) {
                p.remove(READ_ALOUD_SERVER)
                if (p[PROVIDER] == SERVER_PROVIDER) p.remove(PROVIDER)
            }
        }
        requireListed(ids, states.keys, p[READ_ALOUD_SERVER])
        p[READ_ALOUD_STATE] = encodeStates(states)
        val unsettled = p[READ_ALOUD_UNSETTLED].orEmpty().intersect(ids)
        if (unsettled.isEmpty()) p.remove(READ_ALOUD_UNSETTLED) else p[READ_ALOUD_UNSETTLED] = unsettled
    }

    private class LegacyServer(
        val url: String?,
        val model: String?,
        val voice: String?,
        val voices: Set<String>,
        val unsettled: Boolean,
    )

    private fun ServerEdit.adopt(legacy: LegacyServer, selected: Boolean, name: (String) -> String) {
        val url = legacy.url?.trim().orEmpty()
        val id = ServerConnection.idOf(url) ?: return
        val index = servers.indexOfFirst { it.id == id }
        if (index >= 0) servers[index] = servers[index].copy(url = url) else servers += ServerConnection(id, name(url), url)
        states[id] = ServerSpeechState(
            model = legacy.model?.trim()?.takeIf { it.isNotEmpty() },
            voice = legacy.voice?.trim()?.takeIf { it.isNotEmpty() },
            voices = legacy.voices,
        )
        if (legacy.unsettled) unsettled += id
        if (selected) p[READ_ALOUD_SERVER] = id
    }
}

/**
 * The servers and read aloud's state on them, read from [p] to be changed
 * and written back together by [save]. There is none when what is saved
 * cannot be read: nothing may write over it.
 */
internal class ServerEdit private constructor(
    val p: MutablePreferences,
    val servers: MutableList<ServerConnection>,
    val states: MutableMap<String, ServerSpeechState>,
    val unsettled: MutableSet<String>,
) {
    /** Drops the server [id], what read aloud kept on it, and read aloud's use of it. */
    fun forget(id: String): Boolean {
        if (!servers.removeAll { it.id == id }) return false
        states.remove(id)
        unsettled.remove(id)
        if (p[ServerSettings.READ_ALOUD_SERVER] == id) {
            p.remove(ServerSettings.READ_ALOUD_SERVER)
            if (p[ServerSettings.PROVIDER] == ServerSettings.SERVER_PROVIDER) p.remove(ServerSettings.PROVIDER)
        }
        return true
    }

    fun save() {
        p[ServerSettings.SERVERS] = ServerSettings.encodeServers(servers)
        p[ServerSettings.READ_ALOUD_STATE] = ServerSettings.encodeStates(states)
        if (unsettled.isEmpty()) p.remove(ServerSettings.READ_ALOUD_UNSETTLED) else p[ServerSettings.READ_ALOUD_UNSETTLED] = unsettled.toSet()
    }

    companion object {
        fun of(p: MutablePreferences): ServerEdit? {
            val servers = ServerSettings.servers(p).readable ?: return null
            return ServerEdit(
                p,
                servers.toMutableList(),
                ServerSettings.states(p).toMutableMap(),
                p[ServerSettings.READ_ALOUD_UNSETTLED].orEmpty().toMutableSet(),
            )
        }
    }
}

/** Runs [ServerSettings.migrateLegacy] once, before the settings are first read. */
internal class LegacySpeechServerMigration(private val name: (String) -> String) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean = ServerSettings.hasLegacy(currentData)

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().also { ServerSettings.migrateLegacy(it, name) }.toPreferences()

    override suspend fun cleanUp() = Unit
}

/** Whether this voice was remembered for read aloud on the listed server [id]. */
internal fun VoicePreference.ownedBy(id: String): Boolean = provider == ServerSettings.SERVER_PROVIDER && context == id
