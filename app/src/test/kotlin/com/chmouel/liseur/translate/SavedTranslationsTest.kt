package com.chmouel.liseur.translate

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class SavedTranslationsTest {
    private lateinit var db: TranslationCacheDatabase
    private val library = mutableSetOf(BOOK, OTHER)
    private var clock = 1_000L

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TranslationCacheDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun close() = db.close()

    // A book whose presence is asked waits here: what the queue does after it waits too.
    private var held: CompletableDeferred<Unit>? = null

    private fun saved(
        maxSentences: Int = 100,
        maxCharacters: Long = 1_000_000,
    ) = SavedTranslations(
        database = db,
        present = { urls ->
            if (HELD in urls) held?.await()
            urls.filter { it in library }
        },
        now = { clock++ },
        maxSentences = maxSentences,
        maxCharacters = maxCharacters,
    )

    private suspend fun PageTranslationCache.keep(key: String, value: String) = put(key, value, get(key).stamp)

    private suspend fun preview(store: SavedTranslations, total: Int = 1, cached: Int = 1, failed: Int = 0) =
        WholeBookPreview(
            BOOK, "Book", null, "fr", "identity", total, cached, failed, 0, null, null, "fingerprint",
            retentionStamp = store.retentionStamp(BOOK),
            evictionRevision = store.evictionRevision(),
        )

    private suspend fun coverage(store: SavedTranslations, keys: List<String>): WholeBookCoverage {
        val rows = store.scanLookups(BOOK, keys)
        return WholeBookCoverage(keys.size, rows.count { it.translation != null }, rows.count { it.failed }, 0, 0, 0, 0)
    }

    @Test
    fun `startup pause preserves progress and disables automatic resume`() = runTest {
        val store = saved()
        val run = store.begin(BOOK, null, "fr", "identity", 2)
        store.saveSuccess(BOOK, run.generation, "one", "un", 1, 0)
        store.pause(BOOK, run.generation, "network")
        val approved = preview(store, total = 2).copy(resumed = true)

        assertEquals(KeptWriteResult.SAVED, store.pauseBeforeBegin(approved, "paused"))
        val paused = store.getKept(BOOK)!!
        assertEquals("paused", paused.reason)
        assertTrue(paused.generation > run.generation)
        assertEquals(1, paused.done)
        assertEquals(false, resumable(paused.reason, automatic = true))
        assertEquals("un", store.strictLookup(BOOK, "one").translation)
        assertEquals(KeptWriteResult.INVALIDATED, store.pauseBeforeBegin(approved, "interrupted"))
        assertEquals("paused", store.getKept(BOOK)?.reason)
        try {
            store.begin(BOOK, null, "fr", "identity", 2, preview = approved)
            error("Explicitly paused startup resumed")
        } catch (_: kotlinx.coroutines.CancellationException) {
            assertEquals("paused", store.getKept(BOOK)?.reason)
        }
    }

    @Test
    fun `a changed key or server holds only jobs that would resume by themselves`() = runTest {
        val store = saved()
        val reasons = mapOf(
            "network" to "settings_changed",
            "rate_limited:429:${Long.MAX_VALUE - 1}" to "settings_changed",
            "service:503" to "settings_changed",
            "paused" to "paused",
            "rate_limited:402" to "rate_limited:402",
        )
        for ((reason, expected) in reasons) {
            val run = store.begin(BOOK, null, "fr", "identity", 1)
            store.pause(BOOK, run.generation, reason)
            store.requireCheck()
            assertEquals(reason, expected, store.getKept(BOOK)?.reason)
        }
        val running = store.begin(BOOK, null, "fr", "identity", 1)
        store.requireCheck()
        assertEquals("running", store.getKept(BOOK)?.state)
        assertEquals(running.generation, store.getKept(BOOK)?.generation)
    }

    @Test
    fun `startup pause cannot recreate deleted records or pause a replacement generation`() = runTest {
        val store = saved()
        val run = store.begin(BOOK, null, "fr", "identity", 1)
        store.pause(BOOK, run.generation, "interrupted")
        val approved = preview(store).copy(resumed = true)
        store.remove(BOOK)
        assertEquals(KeptWriteResult.INVALIDATED, store.pauseBeforeBegin(approved, "paused"))
        assertNull(store.getKept(BOOK))

        val replacement = store.begin(BOOK, null, "fr", "identity", 1)
        assertEquals(KeptWriteResult.INVALIDATED, store.pauseBeforeBegin(approved, "paused"))
        store.pause(BOOK, replacement.generation, "network")
        assertEquals(KeptWriteResult.INVALIDATED, store.pauseBeforeBegin(approved, "paused"))
        assertEquals("network", store.getKept(BOOK)?.reason)
    }

    @Test
    fun `startup pause refuses running and completed records even with matching stamp`() = runTest {
        val store = saved()
        val run = store.begin(BOOK, null, "fr", "identity", 0)
        val approved = preview(store, total = 0, cached = 0)
        assertEquals(KeptWriteResult.INVALIDATED, store.pauseBeforeBegin(approved, "paused"))
        store.complete(BOOK, run.generation)
        assertEquals(KeptWriteResult.INVALIDATED, store.pauseBeforeBegin(approved, "paused"))
        assertEquals("done", store.getKept(BOOK)?.state)
    }

    @Test
    fun `ordinary cached coverage can be retained and survives subsequent trim`(): Unit = runTest {
        val store = saved(maxSentences = 1)
        store.forBook(BOOK).keep("one", "un")
        val approved = preview(store)
        store.retain(approved) { coverage(store, listOf("one")) }
        store.forBook(OTHER).keep("two", "deux")
        store.tidy()
        assertEquals("done", store.getKept(BOOK)?.state)
        assertEquals("un", store.strictLookup(BOOK, "one").translation)
    }

    @Test
    fun `last committed sentence can be finalized after an interrupted completion`(): Unit = runTest {
        val store = saved()
        val run = store.begin(BOOK, null, "fr", "identity", 2)
        store.saveSuccess(BOOK, run.generation, "one", "un", 1, 0)
        store.saveFailure(BOOK, run.generation, "two", 1, 1)
        store.pause(BOOK, run.generation, "interrupted")
        store.retain(preview(store, total = 2, cached = 1, failed = 1)) { coverage(store, listOf("one", "two")) }
        val kept = store.getKept(BOOK)!!
        assertEquals("done", kept.state)
        assertEquals(1, kept.done)
        assertEquals(1, kept.failed)
        assertTrue(kept.generation > run.generation)
    }

    @Test
    fun `trim between preview and adoption cannot retain missing translations`(): Unit = runTest {
        val store = saved(maxCharacters = 0)
        store.forBook(BOOK).keep("one", "un")
        assertEquals("un", store.strictLookup(BOOK, "one").translation)
        val approved = preview(store)
        store.tidy()
        var checked = false
        try {
            store.retain(approved) {
                checked = true
                coverage(store, listOf("one"))
            }
            error("Missing coverage accepted")
        } catch (_: IllegalStateException) {
            assertNull(store.getKept(BOOK))
            assertTrue(!checked)
        }
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `eviction discards prepared coverage without rescanning and blocks a stale start`(): Unit = runTest {
        val store = saved(maxSentences = 1)
        store.forBook(BOOK).keep("one", "un")
        val approved = preview(store)
        var scans = 0
        val preparation = WholeBookPreparation(backgroundScope, { _, _ ->
            scans++
            approved
        }, { candidate -> store.evictionRevision() == candidate.evictionRevision })
        preparation.prepare()
        runCurrent()
        assertTrue(preparation.state.value is PreparationState.Ready)
        store.forBook(OTHER).keep("two", "deux")
        assertNull(store.strictLookup(BOOK, "one").translation)
        assertEquals(approved.retentionStamp, store.retentionStamp(BOOK))
        preparation.validate()
        val discarded = backgroundScope.launch {
            preparation.state.first { it == PreparationState.Idle }
        }
        discarded.join()
        assertEquals(1, scans)
        try {
            store.begin(BOOK, null, "fr", "identity", 1, preview = approved)
            error("Evicted coverage accepted")
        } catch (_: IllegalStateException) {
            assertNull(store.getKept(BOOK))
        }
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `trim cannot enter between final coverage checking and retention`(): Unit = runTest {
        val store = saved(maxCharacters = 0)
        store.forBook(BOOK).keep("one", "un")
        val approved = preview(store)
        val checking = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val retain = async {
            store.retain(approved) {
                checking.complete(Unit)
                proceed.await()
                coverage(store, listOf("one"))
            }
        }
        checking.await()
        val trim = async { store.tidy() }
        runCurrent()
        assertTrue(!trim.isCompleted)
        proceed.complete(Unit)
        retain.await()
        trim.await()
        assertEquals("done", store.getKept(BOOK)?.state)
        assertEquals("un", store.strictLookup(BOOK, "one").translation)
    }

    @Test
    fun `stale approvals preserve the current generation and refusal markers`(): Unit = runTest {
        val store = saved()
        val first = store.begin(BOOK, null, "fr", "identity", 1)
        store.saveFailure(BOOK, first.generation, "one", 0, 1)
        store.pause(BOOK, first.generation, "paused")
        val stale = preview(store, cached = 0, failed = 1)
        val second = store.begin(BOOK, null, "fr", "identity", 1)
        store.pause(BOOK, second.generation, "paused")
        val before = store.getKept(BOOK)
        try {
            store.begin(BOOK, null, "fr", "identity", 1, retryFailures = true, preview = stale)
            error("Stale preview accepted")
        } catch (_: IllegalStateException) {
            assertEquals(before, store.getKept(BOOK))
            assertTrue(store.strictLookup(BOOK, "one").failed)
        }
        store.remove(BOOK)
        try {
            store.retain(stale) { WholeBookCoverage(1, 1, 0, 0, 0, 0, 0) }
            error("Deleted translation resurrected")
        } catch (_: IllegalStateException) {
            assertNull(store.getKept(BOOK))
        }
    }

    @Test
    fun `a resume only goes on while the very pause it was asked from is there`(): Unit = runTest {
        val store = saved()
        val first = store.begin(BOOK, null, "fr", "identity", 1)
        store.pause(BOOK, first.generation, "network")
        val resumed = preview(store, cached = 0).copy(resumed = true)
        store.remove(BOOK)
        try {
            store.begin(BOOK, null, "fr", "identity", 1, preview = resumed)
            error("Deleted translation resumed")
        } catch (_: kotlin.coroutines.cancellation.CancellationException) {
            assertNull(store.getKept(BOOK))
        }

        val again = store.begin(BOOK, null, "fr", "identity", 1)
        store.pause(BOOK, again.generation, "network")
        val approved = preview(store, cached = 0).copy(resumed = true)
        assertEquals("running", store.begin(BOOK, null, "fr", "identity", 1, preview = approved).state)
    }

    @Test
    fun `local adoption never verifies an imported record`(): Unit = runTest {
        val store = saved()
        val first = store.begin(BOOK, null, "fr", "identity", 1)
        db.keptTranslations().put(first.copy(state = "restored"))
        try {
            store.retain(preview(store)) { WholeBookCoverage(1, 1, 0, 0, 0, 0, 0) }
            error("Imported coverage verified")
        } catch (_: IllegalStateException) {
            assertEquals("restored", store.getKept(BOOK)?.state)
        }
    }

    @Test
    fun `kept running rows and failures survive trimming and global clear`() = runTest {
        val store = saved(maxSentences = 0, maxCharacters = 0)
        val record = store.begin(BOOK, null, "fr", "identity", 2)
        store.saveSuccess(BOOK, record.generation, "one", "un", 1, 0)
        store.saveFailure(BOOK, record.generation, "two", 1, 1)
        store.ensureWork(PEER, BOOK, "work")
        store.forBook(OTHER).keep("ordinary", "gone")
        store.tidy()
        store.clear()
        assertEquals("un", store.strictLookup(BOOK, "one").translation)
        assertTrue(store.forBook(BOOK).get("two").refused)
        assertTrue(store.strictLookup(BOOK, "two").failed)
        assertEquals("work", store.syncState(PEER, BOOK)?.workId)
        assertEquals(1, store.keptRecords().size)
        assertEquals(1, store.stats.first().keptSentences)
        assertEquals(0, store.stats.first().sentences)
        store.complete(BOOK, record.generation)
        assertEquals("done", store.keptRecords().single().state)
    }

    @Test
    fun `delete and resume fence old responses and absolute progress never accumulates`() = runTest {
        val store = saved()
        val first = store.begin(BOOK, null, "fr", "identity", 2)
        store.saveSuccess(BOOK, first.generation, "one", "un", 1, 0)
        store.pause(BOOK, first.generation, "network")
        assertEquals(KeptWriteResult.INVALIDATED, store.saveFailure(BOOK, first.generation, "two", 1, 1))
        val resumed = store.begin(BOOK, null, "fr", "identity", 2)
        repeat(3) { store.progress(BOOK, resumed.generation, 1, 0) }
        assertEquals(1, store.keptRecords().single().done)
        store.remove(BOOK)
        val replacement = store.begin(BOOK, null, "fr", "identity", 2)
        assertTrue(replacement.generation > resumed.generation)
        assertEquals(KeptWriteResult.INVALIDATED,
            store.saveSuccess(BOOK, resumed.generation, "two", "late", 2, 0))
        assertNull(store.strictLookup(BOOK, "two").translation)
    }

    @Test
    fun `sweep removes a kept-only book and strict reads propagate storage failures`() = runTest {
        val store = saved()
        store.begin(BOOK, null, "fr", "identity", 0)
        library -= BOOK
        store.sweep()
        assertTrue(store.keptRecords().isEmpty())
        db.close()
        var failed = false
        try { store.strictLookup(BOOK, "one") } catch (_: Exception) { failed = true }
        assertTrue(failed)
    }

    @Test
    fun `begin preserves failed markers unless retry was explicitly requested`() = runTest {
        val store = saved()
        val first = store.begin(BOOK, null, "fr", "identity", 2)
        store.saveSuccess(BOOK, first.generation, "one", "un", 1, 0)
        store.saveFailure(BOOK, first.generation, "two", 1, 1)
        val resumed = store.begin(BOOK, null, "fr", "identity", 2)
        assertTrue(store.strictLookup(BOOK, "two", resumed.generation).failed)
        val retry = store.begin(BOOK, null, "fr", "identity", 2, retryFailures = true)
        assertEquals(false, store.strictLookup(BOOK, "two", retry.generation).failed)
        assertEquals("un", store.strictLookup(BOOK, "one", retry.generation).translation)
        assertTrue(store.strictLookup(BOOK, "two", resumed.generation).invalidated)
        assertEquals(retry, store.getKept(BOOK))
    }

    @Test
    fun `a failure never replaces a success delivered by sync`() = runTest {
        val store = saved()
        val record = store.begin(BOOK, null, "fr", "identity", 1)
        store.landPulled(PEER, BOOK, store.pullTicket(BOOK),
            listOf(PulledTranslation(hashed("one"), "peer answer")), "cursor")
        assertEquals(KeptWriteResult.ALREADY_PRESENT,
            store.saveFailure(BOOK, record.generation, "one", 1, 0))
        val found = store.strictLookup(BOOK, "one", record.generation)
        assertEquals("peer answer", found.translation)
        assertEquals(false, found.failed)
        assertTrue(store.exportFailures(BOOK).isEmpty())
    }

    @Test
    fun `batched scan matches strict lookups including duplicates and success over refusal`() = runTest {
        val store = saved()
        val record = store.begin(BOOK, null, "fr", "identity", 3)
        store.saveSuccess(BOOK, record.generation, "one", "un", 1, 0)
        store.saveFailure(BOOK, record.generation, "bad", 1, 1)
        store.saveFailure(BOOK, record.generation, "recovered", 1, 2)
        store.landPulled(PEER, BOOK, store.pullTicket(BOOK),
            listOf(PulledTranslation(hashed("recovered"), "recovered answer")), "cursor")
        val keys = listOf("bad", "one", "missing", "recovered", "one")
        assertEquals(keys.map { store.strictLookup(BOOK, it) }, store.scanLookups(BOOK, keys))
        assertTrue(store.scanLookups(OTHER, keys).all { it == KeptLookup() })
        assertTrue(store.scanLookups(BOOK, emptyList()).isEmpty())
    }

    @Test
    fun `a book opened again finds what it translated`() = runTest {
        val store = saved()
        store.forBook(BOOK).keep("one", "un")

        assertEquals("un", store.forBook(BOOK).get("one").translation)
        assertNull(store.forBook(OTHER).get("one").translation)
        assertEquals(1, store.stats.first().sentences)
    }

    @Test
    fun `a reply asked before a clear is not kept after it`() = runTest {
        val store = saved()
        val book = store.forBook(BOOK)
        val asked = book.get("one")

        store.clear()
        book.put("one", "un", asked.stamp)

        assertNull(book.get("one").translation)
        book.keep("two", "deux")
        assertEquals("deux", book.get("two").translation)
    }

    @Test
    fun `a removed book's sentences go and its open handle keeps nothing more`() = runTest {
        val store = saved()
        val book = store.forBook(BOOK)
        book.keep("one", "un")
        store.forBook(OTHER).keep("one", "un")

        library -= BOOK
        store.sweep()

        assertNull(book.get("one").translation)
        book.keep("two", "deux")
        assertNull(book.get("two").translation)
        assertEquals("un", store.forBook(OTHER).get("one").translation)

        // Added back, it is a new opening and keeps again.
        library += BOOK
        store.forBook(BOOK).keep("two", "deux")
        assertEquals("deux", store.forBook(BOOK).get("two").translation)
    }

    @Test
    fun `a book removed during its first translation keeps nothing`() = runTest {
        val store = saved()
        val book = store.forBook(BOOK)
        val asked = book.get("one")

        library -= BOOK
        store.sweep()
        book.put("one", "un", asked.stamp)

        assertEquals(0, store.stats.first().sentences)
    }

    @Test
    fun `a handle made after its book was swept away keeps nothing`() = runTest {
        val store = saved()
        library -= BOOK
        store.sweep()

        val late = store.forBook(BOOK)
        late.keep("one", "un")

        assertEquals(0, store.stats.first().sentences)
    }

    @Test
    fun `a book brought back after its removal saves again through later sweeps`() = runTest {
        val store = saved()
        store.forBook(BOOK).keep("one", "un")
        library -= BOOK
        store.sweep()

        library += BOOK
        val back = store.forBook(BOOK)
        store.sweep()
        back.keep("two", "deux")

        assertEquals("deux", back.get("two").translation)
        assertNull(back.get("one").translation)
    }

    @Test
    fun `a sweep while the book is still there keeps it`() = runTest {
        val store = saved()
        val book = store.forBook(BOOK)
        book.keep("one", "un")

        // As when a removal's transaction has not committed, or rolled back.
        store.sweep()

        assertEquals("un", book.get("one").translation)
        book.keep("two", "deux")
        assertEquals("deux", book.get("two").translation)
    }

    @Test
    fun `the least recently read go first past the cap, also across restarts`() = runTest {
        saved(maxSentences = 1_000).forBook(BOOK).apply {
            for (i in 0 until 5) keep("s$i", "t$i")
        }
        val book = saved(maxSentences = 3).forBook(BOOK)
        // Read again, so newer than the others.
        book.get("s0")

        val store = saved(maxSentences = 3)
        store.tidy()

        assertEquals(3, store.stats.first().sentences)
        assertEquals("t0", book.get("s0").translation)
        assertNull(book.get("s1").translation)
        assertNull(book.get("s2").translation)
        assertEquals("t4", book.get("s4").translation)
    }

    @Test
    fun `a save past the cap trims at once`() = runTest {
        val store = saved(maxSentences = 3)
        store.forBook(BOOK).apply {
            for (i in 0 until 5) keep("s$i", "t$i")
        }

        assertEquals(3, store.stats.first().sentences)
        assertNull(store.forBook(BOOK).get("s0").translation)
        assertEquals("t4", store.forBook(BOOK).get("s4").translation)
    }

    @Test
    fun `long translations are trimmed to the character budget`() = runTest {
        saved().forBook(BOOK).apply {
            keep("a", "x".repeat(400))
            keep("b", "y".repeat(400))
            keep("c", "z".repeat(400))
        }

        val store = saved(maxCharacters = 900)
        store.tidy()

        val book = store.forBook(BOOK)
        assertNull(book.get("a").translation)
        assertEquals(400, book.get("b").translation?.length)
        assertEquals(400, book.get("c").translation?.length)
    }

    @Test
    fun `clearing forgets everything`() = runTest {
        val store = saved()
        store.forBook(BOOK).keep("one", "un")
        store.forBook(OTHER).keep("two", "deux")

        store.clear()

        assertEquals(0, store.stats.first().sentences)
    }

    @Test
    fun `a sweep that empties the store gives the room back`() = runTest {
        val file = File.createTempFile("translations", ".db").also { it.delete() }
        val onDisk = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), TranslationCacheDatabase::class.java, file.path)
            .allowMainThreadQueries()
            .build()
        try {
            val files = { listOf(file, File("${file.path}-wal"), File("${file.path}-shm")) }
            val store = SavedTranslations(onDisk, { urls -> urls.filter { it in library } }, files, now = { clock++ })
            val book = store.forBook(BOOK)
            repeat(300) { book.keep("sentence $it", "x".repeat(2_000)) }
            val full = store.stats.first().bytes

            library.clear()
            store.sweep()

            val after = store.stats.first()
            assertEquals(0, after.sentences)
            assertTrue("${after.bytes} of $full", after.bytes < full / 4)
        } finally {
            onDisk.close()
            listOf("", "-wal", "-shm", "-journal").forEach { File("${file.path}$it").delete() }
        }
    }

    @Test
    fun `a book left translated opens translated until stopped`() = runTest {
        val store = saved()
        val book = store.modeFor(BOOK)
        assertNull(book.saved())

        book.remember(PageTranslationModes.Mode(null, "en"))
        assertEquals(PageTranslationModes.Mode(null, "en"), store.modeFor(BOOK).saved())
        assertNull(store.modeFor(OTHER).saved())

        book.remember(PageTranslationModes.Mode("fr", "de"))
        assertEquals(PageTranslationModes.Mode("fr", "de"), store.modeFor(BOOK).saved())

        book.forget {}
        assertNull(store.modeFor(BOOK).saved())
    }

    @Test
    fun `a removed book stops opening translated and its open handle cannot bring that back`() = runTest {
        val store = saved()
        val book = store.modeFor(BOOK)
        book.remember(PageTranslationModes.Mode("fr", "en"))
        store.modeFor(OTHER).remember(PageTranslationModes.Mode("fr", "en"))
        assertEquals(PageTranslationModes.Mode("fr", "en"), book.saved())

        library -= BOOK
        store.sweep()
        book.remember(PageTranslationModes.Mode("fr", "en"))

        assertNull(store.modeFor(BOOK).saved())
        assertEquals(PageTranslationModes.Mode("fr", "en"), store.modeFor(OTHER).saved())
    }

    @Test
    fun `clearing the sentences leaves books translated`() = runTest {
        val store = saved()
        store.modeFor(BOOK).remember(PageTranslationModes.Mode("fr", "en"))
        store.forBook(BOOK).keep("one", "un")

        store.clear()

        assertEquals(0, store.stats.first().sentences)
        assertEquals(PageTranslationModes.Mode("fr", "en"), store.modeFor(BOOK).saved())
    }

    @Test
    fun `a book reopened right after Stop does not read the mode Stop is removing`() = runTest {
        val store = saved()
        val closing = store.modeFor(BOOK)
        closing.remember(PageTranslationModes.Mode("fr", "en"))
        assertEquals(PageTranslationModes.Mode("fr", "en"), closing.saved())

        holdQueue(store)
        closing.forget {}
        val reopened = async { store.modeFor(BOOK).saved() }
        held!!.complete(Unit)

        assertNull(reopened.await())
    }

    @Test
    fun `changes from two openings of a book land in the order they were made`() = runTest {
        val store = saved()
        val closing = store.modeFor(BOOK)
        val reopened = store.modeFor(BOOK)

        holdQueue(store)
        closing.remember(PageTranslationModes.Mode("fr", "en"))
        closing.forget {}
        reopened.remember(PageTranslationModes.Mode("de", "en"))
        held!!.complete(Unit)
        assertEquals(PageTranslationModes.Mode("de", "en"), reopened.saved())

        holdQueue(store)
        reopened.remember(PageTranslationModes.Mode("it", "en"))
        closing.forget {}
        held!!.complete(Unit)
        assertNull(reopened.saved())
    }

    @Test
    fun `a Stop that could not be saved says so`() = runTest {
        val store = saved()
        store.modeFor(BOOK).remember(PageTranslationModes.Mode("fr", "en"))
        assertEquals(PageTranslationModes.Mode("fr", "en"), store.modeFor(BOOK).saved())
        db.openHelper.writableDatabase.execSQL("DROP TABLE page_translation_modes")

        val failed = CompletableDeferred<Unit>()
        store.modeFor(BOOK).forget { failed.complete(Unit) }

        failed.await()
    }

    // Blocks the shared queue behind a change for another book until [held] completes.
    private fun holdQueue(store: SavedTranslations) {
        library += HELD
        held = CompletableDeferred()
        store.modeFor(HELD).remember(PageTranslationModes.Mode(null, "en"))
    }

    // -- Cross-device exchange (ADR-0052) ----------------------------------

    /** The same hash the store keys its rows by, for speaking its language in a test. */
    private fun hashed(key: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `a pulled page lands and moves the cursor together`() = runTest {
        val store = saved()
        val ticket = store.pullTicket(BOOK)

        val landed = store.landPulled(
            PEER, BOOK, ticket,
            listOf(PulledTranslation(hashed("one"), "un"), PulledTranslation(hashed("two"), "deux")),
            "cursor-1",
        )

        assertEquals(2, landed)
        assertEquals("un", store.forBook(BOOK).get("one").translation)
        assertEquals("cursor-1", store.syncState(PEER, BOOK)!!.pullCursor)
    }

    @Test
    fun `a pulled sentence already here keeps this phone's words`() = runTest {
        val store = saved()
        store.forBook(BOOK).keep("one", "mine")

        val landed = store.landPulled(
            PEER, BOOK, store.pullTicket(BOOK),
            listOf(PulledTranslation(hashed("one"), "theirs")),
            "cursor-1",
        )

        assertEquals(0, landed)
        assertEquals("mine", store.forBook(BOOK).get("one").translation)
    }

    @Test
    fun `a pulled page never comes back as something to push`() = runTest {
        val store = saved()
        store.landPulled(
            PEER, BOOK, store.pullTicket(BOOK),
            listOf(PulledTranslation(hashed("one"), "un")),
            "cursor-1",
        )

        val owed = store.unsynced(BOOK, store.syncState(PEER, BOOK)!!, 500)

        assertTrue(owed.isEmpty())
    }

    @Test
    fun `a save is offered, and not offered again once acknowledged`() = runTest {
        val store = saved()
        store.forBook(BOOK).keep("one", "un")
        val state = store.syncState(PEER, BOOK) ?: TranslationSyncState(PEER, BOOK)

        val owed = store.unsynced(BOOK, state, 500)
        assertEquals(listOf(hashed("one")), owed.map { it.key })

        store.recordPushed(PEER, BOOK, owed.single())
        assertTrue(store.unsynced(BOOK, store.syncState(PEER, BOOK)!!, 500).isEmpty())
    }

    @Test
    fun `saves sharing a millisecond are all offered, not just the first`() = runTest {
        // The clock stands still: a page's sentences are saved within
        // one reading of it, and a watermark that cut at the time alone
        // would strand every row tied with the last one acknowledged.
        val store = saved()
        val book = store.forBook(BOOK)
        book.keep("one", "un")
        book.keep("two", "deux")
        book.keep("three", "trois")
        val state = TranslationSyncState(PEER, BOOK)

        val first = store.unsynced(BOOK, state, 2)
        assertEquals(2, first.size)
        store.recordPushed(PEER, BOOK, first.last())

        val rest = store.unsynced(BOOK, store.syncState(PEER, BOOK)!!, 500)
        assertEquals(listOf(hashed("three")), rest.map { it.key })
    }

    @Test
    fun `a pull ticket sampled during a clear is fenced after commit`() = runTest {
        val transactionExecutor = GatedTransactionExecutor()
        val racingDatabase = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TranslationCacheDatabase::class.java,
        ).setTransactionExecutor(transactionExecutor).allowMainThreadQueries().build()
        try {
            racingDatabase.openHelper.writableDatabase
            val store = SavedTranslations(
                database = racingDatabase,
                present = { urls -> urls.filter { it == BOOK } },
                now = { clock++ },
            )
            val gate = transactionExecutor.pauseNext()
            val clearing = async { store.clear() }
            gate.started.await()
            val ticket = store.pullTicket(BOOK)

            gate.release.complete(Unit)
            clearing.await()
            val landed = store.landPulled(
                PEER, BOOK, ticket,
                listOf(PulledTranslation(hashed("one"), "un")),
                "cursor-1",
            )

            assertEquals(0, landed)
            assertNull(store.forBook(BOOK).get("one").translation)
            assertNull(store.syncState(PEER, BOOK))
        } finally {
            transactionExecutor.releaseAndClose()
            racingDatabase.close()
        }
    }

    @Test
    fun `a ticket sampled during a canceled clear is fenced`() = runTest {
        assertClearTicketFencedByCancellation { it.clear() }
    }

    @Test
    fun `a ticket sampled during a canceled book clear is fenced`() = runTest {
        assertClearTicketFencedByCancellation { it.clearBook(BOOK) }
    }

    private suspend fun assertClearTicketFencedByCancellation(
        clear: suspend (SavedTranslations) -> Unit,
    ) = coroutineScope {
        val transactionExecutor = GatedTransactionExecutor()
        val racingDatabase = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TranslationCacheDatabase::class.java,
        ).setTransactionExecutor(transactionExecutor).allowMainThreadQueries().build()
        try {
            racingDatabase.openHelper.writableDatabase
            val store = SavedTranslations(
                database = racingDatabase,
                present = { urls -> urls.filter { it == BOOK } },
                now = { clock++ },
            )
            val gate = transactionExecutor.pauseNext()
            val clearing = async { clear(store) }
            gate.started.await()
            val ticket = store.pullTicket(BOOK)

            clearing.cancel()
            gate.release.complete(Unit)
            gate.completed.await()
            clearing.join()
            val landed = store.landPulled(
                PEER, BOOK, ticket,
                listOf(PulledTranslation(hashed("one"), "un")),
                "cursor-1",
            )

            assertEquals(0, landed)
            assertNull(store.forBook(BOOK).get("one").translation)
            assertNull(store.syncState(PEER, BOOK))
        } finally {
            transactionExecutor.releaseAndClose()
            racingDatabase.close()
        }
    }

    @Test
    fun `a pull asked before a clear lands nothing and keeps no cursor`() = runTest {
        val store = saved()
        val ticket = store.pullTicket(BOOK)

        store.clear()
        val landed = store.landPulled(
            PEER, BOOK, ticket,
            listOf(PulledTranslation(hashed("one"), "un")),
            "cursor-1",
        )

        assertEquals(0, landed)
        assertNull(store.forBook(BOOK).get("one").translation)
        assertNull(store.syncState(PEER, BOOK))
    }

    @Test
    fun `a pull for a book the library no longer has lands nothing`() = runTest {
        val store = saved()
        val ticket = store.pullTicket(BOOK)

        library -= BOOK
        val landed = store.landPulled(
            PEER, BOOK, ticket,
            listOf(PulledTranslation(hashed("one"), "un")),
            "cursor-1",
        )

        assertEquals(0, landed)
        assertNull(store.syncState(PEER, BOOK))
    }

    @Test
    fun `a clear forgets the peer bookkeeping so the next pull starts over`() = runTest {
        val store = saved()
        store.landPulled(
            PEER, BOOK, store.pullTicket(BOOK),
            listOf(PulledTranslation(hashed("one"), "un")),
            "cursor-1",
        )

        store.clear()

        assertNull(store.syncState(PEER, BOOK))
    }

    @Test
    fun `a removed book's peer bookkeeping goes with its sentences`() = runTest {
        val store = saved()
        store.forBook(BOOK).keep("one", "un")
        store.landPulled(
            PEER, BOOK, store.pullTicket(BOOK),
            listOf(PulledTranslation(hashed("one"), "un")),
            "cursor-1",
        )

        library -= BOOK
        store.sweep()

        assertNull(store.syncState(PEER, BOOK))
    }

    @Test
    fun `each peer keeps its own place`() = runTest {
        val store = saved()
        store.forBook(BOOK).keep("one", "un")
        val owed = store.unsynced(BOOK, TranslationSyncState(PEER, BOOK), 500)
        store.recordPushed(PEER, BOOK, owed.single())
        store.landPulled(
            OTHER_PEER, BOOK, store.pullTicket(BOOK),
            listOf(PulledTranslation(hashed("two"), "deux")),
            "other-cursor",
        )

        val other = store.syncState(OTHER_PEER, BOOK)!!
        assertEquals("other-cursor", other.pullCursor)
        assertEquals(0, other.pushedSavedAt)
        // The other peer is still owed the save this one has taken.
        assertEquals(listOf(hashed("one")), store.unsynced(BOOK, other, 500).map { it.key })

        store.forgetPeer(OTHER_PEER)
        assertNull(store.syncState(OTHER_PEER, BOOK))
        // The first peer's place is untouched by the second's going.
        assertEquals(owed.single().savedAt, store.syncState(PEER, BOOK)!!.pushedSavedAt)
    }

    @Test
    fun `a save is never stamped below the last, whatever the clock did`() = runTest {
        val store = saved()
        store.forBook(BOOK).keep("one", "un")
        // The clock steps back mid-session; a save that landed below a
        // watermark a peer acknowledged would be stranded behind it.
        clock -= 10
        store.forBook(BOOK).keep("two", "deux")

        val owed = store.unsynced(BOOK, TranslationSyncState(PEER, BOOK), 500)
        assertEquals(listOf(hashed("one"), hashed("two")), owed.map { it.key })
        assertTrue(owed[1].savedAt > owed[0].savedAt)
    }

    @Test
    fun `a peer's newer text follows into a row it pulled, but not one saved here`() = runTest {
        val store = saved()
        store.landPulled(
            PEER, BOOK, store.pullTicket(BOOK),
            listOf(PulledTranslation(hashed("one"), "first")), "cursor-1",
        )
        store.landPulled(
            PEER, BOOK, store.pullTicket(BOOK),
            listOf(PulledTranslation(hashed("one"), "second")), "cursor-2",
        )
        assertEquals("second", store.forBook(BOOK).get("one").translation)

        store.forBook(BOOK).keep("two", "mine")
        store.landPulled(
            PEER, BOOK, store.pullTicket(BOOK),
            listOf(PulledTranslation(hashed("two"), "theirs")), "cursor-3",
        )
        assertEquals("mine", store.forBook(BOOK).get("two").translation)
    }

    @Test
    fun `a book re-resolved to a new work starts the exchange over`() = runTest {
        val store = saved()
        store.forBook(BOOK).keep("one", "un")
        val first = store.ensureWork(PEER, BOOK, "work-one")
        val owed = store.unsynced(BOOK, first, 500)
        store.recordPushed(PEER, BOOK, owed.single())
        store.landPulled(
            PEER, BOOK, store.pullTicket(BOOK),
            listOf(PulledTranslation(hashed("pulled"), "un")), "cursor-1",
        )

        // The server disowned the name; the book resolves to another.
        val second = store.ensureWork(PEER, BOOK, "work-two")

        assertEquals("work-two", second.workId)
        assertEquals(0, second.pushedSavedAt)
        assertEquals("", second.pullCursor)
        // The save is owed again under the new name.
        assertEquals(listOf(hashed("one")), store.unsynced(BOOK, second, 500).map { it.key })
    }

    @Test
    fun `a peer's key change carries its bookkeeping`() = runTest {
        val store = saved()
        store.forBook(BOOK).keep("one", "un")
        val state = store.ensureWork(PEER, BOOK, "work-one")
        store.recordPushed(PEER, BOOK, store.unsynced(BOOK, state, 500).single())

        store.rekeyPeer(PEER, OTHER_PEER)

        // Copied, not moved: this store commits outside the main
        // database's transaction, and a rollback there must leave the
        // account's progress intact under the key it still answers to.
        val kept = store.syncState(PEER, BOOK)!!
        val carried = store.syncState(OTHER_PEER, BOOK)!!
        assertEquals(kept.copy(peerId = carried.peerId), carried)
    }

    @Test
    fun `peers beyond the ones still connected lose their bookkeeping, not their sentences`() = runTest {
        val store = saved()
        store.forBook(BOOK).keep("one", "un")
        store.ensureWork(OTHER_PEER, BOOK, "work-one")
        store.ensureWork(PEER, BOOK, "work-one")

        store.forgetPeersBeyond { setOf(PEER) }

        assertNull(store.syncState(OTHER_PEER, BOOK))
        assertEquals("work-one", store.syncState(PEER, BOOK)!!.workId)
        // The sentences are this phone's cache, whoever connected.
        assertEquals("un", store.forBook(BOOK).get("one").translation)
    }

    @Test
    fun `a rekeyed peer's old bookkeeping goes once the new key is the live one`() = runTest {
        val store = saved()
        store.forBook(BOOK).keep("one", "un")
        val state = store.ensureWork(PEER, BOOK, "work-one")
        store.recordPushed(PEER, BOOK, store.unsynced(BOOK, state, 500).single())

        store.rekeyPeer(PEER, OTHER_PEER)
        val carried = store.syncState(OTHER_PEER, BOOK)!!
        store.forgetPeersBeyond { setOf(OTHER_PEER) }

        // The copy the rekey made is the account's progress now; the
        // original is a key nobody answers to.
        assertNull(store.syncState(PEER, BOOK))
        assertEquals(carried, store.syncState(OTHER_PEER, BOOK))
    }

    @Test
    fun `a rekey mid-transaction keeps both keys' bookkeeping`() = runTest {
        val store = saved()
        store.forBook(BOOK).keep("one", "un")
        val state = store.ensureWork(PEER, BOOK, "work-one")
        val pushed = store.unsynced(BOOK, state, 500).single()
        store.recordPushed(PEER, BOOK, pushed)

        // The copy has committed here; the account transaction that
        // renames the key has not, so the account still answers to the
        // old name. A collection now must not take the copy for a dead
        // peer's leftovers, whichever way the transaction lands.
        store.rekeyPeer(PEER, OTHER_PEER)
        store.forgetPeersBeyond { setOf(PEER) }

        assertEquals(pushed.savedAt, store.syncState(PEER, BOOK)!!.pushedSavedAt)
        assertEquals(pushed.savedAt, store.syncState(OTHER_PEER, BOOK)!!.pushedSavedAt)
    }

    @Test
    fun `an abandoned rekey target does not stay protected forever`() = runTest {
        val store = saved()
        store.forBook(BOOK).keep("one", "un")
        store.ensureWork(PEER, BOOK, "work-one")

        // A rekey that rolled back leaves its copy behind, protected
        // while the account still answers to the old name...
        store.rekeyPeer(PEER, OTHER_PEER)
        // ...until the account is carried again, to a different name:
        // the earlier target was never taken, and goes.
        val third = "liseursync|https://elsewhere|carol"
        store.rekeyPeer(PEER, third)
        store.forgetPeersBeyond { setOf(third) }
        assertNull(store.syncState(OTHER_PEER, BOOK))

        // And with no account connected at all, nothing but the
        // sentences survives — not the carried key's bookkeeping, not
        // the abandoned move records that protected the other target.
        store.forgetPeer(third)
        store.forgetPeersBeyond { emptySet() }
        assertNull(store.syncState(third, BOOK))
        assertNull(store.syncState(PEER, BOOK))
        assertEquals("un", store.forBook(BOOK).get("one").translation)
    }

    @Test
    fun `a pulled page counts against the same caps a save does`() = runTest {
        val store = saved(maxSentences = 2)
        store.landPulled(
            PEER, BOOK, store.pullTicket(BOOK),
            listOf(
                PulledTranslation(hashed("one"), "un"),
                PulledTranslation(hashed("two"), "deux"),
                PulledTranslation(hashed("three"), "trois"),
            ),
            "cursor-1",
        )

        // A phone that only receives must fit the cache too.
        assertEquals(2, store.stats.first().sentences)
    }

    @Test
    fun `a pulled page counts against the character cap too`() = runTest {
        val store = saved(maxSentences = 100, maxCharacters = 5)
        store.landPulled(
            PEER, BOOK, store.pullTicket(BOOK),
            listOf(PulledTranslation(hashed("one"), "12345678")),
            "cursor-1",
        )

        // Eight characters against a five-character cap, with one
        // sentence: the count is fine and the bytes are not.
        assertEquals(0, store.stats.first().sentences)
    }

    @Test
    fun `a lengthened refresh counts against the character cap too`() = runTest {
        val store = saved(maxSentences = 100, maxCharacters = 5)
        store.landPulled(
            PEER, BOOK, store.pullTicket(BOOK),
            listOf(PulledTranslation(hashed("one"), "12345")), "cursor-1",
        )
        store.landPulled(
            PEER, BOOK, store.pullTicket(BOOK),
            listOf(PulledTranslation(hashed("one"), "123456789")), "cursor-2",
        )

        assertEquals(0, store.stats.first().sentences)
    }

    @Test
    fun `a save's floor outlives the sentences an eviction took`() = runTest {
        clock = 1_000L
        // A character cap of one: a save alone does not reach for the
        // trimmer, so the upkeep pass is what takes the sentence.
        val first = saved(maxCharacters = 1)
        first.forBook(BOOK).keep("one", "un")
        first.recordPushed(PEER, BOOK, SavedTranslationForPush(1, hashed("one"), "un", 1_000))
        first.tidy()
        assertEquals(0, first.stats.first().sentences)

        // A restart, and a clock stepped back under the watermark.
        clock = 900L
        val second = saved(maxCharacters = 1_000_000)
        second.forBook(BOOK).keep("two", "deux")

        // The save is stamped above the watermark the clock stepped
        // back under — the watermark is the floor now that no sentence
        // survives to be one.
        val state = second.syncState(PEER, BOOK)!!
        assertEquals(listOf(hashed("two")), second.unsynced(BOOK, state, 500).map { it.key })
    }

    @Test
    fun `clearing a book forgets its sentences and bookkeeping and leaves the others`() = runTest {
        val store = saved()
        store.forBook(BOOK).keep("one", "un")
        store.forBook(OTHER).keep("two", "deux")
        store.ensureWork(PEER, BOOK, "work-one")

        store.clearBook(BOOK)

        assertNull(store.forBook(BOOK).get("one").translation)
        assertNull(store.syncState(PEER, BOOK))
        assertEquals("deux", store.forBook(OTHER).get("two").translation)
        assertTrue(store.owedClears(PEER).isEmpty())
    }

    @Test
    fun `a reply or a pull asked before a book's clear is not kept after it`() = runTest {
        val store = saved()
        val book = store.forBook(BOOK)
        val asked = book.get("one")
        val ticket = store.pullTicket(BOOK)

        store.clearBook(BOOK)
        book.put("one", "un", asked.stamp)
        val landed = store.landPulled(
            PEER, BOOK, ticket,
            listOf(PulledTranslation(hashed("two"), "deux")),
            "cursor-1",
        )

        assertNull(book.get("one").translation)
        assertEquals(0, landed)
        assertNull(store.syncState(PEER, BOOK))
    }

    @Test
    fun `an account clear is owed until settled, account-wide covering every work`() = runTest {
        val store = saved()
        store.clearBook(BOOK, listOf(TranslationAccountClear(PEER, "work-one")))
        store.requestAccountClears(listOf(TranslationAccountClear.everyWork(OTHER_PEER)))

        assertTrue(store.clearOwed(PEER, "work-one"))
        assertTrue(!store.clearOwed(PEER, "work-two"))
        assertTrue(store.clearOwed(OTHER_PEER, "work-two"))

        store.settleClear(store.owedClears(PEER).single())
        assertTrue(store.owedClears(PEER).isEmpty())
        assertEquals(listOf(OTHER_PEER to ""), store.owedClears(OTHER_PEER).map { it.peerId to it.workId })
    }

    @Test
    fun `a clear asked again while the first was being sent outlives the first's answer`() = runTest {
        val store = saved()
        store.requestAccountClears(listOf(TranslationAccountClear(PEER, "work-one")))
        val sent = store.owedClears(PEER).single()

        store.requestAccountClears(listOf(TranslationAccountClear(PEER, "work-one")))
        store.settleClear(sent)

        assertTrue(store.clearOwed(PEER, "work-one"))
        store.settleClear(store.owedClears(PEER).single())
        assertTrue(store.owedClears(PEER).isEmpty())
    }

    @Test
    fun `a book's clear leaves the open reader's handles saving`() = runTest {
        val store = saved()
        val book = store.forBook(BOOK)
        book.keep("one", "un")

        store.clearBook(BOOK)
        book.keep("two", "deux")

        assertNull(book.get("one").translation)
        assertEquals("deux", book.get("two").translation)
    }

    @Test
    fun `an account clear for a book leaves its sentences here but never to be pushed`() = runTest {
        val store = saved()
        store.forBook(BOOK).keep("one", "un")

        store.requestAccountClears(listOf(TranslationAccountClear(PEER, "work-one")), BOOK)

        assertEquals("un", store.forBook(BOOK).get("one").translation)
        val state = store.ensureWork(PEER, BOOK, "work-one")
        assertTrue(store.unsynced(BOOK, state, 500).isEmpty())
        store.forBook(BOOK).keep("two", "deux")
        assertEquals(listOf(hashed("two")), store.unsynced(BOOK, state, 500).map { it.key })
    }

    @Test
    fun `an account clear fences a save and pull already in flight`() = runTest {
        val store = saved()
        val book = store.forBook(BOOK)
        book.keep("already-sent", "sent")
        store.ensureWork(PEER, BOOK, "work-one")

        val lookup = book.get("arriving")
        val ticket = store.pullTicket(BOOK)
        store.requestAccountClears(listOf(TranslationAccountClear(PEER, "work-one")), BOOK)

        book.put("arriving", "late save", lookup.stamp)
        val landed = store.landPulled(
            PEER,
            BOOK,
            ticket,
            listOf(PulledTranslation(hashed("remote"), "from server")),
            "cursor-1",
        )

        assertNull(book.get("arriving").translation)
        assertEquals(0, landed)
        val state = store.ensureWork(PEER, BOOK, "work-one")
        assertTrue(store.unsynced(BOOK, state, 500).isEmpty())
    }

    @Test
    fun `clearing everything can owe the account its clear in the same step`() = runTest {
        val store = saved()
        store.forBook(BOOK).keep("one", "un")

        store.clear(listOf(TranslationAccountClear.everyWork(PEER)))

        assertEquals(0, store.stats.first().sentences)
        assertEquals(listOf(PEER to ""), store.owedClears(PEER).map { it.peerId to it.workId })
    }

    @Test
    fun `owed clears follow the peer's key and go with the peer`() = runTest {
        val store = saved()
        store.requestAccountClears(listOf(TranslationAccountClear(PEER, "work-one")))

        store.rekeyPeer(PEER, OTHER_PEER)
        assertTrue(store.clearOwed(OTHER_PEER, "work-one"))

        store.forgetPeersBeyond { setOf(OTHER_PEER) }
        assertTrue(store.owedClears(PEER).isEmpty())
        assertTrue(store.clearOwed(OTHER_PEER, "work-one"))

        store.forgetPeer(OTHER_PEER)
        assertTrue(store.owedClears(OTHER_PEER).isEmpty())
    }

    @Test
    fun `a late clear resolved under the old key survives rekey collection`() = runTest {
        val store = saved()
        store.forBook(BOOK).keep("one", "un")
        store.rekeyPeer(PEER, OTHER_PEER)

        store.requestAccountClears(listOf(TranslationAccountClear(PEER, "work-one")), BOOK)

        assertTrue(store.clearOwed(PEER, "work-one"))
        assertTrue(store.clearOwed(OTHER_PEER, "work-one"))
        val state = store.ensureWork(OTHER_PEER, BOOK, "work-one")
        assertTrue(store.unsynced(BOOK, state, 500).isEmpty())

        store.forgetPeersBeyond { setOf(OTHER_PEER) }

        assertTrue(store.owedClears(PEER).isEmpty())
        assertTrue(store.clearOwed(OTHER_PEER, "work-one"))
    }

    @Test
    fun `settling a clear for the old key also settles its rekey copy`() = runTest {
        val store = saved()
        store.rekeyPeer(PEER, OTHER_PEER)
        store.requestAccountClears(listOf(TranslationAccountClear(PEER, "work-one")))
        store.forgetPeersBeyond { setOf(PEER) }

        store.settleClear(store.owedClears(PEER).single())

        assertTrue(store.owedClears(PEER).isEmpty())
        assertTrue(store.owedClears(OTHER_PEER).isEmpty())
    }

    private class GatedTransactionExecutor : Executor {
        data class Gate(
            val started: CompletableDeferred<Unit> = CompletableDeferred(),
            val release: CompletableDeferred<Unit> = CompletableDeferred(),
            val completed: CompletableDeferred<Unit> = CompletableDeferred(),
        )

        private val delegate = Executors.newSingleThreadExecutor()
        @Volatile private var nextGate: Gate? = null
        @Volatile private var activeGate: Gate? = null

        fun pauseNext(): Gate = Gate().also {
            nextGate = it
            activeGate = it
        }

        override fun execute(command: Runnable) {
            val gate = nextGate
            nextGate = null
            delegate.execute {
                try {
                    if (gate != null) {
                        gate.started.complete(Unit)
                        runBlocking { gate.release.await() }
                        activeGate = null
                    }
                    command.run()
                } finally {
                    gate?.completed?.complete(Unit)
                }
            }
        }

        fun releaseAndClose() {
            nextGate?.release?.complete(Unit)
            activeGate?.release?.complete(Unit)
            delegate.shutdownNow()
        }
    }

    private companion object {
        const val BOOK = "file:///books/one.epub"
        const val OTHER = "file:///books/two.epub"
        const val HELD = "file:///books/held.epub"
        const val PEER = "liseursync|https://example|ada"
        const val OTHER_PEER = "liseursync|https://elsewhere|bob"
    }
}
