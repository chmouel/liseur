package com.chmouel.liseur.tts

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.chmouel.liseur.data.security.SecretCipher
import com.chmouel.liseur.data.remote.LocalNetworkAccess
import com.chmouel.liseur.data.settings.AppSettingsRepository
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
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
class OpenAiSpeechServiceTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** A server whose model list names each model's voices; [gate] holds its voice list replies. */
    private class Server {
        val web = MockWebServer()
        val keys = CopyOnWriteArrayList<String>()

        @Volatile
        var gate: CountDownLatch? = null

        @Volatile
        var status = 200

        init {
            web.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    keys += request.headers["Authorization"].orEmpty()
                    val path = request.url.encodedPath
                    return when {
                        status != 200 -> MockResponse(code = status)
                        path.endsWith("/audio/voices") -> {
                            gate?.await(30, TimeUnit.SECONDS)
                            MockResponse(code = 404)
                        }
                        path.endsWith("/models") -> MockResponse(code = 200, body = MODELS)
                        else -> MockResponse(code = 404)
                    }
                }
            }
            web.start(InetAddress.getByName("127.0.0.1"), 0)
        }

        val url: String get() = web.url("/v1").toString()
    }

    private val servers = mutableListOf<Server>()
    private lateinit var scope: CoroutineScope
    private lateinit var settings: AppSettingsRepository
    private lateinit var keys: ServerKeys
    private val stops = CopyOnWriteArrayList<Unit>()

    private val control = object : SessionControl {
        override fun stop() {
            stops += Unit
        }

        override suspend fun switchVoice() = Unit

        override fun sessionLanguage(service: SpeechService): String? = sessionLanguage
    }

    private var sessionLanguage: String? = null

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        settings = AppSettingsRepository(PreferenceDataStoreFactory.create { File(folder.root, "app.preferences_pb") })
        val cipher = SecretCipher("test").apply {
            keyForTesting = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
        keys = ServerKeys(folder.newFolder("keys"), cipher)
    }

    @After
    fun tearDown() {
        servers.forEach { it.gate?.countDown() }
        scope.cancel()
        servers.forEach { it.web.close() }
    }

    private fun server() = Server().also { servers += it }

    private fun service() = OpenAiSpeechService(keys, settings, control, OpenAiTtsClient(), scope)

    @Test
    fun blockedLocalServerReceivesNoListsTestsOrSpeechUntilAccessIsGranted(): Unit = runBlocking {
        val server = server()
        var allowed = false
        val access = object : LocalNetworkAccess {
            override val required = true
            override val granted get() = allowed
            override suspend fun blocks(url: String?) = !granted && OpenAiTts.baseUrl(url.orEmpty()) == OpenAiTts.baseUrl(server.url)
        }
        val service = OpenAiSpeechService(keys, settings, control, OpenAiTtsClient(), scope, access)
        saveUrl(service, server.url)
        settings.setSpeechServerModelAndVoice("tts", "tts-1")
        val voice = service.voice(settings.settings.first())!!

        assertTrue(service.models(server.url).exceptionOrNull() is SpeechError.LocalNetworkBlocked)
        assertTrue(service.voices(server.url, "tts").exceptionOrNull() is SpeechError.LocalNetworkBlocked)
        assertTrue(service.test(server.url).exceptionOrNull() is SpeechError.LocalNetworkBlocked)
        val speech = try {
            voice.synthesizer.synthesize("Hello.")
            null
        } catch (e: SpeechError) {
            e
        }
        assertTrue(speech is SpeechError.LocalNetworkBlocked)
        assertTrue(server.keys.isEmpty())

        allowed = true
        assertTrue(service.models(server.url).isSuccess)
        assertTrue(server.keys.isNotEmpty())
    }

    @Test
    fun missingLocalPermissionDoesNotBlockOtherSpeechServers(): Unit = runBlocking {
        val local = server()
        val public = server()
        val access = object : LocalNetworkAccess {
            override val required = true
            override val granted = false
            override suspend fun blocks(url: String?) = OpenAiTts.baseUrl(url.orEmpty()) == OpenAiTts.baseUrl(local.url)
        }
        val service = OpenAiSpeechService(keys, settings, control, OpenAiTtsClient(), scope, access)

        assertTrue(service.models(local.url).exceptionOrNull() is SpeechError.LocalNetworkBlocked)
        assertTrue(service.models(public.url).isSuccess)
        assertTrue(local.keys.isEmpty())
        assertTrue(public.keys.isNotEmpty())
    }

    private suspend fun saveUrl(service: OpenAiSpeechService, url: String) {
        service.commitUrl(url)
        // Not settings.first { url }: a DataStore read racing the write can
        // start that collector past the write's version, and it then never
        // sees the new value. Once the save has landed, a plain read does.
        withTimeout(WAIT) { service.savingUrls.first { it == 0 } }
        assertEquals(url, settings.settings.first().speechServerUrl)
    }

    private suspend fun saveKey(service: OpenAiSpeechService, url: String, key: String) {
        val done = CompletableDeferred<Boolean>()
        service.commitKey(ServerKeys.origin(OpenAiTts.baseUrl(url)!!), key) { done.complete(it) }
        assertTrue(withTimeout(WAIT) { done.await() })
    }

    private suspend fun chooseModel(service: OpenAiSpeechService, url: String, model: String, settle: Boolean = true) =
        CompletableDeferred<Result<List<String>>>().also { done ->
            service.commitModel(url, model, changed = true, settle = settle) { done.complete(it) }
        }

    @Test
    fun eachServerOnlyEverSeesItsOwnKey(): Unit = runBlocking {
        val a = server()
        val b = server()
        val service = service()
        saveUrl(service, a.url)
        saveKey(service, a.url, "key-a")
        service.models(a.url).getOrThrow()

        saveUrl(service, b.url)
        service.models(b.url).getOrThrow()
        saveKey(service, b.url, "key-b")
        service.models(b.url).getOrThrow()
        service.models(a.url).getOrThrow()

        assertEquals(listOf("Bearer key-a", "Bearer key-a"), a.keys.toList())
        assertEquals(listOf("", "Bearer key-b"), b.keys.toList())
    }

    @Test
    fun aNewServerStaysUnsettledUntilItsModelAndVoiceAreChosen(): Unit = runBlocking {
        val a = server()
        saveUrl(service(), a.url)

        // As after the screen, or the app, was closed.
        val service = service()
        assertTrue(service.unsettled(a.url))
        // No key yet: the lists fail, and the server stays new.
        a.status = 401
        assertTrue(withTimeout(WAIT) { chooseModel(service, a.url, "tts").await() }.isFailure)
        assertTrue(service.unsettled(a.url))

        a.status = 200
        withTimeout(WAIT) { chooseModel(service, a.url, "tts").await() }.getOrThrow()
        assertFalse(service.unsettled(a.url))
        val s = settings.settings.first()
        assertEquals("tts" to "tts-1", s.speechServerModel to s.speechServerVoice)
    }

    @Test
    fun aSlowModelChoiceForTheOldServerIsNotSavedOnTheNewOne(): Unit = runBlocking {
        val a = server()
        val b = server()
        val service = service()
        saveUrl(service, a.url)
        val gate = CountDownLatch(1).also { a.gate = it }
        val slow = chooseModel(service, a.url, "tts")

        saveUrl(service, b.url)
        gate.countDown()
        withTimeout(WAIT) { slow.await() }

        val s = settings.settings.first()
        assertEquals(b.url, s.speechServerUrl)
        assertEquals(null to null, s.speechServerModel to s.speechServerVoice)
        assertTrue(service.unsettled(b.url))
    }

    @Test
    fun anOlderModelChoiceNeverOverwritesANewerOne(): Unit = runBlocking {
        val a = server()
        val service = service()
        saveUrl(service, a.url)
        val gate = CountDownLatch(1).also { a.gate = it }
        val older = chooseModel(service, a.url, "older", settle = true)
        // Only the older choice's voice list is held.
        withTimeout(WAIT) { while (a.keys.isEmpty()) delay(10) }
        a.gate = null
        val newer = chooseModel(service, a.url, "newer", settle = false)
        withTimeout(WAIT) { newer.await() }
        gate.countDown()
        withTimeout(WAIT) { older.await() }

        assertEquals("newer", settings.settings.first().speechServerModel)
        // Nor does it settle the server: the newer choice did not.
        assertTrue(service.unsettled(a.url))
    }

    @Test
    fun aReopenedScreenSeesAModelChoiceStillAskingForItsVoices(): Unit = runBlocking {
        val a = server()
        val service = service()
        saveUrl(service, a.url)
        val gate = CountDownLatch(1).also { a.gate = it }
        val choice = chooseModel(service, a.url, "tts")
        assertEquals("tts", service.pendingModel.value?.model)

        gate.countDown()
        withTimeout(WAIT) { choice.await() }
        assertEquals(null, service.pendingModel.value)
    }

    @Test
    fun aVoiceKeptFromAnOlderListIsDroppedOnceANewerModelIsChosen(): Unit = runBlocking {
        val a = server()
        val service = service()
        saveUrl(service, a.url)
        withTimeout(WAIT) { chooseModel(service, a.url, "tts").await() }
        val asked = service.choice()

        withTimeout(WAIT) { chooseModel(service, a.url, "tts", settle = false).await() }
        service.keepVoice(a.url, "tts", "tts-2", asked, settle = false)
        assertEquals("tts-1", settings.settings.first().speechServerVoice)

        service.keepVoice(a.url, "tts", "tts-2", service.choice(), settle = false)
        assertEquals("tts-2", settings.settings.first().speechServerVoice)
    }

    @Test
    fun aServerWithoutAVoiceListStillOffersItsTypedVoice(): Unit = runBlocking {
        val a = server()
        val service = service()
        saveUrl(service, a.url)
        settings.setSpeechServerModelAndVoice("custom", "af_bella")

        val catalogue = service.catalogue(settings.settings.first())!!

        // An empty list is an answer, not a failure: the typed voice is offered, and Kokoro's name says English.
        assertFalse(catalogue.failed)
        assertEquals(listOf(CatalogueVoice("af_bella", setOf("en-US"))), catalogue.voices)
        assertEquals(VoiceScope("openai", OpenAiTts.baseUrl(a.url).toString(), "custom"), catalogue.scope)
    }

    @Test
    fun aFailedVoiceListKeepsTheSavedAndRememberedVoicesAndSaysSo(): Unit = runBlocking {
        val a = server()
        val service = service()
        saveUrl(service, a.url)
        settings.setSpeechServerModelAndVoice("tts", "narrator")
        val scope = service.catalogue(settings.settings.first())!!.scope
        assertTrue(service.remember(scope, "fr-FR", "conteur"))
        settings.setSpeechServerModelAndVoice("tts", "narrator")

        a.status = 500
        val catalogue = service().catalogue(settings.settings.first())!!

        assertTrue(catalogue.failed)
        assertEquals(listOf("narrator", "conteur"), catalogue.voices.map { it.id })
        assertEquals(null, catalogue.voices.first().languages)
    }

    @Test
    fun aVoiceRememberedForAnotherModelWritesNothing(): Unit = runBlocking {
        val a = server()
        val service = service()
        saveUrl(service, a.url)
        settings.setSpeechServerModelAndVoice("tts", "tts-1")
        val scope = service.catalogue(settings.settings.first())!!.scope

        settings.setSpeechServerModelAndVoice("older", "older-1")
        assertFalse(service.remember(scope, "en", "tts-2"))

        val s = settings.settings.first()
        assertEquals("older-1", s.speechServerVoice)
        assertTrue(s.voicePreferences.isEmpty())
    }
}

private const val MODELS = """{"data":[{"id":"tts","supported_voices":["tts-1","tts-2"]},
    {"id":"older","supported_voices":["older-1"]},{"id":"newer","supported_voices":["newer-1"]}]}"""

/** Generous: the whole suite shares the machine, and the first settings write can be slow. */
private const val WAIT = 20_000L
