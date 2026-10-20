package com.chmouel.liseur.reader

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.chmouel.liseur.AppContainer
import com.chmouel.liseur.R
import com.chmouel.liseur.data.bookorbit.BookOrbitLocalPositionWriter
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.remote.SyncOutcome
import com.chmouel.liseur.domain.FinishedOverride
import com.chmouel.liseur.sync.LatestPositionSync
import com.chmouel.liseur.sync.PositionUpdate
import com.chmouel.liseur.sync.ReadingPositionPublisher
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.AbsoluteUrl
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderPositionPersistenceTest {
    @Test
    fun `opening waits for saves and unsaved displayed jumps remain saveable`() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val models = ViewModelStore()
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            runCatching { WorkManager.getInstance(context) }.getOrElse {
                WorkManager.initialize(
                    context,
                    Configuration.Builder().setExecutor { it.run() }.build(),
                )
            }
            val container = AppContainer(context)
            val file = book(context)
            val url = file.toURI().toString()
            val dao = container.database.readingProgressDao()
            container.database.bookDao().upsert(
                Book(
                    url = url, title = "Position test", author = null, coverPath = null,
                    source = null, addedAt = 0, lastOpenedAt = null,
                ),
            )
            fun place(progression: Double) = requireNotNull(
                Locator.fromJSON(JSONObject()
                    .put("href", "one.xhtml")
                    .put("type", "application/xhtml+xml")
                    .put("locations", JSONObject().put("progression", progression))),
            )
            fun update(locator: Locator) = PositionUpdate(
                bookUrl = url, locatorJson = locator.toJSON().toString(), progression = null,
                readingSecondsPerPosition = null, readingPaceSamples = null,
                readingPaceElapsedMs = null, readingPaceEvidence = null,
                updatedAt = System.currentTimeMillis(),
            )
            val writer = BookOrbitLocalPositionWriter(container.database)
            writer.save(update(place(0.1)), null)
            val writing = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var failWrites = false
            var writes = 0
            val publisher = ReadingPositionPublisher(
                scope = scope,
                overrideFor = { FinishedOverride.NONE },
                persist = { update, status ->
                    writing.complete(Unit)
                    release.await()
                    check(!failWrites) { "Test write failure" }
                    writer.save(update, status)
                    writes++
                },
                refreshFinished = {}, markFinished = {}, scheduleClose = {}, onError = { _, _ -> },
                latestSync = LatestPositionSync(scope, { SyncOutcome.NotApplicable }, {}, { _, _ -> }),
            )
            fun model() = ReaderViewModel(
                bookUrl = requireNotNull(AbsoluteUrl(url)), bookId = url,
                assetRetriever = container.assetRetriever, publicationOpener = container.publicationOpener,
                progressDao = dao, bookDao = container.database.bookDao(),
                annotationDao = container.database.annotationDao(), typographyDao = container.database.typographyDao(),
                bookScreenDao = container.database.bookScreenDao(), readingModeDao = container.database.bookReadingModeDao(),
                library = container.libraryRepository, prefsRepo = container.readerPreferences,
                readingPace = container.readingPace, positionSync = container.positionSync,
                appSettings = container.appSettings, positionPublisher = publisher,
                downloads = container.bookDownloads, seriesExtras = container.seriesExtras,
                remoteAccount = container.remoteAccount, userFonts = container.userFonts,
                sessionManager = container.readingSessions,
            ).also { models.put("reader", it) }
            suspend fun ReaderViewModel.ready() = withTimeout(15_000) {
                state.filterIsInstance<ReaderViewModel.UiState.Ready>().first()
            }
            suspend fun stored() = requireNotNull(Locator.fromJSON(JSONObject(dao.get(url)!!.locatorJson)))

            publisher.publish(update(place(0.7)))
            withTimeout(5_000) { writing.await() }
            val reader = model()
            assertEquals(ReaderViewModel.UiState.Loading, reader.state.value)
            assertNull(withTimeoutOrNull(1_000) { reader.ready() })
            assertFalse(release.isCompleted)
            release.complete(Unit)
            assertEquals(0.7, reader.ready().initialLocator!!.locations.progression!!, 0.000001)
            assertEquals(1, writes)

            val opening = reader.lastLocator!!
            reader.onLocatorChanged(opening, NavigatorPositionEvent.OPENING_RESTORATION)
            reader.onReaderPaused()
            reader.onLocatorChanged(opening, NavigatorPositionEvent.LOCAL_JUMP)
            assertTrue(publisher.flush(url))
            assertEquals(1, writes)

            val jump = place(0.3)
            reader.onLocatorChanged(jump, NavigatorPositionEvent.OPENING_RESTORATION)
            assertEquals(1, writes)
            reader.onLocatorChanged(jump, NavigatorPositionEvent.LOCAL_JUMP)
            assertTrue(publisher.flush(url))
            assertEquals(0.3, stored().locations.progression!!, 0.000001)
            assertEquals(2, writes)
            reader.onLocatorChanged(jump, NavigatorPositionEvent.LOCAL_JUMP)
            assertTrue(publisher.flush(url))
            assertEquals(2, writes)

            reader.onReaderResumed()
            val reflow = place(0.31)
            reader.onLocatorChanged(reflow, NavigatorPositionEvent.PREFERENCE_REFLOW)
            assertTrue(publisher.flush(url))
            assertEquals(2, writes)
            assertEquals(0.3, stored().locations.progression!!, 0.000001)
            reader.onLocatorChanged(reflow, NavigatorPositionEvent.LOCAL_JUMP)
            assertTrue(publisher.flush(url))
            assertEquals(3, writes)

            models.clear()
            assertTrue(publisher.flush(url))
            failWrites = true
            publisher.publish(update(place(0.9)))
            assertFalse(publisher.flush(url))
            val reopening = model()
            assertEquals(0.31, reopening.ready().initialLocator!!.locations.progression!!, 0.000001)
            assertEquals(
                ReaderViewModel.BookSync.Note(R.string.reader_open_position_not_saved),
                reopening.bookSync.value,
            )

            val failedPlace = place(0.9)
            reopening.onLocatorChanged(failedPlace, NavigatorPositionEvent.LOCAL_JUMP)
            assertFalse(publisher.flush(url))
            assertEquals(
                ReaderViewModel.BookSync.Note(R.string.reader_position_not_saved),
                reopening.bookSync.value,
            )

            failWrites = false
            reopening.onLocatorChanged(place(0.8), NavigatorPositionEvent.PREFERENCE_REFLOW)
            reopening.onLocatorChanged(failedPlace, NavigatorPositionEvent.LOCAL_JUMP)
            assertTrue(publisher.flush(url))
            assertEquals(0.9, stored().locations.progression!!, 0.000001)
            assertEquals(4, writes)
        } finally {
            models.clear()
            scope.cancel()
            Dispatchers.resetMain()
        }
    }

    private fun book(context: Context): File = File.createTempFile("reader-position", ".epub", context.cacheDir).also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry("META-INF/container.xml", """
                <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                  <rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
            """.trimIndent())
            entry("book.opf", """
                <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="id">position-test</dc:identifier><dc:title>Position test</dc:title><dc:language>en</dc:language>
                  </metadata>
                  <manifest><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/>
                    <item id="two" href="two.xhtml" media-type="application/xhtml+xml"/></manifest>
                  <spine><itemref idref="one"/><itemref idref="two"/></spine>
                </package>
            """.trimIndent())
            for (name in listOf("one", "two")) {
                entry("$name.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>$name</title></head><body>""" +
                    (1..100).joinToString("") { "<p>Paragraph $it of chapter $name for the position test.</p>" } + "</body></html>")
            }
        }
    }
}
