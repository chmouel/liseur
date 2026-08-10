package com.chmouel.liseur.ui.stats

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.ReadingSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ReadingStatsViewModelTest {

    private lateinit var db: LiseurDatabase
    private lateinit var models: ViewModelStore

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LiseurDatabase::class.java,
        ).allowMainThreadQueries().build()
        models = ViewModelStore()
    }

    @After
    fun close() {
        models.clear()
        db.close()
    }

    @Test
    fun `loading is distinct from a loaded empty dashboard`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val model = model()

            assertEquals(ReadingStatsUiState.Loading, model.state.value)
            val loaded = model.state.first { it is ReadingStatsUiState.Ready }

            assertTrue((loaded as ReadingStatsUiState.Ready).stats.isEmpty)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `book loading becomes empty or ready only after the dashboard loads`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val url = "calibre:one"
            db.bookDao().upsert(book(url))
            db.readingSessionDao().insert(
                ReadingSession(
                    bookUrl = url,
                    startedAt = 0,
                    endedAt = 60_000,
                    lastCheckpointAt = 60_000,
                    durationMs = 60_000,
                ),
            )
            val model = model()
            val known = model.forBook(url)
            val missing = model.forBook("missing")

            assertEquals(BookReadingStatsUiState.Loading, known.value)
            assertTrue(
                known.first { it !is BookReadingStatsUiState.Loading } is
                    BookReadingStatsUiState.Ready,
            )
            assertEquals(
                BookReadingStatsUiState.Empty,
                missing.first { it !is BookReadingStatsUiState.Loading },
            )
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    private fun model(): ReadingStatsViewModel {
        val factory = viewModelFactory {
            initializer {
                ReadingStatsViewModel(
                    sessionDao = db.readingSessionDao(),
                    bookDao = db.bookDao(),
                    progressDao = db.readingProgressDao(),
                    zone = { ZoneId.of("Europe/Paris") },
                    today = { LocalDate.of(2026, 8, 10) },
                )
            }
        }
        return ViewModelProvider(models, factory)[ReadingStatsViewModel::class.java]
    }

    private fun book(url: String) = Book(
        url = url,
        title = "A book",
        author = "An author",
        coverPath = null,
        source = null,
        addedAt = 0,
        lastOpenedAt = null,
    )
}
