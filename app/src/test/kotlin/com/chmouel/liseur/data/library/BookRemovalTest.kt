package com.chmouel.liseur.data.library

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.ReadingSession
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
