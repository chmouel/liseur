package com.chmouel.liseur.ui.launch

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.chmouel.liseur.R
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.remote.SyncOutcome
import com.chmouel.liseur.domain.FinishedOverride
import com.chmouel.liseur.sync.LatestPositionSync
import com.chmouel.liseur.sync.PositionUpdate
import com.chmouel.liseur.sync.ReadingPositionPublisher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
@OptIn(ExperimentalCoroutinesApi::class)
class LaunchViewModelTest {
    private lateinit var requests: LaunchRequests
    private lateinit var models: ViewModelStore
    private lateinit var saved: SavedStateHandle

    private val book = Book(
        url = "identity", title = "Title", author = null, coverPath = null,
        source = null, addedAt = 0, lastOpenedAt = 100, localUri = "file:///book.epub",
    )

    @Before
    fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        requests = LaunchRequests()
        models = ViewModelStore()
        saved = SavedStateHandle()
    }

    @After
    fun cleanup() {
        models.clear()
        Dispatchers.resetMain()
    }

    private fun model(
        flush: suspend () -> Boolean = { true },
        candidate: suspend () -> Book? = { book },
        errors: (Throwable) -> Unit = { throw AssertionError(it) },
    ): LaunchViewModel = LaunchViewModel(
        saved, requests, flush, candidate, openable = { it.openableUrl }, reportError = errors,
    ).also { models.put("launch", it) }

    @Test
    fun `only the three fixed actions are parsed`() {
        assertEquals(LaunchTarget.CONTINUE, LaunchTarget.fromAction(LaunchTarget.CONTINUE.action))
        assertEquals(LaunchTarget.LIBRARY, LaunchTarget.fromAction(LaunchTarget.LIBRARY.action))
        assertEquals(LaunchTarget.STATS, LaunchTarget.fromAction(LaunchTarget.STATS.action))
        assertNull(LaunchTarget.fromAction(null))
        assertNull(LaunchTarget.fromAction("android.intent.action.VIEW"))
        assertNull(LaunchTarget.fromAction("stats"))
    }

    @Test
    fun `repeated taps have different identities and consuming an older tap leaves the newer one`() {
        val first = requests.shortcut(LaunchTarget.LIBRARY)
        val second = requests.shortcut(LaunchTarget.LIBRARY)
        assertTrue(second.id > first.id)
        requests.consume(first)
        assertEquals(second, requests.pending.value)
    }

    @Test
    fun `continue resolves the stable identity and actual file independently`() = runTest {
        val model = model()
        val request = requests.shortcut(LaunchTarget.CONTINUE)
        runCurrent()
        assertEquals(request, model.ready.value?.request)
        assertEquals("identity", model.ready.value?.book?.url)
        assertEquals("file:///book.epub", model.ready.value?.fileUrl)
        assertFalse(model.resolving.value)
        model.handled(request)
        runCurrent()
        assertNull(model.ready.value)
        assertNull(saved.get<String>(LaunchViewModel.PENDING_ACTION))
    }

    @Test
    fun `library and statistics never query a book or flush reader writes`() = runTest {
        val model = model(flush = { error("No flush") }, candidate = { error("No query") })
        for (target in listOf(LaunchTarget.LIBRARY, LaunchTarget.STATS)) {
            val request = requests.shortcut(target)
            runCurrent()
            assertEquals(target, model.ready.value?.request?.target)
            assertNull(model.ready.value?.book)
            model.handled(request)
            runCurrent()
        }
    }

    @Test
    fun `pending continuation survives model recreation but consumed action does not replay`() = runTest {
        val held = CompletableDeferred<Boolean>()
        val first = model(flush = { held.await() })
        requests.shortcut(LaunchTarget.CONTINUE)
        runCurrent()
        assertTrue(first.resolving.value)
        assertEquals(LaunchTarget.CONTINUE.action, saved.get<String>(LaunchViewModel.PENDING_ACTION))
        models.clear()
        // A killed process loses the in-memory bus, but retains the fixed saved action.
        requests = LaunchRequests()
        val second = model()
        runCurrent()
        val request = second.ready.value!!.request
        assertEquals(LaunchTarget.CONTINUE, request.target)
        second.handled(request)
        runCurrent()
        models.clear()
        requests = LaunchRequests()
        val third = model()
        runCurrent()
        assertNull(third.ready.value)
        assertNull(requests.pending.value)
    }

    @Test
    fun `a newer live widget wins over a restored pending shortcut`() = runTest {
        saved[LaunchViewModel.PENDING_ACTION] = LaunchTarget.CONTINUE.action
        val widget = requests.widget(bookUrl = null)
        val model = model(candidate = { error("Stale shortcut") })
        runCurrent()
        assertEquals(widget, model.ready.value?.request)
        assertNull(saved.get<String>(LaunchViewModel.PENDING_ACTION))
    }

    @Test
    fun `newer shortcut cancels a suspended widget request and cannot be cleared by the old tap`() = runTest {
        val model = model()
        val widget = requests.widget(bookUrl = "remote")
        runCurrent()
        assertEquals(widget, model.ready.value?.request)
        val shortcut = requests.shortcut(LaunchTarget.STATS)
        model.handled(widget)
        runCurrent()
        assertEquals(shortcut, model.ready.value?.request)
        assertNull(model.ready.value?.request?.bookUrl)
    }

    @Test
    fun `a widget or shortcut supersedes a continuation waiting for persistence`() = runTest {
        for (widget in listOf(false, true)) {
            val held = CompletableDeferred<Boolean>()
            var queries = 0
            val model = model(flush = { held.await() }, candidate = { queries++; book })
            requests.shortcut(LaunchTarget.CONTINUE)
            runCurrent()
            val newest = if (widget) requests.widget(bookUrl = null)
                else requests.shortcut(LaunchTarget.LIBRARY)
            runCurrent()
            held.complete(true)
            runCurrent()
            assertEquals(0, queries)
            assertEquals(newest, model.ready.value?.request)
            assertFalse(model.resolving.value)
            model.handled(newest)
            runCurrent()
        }
    }

    @Test
    fun `a reader opened directly supersedes a continuation waiting for persistence`() = runTest {
        val held = CompletableDeferred<Boolean>()
        var queries = 0
        val model = model(flush = { held.await() }, candidate = { queries++; book })
        val continuation = requests.shortcut(LaunchTarget.CONTINUE)
        runCurrent()
        val generation = requests.latestId
        val readerStarts = requests.readerStarts
        requests.supersede()
        runCurrent()
        held.complete(true)
        runCurrent()
        assertEquals(0, queries)
        assertNull(model.ready.value)
        assertFalse(model.resolving.value)
        assertFalse(requests.owns(continuation))
        assertNull(saved.get<String>(LaunchViewModel.PENDING_ACTION))
        assertEquals(generation, requests.latestId)
        // A library waiting on a download sees the reader and won't open over it.
        assertEquals(readerStarts + 1, requests.readerStarts)
    }

    @Test
    fun `lookup errors are reported explicitly with no reader target`() = runTest {
        var reported: Throwable? = null
        val model = model(candidate = { throw IllegalStateException("database") }, errors = { reported = it })
        requests.shortcut(LaunchTarget.CONTINUE)
        runCurrent()
        assertNotNull(reported)
        assertEquals(R.string.shortcut_open_failed, model.ready.value?.error)
        assertNull(model.ready.value?.fileUrl)
        assertFalse(model.resolving.value)
    }

    @Test
    fun `a newer shortcut supersedes an already started candidate query`() = runTest {
        val held = CompletableDeferred<Book>()
        val model = model(candidate = { held.await() })
        requests.shortcut(LaunchTarget.CONTINUE)
        runCurrent()
        assertTrue(model.resolving.value)
        val newest = requests.shortcut(LaunchTarget.STATS)
        runCurrent()
        held.complete(book)
        runCurrent()
        assertEquals(newest, model.ready.value?.request)
        assertNull(model.ready.value?.book)
    }

    private fun TestScope.publisher(
        persist: suspend (PositionUpdate, String?) -> Unit,
        complete: suspend (String) -> Unit = {},
    ) = ReadingPositionPublisher(
        scope = backgroundScope, overrideFor = { FinishedOverride.NONE }, persist = persist,
        refreshFinished = {}, markFinished = complete,
        latestSync = LatestPositionSync(
            backgroundScope, request = { SyncOutcome.Success }, scheduleRetry = {}, onError = { _, e -> throw e },
        ),
        scheduleClose = {}, onError = { _, _ -> },
    )

    private fun update(progression: Double) = PositionUpdate(
        "identity", """{"href":"chapter"}""", progression, null, null, null, null, 100,
    )

    @Test
    fun `selection waits for final progression crossing completion and tries the older book`() = runTest {
        val held = CompletableDeferred<Unit>()
        var progression = 0.2
        val publisher = publisher(persist = { value, _ -> held.await(); progression = value.progression!! })
        publisher.publish(update(0.97))
        var queries = 0
        val older = book.copy(url = "older")
        val model = model(flush = publisher::flushLastReader, candidate = {
            queries++
            if (progression >= 0.97) older else book
        })
        requests.shortcut(LaunchTarget.CONTINUE)
        runCurrent()
        assertEquals(0, queries)
        assertNull(model.ready.value)
        held.complete(Unit)
        runCurrent()
        assertEquals("older", model.ready.value?.book?.url)
    }

    @Test
    fun `queued completion leaves Library when no other book is eligible`() = runTest {
        val held = CompletableDeferred<Unit>()
        var finished = false
        val publisher = publisher(persist = { _, _ -> }, complete = { held.await(); finished = true })
        publisher.completeBook("identity")
        var queries = 0
        val model = model(flush = publisher::flushLastReader, candidate = {
            queries++
            if (finished) null else book
        })
        requests.shortcut(LaunchTarget.CONTINUE)
        runCurrent()
        assertEquals(0, queries)
        held.complete(Unit)
        runCurrent()
        assertEquals(1, queries)
        assertNull(model.ready.value?.book)
        assertNull(model.ready.value?.error)
    }

    @Test
    fun `failed final writes show a save error and never query stale candidates`() = runTest {
        val publisher = publisher(persist = { _, _ -> error("disk full") })
        publisher.publish(update(0.97))
        val model = model(flush = publisher::flushLastReader, candidate = { error("Stale query") })
        requests.shortcut(LaunchTarget.CONTINUE)
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(R.string.reader_position_not_saved, model.ready.value?.error)
        assertNull(model.ready.value?.fileUrl)
    }

    @Test
    fun `a newer request does not cancel publisher owned writes`() = runTest {
        val held = CompletableDeferred<Unit>()
        var persisted = false
        val publisher = publisher(persist = { _, _ -> held.await(); persisted = true })
        publisher.publish(update(0.5))
        val model = model(flush = publisher::flushLastReader, candidate = { error("Superseded") })
        requests.shortcut(LaunchTarget.CONTINUE)
        runCurrent()
        val widget = requests.widget(bookUrl = null)
        runCurrent()
        held.complete(Unit)
        runCurrent()
        assertTrue(persisted)
        assertEquals(widget, model.ready.value?.request)
    }
}
