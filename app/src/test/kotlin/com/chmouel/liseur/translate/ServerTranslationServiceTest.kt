package com.chmouel.liseur.translate

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.chmouel.liseur.data.security.SecretCipher
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.data.settings.ServerChange
import com.chmouel.liseur.data.settings.ServerConnection
import com.chmouel.liseur.providers.ServerConnections
import com.chmouel.liseur.providers.ServiceAccounts
import com.chmouel.liseur.tts.OpenAiTtsClient
import com.chmouel.liseur.tts.ServerKeys
import java.io.File
import java.io.IOException
import java.net.InetAddress
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONObject
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@Config(sdk = [35], application = Application::class)
@RunWith(RobolectricTestRunner::class)
class ServerTranslationServiceTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var web: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var settings: AppSettingsRepository
    private lateinit var service: ServerTranslationService
    private lateinit var connections: ServerConnections
    private lateinit var feature: ServiceTranslate
    private lateinit var url: String
    private var failWrites = false

    @Before
    fun setUp(): Unit = runBlocking {
        web = MockWebServer()
        web.start(InetAddress.getByName("127.0.0.1"), 0)
        url = web.url("/v1").toString()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = PreferenceDataStoreFactory.create { File(folder.root, "app.preferences_pb") }
        settings = AppSettingsRepository(
            object : DataStore<Preferences> by store {
                override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                    if (failWrites) throw IOException("No space left on device")
                    return store.updateData(transform)
                }
            },
        )
        val cipher = SecretCipher("test").apply {
            keyForTesting = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
        connections = ServerConnections(settings, ServerKeys(folder.newFolder("keys"), cipher), client = OpenAiTtsClient(), scope = scope)
        service = ServerTranslationService(connections, settings, OpenAiChatClient(), scope)
        feature = ServiceTranslate(settings, connections, ServiceAccounts(RuntimeEnvironment.getApplication(), connections), listOf(service), scope = scope)
        val id = (settings.addServer("Home", url) as ServerChange.Saved).id
        settings.selectTranslationServer(id)
    }

    @After
    fun tearDown() {
        scope.cancel()
        web.close()
    }

    private fun completion(content: String) = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "application/json")
        .body("""{"choices":[{"message":{"role":"assistant","content":${JSONObject.quote(content)}},"finish_reason":"stop"}]}""")
        .build()

    private fun body() = JSONObject(web.takeRequest().body!!.utf8())

    private suspend inline fun <reified E : Exception> expect(crossinline block: suspend () -> Unit) {
        val error = try {
            block()
            null
        } catch (e: Exception) {
            e
        }
        assertTrue("Expected ${E::class.simpleName}, got $error", error is E)
    }

    @Test
    fun `a server is offered for translation unless it lists only speech models`(): Unit = runBlocking {
        fun listing(vararg ids: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
            .body(ids.joinToString(",", """{"data":[""", "]}") { """{"id":"$it"}""" }).build()
        val home = settings.settings.first().servers.readable!!.single()
        web.enqueue(listing("kokoro", "tts-1"))
        assertFalse(service.offers(home))
        assertFalse(service.offers(home))
        assertEquals(1, web.requestCount)

        val chat = ServerConnection("chat", "Chat", web.url("/chat/v1").toString())
        web.enqueue(listing("kokoro", "llama3"))
        assertTrue(service.offers(chat))

        val down = ServerConnection("down", "Down", web.url("/down/v1").toString())
        web.enqueue(MockResponse.Builder().code(500).build())
        assertTrue(service.offers(down))
        assertFalse(service.offers(ServerConnection("gradium", "Gradium", "https://eu.api.gradium.ai/api")))
    }

    @Test
    fun `inspecting whole book settings needs no requests and tracks the selected recipe`(): Unit = runBlocking {
        settings.setTranslationServerModel(url, "llama3")
        val identity = feature.wholeBookIdentity("en", "fr")
        settings.editTranslationServer(url) { it.copy(instructions = "Use tu.") }
        assertFalse(identity == feature.wholeBookIdentity("en", "fr"))
        settings.setTranslationProvider("device")
        expect<TranslationError.NotSetUp> { feature.wholeBookIdentity("en", "fr") }
        assertEquals(0, web.requestCount)
    }

    @Test
    fun `a pinned preview sends no passage and rejects another identity or provider`(): Unit = runBlocking {
        settings.setTranslationServerModel(url, "llama3")
        val pinned = feature.openPinned("en", "fr")
        val identity = pinned.answering()
        assertEquals(0, web.requestCount)
        expect<TranslationError.SettingsChanged> { feature.openPinned("en", "fr", identity + "other") }
        expect<WholeBookSameLanguageException> { feature.openPinned("en-GB", "en") }
        settings.setTranslationProvider("device")
        expect<TranslationError.NotSetUp> { feature.openPinned("en", "fr") }
        expect<TranslationError.SettingsChanged> { pinned.answering() }
        pinned.close()
        assertEquals(0, web.requestCount)
    }

    @Test
    fun `pinned runs stop at target preference recipe and key changes before cache lookup or submission`(): Unit = runBlocking {
        settings.setTranslationServerModel(url, "llama3")
        val target = feature.openPinned("en", "fr")
        settings.setTranslationTarget("de")
        expect<TranslationError.SettingsChanged> { target.answering() }
        target.close()

        val recipe = feature.openPinned("en", "fr")
        val identity = recipe.answering()
        settings.editTranslationServer(url) { it.copy(instructions = "Use tu.") }
        expect<TranslationError.SettingsChanged> { recipe.translate("It rains.", null, identity) }
        recipe.close()

        val credential = feature.openPinned("en", "fr")
        val changed = CompletableDeferred<Unit>()
        connections.commitKey(ServerConnections.originOf(url)!!, "new-key", done = { changed.complete(Unit) })
        changed.await()
        expect<TranslationError.SettingsChanged> { credential.answering() }
        credential.close()
        assertEquals(0, web.requestCount)
    }

    @Test
    fun `whole book pricing uses only model metadata and an absent price stays unknown`(): Unit = runBlocking {
        settings.setTranslationServerModel(url, "llama3")
        web.enqueue(MockResponse.Builder().code(200).body("""{"data":[{"id":"llama3"}]}""").build())
        assertEquals(null, feature.wholeBookPrice())
        assertEquals("/v1/models", web.takeRequest().url.encodedPath)
        web.enqueue(
            MockResponse.Builder().code(200)
                .body("""{"data":[{"id":"llama3","pricing":{"input":0.1,"output":0.2}}]}""").build(),
        )
        assertEquals(TextPrice(0.1, 0.2), feature.wholeBookPrice())
        assertEquals("/v1/models", web.takeRequest().url.encodedPath)
    }

    @Test
    fun `preview prompt sizing freezes instructions and counts actual context and book markers`(): Unit = runBlocking {
        settings.setTranslationServerModel(url, "llama3")
        settings.editTranslationServer(url) { it.copy(instructions = "Use tu.") }
        val size = feature.wholeBookPrompt("en", "fr")
        val request = SentenceRequest(PageSentence("It rains.", "chapter", null, null), "id", "Clouds.", null, "Book.")
        settings.editTranslationServer(url) { it.copy(instructions = "Different instructions.") }
        assertEquals(
            TranslationPrompt.system("en", "fr", true, "Use tu.", true).length +
                TranslationPrompt.user("It rains.", "Clouds.", "Book.").length,
            size(request),
        )
        assertEquals(0, web.requestCount)
    }

    @Test
    fun `per book directions stay apart from exact text and only matching terms travel`(): Unit = runBlocking {
        settings.setTranslationServerModel(url, "llama3")
        settings.editTranslationServer(url) { it.copy(instructions = "Use tu.") }
        val preferences = BookTranslationPreferences("Keep first person", listOf(TranslationTerm("Anna", "Anne"), TranslationTerm("Bob", "Robert")))
        web.enqueue(completion("Je suis partie."))
        val run = service.open("en", "fr", settings.settings.first(), preferences)
        val source = "  Anna said: I left.  "
        run.translate(source, null)
        val messages = body().getJSONArray("messages")
        val system = messages.getJSONObject(0).getString("content")
        assertTrue(system.contains("Keep first person"))
        assertTrue(system.contains("Use tu."))
        assertTrue(system.contains("Anna → Anne"))
        assertFalse(system.contains("Robert"))
        assertTrue(messages.getJSONObject(1).getString("content").contains("\n$source\n"))
        run.close()
    }

    @Test
    fun `the server's options shape the request`(): Unit = runBlocking {
        settings.setTranslationServerModel(url, "llama3")
        settings.editTranslationServer(url) {
            it.copy(temperature = 0.7, context = false, instructions = "Use tu.", maxTokens = 300)
        }
        web.enqueue(completion("Il pleut."))

        assertEquals("Il pleut.", service.translate("It rains.", "en", "fr", "It was grey."))

        val body = body()
        assertEquals("llama3", body.getString("model"))
        assertEquals(0.7, body.getDouble("temperature"), 0.0)
        assertEquals(300, body.getInt("max_tokens"))
        assertFalse(body.has("keep_alive"))
        val messages = body.getJSONArray("messages")
        assertTrue("Use tu." in messages.getJSONObject(0).getString("content"))
        val user = messages.getJSONObject(1).getString("content")
        assertTrue(user, "It rains." in user)
        assertFalse(user, "It was grey." in user)
    }

    @Test
    fun `a model that refused the temperature is asked without it from then on`(): Unit = runBlocking {
        settings.setTranslationServerModel(url, "o4-mini")
        web.enqueue(
            MockResponse.Builder().code(400)
                .body("""{"error":{"message":"Unsupported value: 'temperature' does not support 0 with this model."}}""")
                .build(),
        )
        web.enqueue(completion("Il pleut."))
        val run = service.open("en", "fr", settings.settings.first())

        assertEquals("Il pleut.", run.translate("It rains.", null))
        assertTrue(settings.settings.first().translationStates.values.single().noTemperature)
        assertTrue(body().has("temperature"))
        assertFalse(body().has("temperature"))

        // The run opened before it learnt this asks once, without it.
        web.enqueue(completion("Il neige."))
        assertEquals("Il neige.", run.translate("It snows.", null))
        assertEquals(3, web.requestCount)
        assertFalse(body().has("temperature"))

        // Another model is asked with one again.
        settings.setTranslationServerModel(url, "gpt-4o-mini")
        web.enqueue(completion("Il vente."))
        assertEquals("Il vente.", service.translate("It is windy.", "en", "fr", null))
        assertEquals(0, body().getInt("temperature"))
    }

    @Test
    fun `a refused temperature that cannot be saved still returns the translation`(): Unit = runBlocking {
        settings.setTranslationServerModel(url, "o4-mini")
        val refusal = MockResponse.Builder().code(400)
            .body("""{"error":{"message":"Unsupported value: 'temperature' does not support 0 with this model."}}""")
            .build()
        web.enqueue(refusal)
        web.enqueue(completion("Il pleut."))
        failWrites = true

        assertEquals("Il pleut.", service.translate("It rains.", "en", "fr", null))
        assertFalse(settings.settings.first().translationStates.values.single().noTemperature)
        assertTrue(body().has("temperature"))
        assertFalse(body().has("temperature"))

        // Nothing was learnt, so the next request is refused and retried again.
        web.enqueue(refusal)
        web.enqueue(completion("Il neige."))
        assertEquals("Il neige.", service.translate("It snows.", "en", "fr", null))
        assertTrue(body().has("temperature"))
        assertFalse(body().has("temperature"))
    }
}
