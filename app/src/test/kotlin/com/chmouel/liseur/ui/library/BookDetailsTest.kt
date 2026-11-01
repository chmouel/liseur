package com.chmouel.liseur.ui.library

import com.chmouel.liseur.R
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookFingerprintRow
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.remote.BookProvenance
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.domain.BookAccessibility
import com.chmouel.liseur.domain.FinishedOverride
import com.chmouel.liseur.domain.MetadataSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class BookDetailsTest {
    @Test
    fun provenanceComesFromTheRowNotTheConnectedServer() {
        assertEquals(BookProvenance.Server(ServerKind.KOMGA), BookProvenance.of("komga:abc", null))
        assertEquals(
            BookProvenance.Server(ServerKind.LISEUR_SYNC),
            BookProvenance.of("browse:7:liseur-sync:42", null),
        )
        assertEquals(BookProvenance.Opds, BookProvenance.of("custom:fp:1", null))
        assertEquals(BookProvenance.Opds, BookProvenance.of("browse:3:custom:fp:1", null))
        assertEquals(BookProvenance.Grimmory, BookProvenance.of("grimmory:12", null))
        assertEquals(
            BookProvenance.Folder("content://tree/books"),
            BookProvenance.of("content://tree/books/document/a.epub", "content://tree/books"),
        )
        assertEquals(BookProvenance.File, BookProvenance.of("content://downloads/a.epub", null))
    }

    @Test
    fun aFingerprintIsShownOnlyForTheFileOnThisDevice() {
        val row = BookFingerprintRow("calibre:1", "aa", "bb", 10, fileModifiedAt = 500, computedAt = 1)
        val book =
            Book(
                url = "calibre:1",
                title = "T",
                author = null,
                coverPath = null,
                source = null,
                addedAt = 0,
                lastOpenedAt = null,
                downloadState = DownloadState.DOWNLOADED,
                fileModifiedAt = 500,
            )
        assertEquals(row, currentFingerprint(book, row))
        assertNull(currentFingerprint(book.copy(fileModifiedAt = 501), row))
        assertNull(currentFingerprint(book.copy(downloadState = DownloadState.REMOTE), row))
        assertNull(currentFingerprint(book, null))
    }

    @Test
    fun wcagProfilesNameTheirVersionAndLevel() {
        assertEquals("2.0" to "AA", wcagConformance("http://www.idpf.org/epub/a11y/accessibility-20170105.html#wcag-aa"))
        assertEquals("2.1" to "AA", wcagConformance("https://www.w3.org/TR/epub-a11y-11#wcag-2.1-aa"))
        assertEquals("2.2" to "AAA", wcagConformance("https://www.w3.org/TR/epub-a11y-11#wcag-2.2-aaa"))
        assertNull(wcagConformance("https://example.com/profile"))
        assertNull(wcagConformance("https://example.com/#wcag-aaaa"))
    }

    @Test
    fun aLanguageTagNamesItsLanguageOrStaysAsWritten() {
        assertEquals("French", languageName("fr", Locale.ENGLISH))
        assertEquals("Anglais (Canada)", languageName("en-CA", Locale.FRENCH))
        assertEquals("qq", languageName("qq", Locale.ENGLISH))
        assertEquals("not a tag", languageName("not a tag", Locale.ENGLISH))
    }

    @Test
    fun accessibilityIsShownExactlyWhenALineWouldBeDrawn() {
        val modesOnly = accessibilityView(BookAccessibility(accessModesSufficient = listOf("textual")))
        assertEquals(listOf(listOf(R.string.book_details_a11y_mode_textual)), modesOnly.sufficient)
        assertTrue(!modesOnly.isEmpty)
        // A feature the screen has no words for draws nothing.
        assertTrue(accessibilityView(BookAccessibility(features = listOf("ruby"))).isEmpty)
    }

    @Test
    fun aLibraryOfCongressSubjectReadsAsATag() {
        assertEquals("Vampires · Fiction", subjectLabel("Vampires -- Fiction"))
        assertEquals("Dracula, Count · Fiction", subjectLabel("Dracula, Count--Fiction"))
        assertEquals("Horror tales", subjectLabel("Horror tales"))
        assertEquals("--", subjectLabel("--"))
    }

    @Test
    fun aBookMarkedUnreadShowsItsPlaceAndNoProgressBar() {
        val lines = readingStatus(details(progression = 0.4, override = FinishedOverride.UNREAD), etaSeconds = 3600.0)

        assertTrue(StatusLine.UnreadWithPlace(0.4) in lines)
        assertTrue(lines.none { it is StatusLine.Progress })
        // A time left would tell the reader they are still at it.
        assertTrue(lines.none { it is StatusLine.TimeLeft })
    }

    @Test
    fun aBookBelowTheStartedThresholdShowsNoProgressBar() {
        val lines = readingStatus(details(progression = 0.001), etaSeconds = 3600.0)

        assertTrue(lines.none { it is StatusLine.Progress })
        assertTrue(lines.none { it is StatusLine.TimeLeft })
    }

    @Test
    fun aStartedBookShowsItsProgressAndTimeLeft() {
        val lines = readingStatus(details(progression = 0.4), etaSeconds = 3600.0)

        assertTrue(StatusLine.Progress(0.4) in lines)
        assertTrue(StatusLine.TimeLeft(3600.0) in lines)
    }

    private fun details(
        progression: Double?,
        override: FinishedOverride = FinishedOverride.NONE,
    ) = BookDetails(
        book =
            Book(
                url = "calibre:1",
                title = "T",
                author = null,
                coverPath = null,
                source = null,
                addedAt = 0,
                lastOpenedAt = null,
                downloadState = DownloadState.DOWNLOADED,
                fileModifiedAt = 0,
            ),
        metadata = MetadataSnapshot(),
        provenance = BookProvenance.File,
        folderName = null,
        progression = progression,
        override = override,
        annotations = 0,
        fingerprint = null,
    )
}
