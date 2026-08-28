package com.chmouel.liseur.data.remote

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.ReadingProgress
import com.chmouel.liseur.data.db.ReadingSession
import com.chmouel.liseur.data.db.RemoteServerDao
import com.chmouel.liseur.data.library.BookRemoval
import java.io.IOException
import java.net.SocketTimeoutException
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a refresh leaves behind for the library to show.
 *
 * The rule every test here is really about is the last one: whatever
 * happens, the status must settle. A refresh that ends still marked as
 * refreshing is a spinner that never stops, and that is what a malformed
 * feed used to produce -- the exception it threw was not one anything
 * caught.
 */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class RemoteCatalogRepositoryTest {

    private lateinit var db: LiseurDatabase

    @Before
    fun open() {
        CredentialCipher.keyForTesting =
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<android.app.Application>(),
            LiseurDatabase::class.java,
        ).build()
    }

    @After
    fun close() {
        db.close()
        CredentialCipher.keyForTesting = null
    }

    private suspend fun connect(kind: ServerKind = ServerKind.KOMGA) {
        db.remoteServerDao().upsert(
            RemoteServer(
                kind = kind,
                baseUrl = "https://books.example",
                username = "reader",
                passwordCipher = null,
                apiKeyCipher = if (kind == ServerKind.KOMGA) RemoteServer.seal("a-key") else null,
                accountId = "u1",
                userId = null,
                koboTokenCipher = null,
                canDownload = true,
                canManageLibrary = kind == ServerKind.LISEUR_SYNC,
                addedAt = 0L,
                catalogSyncedAt = null,
                positionSyncedAt = null,
                syncToken = null,
                liseurTokenCipher = if (kind == ServerKind.LISEUR_SYNC) {
                    RemoteServer.seal("token")
                } else {
                    null
                },
            ),
        )
    }

    /** A catalog that does whatever the test needs it to do. */
    private class FakeCatalog(
        private val complete: Boolean = true,
        private val walk: suspend (suspend (List<RemoteBook>) -> Unit) -> Unit,
    ) : CatalogSource {
        override suspend fun allBooks(
            baseUrl: String,
            credentials: RemoteCredentials,
            onPage: suspend (List<RemoteBook>) -> Unit,
        ): CatalogWalk {
            walk(onPage)
            return CatalogWalk(complete)
        }

        override suspend fun search(
            baseUrl: String,
            credentials: RemoteCredentials,
            query: String,
        ): List<RemoteBook> = emptyList()
    }

    private fun repository(catalog: CatalogSource) = RemoteCatalogRepository(
        router = RemoteRouter(
            serverDao = db.remoteServerDao(),
            catalogs = mapOf(ServerKind.KOMGA to catalog, ServerKind.LISEUR_SYNC to catalog),
            files = emptyMap(),
            positions = emptyMap(),
        ),
        serverDao = db.remoteServerDao(),
        bookDao = db.bookDao(),
        bookRemoval = BookRemoval(
            db.bookDao(),
            db.readingSessionDao(),
            db.syncPeerStateDao(),
            db.workIdentityDao(),
            db.readingProgressDao(),
            db.annotationDao(),
            db.annotationSyncDao(),
        ),
    )

    /** A book DAO that keeps count of what a refresh asked it to do. */
    private class CountingBookDao(private val delegate: BookDao) : BookDao by delegate {
        var listedEverything = 0
        var singleReads = 0
        var batches = 0
        var rowsWritten = 0

        override suspend fun allOnce(): List<Book> {
            listedEverything++
            return delegate.allOnce()
        }

        override suspend fun getByUrl(url: String): Book? {
            singleReads++
            return delegate.getByUrl(url)
        }

        override suspend fun upsertAll(books: List<Book>) {
            batches++
            rowsWritten += books.size
            delegate.upsertAll(books)
        }
    }

    private fun repository(catalog: CatalogSource, bookDao: BookDao) = RemoteCatalogRepository(
        router = RemoteRouter(
            serverDao = db.remoteServerDao(),
            catalogs = mapOf(ServerKind.KOMGA to catalog, ServerKind.LISEUR_SYNC to catalog),
            files = emptyMap(),
            positions = emptyMap(),
        ),
        serverDao = db.remoteServerDao(),
        bookDao = bookDao,
        bookRemoval = BookRemoval(
            bookDao,
            db.readingSessionDao(),
            db.syncPeerStateDao(),
            db.workIdentityDao(),
            db.readingProgressDao(),
            db.annotationDao(),
            db.annotationSyncDao(),
        ),
    )

    private fun failing(e: Throwable) = FakeCatalog { throw e }

    private fun book(id: String) = RemoteBook(
        remoteId = id,
        title = id,
        author = null,
        coverHref = null,
        downloadHref = "/api/v1/books/$id/file",
        updatedAt = null,
        pageCount = null,
    )

    @Test
    fun `liseur-sync personal series becomes the local override`() = runTest {
        connect(ServerKind.LISEUR_SYNC)
        val remote = book("b1").copy(
            updatedAt = 20,
            seriesName = "Imperial Radch",
            seriesIndex = 2.0,
            seriesId = "s1",
            series = listOf(
                RemoteSeriesMembership(
                    id = "s1",
                    name = "Imperial Radch",
                    position = 2.0,
                    source = "personal",
                ),
            ),
            seriesSource = "personal",
            seriesClaimUpdatedAt = 20,
            folderId = "f1",
        )

        assertEquals(true, repository(FakeCatalog { onPage -> onPage(listOf(remote)) }).refresh().completed)

        val stored = db.bookDao().allOnce().single()
        assertEquals("Imperial Radch", stored.userSeriesName)
        assertEquals(2.0, stored.userSeriesIndex!!, 0.0)
        assertEquals(true, stored.seriesOverridden)
        assertEquals("personal", stored.catalogSeriesSource)
        assertEquals("f1", stored.catalogFolderId)
        assertEquals("s1", stored.seriesId)
        assertEquals(20L, stored.personalSeriesUpdatedAt)
    }

    /**
     * The catalog may forget a book the reader uploaded from their own
     * shelf, and must not take the book with it.
     *
     * Such a row is not the catalog's: its file is its URL rather than a
     * download, so the "nothing to open" test reads it as disposable,
     * and a walk that started before the upload cannot name an id that
     * did not exist when it began. Deleting it would take the book, the
     * reading position and every session with it.
     */
    @Test
    fun `a book uploaded from the device survives a walk that never names it`() = runTest {
        connect(ServerKind.LISEUR_SYNC)
        val mine = "content://tree/primary%3ABooks/document/mine.epub"
        db.bookDao().upsertAll(
            listOf(
                Book(
                    url = mine,
                    title = "Mine",
                    author = "Me",
                    coverPath = null,
                    source = null,
                    addedAt = 0,
                    lastOpenedAt = null,
                    localUri = null,
                ),
            ),
        )
        db.bookDao().linkToRemote(
            url = mine,
            remoteUuid = "uploaded",
            downloadHref = "/v1/books/uploaded/download",
            coverUrl = null,
            remoteUpdatedAt = 1L,
        )

        // A page that names some other book: the walk simply never saw
        // the one that was uploaded while it was in flight.
        repository(FakeCatalog { onPage -> onPage(listOf(book("b1"))) }).refresh()

        val kept = db.bookDao().getByUrl(mine)
        assertEquals("the uploaded book was deleted", mine, kept?.url)
        assertEquals("uploaded", kept?.remoteUuid)
    }

    @Test
    fun `a walk that admits it did not finish deletes nothing`() = runTest {
        // The regression that matters most on a compatibility shim. If a
        // client cannot make out a page and says so, the books it never
        // saw are not books that vanished from the server: they are
        // books nobody asked about. Deleting them takes a reader's place
        // in each of them, on a server where nothing can put it back.
        connect(ServerKind.KOMGA)
        shelve("b1", progressedTo = 0.4)
        shelve("b2", progressedTo = 0.9)

        val refresh = repository(
            FakeCatalog(complete = false) { onPage -> onPage(listOf(book("b1"))) },
        ).refresh()

        assertEquals(CatalogRefresh.None, refresh)
        val unseen = db.bookDao().getByUrl("komga:b2")
        assertNotNull("the unseen book was deleted", unseen)
        assertEquals(0.9, db.readingProgressDao().get("komga:b2")?.totalProgression)
        assertEquals(
            listOf("komga:b1", "komga:b2"),
            db.readingSessionDao().observeAll().first().map { it.bookUrl }.sorted(),
        )
        // Nor may anything be told this is what the server now holds.
        assertEquals(null, db.remoteServerDao().get()?.catalogSyncedAt)
    }

    @Test
    fun `a walk that did finish is still allowed to prune`() = runTest {
        // The other half of the rule, so the test above cannot be
        // satisfied by never pruning at all. Twice, because one absence
        // is only a suspicion now.
        connect(ServerKind.KOMGA)
        shelve("b1", progressedTo = 0.4)
        shelve("b2", progressedTo = 0.9)

        val catalog = FakeCatalog { onPage -> onPage(listOf(book("b1"))) }
        repository(catalog).refresh()
        repository(catalog).refresh()

        assertEquals(null, db.bookDao().getByUrl("komga:b2"))
        assertNotNull(db.bookDao().getByUrl("komga:b1"))
    }

    /**
     * The bug this rule exists for.
     *
     * Every catalog here is paged by offset. Delete a book from page one
     * and add another while the walk is between requests, and page two
     * starts past a book nobody has been shown -- with the declared
     * count unmoved, the ids distinct and the arithmetic exact, so
     * nothing in the answer looks wrong. The book is still on the server.
     */
    @Test
    fun `a book skipped by pages shifting under the walk is not pruned`() = runTest {
        connect(ServerKind.KOMGA)
        listOf("b1", "b2", "b3", "b4").forEach { shelve(it, progressedTo = 0.5) }

        // Page 0 of [1,2,3,4] gives 1,2. Then 1 goes and 5 arrives, so
        // page 1 -- offset 2 of [2,3,4,5] -- gives 4,5. Book 3 was never
        // sent, and the walk finished perfectly happily.
        val shifted = FakeCatalog { onPage ->
            onPage(listOf(book("b1"), book("b2")))
            onPage(listOf(book("b4"), book("b5")))
        }
        assertEquals(true, repository(shifted).refresh().completed)

        val survivor = db.bookDao().getByUrl("komga:b3")
        assertNotNull("a book still on the server was pruned", survivor)
        assertEquals(0.5, db.readingProgressDao().get("komga:b3")?.totalProgression)
        assertTrue(
            "the sittings went with it",
            db.readingSessionDao().observeAll().first().any { it.bookUrl == "komga:b3" },
        )
    }

    @Test
    fun `a book missing from one finished walk is only suspected`() = runTest {
        connect(ServerKind.KOMGA)
        shelve("b1", progressedTo = 0.4)
        shelve("b2", progressedTo = 0.9)

        repository(FakeCatalog { onPage -> onPage(listOf(book("b1"))) }).refresh()

        val suspected = db.bookDao().getByUrl("komga:b2")
        assertNotNull("one absence was treated as proof", suspected)
        assertNotNull("nothing was noted, so the next walk starts over", suspected?.catalogMissingSince)
        assertEquals(0.9, db.readingProgressDao().get("komga:b2")?.totalProgression)
    }

    @Test
    fun `a book named again is no longer suspected`() = runTest {
        connect(ServerKind.KOMGA)
        shelve("b1", progressedTo = 0.4)
        shelve("b2", progressedTo = 0.9)

        val partial = FakeCatalog { onPage -> onPage(listOf(book("b1"))) }
        val whole = FakeCatalog { onPage -> onPage(listOf(book("b1"), book("b2"))) }
        repository(partial).refresh()
        repository(whole).refresh()
        assertEquals(null, db.bookDao().getByUrl("komga:b2")?.catalogMissingSince)

        // And the count really did start again: one more absence is
        // still only the first.
        repository(partial).refresh()

        assertNotNull("a single absence pruned it", db.bookDao().getByUrl("komga:b2"))
    }

    /**
     * A walk that gave up half way still stored the pages it did read,
     * and a book on one of them has plainly not gone anywhere.
     *
     * Clearing the suspicion only at the end of a finished walk would
     * leave that book marked, and the next walk to miss it once -- for
     * the paging reason this whole rule is about -- would delete it on
     * the strength of an absence recorded before it was last seen.
     */
    @Test
    fun `a book seen by a walk that did not finish is no longer suspected`() = runTest {
        connect(ServerKind.KOMGA)
        shelve("b1", progressedTo = 0.4)
        shelve("b2", progressedTo = 0.9)

        repository(FakeCatalog { onPage -> onPage(listOf(book("b1"))) }).refresh()
        repository(
            FakeCatalog(complete = false) { onPage -> onPage(listOf(book("b1"), book("b2"))) },
        ).refresh()
        assertEquals(null, db.bookDao().getByUrl("komga:b2")?.catalogMissingSince)

        repository(FakeCatalog { onPage -> onPage(listOf(book("b1"))) }).refresh()

        assertNotNull("an unfinished sighting did not count", db.bookDao().getByUrl("komga:b2"))
    }

    @Test
    fun `a walk that did not finish suspects nothing`() = runTest {
        connect(ServerKind.KOMGA)
        shelve("b1", progressedTo = 0.4)
        shelve("b2", progressedTo = 0.9)

        repository(FakeCatalog(complete = false) { onPage -> onPage(listOf(book("b1"))) }).refresh()

        assertEquals(null, db.bookDao().getByUrl("komga:b2")?.catalogMissingSince)
    }

    /**
     * A library can go all at once -- a share withdrawn, an address
     * pointed at the wrong server -- and `IN (:urls)` binds a variable
     * per book, which SQLite refuses past 999 of before API 31.
     */
    @Test
    fun `a whole library vanishing at once is more books than one statement holds`() = runTest {
        connect(ServerKind.KOMGA)
        val shelved = (1..1200).map { "b$it" }
        shelved.forEach { shelve(it, progressedTo = 0.1) }

        val empty = FakeCatalog { }
        repository(empty).refresh()
        assertEquals(shelved.size, db.bookDao().allOnce().size)

        assertEquals(true, repository(empty).refresh().completed)

        assertEquals(0, db.bookDao().allOnce().size)
    }

    /** A book already on the shelf, read partway, with an hour behind it. */
    private suspend fun shelve(id: String, progressedTo: Double) {
        val url = "komga:$id"
        db.bookDao().upsertAll(
            listOf(
                Book(
                    url = url,
                    title = id,
                    author = null,
                    coverPath = null,
                    source = null,
                    addedAt = 0,
                    lastOpenedAt = null,
                    localUri = null,
                ),
            ),
        )
        db.bookDao().linkToRemote(
            url = url,
            remoteUuid = id,
            downloadHref = "/api/v1/books/$id/file",
            coverUrl = null,
            remoteUpdatedAt = 1L,
        )
        db.readingProgressDao().upsert(
            ReadingProgress(
                bookUrl = url,
                locatorJson = "{}",
                totalProgression = progressedTo,
                updatedAt = 1L,
            ),
        )
        db.readingSessionDao().insert(
            ReadingSession(
                bookUrl = url,
                startedAt = 1L,
                endedAt = 2L,
                lastCheckpointAt = 2L,
                durationMs = 3_600_000,
            ),
        )
    }

    @Test
    fun `non liseur-sync catalog refresh keeps a local series override local`() = runTest {
        connect(ServerKind.KOMGA)
        repository(FakeCatalog { onPage -> onPage(listOf(book("b1"))) }).refresh()
        val url = db.bookDao().allOnce().single().url
        db.bookDao().setSeriesOverride(url, "My Shelf", 7.0, updatedAt = 30)

        val remote = book("b1").copy(
            updatedAt = 40,
            seriesName = "Server Shelf",
            seriesIndex = 1.0,
            seriesId = "ks1",
        )
        repository(FakeCatalog { onPage -> onPage(listOf(remote)) }).refresh()

        val stored = db.bookDao().allOnce().single()
        assertEquals("My Shelf", stored.seriesName)
        assertEquals("My Shelf", stored.userSeriesName)
        assertEquals("Server Shelf", stored.catalogSeriesName)
        assertEquals(true, stored.seriesOverridden)
    }

    /**
     * Komga and calibre-web never acknowledge a pending claim, so it must
     * not be left to freeze the row: once the reader hands a book back to
     * the catalog, the next refresh has to be able to move it again.
     */
    @Test
    fun `a claim raised on a non liseur-sync account does not freeze the row`() = runTest {
        connect(ServerKind.KOMGA)
        repository(
            FakeCatalog { onPage ->
                onPage(listOf(book("b1").copy(seriesName = "Old Series", seriesIndex = 1.0, seriesId = "k1")))
            },
        ).refresh()
        val url = db.bookDao().allOnce().single().url
        db.bookDao().setSeriesOverride(url, "My Shelf", 7.0, updatedAt = 30)
        db.bookDao().clearSeriesOverride(url, updatedAt = 40)
        assertEquals(true, db.bookDao().getByUrl(url)?.seriesClaimPending)

        val remote = book("b1").copy(seriesName = "New Series", seriesIndex = 3.0, seriesId = "k2")
        repository(FakeCatalog { onPage -> onPage(listOf(remote)) }).refresh()

        val stored = db.bookDao().getByUrl(url)
        assertEquals(false, stored?.seriesClaimPending)
        assertEquals("New Series", stored?.seriesName)
        assertEquals("k2", stored?.seriesId)
    }

    @Test
    fun `a refused sign-in is reported as such, not as being offline`() = runTest {
        connect()
        val repository = repository(failing(RemoteHttpFailure(SyncFailure.Unauthorised)))

        assertEquals(false, repository.refresh().completed)
        assertEquals(CatalogStatus.Failed(SyncFailure.Unauthorised), repository.status.value)
    }

    @Test
    fun `a server in trouble keeps its code, so retrying stays worth it`() = runTest {
        connect()
        val repository = repository(failing(RemoteHttpFailure(SyncFailure.ServerError(503))))

        repository.refresh()

        val status = repository.status.value as CatalogStatus.Failed
        assertEquals(SyncFailure.ServerError(503), status.reason)
        assertTrue(status.reason.worthRetrying)
    }

    @Test
    fun `a slow server is waiting, not unreachable`() = runTest {
        connect()
        val repository = repository(failing(SocketTimeoutException("timed out")))

        repository.refresh()

        assertEquals(CatalogStatus.Failed(SyncFailure.Timeout), repository.status.value)
    }

    @Test
    fun `an unreachable server is offline`() = runTest {
        connect()
        val repository = repository(failing(IOException("no route to host")))

        repository.refresh()

        assertEquals(CatalogStatus.Failed(SyncFailure.Offline), repository.status.value)
    }

    /**
     * Signing out mid-walk is not a failure and must not be shown as
     * one: the answer is dropped because it belongs to an account that
     * is gone, which is exactly what was asked for.
     */
    @Test
    fun `signing out mid-refresh settles quietly rather than accusing the server`() = runTest {
        connect()
        val repository = repository(
            FakeCatalog { onPage ->
                onPage(listOf(book("b1")))
                db.remoteServerDao().delete()
                onPage(listOf(book("b2")))
            },
        )

        assertEquals(false, repository.refresh().completed)
        assertEquals(CatalogStatus.Idle, repository.status.value)
    }

    @Test
    fun `a refresh that works leaves nothing showing`() = runTest {
        connect()
        val repository = repository(FakeCatalog { onPage -> onPage(listOf(book("b1"))) })

        assertEquals(true, repository.refresh().completed)
        assertEquals(CatalogStatus.Idle, repository.status.value)
        assertEquals(1, db.bookDao().allRemote().size)
    }

    @Test
    fun `a failure is cleared by the refresh that finally works`() = runTest {
        connect()
        repository(failing(IOException("no route to host"))).refresh()

        val repository = repository(FakeCatalog { onPage -> onPage(listOf(book("b1"))) })
        repository.refresh()

        assertEquals(CatalogStatus.Idle, repository.status.value)
    }

    /**
     * The library screen going away cancels the refresh. It leaves no
     * spinner behind, and it does not blame the server for something
     * nobody's server did.
     */
    @Test
    fun `a cancelled refresh does not leave the library spinning`() = runTest {
        connect()
        val started = CompletableDeferred<Unit>()
        val repository = repository(
            FakeCatalog {
                started.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            },
        )

        val job = async { repository.refresh() }
        started.await()
        job.cancel()
        job.join()

        assertEquals(CatalogStatus.Idle, repository.status.value)
    }

    /**
     * The whole point of the batched merge: what a shelf costs to fold in
     * must not grow with a query per book against an unindexed column.
     */
    @Test
    fun `a catalog is read in with one look at the library and one write a page`() = runTest {
        connect()
        val dao = CountingBookDao(db.bookDao())
        val pages = (0 until 3).map { page -> (0 until 50).map { book("b$page-$it") } }
        val repository = repository(
            FakeCatalog { onPage -> pages.forEach { onPage(it) } },
            dao,
        )

        assertEquals(true, repository.refresh().completed)

        assertEquals(1, dao.listedEverything)
        assertEquals(0, dao.singleReads)
        assertEquals(3, dao.batches)
        assertEquals(150, dao.rowsWritten)
        assertEquals(150, db.bookDao().allRemote().size)
    }

    @Test
    fun `a catalog that has not moved is not written back`() = runTest {
        connect()
        val walk = FakeCatalog { onPage -> onPage(listOf(book("b1"), book("b2"))) }
        repository(walk).refresh()

        val dao = CountingBookDao(db.bookDao())
        assertEquals(true, repository(walk, dao).refresh().completed)

        assertEquals(0, dao.batches)
        assertEquals(0, dao.rowsWritten)
    }

    /**
     * The library moves while a walk is in flight: a download finishes,
     * a book is opened or finished. The walk read its copy of the shelf
     * before any of that, and writing that copy back would undo it --
     * a completed download flipping back to remote and its file lost.
     */
    @Test
    fun `a download landing mid-walk survives the catalog folding in over it`() = runTest {
        connect()
        repository(FakeCatalog { onPage -> onPage(listOf(book("b1"))) }).refresh()
        val url = db.bookDao().allRemote().single().url

        val repository = repository(
            FakeCatalog { onPage ->
                // After the walk snapshotted the shelf, before the page
                // carrying a catalog-side change to the same book lands.
                db.bookDao().setDownloadState(
                    url = url,
                    state = com.chmouel.liseur.data.db.DownloadState.DOWNLOADED,
                    localUri = "content://downloads/b1.epub",
                )
                onPage(listOf(book("b1").copy(title = "b1 (revised)")))
            },
        )
        assertEquals(true, repository.refresh().completed)

        val stored = db.bookDao().allOnce().single()
        assertEquals("b1 (revised)", stored.title)
        assertEquals("content://downloads/b1.epub", stored.localUri)
        assertEquals(com.chmouel.liseur.data.db.DownloadState.DOWNLOADED, stored.downloadState)
    }

    /**
     * Series claims are local writes too. A pull can snapshot the shelf,
     * wait on the network, and then land after the reader has filed a
     * book. Its old copy must not put the book back where it started.
     */
    @Test
    fun `a manual series edit landing mid-walk survives the catalog refresh`() = runTest {
        connect(ServerKind.LISEUR_SYNC)
        val folderBook = book("b1").copy(
            updatedAt = 20,
            seriesName = "Old Shelf",
            seriesIndex = 1.0,
            seriesId = "old-series",
            seriesSource = "folder",
        )
        repository(FakeCatalog { onPage -> onPage(listOf(folderBook)) }).refresh()
        val url = db.bookDao().allOnce().single().url

        val repository = repository(
            FakeCatalog { onPage ->
                // The refresh has already read its old copy by the time
                // the catalog callback runs.
                db.bookDao().setSeriesOverride(url, "My Collection", 4.0, updatedAt = 30)
                onPage(listOf(folderBook.copy(title = "b1 (revised)")))
            },
        )
        assertEquals(true, repository.refresh().completed)

        val stored = db.bookDao().allOnce().single()
        assertEquals("b1 (revised)", stored.title)
        assertEquals("My Collection", stored.seriesName)
        assertEquals(4.0, stored.seriesIndex!!, 0.0)
        assertEquals("My Collection", stored.userSeriesName)
        assertEquals(true, stored.seriesOverridden)
        assertEquals(30L, stored.userSeriesUpdatedAt)
    }

    /**
     * A feed that names the same book twice on one page -- pagination
     * shifting under a server-side change does this -- must fold into
     * one row, not two inserts racing for the same unique URL.
     */
    @Test
    fun `a book named twice on one page lands once`() = runTest {
        connect()
        val repository = repository(
            FakeCatalog { onPage ->
                onPage(listOf(book("b1"), book("b1").copy(title = "b1 again"), book("b2")))
            },
        )

        assertEquals(true, repository.refresh().completed)
        assertEquals(CatalogStatus.Idle, repository.status.value)

        val books = db.bookDao().allOnce()
        assertEquals(2, books.size)
        assertEquals("b1 again", books.single { it.remoteUuid == "b1" }.title)
    }

    /**
     * A detached refresh has no screen behind it to catch a surprise: an
     * exception nobody predicted must settle into nothing rather than
     * crash the app from a scope of its own.
     */
    @Test
    fun `a detached refresh survives a failure nobody predicted`() = runTest {
        connect()
        val repository = RemoteCatalogRepository(
            router = RemoteRouter(
                serverDao = db.remoteServerDao(),
                catalogs = mapOf(ServerKind.KOMGA to failing(IllegalStateException("surprise"))),
                files = emptyMap(),
                positions = emptyMap(),
            ),
            serverDao = db.remoteServerDao(),
            bookDao = db.bookDao(),
            bookRemoval = BookRemoval(
            db.bookDao(),
            db.readingSessionDao(),
            db.syncPeerStateDao(),
            db.workIdentityDao(),
            db.readingProgressDao(),
            db.annotationDao(),
            db.annotationSyncDao(),
        ),
            scope = this,
        )

        val settled = CompletableDeferred<CatalogRefresh>()
        repository.refreshDetached { settled.complete(it) }

        assertEquals(false, settled.await().completed)
        assertEquals(CatalogStatus.Idle, repository.status.value)
    }

    /**
     * A downloaded book keeps its file and loses its link when it goes
     * from the catalog. Coming back must find that same row again --
     * by URL, since the link that was its other name is gone.
     */
    @Test
    fun `a downloaded book that comes back attaches to the row it already had`() = runTest {
        connect()
        val full = FakeCatalog { onPage -> onPage(listOf(book("b1"))) }
        repository(full).refresh()
        db.bookDao().setDownloadState(
            url = db.bookDao().allRemote().single().url,
            state = com.chmouel.liseur.data.db.DownloadState.DOWNLOADED,
            localUri = "content://downloads/b1.epub",
        )
        // Twice: the first absence only raises the suspicion.
        val gone = FakeCatalog { }
        repository(gone).refresh()
        assertEquals("b1", db.bookDao().allOnce().single().remoteUuid)
        repository(gone).refresh()
        assertEquals(null, db.bookDao().allOnce().single().remoteUuid)

        assertEquals(true, repository(full).refresh().completed)

        val books = db.bookDao().allOnce()
        assertEquals(1, books.size)
        assertEquals("b1", books.single().remoteUuid)
        assertEquals("content://downloads/b1.epub", books.single().localUri)
    }

    /** Unlinking clears the suspicion too, or the row would come back marked. */
    @Test
    fun `a downloaded book cut loose from the catalog keeps its file`() = runTest {
        connect()
        val full = FakeCatalog { onPage -> onPage(listOf(book("b1"))) }
        repository(full).refresh()
        db.bookDao().setDownloadState(
            url = db.bookDao().allRemote().single().url,
            state = com.chmouel.liseur.data.db.DownloadState.DOWNLOADED,
            localUri = "content://downloads/b1.epub",
        )
        val gone = FakeCatalog { }
        repository(gone).refresh()
        repository(gone).refresh()

        val cut = db.bookDao().allOnce().single()
        assertEquals(null, cut.remoteUuid)
        assertEquals("content://downloads/b1.epub", cut.localUri)
        assertEquals(null, cut.catalogMissingSince)
    }

    /** A claim route that records what the retry actually asked for. */
    private class RecordingClaims(
        private val layers: SeriesLayers? = SeriesLayers(
            bookId = "b1",
            source = "personal",
            series = emptyList(),
            folder = emptyList(),
            shared = null,
            personal = listOf(
                RemoteSeriesMembership(
                    id = "s1",
                    name = "Imperial Radch",
                    position = 4.0,
                    source = "personal",
                ),
            ),
            sharedUpdatedAt = null,
            personalUpdatedAt = 99,
            outcome = "applied",
        ),
    ) : SeriesClaimSync {
        val sets = mutableListOf<Pair<String?, Double?>>()
        val resets = mutableListOf<String>()

        override suspend fun setPersonalSeries(
            baseUrl: String,
            credentials: RemoteCredentials,
            book: Book,
            name: String?,
            index: Double?,
        ): SeriesLayers? {
            sets += name to index
            return layers
        }

        override suspend fun resetPersonalSeries(
            baseUrl: String,
            credentials: RemoteCredentials,
            book: Book,
        ): SeriesLayers? {
            resets += book.url
            return layers
        }

        override suspend fun resetSharedSeries(
            baseUrl: String,
            credentials: RemoteCredentials,
            book: Book,
        ): SeriesLayers? = layers

        override suspend fun reorderPersonalSeries(
            baseUrl: String,
            credentials: RemoteCredentials,
            booksInOrder: List<Book>,
        ): Boolean = true

        override suspend fun renameSeries(
            baseUrl: String,
            credentials: RemoteCredentials,
            seriesId: String,
            name: String,
        ): SeriesName? = null

        override suspend fun resetSeriesName(
            baseUrl: String,
            credentials: RemoteCredentials,
            seriesId: String,
        ): SeriesName? = null
    }

    private fun repository(catalog: CatalogSource, claims: SeriesClaimSync) =
        RemoteCatalogRepository(
            router = RemoteRouter(
                serverDao = db.remoteServerDao(),
                catalogs = mapOf(
                    ServerKind.KOMGA to catalog,
                    ServerKind.LISEUR_SYNC to catalog,
                ),
                files = emptyMap(),
                positions = emptyMap(),
                seriesClaims = mapOf(ServerKind.LISEUR_SYNC to claims),
            ),
            serverDao = db.remoteServerDao(),
            bookDao = db.bookDao(),
            bookRemoval = BookRemoval(
                db.bookDao(),
                db.readingSessionDao(),
                db.syncPeerStateDao(),
                db.workIdentityDao(),
                db.readingProgressDao(),
                db.annotationDao(),
                db.annotationSyncDao(),
            ),
        )

    /**
     * The number the reader typed has to travel with the claim.
     *
     * A retry that sent the name alone would take a shelf that was
     * dragged into order and give every book on it back to the catalog's
     * numbering, hours after the reader arranged it.
     */
    @Test
    fun `a retried claim carries the volume the reader chose`() = runTest {
        connect(ServerKind.LISEUR_SYNC)
        repository(FakeCatalog { onPage -> onPage(listOf(book("b1"))) }).refresh()
        val url = db.bookDao().allOnce().single().url
        db.bookDao().setSeriesOverride(url, "Imperial Radch", 4.0, updatedAt = 30)

        val claims = RecordingClaims()
        repository(FakeCatalog { }, claims).retryPendingSeriesClaims()

        assertEquals(listOf("Imperial Radch" to 4.0), claims.sets)
        val stored = db.bookDao().getByUrl(url)
        assertEquals(false, stored?.seriesClaimPending)
        assertEquals("s1", stored?.seriesId)
        assertEquals(99L, stored?.personalSeriesUpdatedAt)
    }

    /** A stale answer is not an acknowledgement; the claim stays owed. */
    @Test
    fun `a stale answer leaves the claim pending with the server's revision`() = runTest {
        connect(ServerKind.LISEUR_SYNC)
        repository(FakeCatalog { onPage -> onPage(listOf(book("b1"))) }).refresh()
        val url = db.bookDao().allOnce().single().url
        db.bookDao().setSeriesOverride(url, "Imperial Radch", 4.0, updatedAt = 30)

        val claims = RecordingClaims(
            layers = SeriesLayers(
                bookId = "b1",
                source = null,
                series = emptyList(),
                folder = emptyList(),
                shared = null,
                personal = null,
                sharedUpdatedAt = null,
                personalUpdatedAt = 77,
                outcome = "stale",
            ),
        )
        repository(FakeCatalog { }, claims).retryPendingSeriesClaims()

        val stored = db.bookDao().getByUrl(url)
        assertEquals(true, stored?.seriesClaimPending)
        assertEquals(77L, stored?.personalSeriesUpdatedAt)
    }

    /**
     * The response a claim's network call comes back with answers a
     * mutation made under one account; if that account is gone by the
     * time it lands, writing it would put the old account's series id
     * on a row the new account now owns. Signing out during the first
     * book's request must also stop the pass, not just skip that book.
     */
    @Test
    fun `an account change mid retry is not written by the stale response`() = runTest {
        connect(ServerKind.LISEUR_SYNC)
        repository(FakeCatalog { onPage -> onPage(listOf(book("b1"), book("b2"))) }).refresh()
        val urls = db.bookDao().allOnce().map { it.url }
        urls.forEach { db.bookDao().setSeriesOverride(it, "My Shelf", 1.0, updatedAt = 30) }

        val claims = SignOutOnFirstClaim(db.remoteServerDao())
        repository(FakeCatalog { }, claims).retryPendingSeriesClaims()

        assertEquals(1, claims.sets.size)
        db.bookDao().getByUrls(urls).forEach { book ->
            assertEquals(true, book.seriesClaimPending)
            assertEquals(null, book.seriesId)
        }
    }

    /** A claim route that signs the device out as soon as the first request lands. */
    private class SignOutOnFirstClaim(private val serverDao: RemoteServerDao) : SeriesClaimSync {
        val sets = mutableListOf<Pair<String?, Double?>>()

        override suspend fun setPersonalSeries(
            baseUrl: String,
            credentials: RemoteCredentials,
            book: Book,
            name: String?,
            index: Double?,
        ): SeriesLayers? {
            sets += name to index
            serverDao.delete()
            return SeriesLayers(
                bookId = book.url,
                source = "personal",
                series = emptyList(),
                folder = emptyList(),
                shared = null,
                personal = listOf(
                    RemoteSeriesMembership(id = "stale-id", name = name.orEmpty(), position = index, source = "personal"),
                ),
                sharedUpdatedAt = null,
                personalUpdatedAt = 99,
                outcome = "applied",
            )
        }

        override suspend fun resetPersonalSeries(
            baseUrl: String,
            credentials: RemoteCredentials,
            book: Book,
        ): SeriesLayers? = null

        override suspend fun resetSharedSeries(
            baseUrl: String,
            credentials: RemoteCredentials,
            book: Book,
        ): SeriesLayers? = null

        override suspend fun reorderPersonalSeries(
            baseUrl: String,
            credentials: RemoteCredentials,
            booksInOrder: List<Book>,
        ): Boolean = true

        override suspend fun renameSeries(
            baseUrl: String,
            credentials: RemoteCredentials,
            seriesId: String,
            name: String,
        ): SeriesName? = null

        override suspend fun resetSeriesName(
            baseUrl: String,
            credentials: RemoteCredentials,
            seriesId: String,
        ): SeriesName? = null
    }

    /**
     * A claim this device has not yet got through must survive the pull
     * it races, whatever the catalog says about the personal layer.
     */
    @Test
    fun `a refresh does not overwrite a claim still waiting to be sent`() = runTest {
        connect(ServerKind.LISEUR_SYNC)
        repository(FakeCatalog { onPage -> onPage(listOf(book("b1"))) }).refresh()
        val url = db.bookDao().allOnce().single().url
        db.bookDao().setSeriesOverride(url, "My Shelf", 1.0, updatedAt = 30)

        val remote = book("b1").copy(
            seriesName = "Imperial Radch",
            seriesIndex = 2.0,
            seriesId = "s1",
            series = listOf(
                RemoteSeriesMembership(
                    id = "s1",
                    name = "Imperial Radch",
                    position = 2.0,
                    source = "personal",
                ),
            ),
            seriesSource = "personal",
            seriesClaimUpdatedAt = 20,
        )
        repository(FakeCatalog { onPage -> onPage(listOf(remote)) }).refresh()

        val stored = db.bookDao().getByUrl(url)
        assertEquals("My Shelf", stored?.userSeriesName)
        assertEquals("My Shelf", stored?.seriesName)
        assertEquals(true, stored?.seriesClaimPending)
    }

    /**
     * A claim withdrawn on another device leaves nothing behind in the
     * catalog to adopt, so the withdrawal has to be read from its
     * absence — otherwise this shelf keeps a filing nobody still holds.
     */
    @Test
    fun `a personal claim withdrawn elsewhere is given up here`() = runTest {
        connect(ServerKind.LISEUR_SYNC)
        val claimed = book("b1").copy(
            seriesName = "Imperial Radch",
            seriesIndex = 2.0,
            seriesId = "s1",
            series = listOf(
                RemoteSeriesMembership(
                    id = "s1",
                    name = "Imperial Radch",
                    position = 2.0,
                    source = "personal",
                ),
            ),
            seriesSource = "personal",
            seriesClaimUpdatedAt = 20,
        )
        repository(FakeCatalog { onPage -> onPage(listOf(claimed)) }).refresh()
        assertEquals(true, db.bookDao().allOnce().single().seriesOverridden)

        val withdrawn = book("b1").copy(
            seriesName = "Imperial Radch",
            seriesIndex = 2.0,
            seriesId = "s1",
            series = listOf(
                RemoteSeriesMembership(
                    id = "s1",
                    name = "Imperial Radch",
                    position = 2.0,
                    source = null,
                ),
            ),
        )
        repository(FakeCatalog { onPage -> onPage(listOf(withdrawn)) }).refresh()

        val stored = db.bookDao().allOnce().single()
        assertEquals(false, stored.seriesOverridden)
        assertEquals(null, stored.userSeriesName)
        assertEquals(null, stored.personalSeriesUpdatedAt)
        // The shelf itself is still the catalog's, and still drag-ordered.
        assertEquals("Imperial Radch", stored.seriesName)
        assertEquals("s1", stored.seriesId)
    }
}
