package com.chmouel.liseur.data.library

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.db.AnnotationSync
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.AnnotationKind
import com.chmouel.liseur.data.db.BookAnnotation
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.LibraryFolder
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.ReadingProgress
import com.chmouel.liseur.data.db.ReadingSession
import com.chmouel.liseur.data.db.WorkAlias
import com.chmouel.liseur.domain.LibraryFilterOption
import com.chmouel.liseur.domain.LibraryFilters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class BookRemovalTest {

    private lateinit var db: LiseurDatabase
    private lateinit var removal: BookRemoval
    private lateinit var library: LocalLibraryRepository

    @Before
    fun open() {
        Robolectric.buildContentProvider(FakeDocs::class.java).create(DOCS_AUTHORITY)
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
        val context = ApplicationProvider.getApplicationContext<Context>()
        val httpClient = DefaultHttpClient()
        val assetRetriever = AssetRetriever(context.contentResolver, httpClient)
        library = LocalLibraryRepository(
            context = context,
            assetRetriever = assetRetriever,
            publicationOpener = PublicationOpener(
                publicationParser = DefaultPublicationParser(
                    context,
                    httpClient = httpClient,
                    assetRetriever = assetRetriever,
                    pdfFactory = null,
                ),
            ),
            bookDao = db.bookDao(),
            folderDao = db.libraryFolderDao(),
            bookRemoval = removal,
            fingerprints = BookFingerprintStore(context, db.workIdentityDao()),
        )
    }

    @After
    fun close() {
        FakeDocs.children = emptyMap()
        FakeDocs.loading = emptySet()
        FakeDocs.answersIsChild = false
        db.close()
    }

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
    fun `removing a parent folder keeps a book still held by a child folder`() = runTest {
        val parent =
            "content://com.android.externalstorage.documents/tree/" +
                "primary%3ADocuments/document/primary%3ADocuments"
        val child =
            "content://com.android.externalstorage.documents/tree/" +
                "primary%3ADocuments%2Fbooks/document/primary%3ADocuments%2Fbooks"
        val bookUrl =
            "content://com.android.externalstorage.documents/tree/" +
                "primary%3ADocuments/document/primary%3ADocuments%2Fbooks%2Fbook.epub"
        db.libraryFolderDao().upsert(LibraryFolder(parent, 1))
        db.libraryFolderDao().upsert(LibraryFolder(child, 2))
        db.bookDao().upsert(book("kept").copy(url = bookUrl, source = parent))
        db.readingSessionDao().insert(session(bookUrl))

        library.removeFolder(LibraryFolder(parent, 1))

        assertNull(db.libraryFolderDao().getAll().firstOrNull { it.url == parent })
        assertNotNull(db.libraryFolderDao().getAll().firstOrNull { it.url == child })
        assertNotNull(db.bookDao().getByUrl(bookUrl))
        assertEquals(child, db.bookDao().getByUrl(bookUrl)?.source)
        assertEquals(listOf(bookUrl), db.readingSessionDao().observeAll().first().map { it.bookUrl })
    }

    @Test
    fun `removing a folder keeps an uploaded book as remote-only`() = runTest {
        db.libraryFolderDao().upsert(LibraryFolder("tree", 1))
        db.bookDao().upsert(
            book("adopted", remoteUuid = "remote").copy(
                source = "tree",
                downloadState = DownloadState.DOWNLOADED,
            ),
        )
        db.readingSessionDao().insert(session("adopted"))

        removal.deleteFolder(db.libraryFolderDao(), "tree")

        val adopted = db.bookDao().getByUrl("adopted")
        assertNotNull(adopted)
        assertEquals("remote", adopted?.remoteUuid)
        assertNull(adopted?.source)
        assertEquals(DownloadState.REMOTE, adopted?.downloadState)
        assertEquals(listOf("adopted"), db.readingSessionDao().observeAll().first().map { it.bookUrl })
    }

    @Test
    fun `removing a folder leaves a book in the app's own storage openable`() = runTest {
        // The file is not in the folder at all, so releasing the folder
        // takes nothing away from it. Demoting it to REMOTE would hide a
        // download that is sitting on disk and offer to fetch it again.
        db.libraryFolderDao().upsert(LibraryFolder("tree", 1))
        db.bookDao().upsert(
            book("downloaded", remoteUuid = "remote").copy(
                source = "tree",
                localUri = "content://downloads/downloaded.epub",
                downloadState = DownloadState.DOWNLOADED,
            ),
        )

        removal.deleteFolder(db.libraryFolderDao(), "tree")

        val kept = db.bookDao().getByUrl("downloaded")
        assertNull(kept?.source)
        assertEquals(DownloadState.DOWNLOADED, kept?.downloadState)
        assertEquals("content://downloads/downloaded.epub", kept?.localUri)
        assertNotNull(kept?.openableUrl)
    }

    @Test
    fun `re-adding a folder under another spelling gives an uploaded book its file back`() =
        runTest {
            // The parent was removed, so the row is sourceless and REMOTE.
            // Adding the child back reaches it by identity rather than by
            // URL — a tree rooted one level down spells the same document
            // differently — and joining the folder without taking the file
            // back would leave the reader offered a download of the book
            // they are standing on.
            FakeDocs.children = mapOf(
                "primary:Books/SF" to listOf(
                    listOf<Any?>(
                        "primary:Books/SF/book.epub",
                        "book.epub",
                        "application/epub+zip",
                        1_700L,
                    ),
                ),
            )
            val tree = "content://$DOCS_AUTHORITY/tree/primary%3ABooks%2FSF"
            val aliasUrl = "content://$DOCS_AUTHORITY/tree/primary%3ABooks" +
                "/document/primary%3ABooks%2FSF%2Fbook.epub"
            db.bookDao().upsert(
                book(aliasUrl, remoteUuid = "remote").copy(
                    downloadState = DownloadState.REMOTE,
                    fileModifiedAt = 1_700L,
                ),
            )

            library.addFolder(Uri.parse(tree))

            val readopted = db.bookDao().getByUrl(aliasUrl)
            assertEquals(tree, readopted?.source)
            assertEquals(DownloadState.DOWNLOADED, readopted?.downloadState)
            // Under its own URL, which the reader's place hangs off, and
            // not as a second entry beside it.
            assertEquals(listOf(aliasUrl), db.bookDao().allOnce().map { it.url })
        }

    @Test
    fun `re-adding a folder leaves a book another folder still holds alone`() = runTest {
        FakeDocs.children = mapOf(
            "primary:Books/SF" to listOf(
                listOf<Any?>(
                    "primary:Books/SF/book.epub",
                    "book.epub",
                    "application/epub+zip",
                    1_700L,
                ),
            ),
        )
        val other = "content://$DOCS_AUTHORITY/tree/primary%3AElsewhere"
        val tree = "content://$DOCS_AUTHORITY/tree/primary%3ABooks%2FSF"
        val aliasUrl = "content://$DOCS_AUTHORITY/tree/primary%3ABooks" +
            "/document/primary%3ABooks%2FSF%2Fbook.epub"
        db.libraryFolderDao().upsert(LibraryFolder(other, 1))
        db.bookDao().upsert(
            book(aliasUrl, remoteUuid = "remote").copy(
                source = other,
                downloadState = DownloadState.REMOTE,
                fileModifiedAt = 1_700L,
            ),
        )

        library.addFolder(Uri.parse(tree))

        // A row already looked after stays where it is: taking it would
        // let removing this folder delete a book the other still holds.
        assertEquals(other, db.bookDao().getByUrl(aliasUrl)?.source)
        assertEquals(DownloadState.REMOTE, db.bookDao().getByUrl(aliasUrl)?.downloadState)
    }

    @Test
    fun `a tree that would not be read does not cost a book its shelf row`() = runTest {
        // The survivor's provider declines isChildDocument and will not
        // list its children either, and its ids are opaque, so comparing
        // them says nothing. None of that is evidence the book has gone.
        val removed = "content://$DOCS_AUTHORITY/tree/parent"
        val survivor = "content://$DOCS_AUTHORITY/tree/2f9c11"
        val bookUrl = "content://$DOCS_AUTHORITY/tree/parent/document/4a7e30"
        db.libraryFolderDao().upsert(LibraryFolder(removed, 1))
        db.libraryFolderDao().upsert(LibraryFolder(survivor, 2))
        db.bookDao().upsert(book(bookUrl).copy(source = removed))
        db.readingSessionDao().insert(session(bookUrl))

        library.removeFolder(LibraryFolder(removed, 1))

        // Kept, and under no folder: naming one would be picking a tree
        // out of the air, and the first complete scan of the wrong tree
        // would then prune the row and take the sessions with it.
        assertNotNull(db.bookDao().getByUrl(bookUrl))
        assertNull(db.bookDao().getByUrl(bookUrl)?.source)
        assertEquals(listOf(bookUrl), db.readingSessionDao().observeAll().first().map { it.bookUrl })
    }

    @Test
    fun `a scan that finds the file takes such a book back`() = runTest {
        // The other half of keeping it: belonging to nothing is not a
        // dead end, because readopt() claims a sourceless row the moment
        // a scan actually reaches the file.
        val removed = "content://$DOCS_AUTHORITY/tree/parent"
        val survivor = "content://$DOCS_AUTHORITY/tree/2f9c11"
        val bookUrl = "content://$DOCS_AUTHORITY/tree/parent/document/4a7e30"
        db.libraryFolderDao().upsert(LibraryFolder(removed, 1))
        db.libraryFolderDao().upsert(LibraryFolder(survivor, 2))
        db.bookDao().upsert(book(bookUrl).copy(source = removed, fileModifiedAt = 1_700L))

        library.removeFolder(LibraryFolder(removed, 1))
        assertNull(db.bookDao().getByUrl(bookUrl)?.source)

        // The provider comes back, and the file is where it always was.
        FakeDocs.children = mapOf(
            "2f9c11" to listOf(
                listOf<Any?>("4a7e30", "book.epub", "application/epub+zip", 1_700L),
            ),
        )
        library.addFolder(Uri.parse(survivor))

        assertEquals(survivor, db.bookDao().getByUrl(bookUrl)?.source)
        assertEquals(listOf(bookUrl), db.bookDao().allOnce().map { it.url })
    }

    @Test
    fun `a listing that is still arriving keeps the book it has not mentioned yet`() = runTest {
        // A cloud provider answers immediately with what it has and sets
        // EXTRA_LOADING while the rest is on its way. Reading that first
        // answer as the whole folder deletes every book it has not got
        // to yet.
        FakeDocs.children = mapOf("2f9c11" to emptyList())
        FakeDocs.loading = setOf("2f9c11")
        val removed = "content://$DOCS_AUTHORITY/tree/parent"
        val survivor = "content://$DOCS_AUTHORITY/tree/2f9c11"
        val bookUrl = "content://$DOCS_AUTHORITY/tree/parent/document/4a7e30"
        db.libraryFolderDao().upsert(LibraryFolder(removed, 1))
        db.libraryFolderDao().upsert(LibraryFolder(survivor, 2))
        db.bookDao().upsert(book(bookUrl).copy(source = removed))

        library.removeFolder(LibraryFolder(removed, 1))

        assertNotNull(db.bookDao().getByUrl(bookUrl))
        assertNull(db.bookDao().getByUrl(bookUrl)?.source)
    }

    @Test
    fun `an ordinary scan does not prune against a listing that is still arriving`() = runTest {
        // The same cursor reaches the everyday scan, where reading it as
        // complete prunes books whose files are perfectly well there.
        FakeDocs.children = mapOf("2f9c11" to emptyList())
        FakeDocs.loading = setOf("2f9c11")
        val tree = "content://$DOCS_AUTHORITY/tree/2f9c11"
        val bookUrl = "content://$DOCS_AUTHORITY/tree/2f9c11/document/4a7e30"
        db.bookDao().upsert(book(bookUrl).copy(source = tree))

        library.addFolder(Uri.parse(tree))

        assertNotNull(db.bookDao().getByUrl(bookUrl))
        assertEquals(tree, db.bookDao().getByUrl(bookUrl)?.source)
    }

    @Test
    fun `a tree that was read and did not have the book lets it go`() = runTest {
        // The other side of it: a complete listing that does not mention
        // the file is evidence, and the row goes as it always has.
        FakeDocs.children = mapOf("2f9c11" to emptyList())
        val removed = "content://$DOCS_AUTHORITY/tree/parent"
        val survivor = "content://$DOCS_AUTHORITY/tree/2f9c11"
        val bookUrl = "content://$DOCS_AUTHORITY/tree/parent/document/4a7e30"
        db.libraryFolderDao().upsert(LibraryFolder(removed, 1))
        db.libraryFolderDao().upsert(LibraryFolder(survivor, 2))
        db.bookDao().upsert(book(bookUrl).copy(source = removed))

        library.removeFolder(LibraryFolder(removed, 1))

        assertNull(db.bookDao().getByUrl(bookUrl))
    }

    @Test
    fun `another provider's unreadable tree is not offered a home`() = runTest {
        // A document id is unique within one authority and meaningless
        // outside it, so a tree that could not be read over there says
        // nothing about this book.
        val removed = "content://$DOCS_AUTHORITY/tree/parent"
        val elsewhere = "content://com.example.other/tree/2f9c11"
        val bookUrl = "content://$DOCS_AUTHORITY/tree/parent/document/4a7e30"
        db.libraryFolderDao().upsert(LibraryFolder(removed, 1))
        db.libraryFolderDao().upsert(LibraryFolder(elsewhere, 2))
        db.bookDao().upsert(book(bookUrl).copy(source = removed))

        library.removeFolder(LibraryFolder(removed, 1))

        assertNull(db.bookDao().getByUrl(bookUrl))
    }

    @Test
    fun `a provider that does not do containment is walked rather than believed`() = runTest {
        // DocumentsProvider.isChildDocument returns false by default, so
        // "no" and "I have never implemented this" arrive in the same
        // words. Taking that as a denial deletes a book the surviving
        // tree is holding, and the next scan re-adds it under a URL the
        // reader's place and marks know nothing about.
        FakeDocs.answersIsChild = true
        FakeDocs.children = mapOf(
            "child" to listOf(
                listOf<Any?>("child/book.epub", "book.epub", "application/epub+zip", 1_700L),
            ),
        )
        val removed = "content://$DOCS_AUTHORITY/tree/parent"
        val survivor = "content://$DOCS_AUTHORITY/tree/child"
        val bookUrl = "content://$DOCS_AUTHORITY/tree/parent/document/child%2Fbook.epub"
        db.libraryFolderDao().upsert(LibraryFolder(removed, 1))
        db.libraryFolderDao().upsert(LibraryFolder(survivor, 2))
        db.bookDao().upsert(book(bookUrl).copy(source = removed))
        db.readingSessionDao().insert(session(bookUrl))

        library.removeFolder(LibraryFolder(removed, 1))

        assertEquals(survivor, db.bookDao().getByUrl(bookUrl)?.source)
        assertEquals(listOf(bookUrl), db.readingSessionDao().observeAll().first().map { it.bookUrl })
    }

    @Test
    fun `removing a folder keeps a book with a private copy and no server`() = runTest {
        // Disconnecting an account clears remote_uuid on purpose and
        // leaves the download where it is, so a row can have a file of
        // its own and no server behind it. The folder never held that
        // file and removing it must not take the book.
        db.libraryFolderDao().upsert(LibraryFolder("tree", 1))
        db.bookDao().upsert(
            book("unlinked").copy(
                source = "tree",
                localUri = "content://downloads/unlinked.epub",
                downloadState = DownloadState.DOWNLOADED,
            ),
        )
        db.readingSessionDao().insert(session("unlinked"))

        removal.deleteFolder(db.libraryFolderDao(), "tree")

        val kept = db.bookDao().getByUrl("unlinked")
        assertNotNull(kept)
        assertNull(kept?.source)
        assertEquals(DownloadState.DOWNLOADED, kept?.downloadState)
        assertEquals("content://downloads/unlinked.epub", kept?.localUri)
        assertNotNull(kept?.openableUrl)
        assertEquals(
            listOf("unlinked"),
            db.readingSessionDao().observeAll().first().map { it.bookUrl },
        )
    }

    @Test
    fun `removing a large folder deletes books in bounded batches`() = runTest {
        db.libraryFolderDao().upsert(LibraryFolder("tree", 1))
        val urls = (0 until 1_000).map { "book-$it" }
        urls.forEach { url -> db.bookDao().upsert(book(url).copy(source = "tree")) }

        removal.deleteFolder(db.libraryFolderDao(), "tree")

        assertTrue(db.bookDao().allOnce().isEmpty())
    }

    @Test
    fun `removing a folder removes its books and keeps the other folder`() = runTest {
        db.libraryFolderDao().upsert(
            LibraryFolder("tree", 1),
        )
        db.libraryFolderDao().upsert(
            LibraryFolder("other", 2),
        )
        db.bookDao().upsert(book("gone").copy(source = "tree"))
        db.bookDao().upsert(book("kept").copy(source = "other"))

        removal.deleteFolder(db.libraryFolderDao(), "tree")

        assertNull(db.libraryFolderDao().getAll().firstOrNull { it.url == "tree" })
        assertNotNull(db.libraryFolderDao().getAll().firstOrNull { it.url == "other" })
        assertNull(db.bookDao().getByUrl("gone"))
        assertNotNull(db.bookDao().getByUrl("kept"))
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

    @Test
    fun `a duplicate entry with nothing on it goes`() = runTest {
        // Issue #147: the same file shelved twice, once by the folder
        // scan and once by the file picker, and the second entry never
        // opened.
        db.bookDao().upsert(book("tree"))
        db.bookDao().upsert(book("picked"))

        assertEquals(listOf("picked"), removal.dropUntouchedDuplicates(listOf("picked")))

        assertNull(db.bookDao().getByUrl("picked"))
        assertNotNull(db.bookDao().getByUrl("tree"))
    }

    @Test
    fun `a duplicate that was read is left alone`() = runTest {
        // Reading through Add Book recorded the position against the
        // duplicate, so dropping it would throw away where someone had
        // got to. Better a duplicate than that; the reader can remove
        // it themselves.
        db.bookDao().upsert(book("picked"))
        db.readingProgressDao().upsert(
            ReadingProgress(
                bookUrl = "picked",
                locatorJson = "{}",
                totalProgression = 0.42,
                updatedAt = 1L,
            ),
        )

        assertEquals(emptyList<String>(), removal.dropUntouchedDuplicates(listOf("picked")))
        assertNotNull(db.bookDao().getByUrl("picked"))
    }

    @Test
    fun `a duplicate that was marked, synced or timed is left alone`() = runTest {
        db.bookDao().upsert(book("marked"))
        db.bookDao().upsert(book("synced"))
        db.bookDao().upsert(book("timed"))
        db.annotationDao().upsert(mark().copy(id = "mark-2", bookId = "marked"))
        db.annotationSyncDao().upsert(syncRow().copy(id = "mark-3", bookId = "synced"))
        db.readingSessionDao().insert(session("timed"))

        assertEquals(
            emptyList<String>(),
            removal.dropUntouchedDuplicates(listOf("marked", "synced", "timed")),
        )
        assertNotNull(db.bookDao().getByUrl("marked"))
        assertNotNull(db.bookDao().getByUrl("synced"))
        assertNotNull(db.bookDao().getByUrl("timed"))
    }

    @Test
    fun `a duplicate carrying something only it knows is left alone`() = runTest {
        // None of this is on the entry that would be kept, so none of it
        // can be dropped quietly: the uuid is the only reason an
        // uploaded book is not sent again, and the rest are decisions
        // the reader made.
        db.bookDao().upsert(book("uploaded", remoteUuid = "uuid-1"))
        db.bookDao().upsert(book("opened").copy(lastOpenedAt = 1_700_000_000))
        db.bookDao().upsert(book("finished").copy(finishedAt = 1_700_000_000))
        db.bookDao().upsert(book("archived").copy(archivedAt = 1_700_000_000))
        db.bookDao().upsert(book("refiled").copy(seriesName = "By hand"))

        val urls = listOf("uploaded", "opened", "finished", "archived", "refiled")
        assertEquals(emptyList<String>(), removal.dropUntouchedDuplicates(urls))
        urls.forEach { assertNotNull(db.bookDao().getByUrl(it)) }
    }

    @Test
    fun `taking a book off the shelf keeps every part of it`() = runTest {
        // The reader is told the book can be put back, so putting it
        // back has to give them all of it, not a stranger with the same
        // title: their place in it, their marks, their time, and every
        // decision they made about where it was filed.
        db.bookDao().upsert(
            book("hidden").copy(
                addedAt = 5,
                lastOpenedAt = 1_700_000_000,
                finishedAt = 1_700_000_001,
                seriesName = "By hand",
                seriesIndex = 3.0,
            ),
        )
        db.readingProgressDao().upsert(
            ReadingProgress(
                bookUrl = "hidden",
                locatorJson = """{"href":"one"}""",
                totalProgression = 0.5,
                updatedAt = 1,
            ),
        )
        db.annotationDao().upsert(mark().copy(id = "mark-4", bookId = "hidden"))
        db.readingSessionDao().insert(session("hidden"))
        val was = db.bookDao().getByUrl("hidden")!!

        db.bookDao().setHiddenAt("hidden", 42)

        val whileHidden = db.bookDao().getByUrl("hidden")!!
        assertEquals(true, whileHidden.hidden)
        assertNotNull(db.readingProgressDao().get("hidden"))
        assertEquals(1, db.annotationDao().count("hidden"))
        assertEquals(1, db.readingSessionDao().countForBook("hidden"))

        db.bookDao().setHiddenAt("hidden", null)

        assertEquals(was, db.bookDao().getByUrl("hidden"))
    }

    @Test
    fun `a book taken off the shelf is out of every view of it`() = runTest {
        // Not archiving, which has a shelf of its own: this one is
        // nowhere, including on the shelf the archived box shows.
        val hidden = book("hidden").copy(hiddenAt = 42)
        assertEquals(false, LibraryFilters().accepts(hidden))
        assertEquals(
            false,
            LibraryFilters(setOf(LibraryFilterOption.ARCHIVED)).accepts(hidden),
        )
        assertEquals(true, LibraryFilters().accepts(hidden.copy(hiddenAt = null)))
    }

    @Test
    fun `a url with no book behind it is not an error`() = runTest {
        assertEquals(emptyList<String>(), removal.dropUntouchedDuplicates(listOf("nothing")))
        assertEquals(emptyList<String>(), removal.dropUntouchedDuplicates(emptyList()))
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

    /**
     * A documents provider that answers the one question a scan asks.
     *
     * [findEpubs] walks a tree with a single `children` query per
     * directory, so a folder can be staged as a map from parent document
     * id to the rows the provider would return for it. That is enough to
     * drive a real scan, which is what the shelving branches — and their
     * agreement about what a re-found file means — actually hang off.
     */
    class FakeDocs : ContentProvider() {
        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            val segments = uri.pathSegments
            if (segments.lastOrNull() != "children") return null
            val parent = segments.getOrNull(segments.size - 2) ?: return null
            val rows = children[parent] ?: return null
            val columns = projection ?: COLUMNS
            return MatrixCursor(columns).apply {
                rows.forEach { row -> addRow(columns.map { row[COLUMNS.indexOf(it)] }) }
                if (parent in loading) {
                    extras = Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, true) }
                }
            }
        }

        override fun getType(uri: Uri): String? = null

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            // What the default DocumentsProvider does: containment is not
            // implemented, and saying so is indistinguishable from saying
            // the book is not there.
            if (method == "android:isChildDocument" && answersIsChild) {
                // DocumentsContract.EXTRA_RESULT, which is hidden.
                return Bundle().apply { putBoolean("result", false) }
            }
            return null
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

        companion object {
            /** Rows by parent document id, in [COLUMNS] order. */
            var children: Map<String, List<List<Any?>>> = emptyMap()

            /** Parents whose listing says the provider is still fetching. */
            var loading: Set<String> = emptySet()

            /** Whether the provider replies "not a child" to every question. */
            var answersIsChild: Boolean = false

            private val COLUMNS = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
        }
    }

}

private const val DOCS_AUTHORITY = "com.chmouel.liseur.test.documents"
