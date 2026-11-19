package com.chmouel.liseur.tts

import android.app.Application
import android.net.Uri
import com.chmouel.liseur.translate.wholeBookProgressSubText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Contributor
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.LocalizedString
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.Metadata
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@Config(sdk = [35], application = Application::class)
@RunWith(RobolectricTestRunner::class)
class ReadAloudMediaControlsTest {

    @Test
    fun `nextReadAloudSpeed cycles through all steps and wraps`() {
        assertEquals(1f, nextReadAloudSpeed(0.75f))
        assertEquals(1.25f, nextReadAloudSpeed(1f))
        assertEquals(1.5f, nextReadAloudSpeed(1.25f))
        assertEquals(1.75f, nextReadAloudSpeed(1.5f))
        assertEquals(2f, nextReadAloudSpeed(1.75f))
        assertEquals(0.75f, nextReadAloudSpeed(2f))
        assertEquals(1.5f, nextReadAloudSpeed(1.3f))
    }

    @Test
    fun `nextSleepTimerMinutes cycles null, 15, 30, 60, null and clears end of chapter`() {
        assertEquals(15, nextSleepTimerMinutes(null))
        assertEquals(30, nextSleepTimerMinutes(SleepTimer.starting(15, now = 0L)))
        assertEquals(60, nextSleepTimerMinutes(SleepTimer.starting(30, now = 0L)))
        assertNull(nextSleepTimerMinutes(SleepTimer.starting(60, now = 0L)))
        assertNull(nextSleepTimerMinutes(SleepTimer.EndOfChapter))
        assertEquals(15, nextSleepTimerMinutes(SleepTimer.starting(5, now = 0L)))
        assertEquals(60, nextSleepTimerMinutes(SleepTimer.starting(45, now = 0L)))
    }

    @Test
    fun `formatSpeedCommandLabel formats speed with locale decimal separator`() {
        assertEquals("1×", formatSpeedCommandLabel(1f, Locale.US))
        assertEquals("1.25×", formatSpeedCommandLabel(1.25f, Locale.US))
        assertEquals("1,5×", formatSpeedCommandLabel(1.5f, Locale.FRANCE))
    }

    @Test
    fun `resolveChapterTitleForResource prefers non-blank tocTitle over readingOrderTitle`() {
        assertEquals(
            "Chapter One",
            resolveChapterTitleForResource(readingOrderTitle = "File 1", tocTitle = "Chapter One", index = 0),
        )
        assertEquals(
            "File 1",
            resolveChapterTitleForResource(readingOrderTitle = "File 1", tocTitle = "   ", index = 0),
        )
        assertEquals(
            "File 1",
            resolveChapterTitleForResource(readingOrderTitle = "File 1", tocTitle = null, index = 0),
        )
        assertNull(
            resolveChapterTitleForResource(readingOrderTitle = " ", tocTitle = null, index = 0),
        )
    }

    @Test
    fun `resourceMetadata includes chapter title and album title when available`() = runBlocking {
        val publication = Publication(
            Manifest(
                metadata = Metadata(
                    localizedTitle = LocalizedString("Dune"),
                    authors = listOf(Contributor(name = "Frank Herbert")),
                ),
                readingOrder = listOf(
                    Link(Url("chapter1.xhtml")!!, title = "Book I"),
                    Link(Url("chapter2.xhtml")!!),
                ),
                tableOfContents = listOf(
                    Link(Url("chapter2.xhtml#part1")!!, title = "Arrakis"),
                ),
            ),
        )
        val factory = ReadAloudMetadata(null).createMetadataFactory(publication)

        val first = factory.resourceMetadata(0)
        assertEquals("Dune", first.albumTitle)
        assertEquals("Book I", first.title)
        assertEquals(1, first.trackNumber)
        assertEquals(2, first.totalTrackCount)

        val second = factory.resourceMetadata(1)
        assertEquals("Dune", second.albumTitle)
        assertEquals("Arrakis", second.title)
        assertEquals(2, second.trackNumber)
        assertEquals(2, second.totalTrackCount)
    }

    @Test
    fun `wholeBookProgressSubText formats percentage for valid ranges only`() {
        assertEquals("0%", wholeBookProgressSubText(0, 10))
        assertEquals("50%", wholeBookProgressSubText(5, 10))
        assertEquals("100%", wholeBookProgressSubText(10, 10))
        assertNull(wholeBookProgressSubText(0, 0))
        assertNull(wholeBookProgressSubText(-1, 10))
        assertNull(wholeBookProgressSubText(11, 10))
    }

    @Test
    fun `sleep label refreshes when the whole minutes left change`() {
        val timer = SleepTimer.Timed(minutes = 15, endsAt = 10 * SleepTimer.MINUTE_MS)
        assertEquals(SleepTimer.MINUTE_MS, sleepLabelRefreshDelay(timer, now = 0))
        assertEquals(1L, sleepLabelRefreshDelay(timer, now = SleepTimer.MINUTE_MS - 1))
        assertEquals(30_000L, sleepLabelRefreshDelay(timer, now = 9 * SleepTimer.MINUTE_MS + 30_000))
        assertNull(sleepLabelRefreshDelay(timer, now = 10 * SleepTimer.MINUTE_MS))
    }
}
