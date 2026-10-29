package com.chmouel.liseur.ui.library

import androidx.room.Room
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
                folderName = { null },
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
}
