package com.chmouel.liseur.data.remote

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.AnnotationSync
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookAnnotation
import com.chmouel.liseur.data.db.BookEvaluation
import com.chmouel.liseur.data.db.BookMetadata
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.MetadataOverrideFields
import com.chmouel.liseur.data.db.MetadataOverrideRevision
import com.chmouel.liseur.data.db.ReadingProgress
import com.chmouel.liseur.data.db.ReadingSession
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.WorkAlias
import com.chmouel.liseur.data.library.BookRemoval
import com.chmouel.liseur.domain.Identifier
import com.chmouel.liseur.domain.MetadataSnapshot
import com.chmouel.liseur.domain.MetadataSource
import com.chmouel.liseur.domain.PersonalEvaluation
import com.chmouel.liseur.sync.LibrarySyncBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class RemoteAccountMutualExclusionTest {

    private lateinit var db: LiseurDatabase
    private val mainServerChangeCalls = AtomicInteger(0)

    @Before
    fun open() {
        CredentialCipher.keyForTesting =
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(context, LiseurDatabase::class.java).build()
        mainServerChangeCalls.set(0)
    }

    @After
    fun close() {
        db.close()
        CredentialCipher.keyForTesting = null
    }

    private fun bookRemoval() = BookRemoval(
        bookDao = db.bookDao(),
        sessionDao = db.readingSessionDao(),
        peerStateDao = db.syncPeerStateDao(),
        identityDao = db.workIdentityDao(),
        progressDao = db.readingProgressDao(),
        annotationDao = db.annotationDao(),
        annotationSyncDao = db.annotationSyncDao(),
        evaluationDao = db.bookEvaluationDao(),
        metadataDao = db.bookMetadataDao(),
        inTransaction = { work -> db.withTransaction { work() } },
    )

    private fun accountRepository(
        setups: Map<ServerKind, ServerSetup> = defaultSetups(),
        beforeMainServerChange: suspend () -> Unit = { mainServerChangeCalls.incrementAndGet() },
    ): RemoteAccountRepository {
        val removal = bookRemoval()
        return RemoteAccountRepository(
            dao = db.remoteServerDao(),
            bookDao = db.bookDao(),
            progressDao = db.readingProgressDao(),
            bookRemoval = removal,
            seriesExtraDao = db.seriesExtraDao(),
            peerStateDao = db.syncPeerStateDao(),
            furthestDao = db.furthestPositionDao(),
            identityDao = db.workIdentityDao(),
            sessionDao = db.readingSessionDao(),
            annotationSyncDao = db.annotationSyncDao(),
            evaluationDao = db.bookEvaluationDao(),
            metadataOverrideDao = db.bookMetadataOverrideDao(),
            setups = setups,
            inTransaction = { work -> removal.together(work) },
            beforeMainServerChange = beforeMainServerChange,
        )
    }

    private fun defaultSetups(
        komgaResult: SetupResult = SetupResult.Success(
            ServerCapabilities(
                baseUrl = "https://books.example",
                canDownload = true,
                accountId = "user-1",
                displayName = "reader",
            ),
        ),
        customResult: SetupResult = SetupResult.Success(
            ServerCapabilities(
                baseUrl = "https://opds.example",
                catalogUrl = "https://opds.example/feed",
                canDownload = true,
                accountId = null,
                displayName = "",
            ),
        ),
        liseurSyncResult: SetupResult = SetupResult.Success(
            ServerCapabilities(
                baseUrl = "https://sync.example",
                canDownload = true,
                accountId = "dev-1",
                liseurAccountId = "acct-1",
                displayName = "reader",
                liseurToken = "tok-123",
            ),
        ),
    ): Map<ServerKind, ServerSetup> = mapOf(
        ServerKind.KOMGA to FakeSetup(komgaResult),
        ServerKind.CUSTOM to FakeSetup(customResult),
        ServerKind.LISEUR_SYNC to FakeSetup(liseurSyncResult),
    )

    @Test
    fun `beforeMainServerChange runs on successful connect, custom connect, and non-null backup restore`() = runTest {
        val repo = accountRepository()

        assertTrue(repo.connectKomga("https://books.example", "api-key") is SetupResult.Success)
        assertEquals(1, mainServerChangeCalls.get())

        val custom = repo.connectCustom(
            catalogUrl = "https://opds.example/feed",
            username = "",
            password = "",
            kosyncUrl = "",
            kosyncUsername = "",
            kosyncPassword = "",
        )
        assertTrue(custom.connected)
        assertEquals(2, mainServerChangeCalls.get())

        // Restoring null server clears main server without activating one, so external backend stays untouched.
        repo.restoreBackup(null)
        assertEquals(2, mainServerChangeCalls.get())

        // Restoring a non-null main server deactivates any external backend first.
        repo.restoreBackup(
            RemoteServer(
                kind = ServerKind.KOMGA,
                baseUrl = "https://books.example",
                username = "reader",
                passwordCipher = null,
                apiKeyCipher = RemoteServer.seal("api-key"),
                accountId = "user-1",
                userId = null,
                koboTokenCipher = null,
                canDownload = true,
                addedAt = 100L,
                catalogSyncedAt = null,
                positionSyncedAt = null,
                syncToken = null,
            ),
        )
        assertEquals(3, mainServerChangeCalls.get())
    }

    @Test
    fun `failed server probe does not invoke beforeMainServerChange`() = runTest {
        val repo = accountRepository(
            setups = defaultSetups(
                komgaResult = SetupResult.Failure(SetupFailure.BadCredentials),
                customResult = SetupResult.Failure(SetupFailure.Unreachable("offline", false)),
            ),
        )

        assertTrue(repo.connectKomga("https://books.example", "bad") is SetupResult.Failure)
        val custom = repo.connectCustom(
            catalogUrl = "https://opds.example/feed",
            username = "",
            password = "",
            kosyncUrl = "",
            kosyncUsername = "",
            kosyncPassword = "",
        )
        assertFalse(custom.connected)
        assertEquals(0, mainServerChangeCalls.get())
    }

    @Test
    fun `history-preserving disconnect keeps downloaded books, browse catalogs, and history or override stubs while dropping untouched previews`() = runTest {
        val repo = accountRepository()
        assertTrue(repo.connectLiseurSync("https://sync.example", "reader", "secret") is SetupResult.Success)
        val server = requireNotNull(repo.current())

        // Saved browse-only server (id > 1) and its books must remain completely untouched.
        val browseId = db.remoteServerDao().nextBrowseId()
        db.remoteServerDao().upsert(
            RemoteServer(
                id = browseId,
                kind = ServerKind.CUSTOM,
                baseUrl = "https://browse.example",
                catalogUrl = "https://browse.example/opds",
                username = "",
                passwordCipher = null,
                apiKeyCipher = null,
                accountId = null,
                userId = null,
                koboTokenCipher = null,
                canDownload = true,
                addedAt = 10L,
                catalogSyncedAt = null,
                positionSyncedAt = null,
                syncToken = null,
            ),
        )
        db.bookDao().upsert(
            Book(
                url = "browse:$browseId:preview",
                title = "Browse Preview",
                author = "Author",
                coverPath = null,
                source = null,
                addedAt = 10L,
                lastOpenedAt = null,
                remoteUuid = "browse-remote-1",
                browseServerId = browseId,
                downloadState = DownloadState.REMOTE,
            ),
        )

        // Local sideloaded book & downloaded main-server book.
        db.bookDao().upsert(
            Book(
                url = "file:///local.epub",
                title = "Local Book",
                author = "Local Author",
                coverPath = null,
                source = null,
                addedAt = 10L,
                lastOpenedAt = null,
                downloadState = DownloadState.DOWNLOADED,
            ),
        )
        db.bookDao().upsert(
            Book(
                url = "sync:downloaded",
                title = "Downloaded Server Book",
                author = "Author",
                coverPath = null,
                source = null,
                addedAt = 10L,
                lastOpenedAt = null,
                localUri = "file:///downloaded.epub",
                remoteUuid = "remote-downloaded",
                downloadState = DownloadState.DOWNLOADED,
            ),
        )

        // Uploaded watched-folder book: readable through its url, no localUri, no history.
        db.bookDao().upsert(
            mainStub("file:///watched/uploaded.epub").copy(downloadState = DownloadState.DOWNLOADED),
        )

        // Genuinely untouched undownloaded main-server preview stub -> must be deleted.
        db.bookDao().upsert(mainStub("sync:untouched"))

        // Fileless stubs carrying each preservation predicate from ADR-0078 -> all must survive & unlink.
        val preservedSpecs = listOf(
            "sync:has-session",
            "sync:has-annotation",
            "sync:has-annotation-sync",
            "sync:has-evaluation",
            "sync:has-progress",
            "sync:has-title-override",
            "sync:has-author-override",
            "sync:has-desc-override",
            "sync:has-cover-override",
            "sync:has-meta-rev",
            "sync:has-series-override",
            "sync:has-index-override",
            "sync:has-series-pending",
            "sync:has-series-reset",
            "sync:has-archived",
            "sync:has-finished",
            "sync:has-opened",
            "sync:has-hidden",
        )
        db.bookDao().upsert(mainStub("sync:has-session"))
        db.readingSessionDao().insert(
            ReadingSession(
                bookUrl = "sync:has-session",
                startedAt = 100L,
                endedAt = 200L,
                lastCheckpointAt = 200L,
                durationMs = 100L,
                startProgression = 0.0,
                endProgression = 0.1,
            ),
        )
        db.bookDao().upsert(mainStub("sync:has-annotation"))
        db.annotationDao().upsert(
            BookAnnotation(
                id = "ann-1",
                bookId = "sync:has-annotation",
                kind = "NOTE",
                locatorJson = "{}",
                note = "Remember this",
                createdAt = 100L,
                updatedAt = 100_000L,
            ),
        )
        db.bookDao().upsert(mainStub("sync:has-annotation-sync"))
        db.annotationSyncDao().upsert(
            AnnotationSync(
                id = "ann-deleted",
                peerId = server.accountKey,
                bookId = "sync:has-annotation-sync",
                workId = "work-1",
                rev = 1,
                ackedFingerprint = "fp-1",
            ),
        )
        db.bookDao().upsert(mainStub("sync:has-evaluation"))
        db.bookEvaluationDao().upsert(
            BookEvaluation(
                bookUrl = "sync:has-evaluation",
                field = PersonalEvaluation.RATING,
                value = "4.5",
                title = "Book",
                author = "Author",
                revision = 1,
                updatedAt = 100L,
            ),
        )
        db.bookDao().upsert(mainStub("sync:has-progress"))
        db.readingProgressDao().upsert(
            ReadingProgress(
                bookUrl = "sync:has-progress",
                locatorJson = "{}",
                totalProgression = 0.25,
                updatedAt = 100L,
            ),
        )

        db.bookDao().upsert(mainStub("sync:has-title-override").copy(titleOverridden = true, userTitle = "Custom Title"))
        db.bookDao().upsert(mainStub("sync:has-author-override").copy(authorOverridden = true, userAuthor = "Custom Author"))
        db.bookDao().upsert(mainStub("sync:has-desc-override").copy(descriptionOverridden = true, userDescription = "Custom Desc"))
        db.bookDao().upsert(mainStub("sync:has-cover-override").copy(coverOverridden = true, userCoverPath = "/covers/x.webp"))
        db.bookDao().upsert(mainStub("sync:has-meta-rev"))
        db.bookMetadataOverrideDao().upsertRevision(
            MetadataOverrideRevision(
                bookUrl = "sync:has-meta-rev",
                field = MetadataOverrideFields.TITLE,
                revision = 1,
                updatedAt = 100L,
            ),
        )
        db.bookDao().upsert(mainStub("sync:has-series-override").copy(seriesOverridden = true, userSeriesName = "My Series"))
        db.bookDao().upsert(mainStub("sync:has-index-override").copy(indexOverridden = true, userSeriesIndex = 2.0))
        db.bookDao().upsert(mainStub("sync:has-series-pending").copy(seriesClaimPending = true))
        db.bookDao().upsert(mainStub("sync:has-series-reset").copy(seriesClaimReset = true))
        db.bookDao().upsert(mainStub("sync:has-archived").copy(archivedAt = 100L))
        db.bookDao().upsert(mainStub("sync:has-finished").copy(finishedAt = 100L))
        db.bookDao().upsert(mainStub("sync:has-opened").copy(lastOpenedAt = 100L))
        db.bookDao().upsert(mainStub("sync:has-hidden").copy(hiddenAt = 100L))

        // Attach retiring server work_alias and catalog metadata to verify beforePeerForget sees them BEFORE cleanup.
        db.workIdentityDao().upsert(
            WorkAlias(
                bookUrl = "sync:has-session",
                peerId = server.accountKey,
                workId = "server-work-1",
                confidence = WorkAlias.HIGH,
                confirmed = true,
                editionSha = "deadbeef",
                resolvedAt = 50L,
            ),
        )
        db.bookMetadataDao().upsert(
            BookMetadata.of(
                bookUrl = "sync:has-session",
                source = MetadataSource.CATALOG,
                snapshot = MetadataSnapshot(identifiers = listOf(Identifier("isbn", "9780547928227"))),
            ),
        )

        var seenPreservedUrls: List<String> = emptyList()
        var seenRetiringAliasSha: String? = null
        var seenCatalogMetadataCount = 0

        repo.disconnect(preserveHistoryStubs = true) { retiringServer, preservedStubs ->
            assertEquals(server.accountKey, retiringServer.accountKey)
            seenPreservedUrls = preservedStubs.map { it.url }
            seenRetiringAliasSha = db.workIdentityDao().alias("sync:has-session", retiringServer.accountKey)?.editionSha
            seenCatalogMetadataCount = db.bookMetadataDao().forBook("sync:has-session").size
        }

        // beforePeerForget ran before peer cleanup and catalog metadata wipe.
        assertEquals(preservedSpecs.sorted(), seenPreservedUrls.sorted())
        assertEquals("deadbeef", seenRetiringAliasSha)
        assertEquals(1, seenCatalogMetadataCount)

        // Main server removed; browse-only server and its preview book untouched.
        assertNull(repo.current())
        assertEquals(1, db.remoteServerDao().browseServers().size)
        val browseBook = requireNotNull(db.bookDao().getByUrl("browse:$browseId:preview"))
        assertEquals(browseId, browseBook.browseServerId)
        assertEquals("browse-remote-1", browseBook.remoteUuid)

        // Local & downloaded books preserved; downloaded book unlinked from main server.
        assertNotNull(db.bookDao().getByUrl("file:///local.epub"))
        val downloaded = requireNotNull(db.bookDao().getByUrl("sync:downloaded"))
        assertNull(downloaded.remoteUuid)
        assertEquals("file:///downloaded.epub", downloaded.localUri)
        assertEquals(DownloadState.DOWNLOADED, downloaded.downloadState)

        // Untouched preview stub deleted; a DOWNLOADED row with no localUri stays, unlinked.
        assertNull(db.bookDao().getByUrl("sync:untouched"))
        val uploaded = requireNotNull(db.bookDao().getByUrl("file:///watched/uploaded.epub"))
        assertNull(uploaded.remoteUuid)
        assertNull(uploaded.localUri)
        assertEquals(DownloadState.DOWNLOADED, uploaded.downloadState)

        // Every preserved fileless stub survived, is unlinked, and retains REMOTE / null localUri & data.
        for (url in preservedSpecs) {
            val stub = requireNotNull(db.bookDao().getByUrl(url)) { "Expected preserved stub $url" }
            assertNull("Expected $url unlinked", stub.remoteUuid)
            assertEquals(DownloadState.REMOTE, stub.downloadState)
            assertNull(stub.localUri)
        }
        assertEquals(1, db.readingSessionDao().countForBook("sync:has-session"))
        assertEquals(1, db.annotationDao().count("sync:has-annotation"))
        assertEquals(1, db.bookEvaluationDao().countForBook("sync:has-evaluation"))
        assertNotNull(db.readingProgressDao().get("sync:has-progress"))
        assertEquals("Custom Title", db.bookDao().getByUrl("sync:has-title-override")!!.title)
    }

    @Test
    fun `preserved fileless stubs retain stableTitle, stableAuthor, and manual series context while clearing server transport links`() = runTest {
        val repo = accountRepository()
        assertTrue(repo.connectLiseurSync("https://sync.example", "reader", "secret") is SetupResult.Success)

        // Preserved fileless stub with a title override and no file metadata.
        db.bookDao().upsert(
            mainStub("sync:title-override").copy(
                title = "My Custom Hobbit",
                catalogTitle = "The Hobbit",
                userTitle = "My Custom Hobbit",
                titleOverridden = true,
                fileTitle = null,
                author = "J.R.R. Tolkien",
                catalogAuthor = "J.R.R. Tolkien",
                fileAuthor = null,
                seriesName = "Middle-earth",
                seriesIndex = 1.0,
                catalogSeriesName = "Middle-earth",
                catalogSeriesIndex = 1.0,
                fileSeriesName = null,
                fileSeriesIndex = null,
                remoteBookId = 42,
                coverUrl = "https://sync.example/covers/42",
                downloadHref = "/v1/books/42/download",
                downloadFormats = "epub",
                remoteUpdatedAt = 500L,
                remotePageCount = 310,
                catalogFolderId = "folder-1",
                catalogSeriesSource = "folder",
                catalogMissingSince = 999L,
                seriesId = "srv-series-1",
                linkGeneration = 2L,
            ),
        )

        // Preserved fileless stub with an author override and no file metadata.
        db.bookDao().upsert(
            mainStub("sync:author-override").copy(
                title = "The Silmarillion",
                catalogTitle = "The Silmarillion",
                fileTitle = null,
                author = "Tolkien",
                catalogAuthor = "J.R.R. Tolkien",
                userAuthor = "Tolkien",
                authorOverridden = true,
                fileAuthor = null,
            ),
        )

        // Preserved fileless stub with an index-only manual series reorder and no file metadata.
        db.bookDao().upsert(
            mainStub("sync:index-reorder").copy(
                title = "Words of Radiance",
                catalogTitle = "Words of Radiance",
                fileTitle = null,
                author = "Brandon Sanderson",
                catalogAuthor = "Brandon Sanderson",
                fileAuthor = null,
                seriesName = "The Stormlight Archive",
                seriesIndex = 3.5,
                catalogSeriesName = "The Stormlight Archive",
                catalogSeriesIndex = 2.0,
                fileSeriesName = null,
                fileSeriesIndex = null,
                seriesOverridden = false,
                indexOverridden = true,
                userSeriesIndex = 3.5,
                seriesId = "srv-series-9",
            ),
        )

        // Downloaded main-server book in the same disconnect must still use downloaded-book unlink rules.
        db.bookDao().upsert(
            Book(
                url = "sync:downloaded-with-file-meta",
                title = "Catalog Title",
                catalogTitle = "Catalog Title",
                fileTitle = "File Title",
                author = "Catalog Author",
                catalogAuthor = "Catalog Author",
                fileAuthor = "File Author",
                seriesName = "Catalog Series",
                seriesIndex = 2.0,
                catalogSeriesName = "Catalog Series",
                catalogSeriesIndex = 2.0,
                fileSeriesName = "File Series",
                fileSeriesIndex = 1.0,
                coverPath = null,
                source = null,
                addedAt = 10L,
                lastOpenedAt = null,
                localUri = "file:///downloaded-meta.epub",
                remoteUuid = "remote-downloaded-meta",
                downloadState = DownloadState.DOWNLOADED,
            ),
        )

        repo.disconnect(preserveHistoryStubs = true)

        val titleOverrideStub = requireNotNull(db.bookDao().getByUrl("sync:title-override"))
        assertEquals("My Custom Hobbit", titleOverrideStub.title)
        assertEquals("The Hobbit", titleOverrideStub.stableTitle)
        assertEquals("J.R.R. Tolkien", titleOverrideStub.author)
        assertEquals("J.R.R. Tolkien", titleOverrideStub.stableAuthor)
        assertEquals("Middle-earth", titleOverrideStub.seriesName)
        assertEquals(1.0, titleOverrideStub.seriesIndex)
        assertNull(titleOverrideStub.remoteUuid)
        assertNull(titleOverrideStub.remoteBookId)
        assertNull(titleOverrideStub.coverUrl)
        assertNull(titleOverrideStub.downloadHref)
        assertNull(titleOverrideStub.downloadFormats)
        assertNull(titleOverrideStub.remoteUpdatedAt)
        assertNull(titleOverrideStub.remotePageCount)
        assertNull(titleOverrideStub.catalogFolderId)
        assertNull(titleOverrideStub.catalogSeriesSource)
        assertNull(titleOverrideStub.catalogMissingSince)
        assertNull(titleOverrideStub.seriesId)
        assertEquals(3L, titleOverrideStub.linkGeneration)
        assertEquals(DownloadState.REMOTE, titleOverrideStub.downloadState)
        assertNull(titleOverrideStub.localUri)

        val authorOverrideStub = requireNotNull(db.bookDao().getByUrl("sync:author-override"))
        assertEquals("The Silmarillion", authorOverrideStub.title)
        assertEquals("The Silmarillion", authorOverrideStub.stableTitle)
        assertEquals("Tolkien", authorOverrideStub.author)
        assertEquals("J.R.R. Tolkien", authorOverrideStub.stableAuthor)
        assertNull(authorOverrideStub.remoteUuid)

        val indexReorderStub = requireNotNull(db.bookDao().getByUrl("sync:index-reorder"))
        assertEquals("Words of Radiance", indexReorderStub.stableTitle)
        assertEquals("Brandon Sanderson", indexReorderStub.stableAuthor)
        assertEquals("The Stormlight Archive", indexReorderStub.seriesName)
        assertEquals(3.5, indexReorderStub.seriesIndex)
        assertEquals("The Stormlight Archive", indexReorderStub.catalogSeriesName)
        assertEquals(2.0, indexReorderStub.catalogSeriesIndex)
        assertFalse(indexReorderStub.seriesOverridden)
        assertTrue(indexReorderStub.indexOverridden)
        assertEquals(3.5, indexReorderStub.userSeriesIndex)
        assertNull(indexReorderStub.seriesId)
        assertNull(indexReorderStub.remoteUuid)

        val downloaded = requireNotNull(db.bookDao().getByUrl("sync:downloaded-with-file-meta"))
        assertNull(downloaded.remoteUuid)
        assertNull(downloaded.catalogTitle)
        assertNull(downloaded.catalogAuthor)
        assertNull(downloaded.catalogSeriesName)
        assertEquals("File Title", downloaded.title)
        assertEquals("File Author", downloaded.author)
        assertEquals("File Series", downloaded.seriesName)
        assertEquals(1.0, downloaded.seriesIndex)
    }

    @Test
    fun `router suppresses main-server position sync while external backend is active without affecting browse catalogs`() = runTest {
        val repo = accountRepository()
        assertTrue(repo.connectLiseurSync("https://sync.example", "reader", "secret") is SetupResult.Success)

        val externalActive = MutableStateFlow(false)
        val externalPeer = FakePeerPositionSync(peerId = "external", supportsVocabularySync = true)
        val mainPosition = FakePeerPositionSync(peerId = PeerPositionSync.CATALOG, supportsVocabularySync = true)
        val catalogSource = FakeCatalogSource()
        val fileSource = FakeFileSource()

        val externalBackend = object : LibrarySyncBackend {
            override val active: Flow<Boolean> = externalActive
            override suspend fun isActive(): Boolean = externalActive.value
            override suspend fun deactivate() {
                externalActive.value = false
            }
            override fun resume() = Unit
            override val positionPeer: PeerPositionSync = externalPeer
        }

        val router = RemoteRouter(
            serverDao = db.remoteServerDao(),
            catalogs = mapOf(ServerKind.CUSTOM to catalogSource),
            files = mapOf(ServerKind.CUSTOM to fileSource),
            positions = mapOf(ServerKind.LISEUR_SYNC to mainPosition),
            externalLibrarySync = { externalBackend },
        )

        // Inactive external backend: main server routes normally.
        assertSame(mainPosition, router.positionSync())
        assertTrue(router.maySyncBrowseBook())
        assertTrue(router.supportsVocabularySync())

        // Active external backend: main-server position sync is suppressed; browse catalog/file routes stay intact.
        externalActive.value = true
        assertNull(router.positionSync())
        assertFalse(router.maySyncBrowseBook())
        assertTrue(router.supportsVocabularySync())
        assertSame(catalogSource, router.catalogFor(ServerKind.CUSTOM))
        assertSame(fileSource, router.filesFor(ServerKind.CUSTOM))

        // LibrarySyncBackend.None is always inactive with null peer.
        assertFalse(LibrarySyncBackend.None.isActive())
        assertFalse(LibrarySyncBackend.None.active.first())
        assertNull(LibrarySyncBackend.None.positionPeer)
    }

    private fun mainStub(url: String) = Book(
        url = url,
        title = "Stub $url",
        author = "Author",
        coverPath = null,
        source = null,
        addedAt = 10L,
        lastOpenedAt = null,
        localUri = null,
        remoteUuid = "remote-$url",
        downloadState = DownloadState.REMOTE,
    )

    private class FakeSetup(private val result: SetupResult) : ServerSetup {
        override suspend fun connect(
            rawUrl: String,
            credentials: RemoteCredentials,
            allowHttp: Boolean,
        ): SetupResult = result
    }

    private class FakeCatalogSource : CatalogSource {
        override suspend fun allBooks(
            baseUrl: String,
            credentials: RemoteCredentials,
            onPage: suspend (List<RemoteBook>) -> Unit,
        ): CatalogWalk = CatalogWalk(complete = true)

        override suspend fun search(
            baseUrl: String,
            credentials: RemoteCredentials,
            query: String,
        ): List<RemoteBook> = emptyList()
    }

    private class FakeFileSource : FileSource {
        override fun downloadRequest(
            server: RemoteServer,
            credentials: RemoteCredentials,
            book: Book,
        ): okhttp3.Request.Builder? = null
    }

    private class FakePeerPositionSync(
        override val peerId: String,
        override val supportsVocabularySync: Boolean = false,
    ) : PeerPositionSync {
        override suspend fun dialledAddress(): String? = null
        override suspend fun syncAll(snapshot: SyncSnapshot?): SyncOutcome = SyncOutcome.NotApplicable
        override suspend fun syncBook(bookUrl: String): SyncOutcome = SyncOutcome.NotApplicable
        override suspend fun canSync(bookUrl: String): Boolean = false
        override suspend fun previewBook(bookUrl: String): PreviewOutcome = PreviewOutcome.NotSynced
        override suspend fun preservedConflict(bookUrl: String, peerId: String?): SyncPreview? = null
        override suspend fun takeRemotePosition(
            bookUrl: String,
            atRevision: Long,
            peerId: String?,
            expectedAccountKey: String?,
        ): ResolveOutcome = ResolveOutcome.Done
        override suspend fun keepLocalPosition(bookUrl: String, peerId: String?): ResolveOutcome = ResolveOutcome.Done
        override suspend fun refreshUnresolved() = Unit
        override suspend fun identity(): SyncIdentity? = null
    }
}
