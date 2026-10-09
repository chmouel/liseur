package com.chmouel.liseur.providers

import com.chmouel.liseur.data.remote.LocalNetworkAccess
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.data.settings.ServerChange
import com.chmouel.liseur.data.settings.ServerConnection
import com.chmouel.liseur.data.settings.ServerList
import com.chmouel.liseur.data.settings.ServerSettings
import com.chmouel.liseur.tts.KeyCommits
import com.chmouel.liseur.tts.OpenAiTts
import com.chmouel.liseur.tts.OpenAiTtsClient
import com.chmouel.liseur.tts.ServerKeys
import com.chmouel.liseur.tts.SpeechError
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * The servers and keys on the Services page, shared by every feature that
 * reaches one. Each change tells the features first, so they end what
 * they were doing with the old address or key, then moves the owner's
 * [generation] on, so a reply asked before it is dropped.
 *
 * An owner is a server's origin (see [ServerKeys.origin]), whose key every
 * server on it shares, or an account's own name such as "gemini".
 * Changes outlive the screen that made them. Main thread only.
 */
internal class ServerConnections(
    private val settings: AppSettingsRepository,
    private val keys: ServerKeys,
    val localNetwork: LocalNetworkAccess = LocalNetworkAccess.Unrestricted,
    private val client: OpenAiTtsClient = OpenAiTtsClient(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    val servers: Flow<ServerList> = settings.settings.map { it.servers }.distinctUntilChanged()

    /** The server read aloud uses, when it uses one. */
    val readAloudServer: Flow<String?> = settings.settings.map { s ->
        s.readAloudServerConnection?.id?.takeIf { s.readAloudProvider == ServerSettings.SERVER_PROVIDER }
    }.distinctUntilChanged()

    /** The server translation uses, when it uses one. */
    val translationServer: Flow<String?> = settings.settings.map { s ->
        s.translationServerConnection?.id?.takeIf { s.translationProvider == ServerSettings.SERVER_PROVIDER }
    }.distinctUntilChanged()

    private val lock = Mutex()
    private val keyCommits = KeyCommits(scope)

    /** The owner whose key could not be saved or removed, if any. */
    val keyFailure: StateFlow<String?> = keyCommits.failure

    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val generations = ConcurrentHashMap<String, MutableStateFlow<Int>>()

    /** Has [listener] told of a change to an owner's address or key, before it is made. */
    fun addListener(listener: (owner: String) -> Unit) {
        listeners += listener
    }

    /** Moves on with every change to [owner]'s servers or key. */
    fun generation(owner: String): StateFlow<Int> = mutable(owner).asStateFlow()

    private fun mutable(owner: String) = generations.getOrPut(owner) { MutableStateFlow(0) }

    // Held by a change while it is written and by a write made from a reply, so neither runs inside the other.
    private val writes = Mutex()

    private suspend fun <T> changing(owners: Set<String>, block: suspend () -> T): T {
        owners.forEach { owner -> listeners.forEach { it(owner) } }
        // Moved on before as well as after, so nothing asked while the change is written is kept.
        owners.forEach { owner -> mutable(owner).update { it + 1 } }
        return writes.withLock {
            try {
                block()
            } finally {
                owners.forEach { owner -> mutable(owner).update { it + 1 } }
            }
        }
    }

    /**
     * Runs [block], which saves what a reply asked at [generation] of
     * [owner] chose, only while that is still [owner]'s generation, and
     * holds any change to it until [block] is done. Null when it moved on.
     */
    suspend fun <T> unlessChanged(owner: String, generation: Int, block: suspend () -> T): T? = writes.withLock {
        if (mutable(owner).value == generation) block() else null
    }

    fun keyConfigured(origin: String): StateFlow<Boolean> = keys.configured(origin)

    suspend fun key(origin: String): String? = keys.get(origin)

    /** Runs [op] on [owner]'s key after every key change before it; the features hear of it first. */
    fun commitKey(owner: String, op: suspend () -> Unit, done: (Boolean) -> Unit = {}) =
        keyCommits.submit(owner, { changing(setOf(owner)) { op() } }, done)

    /** Saves [key] for every server on [origin]. */
    fun commitKey(origin: String, key: String, done: (Boolean) -> Unit) =
        commitKey(origin, { keys.set(origin, key) }, done)

    /** Removes the key of the servers on [origin]; other origins keep theirs. */
    fun commitKeyRemoval(origin: String, done: (Boolean) -> Unit = {}) = commitKey(origin, { keys.clear(origin) }, done)

    private val savedDrafts = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * The server each editor's [save]s landed on, by the editor's draft
     * token. An editor recreated, or closed, while a save was still being
     * written follows it here, so it never stays on an id that a new
     * address replaced, nor lists the server it was adding a second time.
     * One entry per editor that saved, for as long as the app runs.
     */
    val drafts: StateFlow<Map<String, String>> = savedDrafts.asStateFlow()

    /**
     * Saves what the editor [draft] holds: the server named [name] at
     * [url], listed as [newName] when the draft has no server yet. Saves
     * are written in turn, so one made while the draft's first save is
     * still listing the server edits that server, and the last one made
     * is what stays, even when it only puts back what an earlier one
     * changed. One that changes nothing writes nothing. A new address ends
     * what features read from the old one; an edit refused, such as onto
     * a server already listed, tells no one.
     */
    fun save(draft: String, id: String?, name: String, url: String, newName: String, done: (ServerChange) -> Unit = {}) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val change = lock.withLock {
                val target = savedDrafts.value[draft] ?: id
                val change = if (target == null) settings.addServer(newName, url) else update(target, name, url)
                if (change is ServerChange.Saved) savedDrafts.update { it + (draft to change.id) }
                change
            }
            done(change)
        }
    }

    private suspend fun update(id: String, name: String, url: String): ServerChange {
        val listed = settings.settings.first().servers.readable ?: return ServerChange.Unreadable
        val before = listed.firstOrNull { it.id == id } ?: return ServerChange.Invalid
        if (before.url == url.trim()) {
            if (name.trim().let { it.isEmpty() || it == before.name }) return ServerChange.Saved(id)
            return settings.updateServer(id, name, url)
        }
        val newId = ServerConnection.idOf(url.trim()) ?: return ServerChange.Invalid
        if (newId != id && listed.any { it.id == newId }) return ServerChange.Duplicate
        return changing(setOfNotNull(originOf(before.url), originOf(url))) { settings.updateServer(id, name, url) }
    }

    /**
     * Forgets the server [id], and its key when no other server is on its
     * origin. A feature using it goes back to the device. With the
     * editor's [draft], the server its last save landed on is the one
     * forgotten, so a new address still being written is not missed.
     */
    fun delete(id: String, draft: String? = null, done: (Boolean) -> Unit = {}) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val deleted = lock.withLock {
                val server = listed(draft?.let { savedDrafts.value[it] } ?: id) ?: return@withLock false
                val origin = originOf(server.url)
                changing(setOfNotNull(origin)) {
                    val deleted = settings.deleteServer(server.id)
                    val shared = settings.settings.first().servers.readable.orEmpty().any { originOf(it.url) == origin }
                    if (deleted && origin != null && !shared) forgetKey(origin)
                    deleted
                }
            }
            done(deleted)
        }
    }

    private suspend fun forgetKey(origin: String) {
        try {
            keys.clear(origin)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The server is gone either way; a key left behind is reused if it comes back.
        }
    }

    private suspend fun listed(id: String): ServerConnection? =
        settings.settings.first().servers.readable?.firstOrNull { it.id == id }

    /**
     * Writes a backup's settings [values] through [restore]. When they hold
     * servers or read aloud's choice, it is a change to every origin listed
     * before, so what used a server the archive replaces ends first. An
     * archive that is refused anyway stops nothing.
     */
    suspend fun restore(values: JSONObject, restore: suspend () -> Unit) {
        if (!ServerSettings.touchesServers(values)) return restore()
        ServerSettings.validateBackup(values)
        // On the scope's thread, where the features hear of changes.
        scope.async {
            lock.withLock {
                val owners = settings.settings.first().servers.readable.orEmpty().mapNotNullTo(mutableSetOf()) { originOf(it.url) }
                changing(owners) { restore() }
            }
        }.await()
    }

    /**
     * How many models the server at [url] lists, asked with its key: that
     * it answers and takes the key, whatever it is used for.
     */
    suspend fun test(url: String): Result<Int> {
        val base = OpenAiTts.baseUrl(url) ?: return Result.failure(IllegalArgumentException("Not a server address"))
        return try {
            if (localNetwork.blocks(base.toString())) throw SpeechError.LocalNetworkBlocked()
            Result.success(client.allModels(base, keys.get(ServerKeys.origin(base))).size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Includes a key that cannot be read back, which the test reports like any other failure.
            Result.failure(e)
        }
    }

    companion object {
        /** Whose key the server at [url] uses. */
        fun originOf(url: String): String? = OpenAiTts.baseUrl(url)?.let(ServerKeys::origin)
    }
}
