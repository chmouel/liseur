package com.chmouel.liseur.translate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.chmouel.liseur.AppContainer
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.settings.ServerConnection
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class WholeBookTranslationsTest {
    @Test
    fun `pause during publication opening keeps user intent without sending a request`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        runCatching { WorkManager.getInstance(context) }.getOrElse {
            WorkManager.initialize(context, Configuration.Builder().setExecutor { it.run() }.build())
        }
        val container = AppContainer(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val opening = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        val coordinator = WholeBookTranslations(context, container, scope) { _, _ ->
            opening.complete(Unit)
            hold.await()
        }
        val file = File(context.filesDir, "opening.epub").apply { writeText("Fingerprint fixture") }
        val book = android.net.Uri.fromFile(file).toString()
        try {
            MockWebServer().use { server ->
                server.start()
                val url = server.url("/v1").toString()
                container.appSettings.addServer("Test", url)
                container.appSettings.selectTranslationServer(checkNotNull(ServerConnection.idOf(url)))
                container.appSettings.setTranslationServerModel(url, "stub-chat")
                container.appSettings.setTranslationTarget("fr")
                container.database.bookDao().upsert(
                    Book(url = book, title = "Opening", author = null, coverPath = null,
                        source = null, addedAt = 0, lastOpenedAt = null),
                )
                coordinator.records.first()
                val identity = (container.translate as ServiceTranslate).wholeBookIdentity("en", "fr")
                val saved = container.savedTranslations
                val old = saved.begin(book, "en", "fr", identity, 1)
                saved.pause(book, old.generation, "interrupted")
                val preview = WholeBookPreview(
                    book, "Opening", "en", "fr", identity, 1, 0, 0, 0, null, null,
                    checkNotNull(container.bookFingerprints.compute(book)).sha256,
                    retentionStamp = saved.retentionStamp(book), resumed = true,
                )
                coordinator.start(preview)
                shadowOf(context).nextStartedService
                assertEquals(preview, coordinator.takePending())
                val worker = launch { coordinator.execute(preview) { _, _ -> error("No work expected") } }
                coordinator.attachCancellation(book) { worker.cancel() }
                withTimeout(10_000) { opening.await() }
                coordinator.pause(book)
                worker.join()
                coordinator.release(book)
                coordinator.resumeInterrupted()
                assertEquals("paused", saved.getKept(book)?.reason)
                assertNull(shadowOf(context).nextStartedService)
                assertEquals(0, server.requestCount)
            }
        } finally {
            scope.cancel()
            file.delete()
        }
    }

    @Test
    fun `notification pause persists before service execution and while reader admission is held`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        runCatching { WorkManager.getInstance(context) }.getOrElse {
            WorkManager.initialize(context, Configuration.Builder().setExecutor { it.run() }.build())
        }
        val container = AppContainer(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = WholeBookTranslations(context, container, scope)
        val saved = container.savedTranslations
        val book = "file:///pause-test.epub"
        try {
            container.database.bookDao().upsert(
                Book(url = book, title = "Pause", author = null, coverPath = null,
                    source = null, addedAt = 0, lastOpenedAt = null),
            )
            coordinator.records.first()
            for (stage in listOf("pending", "pending-timeout", "lazy", "admission", "cancelled")) {
                val old = saved.begin(book, "en", "fr", "identity", 1)
                saved.pause(book, old.generation, "network")
                val preview = WholeBookPreview(
                    book, "Pause", "en", "fr", "identity", 1, 0, 0, 0, null, null, "fingerprint",
                    retentionStamp = saved.retentionStamp(book), resumed = true,
                )
                coordinator.start(preview)
                assertEquals(WholeBookTranslationService::class.java.name,
                    shadowOf(context as android.app.Application).nextStartedService.component?.className)
                if (stage.startsWith("pending")) {
                    if (stage == "pending-timeout") {
                        coordinator.stopActive("timeout")
                        coordinator.stopActive()
                    } else {
                        coordinator.stopActive()
                    }
                    coordinator.stopActive("interrupted")
                    assertNull(coordinator.takePending())
                } else {
                    assertEquals(preview, coordinator.takePending())
                    val held = CompletableDeferred<Unit>()
                    val reader = launch(start = CoroutineStart.UNDISPATCHED) {
                        container.translationAdmissions.reader(book, "identity") { held.await() }
                    }
                    val worker = launch(start = CoroutineStart.LAZY) {
                        coordinator.execute(preview) { _, _ -> error("No provider work expected") }
                    }
                    coordinator.attachCancellation(book) { worker.cancel() }
                    if (stage != "lazy") {
                        worker.start()
                        // Both coroutines use this event loop: yield until execute is waiting on admission.
                        kotlinx.coroutines.yield()
                    }
                    if (stage == "cancelled") worker.cancel() else coordinator.stopActive()
                    worker.join()
                    held.complete(Unit)
                    reader.cancelAndJoin()
                    coordinator.release(book)
                }
                if (stage == "cancelled") {
                    assertEquals("interrupted", saved.getKept(book)?.reason)
                    assertTrue(resumable(saved.getKept(book)?.reason, automatic = true))
                } else {
                    // Foreground resume waits for the application-scoped pause write.
                    coordinator.resumeInterrupted()
                    assertEquals("$stage pause reason", "paused", saved.getKept(book)?.reason)
                    assertFalse(coordinator.resume(book, automatic = true))
                }
                assertNull(shadowOf(context).nextStartedService)
            }
        } finally {
            scope.cancel()
        }
    }
}
