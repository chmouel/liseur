package com.chmouel.liseur.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.domain.seriesKey
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class SeriesMetadataDaoTest {

    private lateinit var db: LiseurDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LiseurDatabase::class.java,
        ).build()
    }

    @After
    fun close() = db.close()

    @Test
    fun `catalog removal clears metadata that came only from the catalog`() = runTest {
        seed(seriesName = "Old Series", seriesIndex = 4.0)

        refreshCatalog(seriesName = null, seriesIndex = null)

        val book = db.bookDao().getByUrl(BOOK_URL)
        assertNull(book?.seriesName)
        assertNull(book?.seriesIndex)
    }

    @Test
    fun `catalog removal reveals metadata extracted from the downloaded file`() = runTest {
        seed(seriesName = "Catalog Series", seriesIndex = 4.0)
        db.bookDao().fillSeriesFromFile(BOOK_URL, "File Series", 2.0)

        refreshCatalog(seriesName = null, seriesIndex = null)

        val book = db.bookDao().getByUrl(BOOK_URL)
        assertEquals("File Series", book?.seriesName)
        assertEquals(2.0, book?.seriesIndex)
        assertEquals("File Series", book?.fileSeriesName)
        assertEquals(2.0, book?.fileSeriesIndex)
    }

    @Test
    fun `a series the reader chose survives a catalog refresh`() = runTest {
        seed(seriesName = "Catalog Series", seriesIndex = 4.0)

        db.bookDao().setSeriesOverride(BOOK_URL, "My Shelf", 1.0)
        refreshCatalog(seriesName = "Catalog Series", seriesIndex = 4.0)

        val book = db.bookDao().getByUrl(BOOK_URL)
        assertEquals("My Shelf", book?.seriesName)
        assertEquals(1.0, book?.seriesIndex)
        // The catalog's own answer is still recorded, so undoing has
        // something to go back to.
        assertEquals("Catalog Series", book?.catalogSeriesName)
    }

    @Test
    fun `a catalog update cannot replace a newer manual series edit`() = runTest {
        seed(seriesName = "Old Catalog Series", seriesIndex = 1.0)
        db.bookDao().setSeriesOverride(BOOK_URL, "My Collection", 4.0, updatedAt = 30)
        db.bookDao().acknowledgeSeriesClaim(
            url = BOOK_URL,
            expectedUserSeriesUpdatedAt = 30,
            seriesId = "current-series-id",
            personalSeriesUpdatedAt = 30,
        )

        db.bookDao().updateCatalogFields(
            url = BOOK_URL,
            title = "A Revised Book",
            author = "A Revised Author",
            remoteUuid = "remote-id",
            remoteBookId = null,
            coverUrl = null,
            downloadHref = null,
            remoteUpdatedAt = 40,
            remotePageCount = null,
            catalogSeriesName = "New Catalog Series",
            catalogSeriesIndex = 9.0,
            catalogFolderId = "folder-id",
            catalogSeriesSource = "personal",
            userSeriesName = "Stale Personal Claim",
            userSeriesIndex = 8.0,
            seriesOverridden = true,
            indexOverridden = true,
            userSeriesUpdatedAt = 40,
            // The refresh saw an older row; 30 is what is stored now.
            expectedUserSeriesUpdatedAt = 20,
            seriesId = "stale-series-id",
        )

        val book = db.bookDao().getByUrl(BOOK_URL)
        // Catalog-owned fields still refresh.
        assertEquals("A Revised Book", book?.title)
        assertEquals("New Catalog Series", book?.catalogSeriesName)
        assertEquals("folder-id", book?.catalogFolderId)
        assertEquals("personal", book?.catalogSeriesSource)
        // The newer manual filing remains internally consistent.
        assertEquals("My Collection", book?.seriesName)
        assertEquals(4.0, book?.seriesIndex)
        assertEquals("My Collection", book?.userSeriesName)
        assertEquals(4.0, book?.userSeriesIndex)
        assertEquals(true, book?.seriesOverridden)
        assertEquals(true, book?.indexOverridden)
        assertEquals(30L, book?.userSeriesUpdatedAt)
        assertEquals("current-series-id", book?.seriesId)
    }

    @Test
    fun `a book taken out of its series stays out of it`() = runTest {
        seed(seriesName = "Catalog Series", seriesIndex = 4.0)
        db.bookDao().fillSeriesFromFile(BOOK_URL, "File Series", 2.0)

        db.bookDao().setSeriesOverride(BOOK_URL, null, null)
        refreshCatalog(seriesName = "Catalog Series", seriesIndex = 4.0)

        val book = db.bookDao().getByUrl(BOOK_URL)
        assertNull(book?.seriesName)
        assertNull(book?.seriesIndex)
    }

    @Test
    fun `a file re-read from disk cannot overrule the reader`() = runTest {
        seed(seriesName = null, seriesIndex = null)
        db.bookDao().setSeriesOverride(BOOK_URL, "My Shelf", null)

        db.bookDao().fillSeriesFromFile(BOOK_URL, "File Series", 2.0)

        val book = db.bookDao().getByUrl(BOOK_URL)
        assertEquals("My Shelf", book?.seriesName)
        assertNull(book?.seriesIndex)
        assertEquals("File Series", book?.fileSeriesName)
    }

    @Test
    fun `undoing gives the book back to the catalog`() = runTest {
        seed(seriesName = "Catalog Series", seriesIndex = 4.0)
        refreshCatalog(seriesName = "Catalog Series", seriesIndex = 4.0)
        db.bookDao().setSeriesOverride(BOOK_URL, "My Shelf", 1.0)

        db.bookDao().clearSeriesOverride(BOOK_URL)

        val book = db.bookDao().getByUrl(BOOK_URL)
        assertEquals("Catalog Series", book?.seriesName)
        assertEquals(4.0, book?.seriesIndex)
        assertNull(book?.userSeriesName)
        assertEquals(false, book?.seriesOverridden)
    }

    @Test
    fun `undoing a book with no catalog falls back to its file`() = runTest {
        seed(seriesName = null, seriesIndex = null)
        db.bookDao().fillSeriesFromFile(BOOK_URL, "File Series", 2.0)
        db.bookDao().setSeriesOverride(BOOK_URL, "My Shelf", 1.0)

        db.bookDao().clearSeriesOverride(BOOK_URL)

        val book = db.bookDao().getByUrl(BOOK_URL)
        assertEquals("File Series", book?.seriesName)
        assertEquals(2.0, book?.seriesIndex)
    }

    @Test
    fun `unlinking from the server keeps the reader's filing`() = runTest {
        seed(seriesName = "Catalog Series", seriesIndex = 4.0)
        db.bookDao().setSeriesOverride(BOOK_URL, "My Shelf", 1.0)

        db.bookDao().unlinkFromRemote(listOf(BOOK_URL))

        val book = db.bookDao().getByUrl(BOOK_URL)
        assertEquals("My Shelf", book?.seriesName)
        assertEquals(1.0, book?.seriesIndex)
    }

    @Test
    fun `filing a book by hand sets both flags even with no number given`() = runTest {
        seed(seriesName = "Catalog Series", seriesIndex = 4.0)

        db.bookDao().setSeriesOverride(BOOK_URL, "My Shelf", null)

        val book = db.bookDao().getByUrl(BOOK_URL)
        assertEquals(true, book?.seriesOverridden)
        // Saying nothing about the number is an answer too: the book has
        // none. Left to the catalog it would keep the number its old
        // series gave it, and get it back on the next refresh.
        assertEquals(true, book?.indexOverridden)
        assertNull(book?.seriesIndex)
    }

    @Test
    fun `a book filed by hand keeps no number through a catalog refresh`() = runTest {
        seed(seriesName = "Catalog Series", seriesIndex = 4.0)
        db.bookDao().setSeriesOverride(BOOK_URL, "My Shelf", null)

        refreshCatalog(seriesName = "Catalog Series", seriesIndex = 4.0)

        val book = db.bookDao().getByUrl(BOOK_URL)
        assertEquals("My Shelf", book?.seriesName)
        assertNull(book?.seriesIndex)
    }

    @Test
    fun `a dragged book keeps the name the catalog gives it`() = runTest {
        seed(seriesName = "Catalog Series", seriesIndex = 4.0)

        assertEquals(true, db.seriesOrderDao().renumber(seriesKey("Catalog Series"), listOf(BOOK_URL)))

        val dragged = db.bookDao().getByUrl(BOOK_URL)
        assertEquals(false, dragged?.seriesOverridden)
        assertEquals(true, dragged?.indexOverridden)
        assertEquals(1.0, dragged?.seriesIndex)

        // The server renames the series. The book follows, because only
        // its place was ever overridden.
        refreshCatalog(seriesName = "Renamed Series", seriesIndex = 4.0)

        val after = db.bookDao().getByUrl(BOOK_URL)
        assertEquals("Renamed Series", after?.seriesName)
        assertEquals(1.0, after?.seriesIndex)
    }

    @Test
    fun `a corrected file replaces the series it gave a downloaded book`() = runTest {
        // The catalog says nothing about series, so the file spoke for
        // the book. When the file is corrected it has to be able to
        // speak again: the resolved column holds what this same file
        // said last time, so consulting it would make the first reading
        // of a bad OPF permanent.
        seed(seriesName = null, seriesIndex = null)
        db.bookDao().fillSeriesFromFile(BOOK_URL, "Typo Serie", 2.0)
        assertEquals("Typo Serie", db.bookDao().getByUrl(BOOK_URL)?.seriesName)

        db.bookDao().fillSeriesFromFile(BOOK_URL, "Fixed Series", 3.0)

        val after = db.bookDao().getByUrl(BOOK_URL)
        assertEquals("Fixed Series", after?.seriesName)
        assertEquals(3.0, after?.seriesIndex)
    }

    @Test
    fun `a file cannot take a downloaded book out of the catalog's series`() = runTest {
        seed(seriesName = null, seriesIndex = null)
        refreshCatalog(seriesName = "Catalog Series", seriesIndex = 8.0)

        db.bookDao().fillSeriesFromFile(BOOK_URL, "File Series", 2.0)

        val after = db.bookDao().getByUrl(BOOK_URL)
        assertEquals("Catalog Series", after?.seriesName)
        assertEquals(8.0, after?.seriesIndex)
    }

    @Test
    fun `a dragged book loses its place when the series stops existing`() = runTest {
        // A number counts within a series. When the catalog drops the
        // series and no file names one either, the book is a standalone,
        // and a standalone at #1 would be sorted and shelved by a
        // position in a series it is no longer in.
        seed(seriesName = "Catalog Series", seriesIndex = 4.0)
        db.seriesOrderDao().renumber(seriesKey("Catalog Series"), listOf(BOOK_URL))

        refreshCatalog(seriesName = null, seriesIndex = null)

        val after = db.bookDao().getByUrl(BOOK_URL)
        assertEquals(null, after?.seriesName)
        assertEquals(null, after?.seriesIndex)
    }

    @Test
    fun `a dragged book loses its place when a re-index finds no series`() = runTest {
        seed(seriesName = "File Series", seriesIndex = 4.0, remote = false)
        db.seriesOrderDao().renumber(seriesKey("File Series"), listOf(BOOK_URL))

        db.bookDao().fillSeriesFromFile(BOOK_URL, null, null)

        val after = db.bookDao().getByUrl(BOOK_URL)
        assertEquals(null, after?.seriesName)
        assertEquals(null, after?.seriesIndex)
    }

    @Test
    fun `a dragged book keeps its place through a file re-index`() = runTest {
        seed(seriesName = "Catalog Series", seriesIndex = 4.0)
        db.seriesOrderDao().renumber(seriesKey("Catalog Series"), listOf(BOOK_URL))

        db.bookDao().fillSeriesFromFile(BOOK_URL, "Catalog Series", 9.0)

        assertEquals(1.0, db.bookDao().getByUrl(BOOK_URL)?.seriesIndex)
    }

    @Test
    fun `a dragged book keeps its place through an unlink`() = runTest {
        seed(seriesName = "Catalog Series", seriesIndex = 4.0)
        db.bookDao().fillSeriesFromFile(BOOK_URL, "Catalog Series", 9.0)
        db.seriesOrderDao().renumber(seriesKey("Catalog Series"), listOf(BOOK_URL))

        db.bookDao().unlinkFromRemote(listOf(BOOK_URL))

        val book = db.bookDao().getByUrl(BOOK_URL)
        assertEquals("Catalog Series", book?.seriesName)
        assertEquals(1.0, book?.seriesIndex)
    }

    @Test
    fun `clearing the numbering hands it back to the catalog`() = runTest {
        seed(seriesName = "Catalog Series", seriesIndex = 4.0)
        refreshCatalog(seriesName = "Catalog Series", seriesIndex = 4.0)
        db.seriesOrderDao().renumber(seriesKey("Catalog Series"), listOf(BOOK_URL))

        assertEquals(
            true,
            db.seriesOrderDao().clearOrder(seriesKey("Catalog Series"), listOf(BOOK_URL)),
        )

        val book = db.bookDao().getByUrl(BOOK_URL)
        assertEquals(false, book?.indexOverridden)
        assertEquals(4.0, book?.seriesIndex)
    }

    @Test
    fun `clearing the numbering leaves a hand-filed book unnumbered`() = runTest {
        seed(seriesName = "Catalog Series", seriesIndex = 4.0)
        refreshCatalog(seriesName = "Catalog Series", seriesIndex = 4.0)
        db.bookDao().setSeriesOverride(BOOK_URL, "My Shelf", 2.0)

        db.seriesOrderDao().clearOrder(seriesKey("My Shelf"), listOf(BOOK_URL))

        val book = db.bookDao().getByUrl(BOOK_URL)
        // The catalog's 4 belongs to Catalog Series, and this book is
        // not in Catalog Series any more.
        assertEquals("My Shelf", book?.seriesName)
        assertEquals(true, book?.seriesOverridden)
        assertNull(book?.seriesIndex)
    }

    @Test
    fun `clearing numbering advances the series claim timestamp`() = runTest {
        seed(seriesName = "Catalog Series", seriesIndex = 4.0)
        db.seriesOrderDao().renumber(seriesKey("Catalog Series"), listOf(BOOK_URL))
        val before = db.bookDao().getByUrl(BOOK_URL)?.userSeriesUpdatedAt

        db.seriesOrderDao().clearOrder(seriesKey("Catalog Series"), listOf(BOOK_URL))

        val after = db.bookDao().getByUrl(BOOK_URL)?.userSeriesUpdatedAt
        assertNotNull(after)
        assertTrue(after!! >= before!!)
    }

    @Test
    fun `renumbering a shelf that changed underneath writes nothing`() = runTest {
        seed(seriesName = "Catalog Series", seriesIndex = 4.0)
        db.bookDao().upsert(
            Book(
                url = "file:///books/second.epub",
                title = "Second",
                author = null,
                coverPath = null,
                source = null,
                addedAt = 2,
                lastOpenedAt = null,
                downloadState = DownloadState.DOWNLOADED,
                seriesName = "Catalog Series",
                seriesIndex = 5.0,
            ),
        )

        // The draft was captured when the shelf held one book.
        val committed = db.seriesOrderDao().renumber(seriesKey("Catalog Series"), listOf(BOOK_URL))

        assertEquals(false, committed)
        assertEquals(4.0, db.bookDao().getByUrl(BOOK_URL)?.seriesIndex)
        assertEquals(false, db.bookDao().getByUrl(BOOK_URL)?.indexOverridden)
    }

    @Test
    fun `renumbering matches a series by its folded name`() = runTest {
        seed(seriesName = "L'Épée", seriesIndex = 4.0)

        // No SQL comparison reproduces the folding, which is why the
        // membership check is done in Kotlin.
        assertEquals(true, db.seriesOrderDao().renumber(seriesKey("l'epee"), listOf(BOOK_URL)))
        assertEquals(1.0, db.bookDao().getByUrl(BOOK_URL)?.seriesIndex)
    }

    @Test
    fun `an archived book is not part of the shelf being renumbered`() = runTest {
        seed(seriesName = "Catalog Series", seriesIndex = 4.0)
        db.bookDao().upsert(
            Book(
                url = "file:///books/away.epub",
                title = "Away",
                author = null,
                coverPath = null,
                source = null,
                addedAt = 2,
                lastOpenedAt = null,
                downloadState = DownloadState.DOWNLOADED,
                archivedAt = 99,
                seriesName = "Catalog Series",
                seriesIndex = 5.0,
            ),
        )

        assertEquals(
            true,
            db.seriesOrderDao().renumber(seriesKey("Catalog Series"), listOf(BOOK_URL)),
        )
    }

    private suspend fun seed(
        seriesName: String?,
        seriesIndex: Double?,
        remote: Boolean = true,
    ) {
        db.bookDao().upsert(
            Book(
                url = BOOK_URL,
                title = "A Book",
                author = "An Author",
                coverPath = null,
                source = null,
                addedAt = 1,
                lastOpenedAt = null,
                remoteUuid = if (remote) "remote-id" else null,
                downloadState = DownloadState.DOWNLOADED,
                seriesName = seriesName,
                seriesIndex = seriesIndex,
            ),
        )
    }

    private suspend fun refreshCatalog(seriesName: String?, seriesIndex: Double?) {
        val existing = db.bookDao().getByUrl(BOOK_URL)
        db.bookDao().updateCatalogFields(
            url = BOOK_URL,
            title = "A Book",
            author = "An Author",
            remoteUuid = "remote-id",
            remoteBookId = null,
            coverUrl = null,
            downloadHref = null,
            remoteUpdatedAt = null,
            remotePageCount = null,
            catalogSeriesName = seriesName,
            catalogSeriesIndex = seriesIndex,
            catalogFolderId = null,
            catalogSeriesSource = null,
            userSeriesName = existing?.userSeriesName,
            userSeriesIndex = existing?.userSeriesIndex,
            seriesOverridden = existing?.seriesOverridden ?: false,
            indexOverridden = existing?.indexOverridden ?: false,
            userSeriesUpdatedAt = existing?.userSeriesUpdatedAt,
            expectedUserSeriesUpdatedAt = existing?.userSeriesUpdatedAt,
            seriesId = null,
        )
    }

    /**
     * Giving a shelf's numbering back is a different mutation depending
     * on what else the reader claimed. A book they only numbered has
     * nothing left in its personal layer, so that layer goes; a book
     * they also filed by hand keeps the claim, minus the number.
     */
    @Test
    fun `clearing numbers on a book filed only by the catalog asks for a reset`() = runTest {
        seed(seriesName = "Catalog Series", seriesIndex = 4.0)

        assertTrue(db.seriesOrderDao().clearOrder(seriesKey("Catalog Series"), listOf(BOOK_URL)))

        val book = db.bookDao().getByUrl(BOOK_URL)
        assertEquals(true, book?.seriesClaimPending)
        assertEquals(true, book?.seriesClaimReset)
        assertEquals(false, book?.indexOverridden)
    }

    @Test
    fun `clearing numbers on a book filed by hand keeps its claim`() = runTest {
        seed(seriesName = "Catalog Series", seriesIndex = 4.0)
        db.bookDao().setSeriesOverride(BOOK_URL, "My Shelf", 4.0)

        assertTrue(db.seriesOrderDao().clearOrder(seriesKey("My Shelf"), listOf(BOOK_URL)))

        val book = db.bookDao().getByUrl(BOOK_URL)
        assertEquals(true, book?.seriesClaimPending)
        assertEquals(false, book?.seriesClaimReset)
        assertEquals(true, book?.seriesOverridden)
    }

    private companion object {
        const val BOOK_URL = "calibre:remote-id"
    }
}
