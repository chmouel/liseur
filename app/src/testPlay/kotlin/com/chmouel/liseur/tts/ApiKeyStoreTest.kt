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
    fun eachServiceKeepsItsOwnKeyUnderNoBackup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val field = ApiKeyStore::class.java.getDeclaredField("file").apply { isAccessible = true }
        val files = listOf(ApiKeyStore.gemini(context), ApiKeyStore.openAi(context)).map { field.get(it) as File }
        files.forEach { assertEquals(context.noBackupFilesDir.canonicalFile, it.parentFile!!.canonicalFile) }
        assertEquals("gemini-key", files[0].name)
        assertEquals(2, files.toSet().size)
    }
}
