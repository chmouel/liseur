package com.chmouel.liseur.tts

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.chmouel.liseur.companion.GeminiCompanionService
import com.chmouel.liseur.data.security.SecretCipher
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.providers.ServerConnections
import java.io.File
import java.io.IOException
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = Application::class)
@RunWith(RobolectricTestRunner::class)
class GeminiAccountTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun savingAKeyAdoptsDefaultsOnlyWhenAcceptedOrOffline(): Unit = runBlocking {
        for (outcome in listOf(KeyCheck.VALID, KeyCheck.REFUSED, null)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val directory = folder.newFolder()
                val settings = AppSettingsRepository(
                    PreferenceDataStoreFactory.create(scope = scope) { File(directory, "app.preferences_pb") },
                )
                val cipher = SecretCipher("test").apply {
                    keyForTesting = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
                }
                val connections = ServerConnections(settings, ServerKeys(directory, cipher), scope = scope)
                val gate = CompletableDeferred<Unit>()
                val account = GeminiAccount(ApiKeyStore(File(directory, "gemini-key"), cipher), connections) { key ->
                    assertEquals("secret", key)
                    gate.await()
                    when (outcome) {
                        KeyCheck.REFUSED -> throw SpeechError.InvalidKey(401)
                        null -> throw SpeechError.Network(IOException("offline"))
                        else -> Unit
                    }
                }
                val saved = CompletableDeferred<Boolean>()
                account.commitKey("secret") { saved.complete(it) }
                assertTrue(withTimeout(20_000) { saved.await() })
                assertEquals("secret", account.key())
                assertEquals(KeyCheck.CHECKING, account.keyCheck.value)
                assertEquals(null, settings.settings.first().companionProvider)

                gate.complete(Unit)
                assertEquals(outcome, withTimeout(20_000) { account.keyCheck.first { it != KeyCheck.CHECKING } })
                // Wait for the guarded settings write that follows the check result.
                withTimeout(20_000) { connections.unlessChanged(GeminiAccount.OWNER, account.generation.value) {} }
                val selected = settings.settings.first()
                val expected = GeminiAccount.OWNER.takeUnless { outcome == KeyCheck.REFUSED }
                assertEquals(expected, selected.companionProvider)
                assertEquals(expected, selected.readAloudProvider)
                assertEquals(
                    GeminiCompanionService.RECOMMENDED_MODEL.takeIf { expected != null },
                    selected.companionGeminiModel,
                )
            } finally {
                scope.cancel()
            }
        }
    }
}
