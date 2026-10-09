package com.chmouel.liseur.providers

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.chmouel.liseur.data.security.SecretCipher
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.data.settings.ServerChange
import com.chmouel.liseur.tts.ServerKeys
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = Application::class)
@RunWith(RobolectricTestRunner::class)
class ServerConnectionsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var connections: ServerConnections
    private val told = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val settings = AppSettingsRepository(PreferenceDataStoreFactory.create { File(folder.root, "app.preferences_pb") })
        val cipher = SecretCipher("test").apply {
            keyForTesting = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
        connections = ServerConnections(settings, ServerKeys(folder.newFolder("keys"), cipher), scope = scope)
        connections.addListener { told += it }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private var drafts = 0

    private fun save(draft: String, id: String?, name: String, url: String) =
        CompletableDeferred<ServerChange>().also { done -> connections.save(draft, id, name, url, name) { done.complete(it) } }

    private suspend fun add(name: String, url: String): String =
        (withTimeout(WAIT) { save("add-${drafts++}", null, name, url).await() } as ServerChange.Saved).id

    private suspend fun update(id: String, name: String, url: String): ServerChange =
        withTimeout(WAIT) { save("edit-${drafts++}", id, name, url).await() }

    private suspend fun setKey(url: String, key: String) {
        val done = CompletableDeferred<Boolean>()
        connections.commitKey(origin(url), key) { done.complete(it) }
        assertTrue(withTimeout(WAIT) { done.await() })
    }

    private suspend fun delete(id: String) {
        val done = CompletableDeferred<Boolean>()
        connections.delete(id) { done.complete(it) }
        assertTrue(withTimeout(WAIT) { done.await() })
    }

    private fun origin(url: String) = ServerConnections.originOf(url)!!

    @Test
    fun aKeyChangedThroughOneServerEndsWhatEveryServerOnItsOriginWasDoing(): Unit = runBlocking {
        add("Speech", SPEECH)
        add("Chat", CHAT)
        val before = connections.generation(origin(SPEECH)).value
        told.clear()

        setKey(CHAT, "secret")

        // Read aloud on the other server heard of it, and its older replies are dropped.
        assertEquals(listOf(origin(SPEECH)), told.toList())
        assertTrue(connections.generation(origin(SPEECH)).value > before)
        assertEquals("secret", connections.key(origin(SPEECH)))
        assertEquals(0, connections.generation(origin(ELSEWHERE)).value)
    }

    @Test
    fun deletingAServerKeepsTheKeyWhileAnotherServerSharesItsOrigin(): Unit = runBlocking {
        val speech = add("Speech", SPEECH)
        val chat = add("Chat", CHAT)
        setKey(SPEECH, "secret")

        delete(speech)
        assertTrue(connections.keyConfigured(origin(SPEECH)).value)
        assertEquals("secret", connections.key(origin(SPEECH)))

        delete(chat)
        assertEquals(null, connections.key(origin(SPEECH)))
    }

    @Test
    fun movingAServerTellsBothTheOldAndTheNewOrigin(): Unit = runBlocking {
        val id = add("Speech", SPEECH)
        told.clear()

        assertTrue(update(id, "Speech", ELSEWHERE) is ServerChange.Saved)

        assertEquals(setOf(origin(SPEECH), origin(ELSEWHERE)), told.toSet())
    }

    @Test
    fun renamingAServerEndsNothing(): Unit = runBlocking {
        val id = add("Speech", SPEECH)
        told.clear()

        assertEquals(ServerChange.Saved(id), update(id, "Kokoro", SPEECH))

        assertTrue(told.isEmpty())
    }

    @Test
    fun anEditOntoAServerAlreadyListedEndsNothing(): Unit = runBlocking {
        val id = add("Speech", SPEECH)
        add("Elsewhere", ELSEWHERE)
        val before = connections.generation(origin(SPEECH)).value
        told.clear()

        assertEquals(ServerChange.Duplicate, update(id, "Speech", ELSEWHERE))

        assertTrue(told.isEmpty())
        assertEquals(before, connections.generation(origin(SPEECH)).value)
    }

    @Test
    fun aKeyChangeWaitsForAWriteAlreadyCheckedAndStopsTheOnesAfterIt(): Unit = runBlocking {
        add("Speech", SPEECH)
        val asked = connections.generation(origin(SPEECH)).value
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val write = async(Dispatchers.Default) {
            connections.unlessChanged(origin(SPEECH), asked) {
                inside.complete(Unit)
                release.await()
                "written"
            }
        }
        withTimeout(WAIT) { inside.await() }

        val keySaved = CompletableDeferred<Boolean>()
        connections.commitKey(origin(SPEECH), "secret") { keySaved.complete(it) }
        delay(200)
        // The key waits for the write that was still current when it began.
        assertFalse(keySaved.isCompleted)
        assertEquals(null, connections.key(origin(SPEECH)))

        release.complete(Unit)
        assertEquals("written", withTimeout(WAIT) { write.await() })
        assertTrue(withTimeout(WAIT) { keySaved.await() })
        // One asked with the old key is now dropped.
        assertEquals(null, connections.unlessChanged(origin(SPEECH), asked) { "late" })
    }

    @Test
    fun aSaveMadeWhileItsDraftIsStillAddingTheServerEditsIt(): Unit = runBlocking {
        val adding = save("draft", null, "Speech", SPEECH)
        val renamed = save("draft", null, "Kokoro", SPEECH)

        val id = (withTimeout(WAIT) { adding.await() } as ServerChange.Saved).id
        assertEquals(ServerChange.Saved(id), withTimeout(WAIT) { renamed.await() })
        assertEquals(listOf("Kokoro"), connections.servers.first().readable?.map { it.name })
        assertEquals(id, connections.drafts.value["draft"])
    }

    @Test
    fun aDraftFollowsItsServerToANewAddress(): Unit = runBlocking {
        val id = add("Speech", SPEECH)

        val moved = withTimeout(WAIT) { save("draft", id, "Speech", ELSEWHERE).await() } as ServerChange.Saved

        assertTrue(moved.id != id)
        assertEquals(moved.id, connections.drafts.value["draft"])
        // A later save from an editor still holding the old id edits the moved server.
        assertEquals(moved, withTimeout(WAIT) { save("draft", id, "Hosted", ELSEWHERE).await() })
        assertEquals(listOf("Hosted"), connections.servers.first().readable?.map { it.name })
    }

    @Test
    fun anEditPutBackWhileTheEarlierSaveIsPendingIsWhatStays(): Unit = runBlocking {
        val id = add("Speech", SPEECH)
        told.clear()

        val moving = save("draft", id, "Speech", ELSEWHERE)
        val back = save("draft", id, "Speech", SPEECH)
        withTimeout(WAIT) { moving.await() }
        withTimeout(WAIT) { back.await() }

        assertEquals(listOf(SPEECH), connections.servers.first().readable?.map { it.url })
        // A save that changes nothing tells no one.
        told.clear()
        assertEquals(ServerChange.Saved(id), update(id, "", SPEECH))
        assertTrue(told.isEmpty())
    }

    private companion object {
        const val SPEECH = "http://192.168.1.10:8880/v1"
        const val CHAT = "http://192.168.1.10:8880/chat/v1"
        const val ELSEWHERE = "https://openrouter.ai/api/v1"
        const val WAIT = 20_000L
    }
}
