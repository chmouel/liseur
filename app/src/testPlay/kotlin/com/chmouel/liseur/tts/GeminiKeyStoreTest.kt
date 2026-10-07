package com.chmouel.liseur.tts

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@Config(sdk = [35], application = Application::class)
@RunWith(RobolectricTestRunner::class)
class GeminiKeyStoreTest {
    @Test
    fun eachServiceKeepsItsOwnKeyUnderNoBackup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val field = ApiKeyStore::class.java.getDeclaredField("file").apply { isAccessible = true }
        val speechServer = ServerKeys(context).store("https://api.groq.com:443")
        val files = listOf(ApiKeyStore.gemini(context), speechServer).map { field.get(it) as File }
        files.forEach { assertEquals(context.noBackupFilesDir.canonicalFile, it.parentFile!!.canonicalFile) }
        assertEquals("gemini-key", files[0].name)
        assertEquals(2, files.toSet().size)
    }
}
