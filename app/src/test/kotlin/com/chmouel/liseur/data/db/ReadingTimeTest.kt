package com.chmouel.liseur.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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

/**
 * When a book counts as read, against the real SQL.
 *
 * A fresh device connected to a sync account imports a whole reading
 * history at once. If each position is filed under the moment the sync
 * happened to deliver it, the shelf reads as though every book was
 * picked up just now — and reads differently again after the next
 * refresh, since the import arrives in batches. What the shelf wants is
 * when the reading happened, on whichever device did it.
 */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class ReadingTimeTest {

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

    private val progress get() = db.readingProgressDao()

    private suspend fun add(url: String) = db.bookDao().upsert(
        Book(
            url = url,
            title = url,
            author = null,
            coverPath = null,
            source = null,
            addedAt = 0,
            lastOpenedAt = null,
        ),
    )

    /** A position arriving from another device, read there at [readThere]. */
    private suspend fun importAt(url: String, readThere: Long, at: Long, progression: Double) {
        assertTrue(
            progress.applyPeerPull(
                bookUrl = url,
                expectedRevision = 0,
                progression = progression,
                status = "READING",
                now = at,
                remoteUpdatedAt = readThere,
            ),
        )
    }

    private suspend fun readAt(): Map<String, Long> =
        progress.observeReadAt().first().associate { it.bookUrl to it.readAt }

    @Test
    fun `an imported position is filed under when it was read, not when it arrived`() = runTest {
        add("old")
        add("recent")

        // Both arrive in the same sync, in the order the server happened
        // to mention them, which is the opposite of the reading order.
        importAt("recent", readThere = 9_000, at = 100_000, progression = 0.3)
        importAt("old", readThere = 1_000, at = 100_001, progression = 0.7)

        assertEquals(mapOf("old" to 1_000L, "recent" to 9_000L), readAt())
    }

    @Test
    fun `the shelf order does not change when a second batch is imported`() = runTest {
        add("first")
        add("second")

        importAt("first", readThere = 9_000, at = 100_000, progression = 0.3)
        val before = readAt()

        // The next refresh names another book and imports its history.
        add("late")
        importAt("late", readThere = 2_000, at = 200_000, progression = 0.4)

        val after = readAt()
        assertEquals(before["first"], after["first"])
        // Older reading files behind, however late this device heard of it.
        assertTrue(after.getValue("late") < after.getValue("first"))
    }

    @Test
    fun `a peer clock running ahead cannot pin a book to the top of the shelf`() = runTest {
        add("ahead")
        importAt("ahead", readThere = 500_000, at = 1_000, progression = 0.2)

        assertEquals(1_000L, readAt().getValue("ahead"))
    }

    @Test
    fun `a server that reports no time falls back to now`() = runTest {
        add("timeless")
        assertTrue(
            progress.applyPeerPull(
                bookUrl = "timeless",
                expectedRevision = 0,
                progression = 0.2,
                status = "READING",
                now = 4_000,
                remoteUpdatedAt = null,
            ),
        )

        assertEquals(4_000L, readAt().getValue("timeless"))
    }

    @Test
    fun `a page turn here is read now, even after an older position was imported`() = runTest {
        add("carried on")
        importAt("carried on", readThere = 1_000, at = 100_000, progression = 0.2)

        progress.recordLocal(
            bookUrl = "carried on",
            locatorJson = """{"at":0.5}""",
            progression = 0.5,
            readingSecondsPerPosition = null,
            readingPaceSamples = null,
            readingPaceElapsedMs = null,
            readingPaceEvidence = null,
            status = "READING",
            updatedAt = 120_000,
        )

        assertEquals(120_000L, readAt().getValue("carried on"))
    }

    @Test
    fun `a row holding no reading reports none`() = runTest {
        add("declined")
        // A pull that made the row exist and then declined to apply,
        // because a page was turned here while it was being decided.
        // Nothing was read, so the shelf must not be told it was.
        assertTrue(
            !progress.applyPeerPull(
                bookUrl = "declined",
                expectedRevision = 7,
                progression = 0.2,
                status = "READING",
                now = 100_000,
                remoteUpdatedAt = 1_000,
            ),
        )

        assertNull(progress.get("declined")?.totalProgression)
        assertEquals(emptyMap<String, Long>(), readAt())
    }

    @Test
    fun `the book to carry on with is the one read last, not the one imported last`() = runTest {
        add("left off here")
        add("finished ages ago")

        importAt("left off here", readThere = 9_000, at = 100_000, progression = 0.3)
        importAt("finished ages ago", readThere = 1_000, at = 100_001, progression = 0.9)

        assertEquals("left off here", db.bookDao().mostRecentlyOpened()?.url)
    }
}
