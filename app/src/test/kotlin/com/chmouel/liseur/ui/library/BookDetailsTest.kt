package com.chmouel.liseur.ui.library

import com.chmouel.liseur.R
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookFingerprintRow
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.MetadataOverrideFields
import com.chmouel.liseur.data.library.CoverEdit
import com.chmouel.liseur.data.library.FieldEdit
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
    fun aBookBelowTheStartedThresholdKeepsItsPlaceWithoutABar() {
        val lines = readingStatus(details(progression = 0.001), etaSeconds = 3600.0)

        // The shelf files it as unread, so the page says the place is kept
        // rather than drawing a bar over a book nobody has started.
        assertTrue(StatusLine.UnreadWithPlace(0.001) in lines)
        assertTrue(lines.none { it is StatusLine.Progress })
        assertTrue(lines.none { it is StatusLine.TimeLeft })
    }

    @Test
    fun aBookWithNoSavedPlaceSaysNothing() {
        val lines = readingStatus(details(progression = null), etaSeconds = 3600.0)

        assertTrue(lines.none { it is StatusLine.Progress })
        assertTrue(lines.none { it is StatusLine.UnreadWithPlace })
    }

    @Test
    fun aStartedBookShowsItsProgressAndTimeLeft() {
        val lines = readingStatus(details(progression = 0.4), etaSeconds = 3600.0)

        assertTrue(StatusLine.Progress(0.4) in lines)
        assertTrue(StatusLine.TimeLeft(3600.0) in lines)
    }

    @Test
    fun fieldEditDistinguishesUnchangedEmptyAndReset() {
        assertEquals(
            FieldEdit.Unchanged,
            resolveFieldEdit(FieldEditMode.UNCHANGED, currentValue = "Dune", initialValue = "Dune"),
        )
        assertEquals(
            FieldEdit.Set(""),
            resolveFieldEdit(FieldEditMode.SET, currentValue = "", initialValue = "Dune"),
        )
        assertEquals(
            FieldEdit.Set(""),
            resolveFieldEdit(FieldEditMode.SET, currentValue = "", initialValue = ""),
        )
        assertEquals(
            FieldEdit.Reset,
            resolveFieldEdit(FieldEditMode.RESET, currentValue = "Dune", initialValue = "Custom"),
        )
    }

    @Test
    fun aCoverDraftBecomesOneEditOnSave() {
        val bytes = byteArrayOf(1, 2, 3)
        assertEquals(CoverEdit.Unchanged, coverEditOf(CoverDraft.UNCHANGED, null))
        assertEquals(CoverEdit.Empty, coverEditOf(CoverDraft.EMPTY, bytes))
        assertEquals(CoverEdit.Reset, coverEditOf(CoverDraft.RESET, null))
        val set = coverEditOf(CoverDraft.SET, bytes) as CoverEdit.Set
        assertEquals(bytes.toList(), set.bytes.toList())
        assertEquals(CoverEdit.Unchanged, coverEditOf(CoverDraft.SET, null))
    }

    @Test
    fun aCoverDraftDecidesWhichCoverButtonsAreOffered() {
        val book = details(progression = null).book.copy(coverPath = "file-cover.jpg")
        assertTrue(canRemoveCover(book, CoverDraft.UNCHANGED))
        assertTrue(!canResetCover(book, CoverDraft.UNCHANGED))

        assertTrue(!canRemoveCover(book, CoverDraft.EMPTY))
        assertTrue(canResetCover(book, CoverDraft.EMPTY))

        assertTrue(canRemoveCover(book, CoverDraft.SET))
        assertTrue(canResetCover(book, CoverDraft.SET))

        assertTrue(!canResetCover(book, CoverDraft.RESET))
    }

    @Test
    fun onlyAValueOnItsWayToTheServerHasAWireLimit() {
        val long = "x".repeat(20_000)
        val field = MetadataOverrideFields.DESCRIPTION

        // The source text is long and left alone: nothing is sent.
        assertTrue(!overWireLimit(FieldEditMode.UNCHANGED, long, long, field))
        // Reset sends no value either.
        assertTrue(!overWireLimit(FieldEditMode.RESET, long, "short", field))
        // Editing it to something the server takes is fine.
        assertTrue(!overWireLimit(FieldEditMode.SET, "short", "old", field))
        assertTrue(overWireLimit(FieldEditMode.SET, long, "old", field))
    }

    @Test
    fun starFillsMapWireRatingsToWholeAndHalfStars() {
        assertEquals(List(5) { StarFill.EMPTY }, starFills(null))
        assertEquals(
            listOf(StarFill.FULL, StarFill.EMPTY, StarFill.EMPTY, StarFill.EMPTY, StarFill.EMPTY),
            starFills(2),
        )
        assertEquals(
            listOf(StarFill.FULL, StarFill.FULL, StarFill.FULL, StarFill.HALF, StarFill.EMPTY),
            starFills(7),
        )
        assertEquals(List(5) { StarFill.FULL }, starFills(10))
    }

    @Test
    fun tappingAStarTogglesBetweenFullAndHalfWithinValidBounds() {
        // First star is the 1.0-star minimum (wire 2), never 0.5 (wire 1).
        assertEquals(2, nextStarRating(null, 1))
        assertEquals(2, nextStarRating(2, 1))
        // Tapping star 4 picks 4.0 (wire 8), tapping again steps down to 3.5 (wire 7), then back to 4.0.
        assertEquals(8, nextStarRating(null, 4))
        assertEquals(7, nextStarRating(8, 4))
        assertEquals(8, nextStarRating(7, 4))
        // Tapping a different star jumps directly to that full star.
        assertEquals(10, nextStarRating(7, 5))
    }

    @Test
    fun effectiveStatusLabelResolvesAutomaticToActualShelfState() {
        assertEquals(R.string.home_tile_to_read, effectiveStatusLabel(details(progression = null)))
        assertEquals(R.string.reading_status_reading, effectiveStatusLabel(details(progression = 0.25)))
        assertEquals(
            R.string.state_finished,
            effectiveStatusLabel(details(progression = 0.99).let { it.copy(book = it.book.copy(finishedAt = 1_000L)) }),
        )
        // Explicit overrides keep their own label.
        assertEquals(
            R.string.reading_status_paused,
            effectiveStatusLabel(details(progression = 0.25, override = FinishedOverride.PAUSED)),
        )
        assertEquals(
            R.string.reading_status_want_to_read,
            effectiveStatusLabel(details(progression = null, override = FinishedOverride.WANT_TO_READ)),
        )
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
