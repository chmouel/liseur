package com.chmouel.liseur.companion

import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Star
import androidx.compose.runtime.Composable
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.chmouel.liseur.R
import com.chmouel.liseur.data.security.SecretCipher
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.data.settings.ServerChange
import com.chmouel.liseur.providers.ServerConnections
import com.chmouel.liseur.providers.ServiceOption
import com.chmouel.liseur.translate.TranslationError
import com.chmouel.liseur.tts.OpenAiTtsClient
import com.chmouel.liseur.tts.ServerKeys
import java.io.File
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = Application::class)
@RunWith(RobolectricTestRunner::class)
class CompanionServerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var settings: AppSettingsRepository
    private lateinit var companion: CompanionServer
    private lateinit var serverId: String

    private val other = object : CompanionService {
        override val id = "other"
        override val offered: Flow<Boolean> = flowOf(true)
        override val choice: Flow<CompanionChoice?> = flowOf(CHOICE)
        override val privacy = R.string.companion_settings_privacy
        override fun chat(choice: CompanionChoice) = CompanionChat { _, beforeRequest ->
            beforeRequest()
            "from other"
        }

        @Composable
        override fun heading() = ServiceHeading(Icons.Outlined.Star, "Other", "")

        @Composable
        override fun summary(): String? = null

        @Composable
        override fun options(inUse: Boolean, pick: (suspend () -> Unit) -> Unit): List<ServiceOption> = emptyList()

        @Composable
        override fun SettingsRows(onManageServices: () -> Unit) = Unit
    }

    @Before
    fun setUp(): Unit = runBlocking {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        settings = AppSettingsRepository(PreferenceDataStoreFactory.create { File(folder.root, "app.preferences_pb") })
        val cipher = SecretCipher("test").apply {
            keyForTesting = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
        val connections = ServerConnections(settings, ServerKeys(folder.newFolder("keys"), cipher), client = OpenAiTtsClient(), scope = scope)
        companion = CompanionServer(settings, ServerCompanionService(connections, settings), listOf(other))
        serverId = (settings.addServer("Home", "http://127.0.0.1:1/v1") as ServerChange.Saved).id
        settings.selectCompanionServer(serverId)
        settings.setCompanionServerModel("http://127.0.0.1:1/v1", "m")
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private inline fun <reified E : TranslationError> expect(block: () -> Unit) {
        try {
            block()
        } catch (e: TranslationError) {
            if (e is E) return
            fail("expected ${E::class.simpleName}, got $e")
        }
        fail("expected ${E::class.simpleName}")
    }

    @Test
    fun `with no service chosen the listed server answers, as before there were others`(): Unit = runBlocking {
        val choice = companion.current()!!
        assertEquals(CompanionService.SERVER, choice.service)
        assertEquals(serverId, choice.serverId)
    }

    @Test
    fun `another service answers once chosen, and a chat bound to the server then fails`(): Unit = runBlocking {
        val before = companion.chat(companion.current()!!)
        companion.choose(other)

        assertEquals(CHOICE, companion.current())
        assertEquals("from other", companion.chat(CHOICE).reply(listOf(ChatMessage("user", "q"))) {})
        expect<TranslationError.Changed> { runBlocking { before.reply(listOf(ChatMessage("user", "q"))) {} } }
    }

    @Test
    fun `a service this build lacks is not set up rather than falling back`(): Unit = runBlocking {
        settings.setCompanionProvider("gemini")

        assertNull(companion.current())
        expect<TranslationError.NotSetUp> {
            runBlocking { companion.chat(CHOICE.copy(service = "gemini")).reply(listOf(ChatMessage("user", "q"))) {} }
        }
    }

    @Test
    fun `picking a listed server leaves the other service`(): Unit = runBlocking {
        companion.choose(other)
        settings.selectCompanionServer(serverId)

        assertEquals(CompanionService.SERVER, companion.current()!!.service)
    }

    private companion object {
        val CHOICE = CompanionChoice("other", "", "small", "other", 0, "other", CompanionLimits(recapBatch = 1_000))
    }
}
