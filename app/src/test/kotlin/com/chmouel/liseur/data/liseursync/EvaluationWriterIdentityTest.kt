package com.chmouel.liseur.data.liseursync

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class EvaluationWriterIdentityTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun phone(noBackup: File, backedUpFiles: File): Context = object : ContextWrapper(
        ApplicationProvider.getApplicationContext(),
    ) {
        override fun getNoBackupFilesDir() = noBackup
        override fun getFilesDir() = backedUpFiles
    }

    @Test
    fun `reopening and concurrent reads keep the persisted writer`() = runTest {
        val context = phone(folder.newFolder("no-backup"), folder.newFolder("files"))
        val writers = List(10) { async { EvaluationWriterIdentity(context).current() } }.awaitAll()
        assertEquals(1, writers.distinct().size)
        assertEquals(writers.first(), EvaluationWriterIdentity(context).current())
        assertTrue(File(context.noBackupFilesDir, "evaluation-writer-id").isFile)
        assertTrue(context.filesDir.listFiles()!!.isEmpty())
    }

    @Test
    fun `restored backed up files do not clone the writer`() = runTest {
        val restoredFiles = folder.newFolder("restored-files")
        val original = phone(folder.newFolder("original-no-backup"), restoredFiles)
        val restored = phone(folder.newFolder("restored-no-backup"), restoredFiles)
        assertNotEquals(EvaluationWriterIdentity(original).current(), EvaluationWriterIdentity(restored).current())
    }
}
