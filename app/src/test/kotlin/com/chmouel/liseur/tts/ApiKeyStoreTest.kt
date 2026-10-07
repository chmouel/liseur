package com.chmouel.liseur.tts

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.security.SecretCipher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.KeyGenerator

@Config(sdk = [35], application = Application::class)
@RunWith(RobolectricTestRunner::class)
class ApiKeyStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun cipher() = SecretCipher("test").apply {
        keyForTesting = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }

    private fun keyStore(file: File, cipher: SecretCipher = cipher()) =
        ApiKeyStore(file, cipher, UnconfinedTestDispatcher())

    @Test
    fun keepsTheKeyEncryptedAndReadsItBack() = runTest {
        val file = File(folder.root, "gemini-key")
        val store = keyStore(file)
        assertFalse(store.configured.value)

        store.set("  AIza-secret-key \n")
        assertTrue(store.configured.value)
        assertEquals("AIza-secret-key", store.get())
        assertFalse(file.readText().contains("secret"))

        store.clear()
        assertFalse(store.configured.value)
        assertNull(store.get())
        assertFalse(file.exists())
    }

    @Test
    fun aKeyThatCannotBeDecryptedCountsAsAbsent() = runTest {
        val file = File(folder.root, "gemini-key")
        keyStore(file).set("AIza-secret-key")

        // A new Keystore key, as after a Keystore reset.
        val store = keyStore(file)
        assertTrue(store.configured.value)
        assertNull(store.get())
        assertFalse(store.configured.value)
        assertFalse(file.exists())
    }

    @Test
    fun aBlankKeyClears() = runTest {
        val store = keyStore(File(folder.root, "gemini-key"))
        store.set("AIza-secret-key")
        store.set("   ")
        assertFalse(store.configured.value)
    }

    @Test
    fun serverKeysAreKeptUnderNoBackup() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val keys = ServerKeys(context)
        val field = ApiKeyStore::class.java.getDeclaredField("file").apply { isAccessible = true }
        val file = field.get(keys.store("https://api.groq.com:443")) as File
        assertEquals(context.noBackupFilesDir.canonicalFile, file.parentFile!!.canonicalFile)
        assertTrue(file.name.matches(Regex("openai-key-[0-9a-f]{16}")))
    }

    private fun serverKeys(dir: File = folder.root) = ServerKeys(dir, cipher(), UnconfinedTestDispatcher())

    private fun origin(url: String) = ServerKeys.origin(OpenAiTts.baseUrl(url)!!)

    @Test
    fun eachServerKeepsItsOwnKey() = runTest {
        val keys = serverKeys()
        val deepInfra = origin("https://api.deepinfra.com/v1/openai")
        val mistral = origin("https://api.mistral.ai/v1")
        keys.set(deepInfra, "di-key")
        keys.set(mistral, "mi-key")
        assertEquals("di-key", keys.get(deepInfra))
        assertEquals("mi-key", keys.get(mistral))
        assertNull(keys.get(origin("http://192.168.1.10:8880")))

        keys.clear(mistral)
        assertNull(keys.get(mistral))
        assertEquals("di-key", keys.get(deepInfra))
        assertTrue(keys.configured(deepInfra).value)
        assertFalse(keys.configured(mistral).value)
        assertFalse(folder.root.listFiles()!!.any { it.name.contains("deepinfra") })
    }

    @Test
    fun aServerIsItsSchemeHostAndPort() {
        assertEquals("https://api.groq.com:443", origin("https://API.groq.com/openai/v1"))
        assertEquals(origin("https://api.groq.com:443/openai/v1"), origin("https://api.groq.com/v1"))
        assertEquals("http://192.168.1.10:8880", origin("192.168.1.10:8880"))
        assertTrue(origin("http://api.groq.com") != origin("https://api.groq.com"))
        assertTrue(origin("http://localhost:8880") != origin("http://localhost:8881"))
    }

    @Test
    fun theSingleKeyOfOldBuildsIsDeletedUnused() = runTest {
        val legacy = File(folder.root, "openai-key")
        keyStore(legacy).set("whose-key")
        val keys = serverKeys()
        val deepInfra = origin("https://api.deepinfra.com/v1/openai")
        assertNull(keys.get(deepInfra))
        assertFalse(legacy.exists())
    }
}
