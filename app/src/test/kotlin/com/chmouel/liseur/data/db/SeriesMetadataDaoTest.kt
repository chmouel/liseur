package com.chmouel.liseur.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private suspend fun seed(seriesName: String?, seriesIndex: Double?) {
        db.bookDao().upsert(
            Book(
                url = BOOK_URL,
                title = "A Book",
                author = "An Author",
                coverPath = null,
                source = null,
                addedAt = 1,
                lastOpenedAt = null,
                remoteUuid = "remote-id",
                downloadState = DownloadState.DOWNLOADED,
                seriesName = seriesName,
                seriesIndex = seriesIndex,
            ),
        )
    }

    private suspend fun refreshCatalog(seriesName: String?, seriesIndex: Double?) {
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
            seriesId = null,
        )
    }

    private companion object {
        const val BOOK_URL = "calibre:remote-id"
    }
}
