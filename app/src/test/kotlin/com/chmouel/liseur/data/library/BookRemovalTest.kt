package com.chmouel.liseur.data.library

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.db.AnnotationSync
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.AnnotationKind
import com.chmouel.liseur.data.db.BookAnnotation
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.ReadingSession
import com.chmouel.liseur.data.db.WorkAlias
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class BookRemovalTest {

    private lateinit var db: LiseurDatabase
    private lateinit var removal: BookRemoval

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LiseurDatabase::class.java,
        ).allowMainThreadQueries().build()
        removal = BookRemoval(
            bookDao = db.bookDao(),
            sessionDao = db.readingSessionDao(),
            peerStateDao = db.syncPeerStateDao(),
            identityDao = db.workIdentityDao(),
            progressDao = db.readingProgressDao(),
            annotationDao = db.annotationDao(),
            annotationSyncDao = db.annotationSyncDao(),
            inTransaction = { work -> db.withTransaction { work() } },
        )
    }

    @After
    fun close() = db.close()

    @Test
    fun `deleting a book deletes its sessions and leaves other history`() = runTest {
        db.bookDao().upsert(book("gone"))
        db.bookDao().upsert(book("kept"))
        db.readingSessionDao().insert(session("gone"))
        db.readingSessionDao().insert(session("kept"))
        db.syncPeerStateDao().persistPending(
            "gone",
            "peer",
            0.8,
            "Reading",
            1_000,
            locatorJson = """{"href":"gone","locations":{"liseurAnchor":1}}""",
            editionSha = "sha-gone",
        )
        db.syncPeerStateDao().persistPending(
            "kept",
            "peer",
            0.4,
            "Reading",
            1_000,
            locatorJson = """{"href":"kept","locations":{"liseurAnchor":1}}""",
            editionSha = "sha-kept",
        )

        // Account switching and catalog pruning already hold a transaction;
        // Room must safely fold this removal into that same boundary.
        db.withTransaction { removal.deleteByUrls(listOf("gone")) }

        assertNull(db.bookDao().getByUrl("gone"))
        assertNotNull(db.bookDao().getByUrl("kept"))
        assertNull(db.syncPeerStateDao().get("gone", "peer"))
        assertEquals("sha-kept", db.syncPeerStateDao().get("kept", "peer")?.pendingEditionSha)
        assertEquals(listOf("kept"), db.readingSessionDao().observeAll().first().map { it.bookUrl })
    }

    @Test
    fun `disconnect removes remote-only history but keeps downloaded history`() = runTest {
        db.bookDao().upsert(book("remote", remoteUuid = "remote"))
        db.bookDao().upsert(
            book("downloaded", remoteUuid = "downloaded").copy(
                localUri = "content://downloads/downloaded.epub",
                downloadState = DownloadState.DOWNLOADED,
            ),
        )
        db.readingSessionDao().insert(session("remote"))
        db.readingSessionDao().insert(session("downloaded"))

        removal.deleteRemoteNotDownloaded()

        assertNull(db.bookDao().getByUrl("remote"))
        assertNotNull(db.bookDao().getByUrl("downloaded"))
        assertEquals(
            listOf("downloaded"),
            db.readingSessionDao().observeAll().first().map { it.bookUrl },
        )
    }

    @Test
    fun `a book removed from this device keeps its marks and its agreements`() = runTest {
        db.bookDao().upsert(book("gone"))
        db.annotationDao().upsert(mark())
        db.annotationSyncDao().upsert(syncRow())

        db.withTransaction { removal.deleteByUrls(listOf("gone")) }

        // Removing a book from this device says nothing about the
        // highlights in it: they are still on the server and still on
        // the other phone. Dropping only the agreements would be worse
        // than useless — the book coming back would push every mark
        // again as if it were new.
        assertNotNull(db.annotationDao().byId("mark-1"))
        assertNotNull(db.annotationSyncDao().get("peer", "mark-1"))
    }

    @Test
    fun `a different book taking over a path takes the agreements with the marks`() = runTest {
        db.bookDao().upsert(book("gone"))
        db.annotationDao().upsert(mark())
        db.annotationSyncDao().upsert(syncRow())
        db.workIdentityDao().upsert(
            WorkAlias(
                bookUrl = "gone",
                peerId = "peer",
                workId = "w-1",
                confidence = "high",
                confirmed = true,
                seeded = true,
                sourceSent = true,
                editionSha = null,
                resolvedAt = 0,
            ),
        )

        removal.contentReplaced("gone")

        // Here the marks really are gone — they anchored into a file
        // that is not there any more. The agreements have to go in the
        // same transaction: a sync row with no annotation behind it
        // reads as a deletion the reader made, and the next pass would
        // tell the server to delete a highlight that is alive and well
        // on every other device.
        assertNull(db.annotationDao().byId("mark-1"))
        assertNull(db.annotationSyncDao().get("peer", "mark-1"))
        // And the name a server knew this path by. Left standing, the
        // next pass would take the new book for the old one: its
        // highlights would be pushed onto the old work, and the old
        // work's would arrive here and anchor into text that never
        // contained them.
        assertNull(db.workIdentityDao().alias("gone", "peer"))
        assertNull(db.workIdentityDao().fingerprint("gone"))
    }

    private fun mark() = BookAnnotation(
        id = "mark-1",
        bookId = "gone",
        kind = AnnotationKind.HIGHLIGHT.name,
        chapter = "One",
        text = "a sentence",
        note = null,
        tint = "YELLOW",
        locatorJson = """{"href":"one"}""",
        position = 1,
        totalProgression = 0.25,
        createdAt = 1_700_000_000,
        updatedAt = 1_700_000_000_000_000,
    )

    private fun syncRow() = AnnotationSync(
        id = "mark-1",
        peerId = "peer",
        bookId = "gone",
        workId = "w-1",
        rev = 4,
        seq = 12,
        ackedFingerprint = "settled",
    )

    private fun book(url: String, remoteUuid: String? = null) = Book(
        url = url,
        title = url,
        author = null,
        coverPath = null,
        source = null,
        addedAt = 0,
        lastOpenedAt = null,
        remoteUuid = remoteUuid,
        downloadState = if (remoteUuid == null) DownloadState.DOWNLOADED else DownloadState.REMOTE,
    )

    private fun session(bookUrl: String) = ReadingSession(
        bookUrl = bookUrl,
        startedAt = 0,
        endedAt = 60_000,
        lastCheckpointAt = 60_000,
        durationMs = 60_000,
    )
}
