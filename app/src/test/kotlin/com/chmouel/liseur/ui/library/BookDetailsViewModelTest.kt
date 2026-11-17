package com.chmouel.liseur.ui.library

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookMetadata
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.domain.MetadataSnapshot
import com.chmouel.liseur.domain.MetadataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
@OptIn(ExperimentalCoroutinesApi::class)
class BookDetailsViewModelTest {

    private lateinit var db: LiseurDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LiseurDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun close() = db.close()

    @Test
    fun showsTheResolvedSnapshotThenGoesWhenTheBookIsRemoved() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val url = "content://books/a.epub"
            db.bookDao().upsert(
                Book(
                    url = url,
                    title = "A",
                    author = null,
                    coverPath = null,
                    source = null,
                    addedAt = 1,
                    lastOpenedAt = null,
                    remotePageCount = 320,
                ),
            )
            db.bookMetadataDao().upsert(
                BookMetadata.of(url, MetadataSource.FILE, MetadataSnapshot(description = "From the file")),
            )
            val model = BookDetailsViewModel(
                bookDao = db.bookDao(),
                metadataDao = db.bookMetadataDao(),
                progressDao = db.readingProgressDao(),
                annotationDao = db.annotationDao(),
                identityDao = db.workIdentityDao(),
                remoteServerDao = db.remoteServerDao(),
                folderName = { null },
                inTransaction = { block -> db.withTransaction { block() } },
            )
            val state = model.details(url)
            val collecting = launch { state.collect {} }

            val ready = state.first { it is BookDetailsUiState.Ready } as BookDetailsUiState.Ready
            assertEquals("From the file", ready.details.metadata.description)
            assertEquals(0, ready.details.annotations)
            // No catalog row yet, as after an upgrade: the server's count still shows.
            assertEquals(320, ready.details.metadata.pageCount)

            db.bookDao().deleteByUrls(listOf(url))
            assertEquals(BookDetailsUiState.Gone, state.first { it is BookDetailsUiState.Gone })
            collecting.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `stale editors cannot save to removed or replaced books`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val url = "content://books/reused.epub"
            val synced = mutableListOf<String>()
            val model = evaluationModel(synced)
            val seed = Book(url = url, title = "Original", author = "Author", coverPath = null,
                source = null, addedAt = 1, lastOpenedAt = null, workId = "original", fileModifiedAt = 1)
            val replacements: List<(Book) -> Book?> = listOf(
                { null },
                { it.copy(id = 0) },
                { it.copy(workId = "replacement") },
                { it.copy(fileModifiedAt = 2) },
                { it.copy(linkGeneration = it.linkGeneration + 1) },
            )
            for (replace in replacements) {
                db.bookDao().deleteByUrls(listOf(url))
                db.bookDao().upsert(seed)
                val original = db.bookDao().getByUrl(url)!!
                val replacement = replace(original)
                if (replacement == null || replacement.id == 0L) db.bookDao().deleteByUrls(listOf(url))
                replacement?.let { db.bookDao().upsert(it) }
                model.setEvaluation(original, "review", "Original book's private review").join()
                assertNull(db.bookEvaluationDao().get(url, "review"))
                assertEquals(emptyList<String>(), synced)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `evaluation saves use current display metadata and request sync after commit`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val url = "content://books/a.epub"
            db.bookDao().upsert(Book(url = url, title = "Original", author = "Author", coverPath = null,
                source = null, addedAt = 1, lastOpenedAt = null))
            val original = db.bookDao().getByUrl(url)!!
            db.bookDao().upsert(original.copy(title = "Corrected title", author = "Corrected author", lastOpenedAt = 2))
            val synced = mutableListOf<String>()
            evaluationModel(synced).setEvaluation(original, "review", "Private review").join()
            val saved = db.bookEvaluationDao().get(url, "review")!!
            assertEquals("Private review", saved.value)
            assertEquals("Corrected title", saved.title)
            assertEquals("Corrected author", saved.author)
            assertEquals(listOf(url), synced)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun evaluationModel(synced: MutableList<String>) = BookDetailsViewModel(
        bookDao = db.bookDao(), metadataDao = db.bookMetadataDao(), progressDao = db.readingProgressDao(),
        annotationDao = db.annotationDao(), identityDao = db.workIdentityDao(), remoteServerDao = db.remoteServerDao(),
        folderName = { null }, inTransaction = { block -> db.withTransaction { block() } },
        evaluationDao = db.bookEvaluationDao(), requestSync = {
            check(!db.inTransaction())
            synced += it
        },
    )
}
