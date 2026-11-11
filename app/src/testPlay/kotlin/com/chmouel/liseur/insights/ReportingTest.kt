package com.chmouel.liseur.insights

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = Application::class)
@RunWith(RobolectricTestRunner::class)
class ReportingTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Before fun clear() {
        context.noBackupFilesDir.deleteRecursively()
        context.noBackupFilesDir.mkdirs()
    }

    @Test fun newInstallDefaultsOnAndPersistsIdentity() {
        val first = ReportingPreference(context.noBackupFilesDir).read()
        assertTrue(first.enabled)
        assertEquals(first, ReportingPreference(context.noBackupFilesDir).read())
    }

    @Test fun optOutSurvivesRestartAndDiscardsQueue() = runBlocking {
        val reports = PlayUsageReporting(context, token = "")
        val original = ReportingPreference(context.noBackupFilesDir).read()
        File(context.noBackupFilesDir, "usage-reporting-queue/pending").apply {
            parentFile!!.mkdirs()
            writeText("private fixture")
        }
        reports.setEnabled(false)
        assertFalse(File(context.noBackupFilesDir, "usage-reporting-queue").exists())
        val restarted = PlayUsageReporting(context, token = "")
        assertFalse(restarted.enabled.value)
        restarted.capture(UsageEvent.BOOK_OPENED)
        restarted.setEnabled(true)
        assertNotEquals(original.installation, ReportingPreference(context.noBackupFilesDir).read().installation)
    }

    @Test fun captureDoesNotWaitForOptOut() {
        val reports = PlayUsageReporting(context, token = "phc_test", host = "http://127.0.0.1:9/")
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stopping = Thread {
            synchronized(reports) {
                held.countDown()
                release.await()
            }
        }.apply { start() }
        try {
            held.await()
            val captured = Thread { reports.capture(UsageEvent.BOOK_OPENED) }.apply { start() }
            captured.join(2_000)
            assertFalse(captured.isAlive)
        } finally {
            release.countDown()
            stopping.join()
            reports.stop()
        }
    }

    @Test fun crashIsQueuedWithoutItsMessageThenHandedOnOnce() {
        val before = Thread.getDefaultUncaughtExceptionHandler()
        val handed = mutableListOf<Throwable>()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> handed += error }
        val reports = PlayUsageReporting(context, token = "phc_test", host = "http://127.0.0.1:9/")
        try {
            val crash = IllegalStateException("private book title")
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), crash)
            assertEquals(listOf<Throwable>(crash), handed)
            val queue = File(context.noBackupFilesDir, "usage-reporting-queue")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var queued = ""
            while (System.nanoTime() < deadline && "IllegalStateException" !in queued) {
                queued = queue.walk().filter { it.isFile }.joinToString { it.readText() }
                Thread.sleep(50)
            }
            assertTrue(queued.contains("IllegalStateException"))
            assertFalse(queued.contains("private book title"))
        } finally {
            reports.stop()
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
    }

    @Test fun corruptPreferenceFailsClosed() {
        File(context.noBackupFilesDir, "usage-reporting").writeText("bad")
        assertFalse(ReportingPreference(context.noBackupFilesDir).read().enabled)
    }

    @Test fun disabledStartupDoesNotInstallCrashHandler() {
        val preference = ReportingPreference(context.noBackupFilesDir)
        preference.write(preference.read().copy(enabled = false))
        val before = Thread.getDefaultUncaughtExceptionHandler()
        PlayUsageReporting(context, token = "phc_test")
        assertSame(before, Thread.getDefaultUncaughtExceptionHandler())
    }

    @Test fun unknownEventsAreDroppedAndAllowedEventsContainOnlyFixedFields() {
        assertNull(ReportingPayload.properties("private book title", "1"))
        UsageEvent.entries.forEach { event ->
            assertEquals(setOf("app_version", "feature", "\$geoip_disable", "\$process_person_profile"),
                ReportingPayload.properties(event.wireName, "1")!!.keys)
        }
    }

    @Test fun realClientSendsOnlyFixedFieldsAndForgetsEverythingOnOptOut() = runBlocking {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val host = server.url("/").toString()
            val reports = PlayUsageReporting(context, token = "phc_test", host = host)
            val first = ReportingPreference(context.noBackupFilesDir).read().installation
            server.enqueue(MockResponse(code = 200, body = "{}"))
            reports.capture(UsageEvent.BOOK_OPENED)
            reports.flush()
            val sent = JSONObject(server.takeRequest(10, TimeUnit.SECONDS)!!.body!!.utf8())
            val event = sent.getJSONArray("batch").getJSONObject(0)
            assertEquals("book_opened", event.getString("event"))
            assertEquals(first, event.getString("distinct_id"))
            assertEquals(setOf("app_version", "feature", "\$geoip_disable", "\$process_person_profile"),
                event.getJSONObject("properties").keys().asSequence().toSet())

            // Unreachable: the event stays queued on disk until reporting is turned off.
            server.enqueue(MockResponse(code = 503))
            reports.capture(UsageEvent.COMPANION_OPENED)
            reports.flush()
            server.takeRequest(10, TimeUnit.SECONDS)
            val queue = File(context.noBackupFilesDir, "usage-reporting-queue")
            val straggler = File(context.cacheDir, "straggler").apply { deleteRecursively() }
            queue.copyRecursively(straggler)
            reports.setEnabled(false)
            assertFalse(queue.exists())
            // A queue write that outlived the opt-out lands after the deletion.
            straggler.copyRecursively(queue)

            reports.setEnabled(true)
            val second = ReportingPreference(context.noBackupFilesDir).read().installation
            assertNotEquals(first, second)
            server.enqueue(MockResponse(code = 200, body = "{}"))
            reports.capture(UsageEvent.READ_ALOUD_STARTED)
            reports.flush()
            val resent = JSONObject(server.takeRequest(10, TimeUnit.SECONDS)!!.body!!.utf8()).getJSONArray("batch")
            assertEquals(1, resent.length())
            assertEquals("read_aloud_started", resent.getJSONObject(0).getString("event"))
            assertEquals(second, resent.getJSONObject(0).getString("distinct_id"))
            reports.setEnabled(false)
            reports.capture(UsageEvent.BOOK_OPENED)
            reports.flush()
            assertNull(server.takeRequest(1, TimeUnit.SECONDS))
        } finally {
            server.close()
        }
    }

    @Test fun crashesKeepTypesAndFramesWithoutMessagesOrFreeContext() {
        val failure = IllegalStateException("private book title", IllegalArgumentException("https://secret/token"))
        failure.stackTrace = arrayOf(StackTraceElement("com.chmouel.Reader", "open", "/private/book.epub", 42))
        val raw = ReportingPayload.exception(failure).map { it + mapOf("value" to "secret", "context" to "private") }
        val payload = ReportingPayload.properties("\$exception", "1", raw)!!.toString()
        assertTrue(payload.contains("IllegalStateException"))
        assertTrue(payload.contains("com.chmouel.Reader"))
        assertTrue(payload.contains("lineno=42"))
        listOf("secret", "private", "book.epub", "context=").forEach { assertFalse(payload.contains(it)) }
    }
}
