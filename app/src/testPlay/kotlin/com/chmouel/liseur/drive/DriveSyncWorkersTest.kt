package com.chmouel.liseur.drive

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DriveSyncWorkersTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        if (!WorkManager.isInitialized()) {
            // Workers never run, so the enqueued request stays pending.
            WorkManager.initialize(context, Configuration.Builder().setExecutor { }.build())
        }
        WorkManager.getInstance(context).cancelAllWork().result.get()
    }

    @Test
    fun `a second download enqueue returns the pending request instead of its discarded duplicate`() = runTest {
        val bookUrl = "drive:isbn:9780547928227"
        val first = DriveSyncWorkers.enqueueEpubDownload(context, bookUrl, allowMetered = false)
        val second = DriveSyncWorkers.enqueueEpubDownload(context, bookUrl, allowMetered = true)

        assertNotNull(first)
        assertEquals(first, second)
        val infos = withContext(Dispatchers.IO) {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(DriveSyncWorkers.epubDownloadWorkName(bookUrl)).get()
        }
        assertEquals(listOf(first), infos.filter { !it.state.isFinished }.map { it.id })
    }

    @Test
    fun `cancelling a download returns only once the work is cancelled`() = runTest {
        val bookUrl = "drive:isbn:9780547928227"
        val id = DriveSyncWorkers.enqueueEpubDownload(context, bookUrl, allowMetered = false)!!

        DriveSyncWorkers.cancelEpubDownload(context, bookUrl)

        val info = withContext(Dispatchers.IO) { WorkManager.getInstance(context).getWorkInfoById(id).get() }
        assertEquals(WorkInfo.State.CANCELLED, info!!.state)
    }
}
