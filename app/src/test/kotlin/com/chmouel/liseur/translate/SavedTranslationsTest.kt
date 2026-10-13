package com.chmouel.liseur.translate

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
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

    private companion object {
        const val BOOK = "file:///books/one.epub"
        const val OTHER = "file:///books/two.epub"
        const val HELD = "file:///books/held.epub"
        const val PEER = "liseursync|https://example|ada"
        const val OTHER_PEER = "liseursync|https://elsewhere|bob"
    }
}
