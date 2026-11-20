package com.chmouel.liseur.data.library

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookMetadata
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.domain.MetadataSource
import com.chmouel.liseur.domain.resolveMetadata
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class BookMetadataOverrideTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var db: LiseurDatabase
    private lateinit var repository: BookMetadataOverrideRepository

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LiseurDatabase::class.java,
        ).build()
        repository = BookMetadataOverrideRepository(
            bookDao = db.bookDao(),
            overrideDao = db.bookMetadataOverrideDao(),
            coversDir = folder.newFolder("covers"),
            inTransaction = { block -> db.withTransaction { block() } },
            encodeCoverJpeg = { it },
        )
    }

    @After
    fun close() {
        db.close()
    }

    @Test
    fun `user metadata overrides win and survive catalog and file refresh until reset`() = runTest {
        val url = "book"
        db.bookDao().upsert(
            Book(
                url = url,
                title = "Original",
                author = "Original author",
                coverPath = "file-cover-1.jpg",
                source = null,
                addedAt = 1,
                lastOpenedAt = null,
            ),
        )
        db.bookDao().refreshIndexedFile(
            url = url,
            title = "File title",
            author = "File author",
            coverPath = "file-cover-1.jpg",
            fileModifiedAt = 1,
            workId = "work-a",
            seriesName = null,
            seriesIndex = null,
        )
        db.bookMetadataDao().upsert(
            BookMetadata.of(url, MetadataSource.FILE, com.chmouel.liseur.domain.MetadataSnapshot(description = "File description")),
        )
        updateCatalog(url, title = "Catalog title", author = "Catalog author")
        db.bookMetadataDao().upsert(
            BookMetadata.of(url, MetadataSource.CATALOG, com.chmouel.liseur.domain.MetadataSnapshot(description = "Catalog description")),
        )

        val fromCatalog = db.bookDao().getByUrl(url)!!
        assertEquals("Catalog title", fromCatalog.title)
        assertEquals("Catalog author", fromCatalog.author)

        repository.applyTextEdits(
            bookUrl = url,
            title = FieldEdit.Set(""),
            author = FieldEdit.Set(""),
            description = FieldEdit.Set(""),
        )
        repository.clearCoverToEmpty(url)

        updateCatalog(url, title = "Catalog title updated", author = "Catalog author updated")
        db.bookDao().refreshIndexedFile(
            url = url,
            title = "File title updated",
            author = "File author updated",
            coverPath = "file-cover-2.jpg",
            fileModifiedAt = 2,
            workId = "work-a",
            seriesName = null,
            seriesIndex = null,
        )
        db.bookDao().updateAuthor(url, "Rescanned file author")
        db.bookMetadataDao().upsert(
            BookMetadata.of(url, MetadataSource.CATALOG, com.chmouel.liseur.domain.MetadataSnapshot(description = "Catalog description updated")),
        )

        val overridden = db.bookDao().getByUrl(url)!!
        assertEquals("", overridden.title)
        assertNull(overridden.author)
        assertTrue(overridden.titleOverridden)
        assertTrue(overridden.authorOverridden)
        assertTrue(overridden.descriptionOverridden)
        assertEquals("", overridden.userDescription)
        assertTrue(overridden.coverOverridden)
        assertNull(overridden.userCoverPath)
        assertNull(overridden.effectiveCover)
        assertEquals(1, db.bookDao().allOnce().size)
        assertNull(effectiveDescription(url))

        repository.applyTextEdits(
            bookUrl = url,
            title = FieldEdit.Reset,
            author = FieldEdit.Reset,
            description = FieldEdit.Reset,
        )
        repository.resetCover(url)

        val reset = db.bookDao().getByUrl(url)!!
        assertFalse(reset.titleOverridden)
        assertFalse(reset.authorOverridden)
        assertFalse(reset.descriptionOverridden)
        assertFalse(reset.coverOverridden)
        assertEquals("Catalog title updated", reset.title)
        assertEquals("Catalog author updated", reset.author)
        assertEquals("file-cover-2.jpg", reset.effectiveCover)
        assertEquals("Catalog description updated", effectiveDescription(url))

        repository.applyTextEdits(
            bookUrl = url,
            title = FieldEdit.Set("Manual title"),
            author = FieldEdit.Set("Manual author"),
            description = FieldEdit.Set("Manual description"),
        )
        repository.setCoverFromBytes(url, byteArrayOf(7, 8, 9))
        repository.resetAllTextAndCover(url)

        val resetAll = db.bookDao().getByUrl(url)!!
        assertFalse(resetAll.titleOverridden)
        assertFalse(resetAll.authorOverridden)
        assertFalse(resetAll.descriptionOverridden)
        assertFalse(resetAll.coverOverridden)
        assertEquals("Catalog title updated", resetAll.title)
        assertEquals("Catalog author updated", resetAll.author)
        assertEquals("Catalog description updated", effectiveDescription(url))
        assertEquals("file-cover-2.jpg", resetAll.effectiveCover)
    }

    @Test
    fun `backup export and restore round trip explicit empty text series and custom cover`() = runTest {
        val url = "book"
        db.bookDao().upsert(
            Book(
                url = url,
                title = "Catalog title",
                author = "Catalog author",
                coverPath = "base-cover.jpg",
                source = null,
                addedAt = 1,
                lastOpenedAt = null,
                fileTitle = "File title",
                fileAuthor = "File author",
                catalogTitle = "Catalog title",
                catalogAuthor = "Catalog author",
            ),
        )

        repository.applyTextEdits(
            bookUrl = url,
            title = FieldEdit.Set(""),
            author = FieldEdit.Set("User author"),
            description = FieldEdit.Set(""),
        )
        db.bookDao().setSeriesOverride(url, "Saga", 3.5, updatedAt = 10)
        val coverBytes = byteArrayOf(1, 2, 3, 4)
        assertTrue(repository.setCoverFromBytes(url, coverBytes))

        val backupRepo = BookMetadataOverrideBackupRepository(db.bookDao(), repository)
        val exported = backupRepo.exportOverrides()
        assertNotNull(exported)
        val payload = exported!!
        assertEquals(1, payload.covers.size)

        val restoreDb = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LiseurDatabase::class.java,
        ).build()
        try {
            val restoreRepository = BookMetadataOverrideRepository(
                bookDao = restoreDb.bookDao(),
                overrideDao = restoreDb.bookMetadataOverrideDao(),
                coversDir = folder.newFolder("restore-covers"),
                inTransaction = { block -> restoreDb.withTransaction { block() } },
                encodeCoverJpeg = { it },
            )
            restoreDb.bookDao().upsert(
                Book(
                    url = url,
                    title = "Catalog title",
                    author = "Catalog author",
                    coverPath = "base-cover.jpg",
                    source = null,
                    addedAt = 1,
                    lastOpenedAt = null,
                ),
            )

            val staged = payload.covers.associate { it.path to it.file }
            val restored = BookMetadataOverrideBackupRepository(restoreDb.bookDao(), restoreRepository)
                .restore(payload.jsonBytes, staged)
            assertEquals(1, restored)

            val roundTripped = restoreDb.bookDao().getByUrl(url)!!
            assertTrue(roundTripped.titleOverridden)
            assertEquals("", roundTripped.userTitle)
            assertTrue(roundTripped.authorOverridden)
            assertEquals("User author", roundTripped.userAuthor)
            assertTrue(roundTripped.descriptionOverridden)
            assertEquals("", roundTripped.userDescription)
            assertTrue(roundTripped.seriesOverridden)
            assertTrue(roundTripped.indexOverridden)
            assertEquals("Saga", roundTripped.userSeriesName)
            assertEquals(3.5, roundTripped.userSeriesIndex!!, 0.0)
            assertTrue(roundTripped.coverOverridden)
            assertNotNull(roundTripped.userCoverPath)
            assertEquals(BookMetadataOverrideRepository.sha256Hex(coverBytes), roundTripped.userCoverSha256)
            val restoredCover = File(roundTripped.userCoverPath!!)
            assertTrue(restoredCover.isFile)
            assertEquals(coverBytes.toList(), restoredCover.readBytes().toList())
        } finally {
            restoreDb.close()
        }
    }

    private suspend fun updateCatalog(url: String, title: String, author: String?) {
        val current = db.bookDao().getByUrl(url)!!
        db.bookDao().updateCatalogFields(
            url = url,
            title = title,
            author = author,
            remoteUuid = current.remoteUuid,
            remoteBookId = null,
            coverUrl = "https://example.test/cover.jpg",
            downloadHref = null,
            remoteUpdatedAt = 2,
            remotePageCount = null,
            downloadFormats = null,
            catalogSeriesName = null,
            catalogSeriesIndex = null,
            catalogFolderId = null,
            catalogSeriesSource = null,
            userSeriesName = current.userSeriesName,
            userSeriesIndex = current.userSeriesIndex,
            seriesOverridden = current.seriesOverridden,
            indexOverridden = current.indexOverridden,
            userSeriesUpdatedAt = current.userSeriesUpdatedAt,
            expectedUserSeriesUpdatedAt = current.userSeriesUpdatedAt,
            seriesId = current.seriesId,
            personalSeriesUpdatedAt = current.personalSeriesUpdatedAt,
            sizeBytes = null,
            expectedRemoteUuid = current.remoteUuid,
            expectedLinkGeneration = current.linkGeneration,
            expectedBrowseServerId = current.browseServerId,
            promoteBrowse = false,
        )
    }

    private suspend fun effectiveDescription(url: String): String? {
        val book = db.bookDao().getByUrl(url)!!
        val resolved = resolveMetadata(
            db.bookMetadataDao().forBook(url)
                .mapNotNull { row -> MetadataSource.of(row.source)?.let { it to row.toSnapshot() } }
                .toMap(),
        )
        return if (book.descriptionOverridden) {
            book.userDescription?.takeIf { it.isNotEmpty() }
        } else {
            resolved?.description
        }
    }
}
