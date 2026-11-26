package com.chmouel.liseur.drive

import android.app.Application
import android.content.Intent
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookFingerprintRow
import com.chmouel.liseur.data.db.BookMetadata
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.library.BookFingerprintStore
import com.chmouel.liseur.data.library.BookRemoval
import com.chmouel.liseur.data.library.FinishedState
import com.chmouel.liseur.data.remote.CompositePositionSync
import com.chmouel.liseur.data.remote.RemoteAccountRepository
import com.chmouel.liseur.data.remote.ResolveOutcome
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.SyncOutcome
import com.chmouel.liseur.domain.BookFingerprint
import com.chmouel.liseur.domain.BookFingerprints
import com.chmouel.liseur.domain.FinishedOverride
import com.chmouel.liseur.domain.Identifier
import com.chmouel.liseur.domain.MetadataSnapshot
import com.chmouel.liseur.domain.MetadataSource
import com.chmouel.liseur.domain.ReadingStatus
import com.chmouel.liseur.domain.ReadingStatusChoice
import com.chmouel.liseur.domain.readingStatusFor
import com.chmouel.liseur.sync.LibrarySyncSwitchCoordinator
import com.chmouel.liseur.sync.PositionSyncCoordinator
import com.chmouel.liseur.sync.SyncScope
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Deterministic two-device end-to-end tests for Phase 3 Google Drive sync:
 * - Whole-library catalog sync + opt-in EPUB upload + on-demand SHA-256-verified download
 * - Reading position, backward reading moves, concurrent conflict preservation, edition-gated locators
 * - Furthest position peaks and resets (`deleteUpToForOrigin`)
 * - Concurrent first-connection duplicate `Liseur/` folder resolution
 * - `batchId` idempotent retry after simulated crash & expired `pageToken` rescan
 * - Unsupported `schemaVersion` stream pause + per-domain pause without corrupting local state
 * - Separate WorkManager workers & unmetered default constraints
 */
@Config(sdk = [35], application = Application::class)
@RunWith(RobolectricTestRunner::class)
class DrivePhase3EndToEndTest {

    private lateinit var context: Application
    private lateinit var driveServer: InMemoryGoogleDriveServer
    private lateinit var deviceA: DeviceHarness
    private lateinit var deviceB: DeviceHarness
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        CredentialCipher.keyForTesting =
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        context = ApplicationProvider.getApplicationContext()
        tempDir = File(context.cacheDir, "drive-p3-test-${System.nanoTime()}").apply { mkdirs() }
        driveServer = InMemoryGoogleDriveServer()
        deviceA = createDevice("dev-a", File(tempDir, "books-a"))
        deviceB = createDevice("dev-b", File(tempDir, "books-b"))
    }

    @After
    fun tearDown() {
        deviceA.close()
        deviceB.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun `a missing Drive download source leaves the book retryable`() = runTest {
        deviceB.connect()
        val book = peakBook("missing-source").copy(localUri = null, downloadState = DownloadState.QUEUED)
        deviceB.liseurDb.bookDao().upsert(book)
        assertTrue(deviceB.epubTransfers.downloadBook(book.url) is DriveEpubDownloadOutcome.PermanentFailure)
        assertEquals(DownloadState.FAILED, deviceB.liseurDb.bookDao().getByUrl(book.url)?.downloadState)
    }

    @Test
    fun `replaying removed edition restores another available download`() = runTest {
        deviceB.connect()
        val key = "isbn:replay-edition"
        val hlc = "0017450000000-0001-dev-a"
        val first = DriveCatalogEdition("a".repeat(64), 42L, "file-1", "one.epub", 100L, hlc)
        val second = DriveCatalogEdition("b".repeat(64), 43L, "file-2", "two.epub", 101L, hlc)
        val body = DriveCatalogBody(key, "Book", null, emptyList(), listOf(first, second), null, null, 1L, null, 1L)
        val record = DriveWireCodec.putRecord(DriveWireCodec.DOMAIN_CATALOG, key, hlc, DriveWireCodec.encodeCatalogBody(body))
        deviceB.catalogSync.applyRemoteRecord(PERM_ID, record, "dev-a", 1L) { deviceB.changeEngine.nextHlc(PERM_ID) }
        val stub = "drive:$key"
        deviceB.catalogSync.handleDriveEpubRemoved(PERM_ID, "file-1") { deviceB.changeEngine.nextHlc(PERM_ID) }
        val row = requireNotNull(deviceB.liseurDb.bookDao().getByUrl(stub))
        deviceB.liseurDb.bookDao().upsert(row.copy(downloadHref = DriveCatalogSync.downloadHrefFor("file-1")))
        deviceB.catalogSync.handleDriveEpubRemoved(PERM_ID, "file-1") { deviceB.changeEngine.nextHlc(PERM_ID) }
        assertEquals(DriveCatalogSync.downloadHrefFor("file-2"), deviceB.liseurDb.bookDao().getByUrl(stub)?.downloadHref)
    }

    @Test
    fun `newer catalog metadata cannot restore cleared edition availability`() = runTest {
        deviceB.connect()
        val key = "isbn:123"
        val oldClock = "0017450000000-0001-dev-a"
        val clearClock = "0017450000000-0002-dev-a"
        val laterClock = "0017450000000-0003-dev-b"
        val edition = DriveCatalogEdition("a".repeat(64), 42L, "file-1", "book.epub", 100L, oldClock)
        val body = DriveCatalogBody(key, "Book", null, emptyList(), listOf(edition), null, null, 1L, null, 1L)
        suspend fun apply(value: DriveCatalogBody, hlc: String) {
            deviceB.catalogSync.applyRemoteRecord(
                PERM_ID, DriveWireCodec.putRecord(DriveWireCodec.DOMAIN_CATALOG, key, hlc, DriveWireCodec.encodeCatalogBody(value)),
                "dev-a", 1L,
            ) { deviceB.changeEngine.nextHlc(PERM_ID) }
        }
        apply(body, oldClock)
        apply(body.copy(editions = listOf(edition.copy(driveFileId = null, fileName = null, uploadedAt = null, availabilityHlc = clearClock))), clearClock)
        apply(body.copy(title = "Updated title"), laterClock)
        val state = requireNotNull(deviceB.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_CATALOG, key))
        val bodyJson = requireNotNull(state.bodyJson)
        val merged = requireNotNull(DriveWireCodec.decodeCatalogBody(org.json.JSONObject(bodyJson)))
        assertEquals(clearClock, merged.editions.single().availabilityHlc)
        assertNull(merged.editions.single().driveFileId)
        assertEquals(DriveCanonicalJson.contentFp(org.json.JSONObject(bodyJson)), state.contentFp)
        assertEquals(DriveCanonicalJson.putFp(org.json.JSONObject(bodyJson), state.lastHlc), state.versionFp)
    }

    @Test
    fun `catalog tombstone preserves other accounts and rejects an older put`() = runTest {
        deviceB.connect()
        val file = File(tempDir, "retained.epub").apply { writeText("retained EPUB") }
        val book = peakBook("retained").copy(url = file.toURI().toString(), localUri = file.toURI().toString())
        deviceB.liseurDb.bookDao().upsert(book)
        val aliases = deviceB.liseurDb.workIdentityDao()
        val peer = DriveLibrarySync.accountPeerId(PERM_ID)
        val otherPeer = DriveLibrarySync.accountPeerId("other-account")
        val key = "isbn:123"
        aliases.upsert(com.chmouel.liseur.data.db.WorkAlias(book.url, peer, key, "high", confirmed = true, resolvedAt = 1L))
        aliases.upsert(com.chmouel.liseur.data.db.WorkAlias(book.url, otherPeer, key, "high", confirmed = true, resolvedAt = 1L))
        val hlc = "0017450000000-0002-dev-a"
        val deletion = DriveWireRecord(
            d = DriveWireCodec.DOMAIN_CATALOG, k = key, op = DriveRecordState.OP_DEL,
            hlc = hlc, fp = DriveCanonicalJson.delFp(DriveWireCodec.DOMAIN_CATALOG, key, hlc),
        )
        deviceB.catalogSync.applyRemoteRecord(PERM_ID, deletion, "dev-a", 2L) { deviceB.changeEngine.nextHlc(PERM_ID) }
        assertEquals(com.chmouel.liseur.data.db.WorkAlias.REJECTED, aliases.alias(book.url, peer)?.confidence)
        assertNotNull(aliases.alias(book.url, otherPeer))
        assertTrue(deviceB.catalogSync.collectDirtyRecords(PERM_ID) { deviceB.changeEngine.nextHlc(PERM_ID) }.isEmpty())
        val body = DriveCatalogBody(key, "The Hobbit", null, emptyList(), emptyList(), null, null, 1L, null, 1L)
        val older = DriveWireCodec.putRecord(DriveWireCodec.DOMAIN_CATALOG, key, "0017450000000-0001-dev-a", DriveWireCodec.encodeCatalogBody(body))
        deviceB.catalogSync.applyRemoteRecord(PERM_ID, older, "dev-a", 1L) { deviceB.changeEngine.nextHlc(PERM_ID) }
        assertNull(deviceB.liseurDb.bookDao().getByUrl("drive:$key"))
        assertEquals(DriveRecordState.OP_DEL, deviceB.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_CATALOG, key)?.versionOp)
        assertEquals(1, deviceB.epubTransfers.queueSelectedBooks(PERM_ID, setOf(book.url)))
        val put = deviceB.catalogSync.collectDirtyRecords(PERM_ID) { deviceB.changeEngine.nextHlc(PERM_ID) }.single()
        assertTrue(deletion.fp in put.anc)
        deviceB.catalogSync.applyRemoteRecord(PERM_ID, deletion, "dev-a", 2L) { deviceB.changeEngine.nextHlc(PERM_ID) }
        assertEquals(put.fp, deviceB.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_CATALOG, key)?.versionFp)
        assertTrue(requireNotNull(aliases.alias(book.url, peer)).usable)
    }

    @Test
    fun `two-device spine syncs whole catalog, uploads selected EPUB, downloads with SHA-256, and converges positions`() = runTest {
        deviceA.connect()
        deviceB.connect()

        // Create two local EPUB files on Device A; only Book 1 will be selected in "Upload EPUB files".
        val epub1Bytes = "EPUB-CONTENT-BOOK-ONE-HOBBIT-BYTES".toByteArray()
        val epub2Bytes = "EPUB-CONTENT-BOOK-TWO-SILMARILLION-BYTES".toByteArray()
        val book1File = File(tempDir, "hobbit.epub").apply { writeBytes(epub1Bytes) }
        val book2File = File(tempDir, "silmarillion.epub").apply { writeBytes(epub2Bytes) }
        val fp1 = BookFingerprints.of(ByteArrayInputStream(epub1Bytes))
        val fp2 = BookFingerprints.of(ByteArrayInputStream(epub2Bytes))

        val book1 = Book(
            url = book1File.toURI().toString(),
            title = "The Hobbit",
            author = "J.R.R. Tolkien",
            coverPath = null,
            source = null,
            addedAt = 1_000L,
            lastOpenedAt = null,
            localUri = book1File.toURI().toString(),
            downloadState = DownloadState.DOWNLOADED,
            sizeBytes = fp1.size,
            fileTitle = "The Hobbit",
            fileAuthor = "J.R.R. Tolkien",
            fileMetadataChecked = true,
        )
        val book2 = Book(
            url = book2File.toURI().toString(),
            title = "The Silmarillion",
            author = "J.R.R. Tolkien",
            coverPath = null,
            source = null,
            addedAt = 2_000L,
            lastOpenedAt = null,
            localUri = book2File.toURI().toString(),
            downloadState = DownloadState.DOWNLOADED,
            sizeBytes = fp2.size,
            fileTitle = "The Silmarillion",
            fileAuthor = "J.R.R. Tolkien",
            fileMetadataChecked = true,
        )
        deviceA.liseurDb.bookDao().upsertAll(listOf(book1, book2))
        deviceA.liseurDb.workIdentityDao().upsert(BookFingerprintRow(book1.url, fp1.sha256, fp1.partialMd5, fp1.size, book1File.lastModified(), 1L))
        deviceA.liseurDb.workIdentityDao().upsert(BookFingerprintRow(book2.url, fp2.sha256, fp2.partialMd5, fp2.size, book2File.lastModified(), 1L))
        deviceA.liseurDb.bookMetadataDao().upsert(
            BookMetadata.of(book1.url, MetadataSource.FILE, MetadataSnapshot(identifiers = listOf(Identifier("isbn", "9780547928227")))),
        )
        deviceA.liseurDb.bookMetadataDao().upsert(
            BookMetadata.of(book2.url, MetadataSource.FILE, MetadataSnapshot(identifiers = listOf(Identifier("isbn", "9780618391110")))),
        )

        // Device A syncs BEFORE selecting any EPUB to upload: catalog syncs for whole library, NO Liseur/ folder created yet, NO driveFileId published!
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertTrue(driveServer.liseurFolders.isEmpty())

        // Device B pulls: sees BOTH catalog stubs (`DownloadState.REMOTE`, `remoteUuid == null`), neither downloadable yet.
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val stub1Before = deviceB.liseurDb.bookDao().getByUrl("drive:isbn:9780547928227")
        val stub2Before = deviceB.liseurDb.bookDao().getByUrl("drive:isbn:9780618391110")
        assertNotNull(stub1Before)
        assertNotNull(stub2Before)
        assertEquals(DownloadState.REMOTE, stub1Before!!.downloadState)
        assertNull(stub1Before.remoteUuid) // Strict invariant: never set remote_uuid on Drive rows!
        assertNull(stub1Before.downloadHref)
        assertNull(stub2Before!!.downloadHref)

        // Device A selects ONLY Book 1 in "Upload EPUB files" and runs bulk upload.
        val candidates = deviceA.epubTransfers.listUploadCandidates(PERM_ID)
        assertEquals(2, candidates.size)
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(book1.url)))
        val uploadSummary = deviceA.epubTransfers.uploadPending()
        assertEquals(1, uploadSummary.uploadedCount)
        assertEquals(1, driveServer.liseurFolders.size)

        // Device B incremental pull via changes.list: Book 1 now has downloadHref, Book 2 still does not.
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val stub1After = deviceB.liseurDb.bookDao().getByUrl("drive:isbn:9780547928227")!!
        val stub2After = deviceB.liseurDb.bookDao().getByUrl("drive:isbn:9780618391110")!!
        assertNotNull(stub1After.downloadHref)
        assertNull(stub2After.downloadHref)

        // Device B downloads Book 1 on demand and verifies SHA-256 before moving into place.
        val downloadOutcome = deviceB.epubTransfers.downloadBook(stub1After.url)
        assertTrue(downloadOutcome is DriveEpubDownloadOutcome.Downloaded)
        val downloadedBook1 = deviceB.liseurDb.bookDao().getByUrl(stub1After.url)!!
        assertEquals(DownloadState.DOWNLOADED, downloadedBook1.downloadState)
        assertNotNull(downloadedBook1.localUri)
        assertEquals(fp1.sha256, deviceB.liseurDb.workIdentityDao().fingerprint(stub1After.url)?.sha256)

        // Device A reads to 42% with exact locator and syncs.
        val exactLocator = """{"href":"ch04.xhtml","locations":{"liseurAnchor":1,"cssSelector":"p#p4","progression":0.5,"totalProgression":0.42},"text":{"highlight":"In a hole"},"type":"application/xhtml+xml"}"""
        deviceA.liseurDb.readingProgressDao().recordLocal(
            bookUrl = book1.url,
            locatorJson = exactLocator,
            progression = 0.42,
            readingSecondsPerPosition = null,
            readingPaceSamples = null,
            readingPaceElapsedMs = null,
            readingPaceEvidence = null,
            status = "Reading",
            updatedAt = 5_000L,
        )
        assertEquals(SyncOutcome.Success, deviceA.positionSync.syncBook(book1.url))

        // Device B pulls on book open: adopts exact locator & 42% progression.
        assertEquals(SyncOutcome.Success, deviceB.positionSync.syncBook(stub1After.url))
        val progressB1 = deviceB.liseurDb.readingProgressDao().get(stub1After.url)!!
        assertEquals(0.42, progressB1.totalProgression!!, 0.0001)
        assertTrue(progressB1.locatorJson.contains("p#p4"))

        // Device B deliberately rereads backward to 18% (backward move!) while Device A is untouched.
        val backwardLocator = """{"href":"ch02.xhtml","locations":{"liseurAnchor":1,"cssSelector":"p#p2","progression":0.2,"totalProgression":0.18},"text":{"highlight":"Roast mutton"},"type":"application/xhtml+xml"}"""
        deviceB.liseurDb.readingProgressDao().recordLocal(
            bookUrl = stub1After.url,
            locatorJson = backwardLocator,
            progression = 0.18,
            readingSecondsPerPosition = null,
            readingPaceSamples = null,
            readingPaceElapsedMs = null,
            readingPaceEvidence = null,
            status = "Reading",
            updatedAt = 6_000L,
        )
        assertEquals(SyncOutcome.Success, deviceB.positionSync.syncBook(stub1After.url))
        assertEquals(SyncOutcome.Success, deviceA.positionSync.syncBook(book1.url))
        val progressA2 = deviceA.liseurDb.readingProgressDao().get(book1.url)!!
        assertEquals(0.18, progressA2.totalProgression!!, 0.0001)
        assertTrue(progressA2.locatorJson.contains("p#p2"))

        // Concurrent offline reading: A reads to 60%, B reads to 55% -> preserved conflict!
        deviceA.liseurDb.readingProgressDao().recordLocal(
            bookUrl = book1.url, locatorJson = """{"href":"ch06.xhtml","locations":{"totalProgression":0.6}}""",
            progression = 0.60, readingSecondsPerPosition = null, readingPaceSamples = null,
            readingPaceElapsedMs = null, readingPaceEvidence = null, status = "Reading", updatedAt = 7_000L,
        )
        deviceB.liseurDb.readingProgressDao().recordLocal(
            bookUrl = stub1After.url, locatorJson = """{"href":"ch05.xhtml","locations":{"totalProgression":0.55}}""",
            progression = 0.55, readingSecondsPerPosition = null, readingPaceSamples = null,
            readingPaceElapsedMs = null, readingPaceEvidence = null, status = "Reading", updatedAt = 7_500L,
        )
        assertEquals(SyncOutcome.Success, deviceA.positionSync.syncBook(book1.url))
        assertEquals(SyncOutcome.Success, deviceB.positionSync.syncBook(stub1After.url))
        val conflict = deviceB.positionSync.preservedConflict(stub1After.url)
        assertNotNull(conflict)
        assertEquals(0.55, conflict!!.local!!, 0.001)
        assertEquals(0.60, conflict.remote!!, 0.001)
        assertEquals(
            ResolveOutcome.Done,
            deviceB.positionSync.takeRemotePosition(stub1After.url, conflict.localRevision!!, expectedAccountKey = conflict.accountKey),
        )
        assertEquals(0.60, deviceB.liseurDb.readingProgressDao().get(stub1After.url)!!.totalProgression!!, 0.001)
    }

    @Test
    fun `different editions share progression without overwriting local edition locator`() = runTest {
        deviceA.connect()
        deviceB.connect()

        val isbn = "9780441172719"
        val shaA = "a".repeat(64)
        val shaB = "b".repeat(64)
        val bookA = Book(
            url = "file:///dune-a.epub", title = "Dune", author = "Frank Herbert",
            coverPath = null, source = null, addedAt = 100L, lastOpenedAt = null,
            localUri = "file:///dune-a.epub", downloadState = DownloadState.DOWNLOADED,
            fileTitle = "Dune", fileAuthor = "Frank Herbert", fileMetadataChecked = true,
        )
        val bookB = bookA.copy(url = "file:///dune-b.epub", localUri = "file:///dune-b.epub")
        deviceA.liseurDb.bookDao().upsert(bookA)
        deviceA.liseurDb.workIdentityDao().upsert(BookFingerprintRow(bookA.url, shaA, "m1", 100L, null, 1L))
        deviceA.liseurDb.bookMetadataDao().upsert(
            BookMetadata.of(bookA.url, MetadataSource.FILE, MetadataSnapshot(identifiers = listOf(Identifier("isbn", isbn)))),
        )
        deviceB.liseurDb.bookDao().upsert(bookB)
        deviceB.liseurDb.workIdentityDao().upsert(BookFingerprintRow(bookB.url, shaB, "m2", 120L, null, 1L))
        deviceB.liseurDb.bookMetadataDao().upsert(
            BookMetadata.of(bookB.url, MetadataSource.FILE, MetadataSnapshot(identifiers = listOf(Identifier("isbn", isbn)))),
        )

        val locatorA = """{"href":"ch08.xhtml","locations":{"liseurAnchor":1,"cssSelector":"p#a","totalProgression":0.65},"text":{"highlight":"Spice"},"type":"application/xhtml+xml"}"""
        deviceA.liseurDb.readingProgressDao().recordLocal(
            bookUrl = bookA.url, locatorJson = locatorA, progression = 0.65,
            readingSecondsPerPosition = null, readingPaceSamples = null, readingPaceElapsedMs = null,
            readingPaceEvidence = null, status = "Reading", updatedAt = 5_000L,
        )
        deviceA.changeEngine.sync()
        deviceB.changeEngine.sync()

        val progressB = deviceB.liseurDb.readingProgressDao().get(bookB.url)!!
        assertEquals(0.65, progressB.totalProgression!!, 0.0001)
        // Edition mismatch MUST NOT copy Device A's locator onto Device B's different edition!
        assertEquals("{}", progressB.locatorJson)
    }

    @Test
    fun `remote stub takes later catalog metadata and never republishes the old values`() = runTest {
        deviceA.connect()
        deviceB.connect()
        val isbn = "9780441172719"
        val bookA = Book(
            url = "file:///dune-a.epub", title = "Dune", author = "Frank Herbert",
            coverPath = null, source = null, addedAt = 100L, lastOpenedAt = null,
            localUri = "file:///dune-a.epub", downloadState = DownloadState.DOWNLOADED,
            fileTitle = "Dune", fileAuthor = "Frank Herbert", fileMetadataChecked = true,
        )
        deviceA.liseurDb.bookDao().upsert(bookA)
        deviceA.liseurDb.workIdentityDao().upsert(BookFingerprintRow(bookA.url, "a".repeat(64), "m1", 100L, null, 1L))
        deviceA.liseurDb.bookMetadataDao().upsert(
            BookMetadata.of(bookA.url, MetadataSource.FILE, MetadataSnapshot(identifiers = listOf(Identifier("isbn", isbn)))),
        )
        deviceA.changeEngine.sync()
        deviceB.changeEngine.sync()
        val stubUrl = "drive:isbn:$isbn"
        assertEquals("Dune", deviceB.liseurDb.bookDao().getByUrl(stubUrl)!!.title)

        val storedA = deviceA.liseurDb.bookDao().getByUrl(bookA.url)!!
        deviceA.liseurDb.bookDao().upsert(storedA.copy(title = "Dune (Deluxe)", fileTitle = "Dune (Deluxe)"))
        deviceA.changeEngine.sync()
        deviceB.changeEngine.sync()
        val stub = deviceB.liseurDb.bookDao().getByUrl(stubUrl)!!
        assertEquals("Dune (Deluxe)", stub.title)
        assertEquals("Dune (Deluxe)", stub.fileTitle)

        // B's next run must not author a catalog record restoring the old title.
        deviceB.changeEngine.sync()
        deviceA.changeEngine.sync()
        val catalogOnA = deviceA.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_CATALOG, "isbn:$isbn")!!
        assertTrue(catalogOnA.bodyJson!!.contains("Dune (Deluxe)"))
        assertEquals("Dune (Deluxe)", deviceA.liseurDb.bookDao().getByUrl(bookA.url)!!.title)
    }

    @Test
    fun `an oversized record stays unpublished while a fitting record publishes, and nothing is lost on retry`() = runTest {
        deviceA.connect()
        suspend fun addCatalogBook(name: String, isbn: String, title: String, shaChar: Char, displayTitle: String = title) {
            val book = Book(
                url = "file:///$name.epub", title = displayTitle, author = "Author",
                coverPath = null, source = null, addedAt = 100L, lastOpenedAt = null,
                localUri = "file:///$name.epub", downloadState = DownloadState.DOWNLOADED,
                fileTitle = title, fileAuthor = "Author", fileMetadataChecked = true,
            )
            deviceA.liseurDb.bookDao().upsert(book)
            deviceA.liseurDb.workIdentityDao().upsert(BookFingerprintRow(book.url, shaChar.toString().repeat(64), "m1", 100L, null, 1L))
            deviceA.liseurDb.bookMetadataDao().upsert(
                BookMetadata.of(book.url, MetadataSource.FILE, MetadataSnapshot(identifiers = listOf(Identifier("isbn", isbn)))),
            )
        }
        addCatalogBook("small", "9780441172719", "Dune", 'a')
        addCatalogBook("huge", "9780765326355", "x".repeat(DriveWireCodec.MAX_BATCH_BYTES + 1_000), 'b', displayTitle = "Huge")

        val first = deviceA.changeEngine.sync()
        assertTrue(first.outcome is SyncOutcome.Partial)
        assertEquals(listOf("catalog:isbn:9780765326355"), first.oversizedRecords)
        val payloads = driveServer.deltaPayloads()
        assertTrue(payloads.isNotEmpty())
        assertTrue(payloads.all { it.size <= DriveWireCodec.MAX_BATCH_BYTES })
        assertTrue(payloads.any { String(it).contains("isbn:9780441172719") })
        assertTrue(payloads.none { String(it).contains("isbn:9780765326355") })
        val states = deviceA.driveDb.recordStates()
        val small = states.get(PERM_ID, DriveWireCodec.DOMAIN_CATALOG, "isbn:9780441172719")!!
        val huge = states.get(PERM_ID, DriveWireCodec.DOMAIN_CATALOG, "isbn:9780765326355")!!
        assertEquals(small.versionFp, small.publishedFp)
        assertNull(huge.publishedFp)
        assertNull(deviceA.driveDb.outbox().inFlight(PERM_ID))
        assertTrue(deviceA.driveDb.accounts().active()!!.lastError!!.contains("too large"))

        val deltasAfterFirst = payloads.size
        val retry = deviceA.changeEngine.sync()
        assertTrue(retry.outcome is SyncOutcome.Partial)
        assertEquals(0, retry.publishedBatches)
        assertEquals(deltasAfterFirst, driveServer.deltaPayloads().size)
        assertNull(states.get(PERM_ID, DriveWireCodec.DOMAIN_CATALOG, "isbn:9780765326355")!!.publishedFp)

        val stored = deviceA.liseurDb.bookDao().getByUrl("file:///huge.epub")!!
        deviceA.liseurDb.bookDao().upsert(stored.copy(title = "Hyperion", fileTitle = "Hyperion"))
        val fixed = deviceA.changeEngine.sync()
        assertEquals(SyncOutcome.Success, fixed.outcome)
        assertTrue(fixed.oversizedRecords.isEmpty())
        assertEquals(deltasAfterFirst + 1, driveServer.deltaPayloads().size)
        val published = states.get(PERM_ID, DriveWireCodec.DOMAIN_CATALOG, "isbn:9780765326355")!!
        assertEquals(published.versionFp, published.publishedFp)
        assertNull(deviceA.driveDb.accounts().active()!!.lastError)
    }

    @Test
    fun `a newer remote catalog deletion keeps this device's queued EPUB trash ids`() = runTest {
        deviceA.connect()
        val key = "isbn:9780441172719"
        val pending = DriveCanonicalJson.encode(mapOf("pendingTrashFileIds" to listOf("file-1")))
        deviceA.driveDb.recordStates().upsert(
            DriveRecordState(
                permissionId = PERM_ID, domain = DriveWireCodec.DOMAIN_CATALOG, recordKey = key,
                versionFp = "local-del", versionOp = DriveRecordState.OP_DEL, publishedFp = null,
                contentFp = null, originDeviceId = null, originSeq = null, ancestorsJson = "[]",
                resRefsJson = "[]", lastHlc = "0017450000000-0001-dev-a", bodyJson = pending,
            ),
        )

        deviceA.catalogSync.applyRemoteRecord(
            PERM_ID,
            DriveWireCodec.delRecord(DriveWireCodec.DOMAIN_CATALOG, key, "0017450000500-0001-dev-b"),
            "dev-b",
            1L,
        ) { deviceA.changeEngine.nextHlc(PERM_ID) }

        val state = deviceA.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_CATALOG, key)!!
        assertEquals("0017450000500-0001-dev-b", state.lastHlc)
        assertEquals(pending, state.bodyJson)
    }

    @Test
    fun `an incoming catalog entry matches a local book whose fingerprint is still missing`() = runTest {
        deviceA.connect()
        deviceB.connect()
        val bytes = "dune bytes".toByteArray()
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val bookA = Book(
            url = "file:///dune-a.epub", title = "Dune", author = "Frank Herbert",
            coverPath = null, source = null, addedAt = 100L, lastOpenedAt = null,
            localUri = "file:///dune-a.epub", downloadState = DownloadState.DOWNLOADED,
            fileTitle = "Dune", fileAuthor = "Frank Herbert", fileMetadataChecked = true,
        )
        deviceA.liseurDb.bookDao().upsert(bookA)
        deviceA.liseurDb.workIdentityDao().upsert(BookFingerprintRow(bookA.url, sha, "m1", 100L, null, 1L))
        deviceA.changeEngine.sync()

        val file = File(tempDir, "dune-b.epub").apply { writeBytes(bytes) }
        val bookB = bookA.copy(url = file.toURI().toString(), localUri = file.toURI().toString())
        deviceB.liseurDb.bookDao().upsert(bookB)
        assertNull(deviceB.liseurDb.workIdentityDao().fingerprint(bookB.url))

        deviceB.changeEngine.sync()

        val urls = deviceB.liseurDb.bookDao().allOnce().map { it.url }
        assertEquals(listOf(bookB.url), urls)
        assertEquals(sha, deviceB.liseurDb.workIdentityDao().fingerprint(bookB.url)!!.sha256)
        val alias = deviceB.liseurDb.workIdentityDao()
            .alias(bookB.url, DriveLibrarySync.accountPeerId(PERM_ID))!!
        assertEquals("sha:$sha", alias.workId)
    }

    @Test
    fun `confirming a match applies remote positions received before the confirmation`() = runTest {
        deviceA.connect()
        deviceB.connect()
        val isbn = "9780441172719"
        val shaB = "b".repeat(64)
        val bookA = Book(
            url = "file:///dune-a.epub", title = "Dune", author = "Frank Herbert",
            coverPath = null, source = null, addedAt = 100L, lastOpenedAt = null,
            localUri = "file:///dune-a.epub", downloadState = DownloadState.DOWNLOADED,
            fileTitle = "Dune", fileAuthor = "Frank Herbert", fileMetadataChecked = true,
        )
        deviceA.liseurDb.bookDao().upsert(bookA)
        deviceA.liseurDb.workIdentityDao().upsert(BookFingerprintRow(bookA.url, "a".repeat(64), "m1", 100L, null, 1L))
        deviceA.liseurDb.bookMetadataDao().upsert(
            BookMetadata.of(bookA.url, MetadataSource.FILE, MetadataSnapshot(identifiers = listOf(Identifier("isbn", isbn)))),
        )
        // Device B's copy carries no identifier, so it is keyed by its own edition sha.
        val bookB = bookA.copy(url = "file:///dune-b.epub", localUri = "file:///dune-b.epub")
        deviceB.liseurDb.bookDao().upsert(bookB)
        deviceB.liseurDb.workIdentityDao().upsert(BookFingerprintRow(bookB.url, shaB, "m2", 120L, null, 1L))

        deviceA.liseurDb.readingProgressDao().recordLocal(
            bookUrl = bookA.url, locatorJson = """{"href":"ch08.xhtml","locations":{"totalProgression":0.65}}""",
            progression = 0.65, readingSecondsPerPosition = null, readingPaceSamples = null,
            readingPaceElapsedMs = null, readingPaceEvidence = null, status = "Reading", updatedAt = 5_000L,
        )
        deviceA.changeEngine.sync()
        deviceB.changeEngine.sync()
        assertNull(deviceB.liseurDb.readingProgressDao().get(bookB.url)?.totalProgression)

        deviceB.workIdentity.confirmMerge(
            permissionId = PERM_ID,
            bookUrl = bookB.url,
            keyA = "isbn:$isbn",
            keyB = "sha:$shaB",
            hlc = "0000000009000-0000-dev-b",
        )
        // No new remote edit: the next run alone must catch the matched book up.
        deviceB.changeEngine.sync()
        assertEquals(0.65, deviceB.liseurDb.readingProgressDao().get(bookB.url)!!.totalProgression!!, 0.0001)
    }

    @Test
    fun `a token refreshed after 401 for a different account never carries this account's upload queue`() = runTest {
        deviceA.connect()
        val epubBytes = "EPUB-ACCOUNT-SWITCH-DURING-401".toByteArray()
        val bookFile = File(tempDir, "switch.epub").apply { writeBytes(epubBytes) }
        val fp = BookFingerprints.of(ByteArrayInputStream(epubBytes))
        val book = Book(
            url = bookFile.toURI().toString(), title = "Switch Book", author = "Author",
            coverPath = null, source = null, addedAt = 1_000L, lastOpenedAt = null,
            localUri = bookFile.toURI().toString(), downloadState = DownloadState.DOWNLOADED,
            sizeBytes = fp.size, fileTitle = "Switch Book", fileAuthor = "Author", fileMetadataChecked = true,
        )
        deviceA.liseurDb.bookDao().upsert(book)
        deviceA.liseurDb.workIdentityDao().upsert(BookFingerprintRow(book.url, fp.sha256, fp.partialMd5, fp.size, bookFile.lastModified(), 1L))
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(book.url)))

        var calls = 0
        val switching = DriveEpubTransferManager(
            context = context,
            database = deviceA.liseurDb,
            store = deviceA.driveDb,
            driveApi = driveServer,
            workIdentity = deviceA.workIdentity,
            catalogSync = deviceA.catalogSync,
            fingerprintStore = BookFingerprintStore(context, deviceA.liseurDb.workIdentityDao()),
            nextHlc = { perm -> deviceA.changeEngine.nextHlc(perm) },
            acquireToken = {
                calls++
                if (calls == 1) {
                    SilentTokenResult.Token("token-ok", PERM_ID)
                } else {
                    SilentTokenResult.Token("token-other", "perm-other-account")
                }
            },
            booksDirProvider = { deviceA.booksDir.apply { mkdirs() } },
        )
        driveServer.nextApiException = DriveAuthExpiredException("Token expired")

        val summary = switching.uploadPending()

        assertEquals(0, summary.uploadedCount)
        assertEquals(2, calls)
        assertTrue(driveServer.liseurFolders.isEmpty())
        assertTrue(deviceA.driveDb.uploads().get(PERM_ID, fp.sha256)!!.state != DriveUpload.STATE_UPLOADED)
    }

    /**
     * A switch away and back drops the picker answer but keeps the queue: what is
     * still waiting there goes up only once the new answer has reconciled it, even
     * in a worker that starts after the process restarted.
     */
    @Test
    fun `a queue kept across an account switch waits for the new picker answer`() = runTest {
        deviceA.connect()
        val epubBytes = "EPUB-SWITCH-BACK-QUEUE".toByteArray()
        val bookFile = File(tempDir, "switch-back.epub").apply { writeBytes(epubBytes) }
        val fp = BookFingerprints.of(ByteArrayInputStream(epubBytes))
        val book = Book(
            url = bookFile.toURI().toString(), title = "Switch Back", author = "Author",
            coverPath = null, source = null, addedAt = 1_000L, lastOpenedAt = null,
            localUri = bookFile.toURI().toString(), downloadState = DownloadState.DOWNLOADED,
            sizeBytes = fp.size, fileTitle = "Switch Back", fileAuthor = "Author", fileMetadataChecked = true,
        )
        deviceA.liseurDb.bookDao().upsert(book)
        deviceA.liseurDb.workIdentityDao().upsert(BookFingerprintRow(book.url, fp.sha256, fp.partialMd5, fp.size, bookFile.lastModified(), 1L))
        assertEquals(setOf(book.url), deviceA.epubTransfers.queueBooks(PERM_ID, listOf(book.url)))

        // The queue was written under A's previous answer, before this activation.
        val kept = requireNotNull(deviceA.driveDb.uploads().get(PERM_ID, fp.sha256))
        deviceA.driveDb.uploads().upsert(kept.copy(selectedAt = 1L, updatedAt = 1L))

        val switchedAt = 2_000L
        deviceA.driveSync.activateAccount("perm-b", "b@example.com", now = switchedAt)
        deviceA.driveSync.activateAccount(PERM_ID, "reader@example.com", now = switchedAt + 1_000L)
        assertNull(deviceA.driveDb.accounts().get(PERM_ID)?.uploadSelectionAt)

        assertEquals(0, deviceA.epubTransfers.uploadPending().uploadedCount)
        assertTrue(driveServer.liseurFolders.isEmpty())
        assertEquals(DriveUpload.STATE_PENDING, deviceA.driveDb.uploads().get(PERM_ID, fp.sha256)!!.state)

        // A book the reader asks for by hand is consent under this activation too.
        val askedBytes = "EPUB-ASKED-BY-HAND".toByteArray()
        val askedFile = File(tempDir, "asked.epub").apply { writeBytes(askedBytes) }
        val askedFp = BookFingerprints.of(ByteArrayInputStream(askedBytes))
        val asked = book.copy(
            url = askedFile.toURI().toString(),
            title = "Asked",
            localUri = askedFile.toURI().toString(),
            sizeBytes = askedFp.size,
        )
        deviceA.liseurDb.bookDao().upsert(asked)
        deviceA.liseurDb.workIdentityDao().upsert(
            BookFingerprintRow(asked.url, askedFp.sha256, askedFp.partialMd5, askedFp.size, askedFile.lastModified(), 1L),
        )
        assertEquals(setOf(asked.url), deviceA.epubTransfers.queueBooks(PERM_ID, listOf(asked.url)))
        assertEquals(1, deviceA.epubTransfers.uploadPending().uploadedCount)
        assertEquals(DriveUpload.STATE_UPLOADED, deviceA.driveDb.uploads().get(PERM_ID, askedFp.sha256)!!.state)
        assertEquals(DriveUpload.STATE_PENDING, deviceA.driveDb.uploads().get(PERM_ID, fp.sha256)!!.state)

        // The picker answer reconciles the kept queue with the new selection.
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(book.url), switchedAt + 2_000L))
        assertEquals(1, deviceA.epubTransfers.uploadPending().uploadedCount)
        assertEquals(DriveUpload.STATE_UPLOADED, deviceA.driveDb.uploads().get(PERM_ID, fp.sha256)!!.state)
    }

    /**
     * An edition asked for by hand is consent under the activation it was asked in, even
     * while cleanup still owes the account the cancelled upload of its bytes.
     */
    @Test
    fun `an edition asked for by hand after a switch uploads with its cleanup still pending`() = runTest {
        deviceA.connect()
        val epubBytes = "EPUB-ASKED-AFTER-SWITCH".toByteArray()
        val bookFile = File(tempDir, "asked-after-switch.epub").apply { writeBytes(epubBytes) }
        val fp = BookFingerprints.of(ByteArrayInputStream(epubBytes))
        val book = Book(
            url = bookFile.toURI().toString(), title = "Asked After Switch", author = "Author",
            coverPath = null, source = null, addedAt = 1_000L, lastOpenedAt = null,
            localUri = bookFile.toURI().toString(), downloadState = DownloadState.DOWNLOADED,
            sizeBytes = fp.size, fileTitle = "Asked After Switch", fileAuthor = "Author", fileMetadataChecked = true,
        )
        deviceA.liseurDb.bookDao().upsert(book)
        deviceA.liseurDb.workIdentityDao().upsert(
            BookFingerprintRow(book.url, fp.sha256, fp.partialMd5, fp.size, bookFile.lastModified(), 1L),
        )
        // Cleanup still owes the account the cancelled upload of these bytes.
        deviceA.driveDb.uploads().upsert(
            DriveUpload(
                permissionId = PERM_ID,
                sha256 = fp.sha256,
                bookUrl = book.url,
                sizeBytes = fp.size,
                state = DriveUpload.STATE_CANCELLED,
                selectedAt = 1L,
                updatedAt = 1L,
            ),
        )

        val switchedAt = 2_000L
        deviceA.driveSync.activateAccount("perm-b", "b@example.com", now = switchedAt)
        deviceA.driveSync.activateAccount(PERM_ID, "reader@example.com", now = switchedAt + 1_000L)
        assertNull(deviceA.driveDb.accounts().get(PERM_ID)?.uploadSelectionAt)

        // Asking for it by hand selects it under this activation; cleanup keeps its obligation.
        assertEquals(setOf(book.url), deviceA.epubTransfers.queueBooks(PERM_ID, listOf(book.url)))
        val requeued = deviceA.driveDb.uploads().get(PERM_ID, fp.sha256)!!
        assertEquals(DriveUpload.STATE_REQUEUE, requeued.state)
        assertTrue(requeued.selectedAt >= switchedAt + 1_000L)

        // Cleanup turns the obligation into a fresh upload, which the missing answer no longer holds.
        assertEquals(1, deviceA.epubTransfers.uploadPending().uploadedCount)
        assertEquals(DriveUpload.STATE_UPLOADED, deviceA.driveDb.uploads().get(PERM_ID, fp.sha256)!!.state)
    }

    @Test
    fun `an unpublished local archive survives a newer remote availability update`() = runTest {
        deviceA.connect()
        deviceB.connect()
        val epubBytes = "EPUB-ARCHIVE-VS-AVAILABILITY".toByteArray()
        val bookFile = File(tempDir, "archive.epub").apply { writeBytes(epubBytes) }
        val fp = BookFingerprints.of(ByteArrayInputStream(epubBytes))
        val book = Book(
            url = bookFile.toURI().toString(), title = "Archive Book", author = "Author",
            coverPath = null, source = null, addedAt = 1_000L, lastOpenedAt = null,
            localUri = bookFile.toURI().toString(), downloadState = DownloadState.DOWNLOADED,
            sizeBytes = fp.size, fileTitle = "Archive Book", fileAuthor = "Author", fileMetadataChecked = true,
        )
        deviceA.liseurDb.bookDao().upsert(book)
        deviceA.liseurDb.workIdentityDao().upsert(BookFingerprintRow(book.url, fp.sha256, fp.partialMd5, fp.size, bookFile.lastModified(), 1L))
        deviceA.liseurDb.bookMetadataDao().upsert(
            BookMetadata.of(book.url, MetadataSource.FILE, MetadataSnapshot(identifiers = listOf(Identifier("isbn", "9780441172757")))),
        )
        deviceA.changeEngine.sync()
        deviceB.changeEngine.sync()
        val stubUrl = "drive:isbn:9780441172757"
        assertNull(deviceB.liseurDb.bookDao().getByUrl(stubUrl)!!.archivedAt)

        // B archives offline; A then uploads the EPUB, publishing a newer catalog record.
        deviceB.liseurDb.bookDao().setArchived(stubUrl, 9_000L)
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(book.url)))
        assertEquals(1, deviceA.epubTransfers.uploadPending().uploadedCount)

        deviceB.changeEngine.sync()
        val stub = deviceB.liseurDb.bookDao().getByUrl(stubUrl)!!
        assertNotNull(stub.archivedAt)
        assertNotNull(stub.downloadHref)
        deviceA.changeEngine.sync()
        assertNotNull(deviceA.liseurDb.bookDao().getByUrl(book.url)!!.archivedAt)
    }

    @Test
    fun `a collected but unpublished archive survives a newer remote catalog record`() = runTest {
        deviceA.connect()
        deviceB.connect()
        val epubBytes = "EPUB-ARCHIVE-COLLECTED-NOT-PUBLISHED".toByteArray()
        val bookFile = File(tempDir, "archive-collected.epub").apply { writeBytes(epubBytes) }
        val fp = BookFingerprints.of(ByteArrayInputStream(epubBytes))
        val book = Book(
            url = bookFile.toURI().toString(), title = "Archive Two", author = "Author",
            coverPath = null, source = null, addedAt = 1_000L, lastOpenedAt = null,
            localUri = bookFile.toURI().toString(), downloadState = DownloadState.DOWNLOADED,
            sizeBytes = fp.size, fileTitle = "Archive Two", fileAuthor = "Author", fileMetadataChecked = true,
        )
        deviceA.liseurDb.bookDao().upsert(book)
        deviceA.liseurDb.workIdentityDao().upsert(BookFingerprintRow(book.url, fp.sha256, fp.partialMd5, fp.size, bookFile.lastModified(), 1L))
        deviceA.liseurDb.bookMetadataDao().upsert(
            BookMetadata.of(book.url, MetadataSource.FILE, MetadataSnapshot(identifiers = listOf(Identifier("isbn", "9780441172764")))),
        )
        deviceA.changeEngine.sync()
        deviceB.changeEngine.sync()
        val stubUrl = "drive:isbn:9780441172764"

        // B archives and its catalog collector authors the new version, but the upload never happens.
        deviceB.liseurDb.bookDao().setArchived(stubUrl, 9_000L)
        val collected = deviceB.catalogSync.collectDirtyRecords(PERM_ID) { deviceB.changeEngine.nextHlc(PERM_ID) }
        assertEquals(1, collected.size)

        // A publishes a newer catalog record that still carries "not archived".
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(book.url)))
        assertEquals(1, deviceA.epubTransfers.uploadPending().uploadedCount)

        deviceB.changeEngine.sync()
        assertNotNull(deviceB.liseurDb.bookDao().getByUrl(stubUrl)!!.archivedAt)
        deviceA.changeEngine.sync()
        assertNotNull(deviceA.liseurDb.bookDao().getByUrl(book.url)!!.archivedAt)
    }

    @Test
    fun `two local editions of one work publish one position record and ack only the edition that speaks for it`() = runTest {
        deviceA.connect()
        val isbn = "9780547928227"
        fun edition(name: String, addedAt: Long) = Book(
            url = File(tempDir, name).toURI().toString(),
            title = "The Hobbit",
            author = "J.R.R. Tolkien",
            coverPath = null,
            source = null,
            addedAt = addedAt,
            lastOpenedAt = null,
            localUri = File(tempDir, name).toURI().toString(),
            downloadState = DownloadState.DOWNLOADED,
            fileTitle = "The Hobbit",
            fileAuthor = "J.R.R. Tolkien",
            fileMetadataChecked = true,
            fileModifiedAt = addedAt / 1_000L,
            workId = "urn:isbn:$isbn",
        )
        val older = edition("hobbit-older.epub", 1_000L)
        val newer = edition("hobbit-newer.epub", 2_000L)
        deviceA.liseurDb.bookDao().upsertAll(listOf(older, newer))
        deviceA.liseurDb.workIdentityDao().upsert(BookFingerprintRow(older.url, "1".repeat(64), "md5-older", 10L, 1L, 1L))
        deviceA.liseurDb.workIdentityDao().upsert(BookFingerprintRow(newer.url, "2".repeat(64), "md5-newer", 10L, 2L, 1L))
        deviceA.liseurDb.readingProgressDao().recordLocal(
            bookUrl = older.url,
            locatorJson = "{\"href\":\"ch01.xhtml\"}",
            progression = 0.25,
            readingSecondsPerPosition = null,
            readingPaceSamples = null,
            readingPaceElapsedMs = null,
            readingPaceEvidence = null,
            status = "Reading",
            updatedAt = 5_000L,
        )
        deviceA.liseurDb.readingProgressDao().recordLocal(
            bookUrl = newer.url,
            locatorJson = "{\"href\":\"ch09.xhtml\"}",
            progression = 0.75,
            readingSecondsPerPosition = null,
            readingPaceSamples = null,
            readingPaceElapsedMs = null,
            readingPaceEvidence = null,
            status = "Reading",
            updatedAt = 9_000L,
        )

        var hlc = 0
        // A book-scoped sync of the losing edition must not publish into the shared slot.
        val scopedLoser = deviceA.positionSync.collectDirtyRecords(PERM_ID, "dev-a", bookUrlFilter = older.url) {
            "00000000${hlc++}-0000-dev-a"
        }
        assertEquals(emptyList<String>(), scopedLoser.records.filter { it.d == DriveWireCodec.DOMAIN_POSITION }.map { it.k })
        assertEquals(emptyList<String>(), scopedLoser.positionAcks.map { it.bookUrl })

        val bundle = deviceA.positionSync.collectDirtyRecords(PERM_ID, "dev-a") {
            "00000000${hlc++}-0000-dev-a"
        }

        val positions = bundle.records.filter { it.d == DriveWireCodec.DOMAIN_POSITION }
        assertEquals(1, positions.size)
        assertEquals("isbn:$isbn/dev-a", positions.single().k)
        val body = requireNotNull(DriveWireCodec.decodePositionBody(requireNotNull(positions.single().body)))
        assertEquals(0.75, requireNotNull(body.totalProgression), 0.0001)
        // Only the edition the record represents may be acknowledged; the other stays dirty.
        assertEquals(listOf(newer.url), bundle.positionAcks.map { it.bookUrl })

        deviceA.positionSync.acknowledgePublished(PERM_ID, bundle.records, bundle.positionAcks, bundle.peakAcks)
        val second = deviceA.positionSync.collectDirtyRecords(PERM_ID, "dev-a") {
            "00000000${hlc++}-0000-dev-a"
        }
        // The un-acked older edition must not overwrite the winning shared position.
        assertEquals(emptyList<String>(), second.records.filter { it.d == DriveWireCodec.DOMAIN_POSITION }.map { it.k })
        assertEquals(emptyList<String>(), second.positionAcks.map { it.bookUrl })

        val scopedAfterAck = deviceA.positionSync.collectDirtyRecords(PERM_ID, "dev-a", bookUrlFilter = older.url) {
            "00000000${hlc++}-0000-dev-a"
        }
        assertEquals(emptyList<String>(), scopedAfterAck.records.filter { it.d == DriveWireCodec.DOMAIN_POSITION }.map { it.k })
    }

    @Test
    fun `seeding applies stored positions oldest first whatever their record-key order`() = runTest {
        deviceB.connect()
        val shaB = "b".repeat(64)
        val bookB = Book(
            url = "file:///seed-b.epub", title = "Seed", author = "Author",
            coverPath = null, source = null, addedAt = 100L, lastOpenedAt = null,
            localUri = "file:///seed-b.epub", downloadState = DownloadState.DOWNLOADED,
            fileTitle = "Seed", fileAuthor = "Author", fileMetadataChecked = true,
        )
        deviceB.liseurDb.bookDao().upsert(bookB)
        deviceB.liseurDb.workIdentityDao().upsert(BookFingerprintRow(bookB.url, shaB, "m2", 120L, null, 1L))
        val workKey = requireNotNull(deviceB.workIdentity.resolveBook(PERM_ID, bookB)).workId

        fun stored(device: String, progression: Double, hlc: String): DriveRecordState {
            val body = DriveWireCodec.encodePositionBody(
                DrivePositionBody(
                    workKey = workKey, deviceId = device, editionSha = null,
                    locatorJson = "{}", totalProgression = progression, status = "Reading",
                    finishedOverride = "", coverage = null, unreadResetAt = null, readAt = null, updatedAt = 1L,
                ),
            )
            val fp = DriveCanonicalJson.contentFp(body)
            return DriveRecordState(
                permissionId = PERM_ID, domain = DriveWireCodec.DOMAIN_POSITION,
                recordKey = DrivePositionSync.positionRecordKey(workKey, device),
                versionFp = fp, versionOp = DriveRecordState.OP_PUT, publishedFp = fp, contentFp = fp,
                originDeviceId = device, originSeq = 1L, ancestorsJson = "[]", resRefsJson = "[]",
                lastHlc = hlc, bodyJson = DriveCanonicalJson.encode(body),
            )
        }
        // Record keys sort dev-a1 before dev-z9, the opposite of reading chronology.
        deviceB.driveDb.recordStates().upsert(stored("dev-a1", 0.65, "0000000009000-0000-dev-a1"))
        deviceB.driveDb.recordStates().upsert(stored("dev-z9", 0.20, "0000000005000-0000-dev-z9"))

        deviceB.positionSync.seedUnseededAliases(PERM_ID, "dev-b")

        assertEquals(0.20, deviceB.liseurDb.readingProgressDao().get(bookB.url)!!.totalProgression!!, 0.0001)
        assertEquals(0.65, deviceB.positionSync.preservedConflict(bookB.url)!!.remote!!, 0.0001)
        assertTrue(deviceB.liseurDb.workIdentityDao().alias(bookB.url, DriveLibrarySync.accountPeerId(PERM_ID))!!.seeded)
    }

    @Test
    fun `furthest peaks and resets propagate across devices and suppress stale peaks`() = runTest {
        deviceA.connect()
        deviceB.connect()

        val sha = "c".repeat(64)
        val bookA = Book(
            url = "file:///peak-a.epub", title = "Peak Book", author = "Author",
            coverPath = null, source = null, addedAt = 100L, lastOpenedAt = null,
            localUri = "file:///peak-a.epub", downloadState = DownloadState.DOWNLOADED,
            fileTitle = "Peak Book", fileAuthor = "Author", fileMetadataChecked = true,
        )
        deviceA.liseurDb.bookDao().upsert(bookA)
        deviceA.liseurDb.workIdentityDao().upsert(BookFingerprintRow(bookA.url, sha, "m1", 100L, null, 1L))
        // Read to 80%, then turn back to 20% -> peak stays at 80%
        deviceA.liseurDb.readingProgressDao().recordLocal(
            bookUrl = bookA.url, locatorJson = """{"href":"ch8.xhtml"}""", progression = 0.80,
            readingSecondsPerPosition = null, readingPaceSamples = null, readingPaceElapsedMs = null,
            readingPaceEvidence = null, status = "Reading", updatedAt = 1_000L,
        )
        deviceA.liseurDb.readingProgressDao().recordLocal(
            bookUrl = bookA.url, locatorJson = """{"href":"ch2.xhtml"}""", progression = 0.20,
            readingSecondsPerPosition = null, readingPaceSamples = null, readingPaceElapsedMs = null,
            readingPaceEvidence = null, status = "Reading", updatedAt = 2_000L,
        )

        deviceA.changeEngine.sync()
        deviceB.changeEngine.sync()

        val stubUrl = "drive:sha:$sha"
        val peerId = DriveLibrarySync.accountPeerId(PERM_ID)
        val observedOnB = deviceB.liseurDb.furthestPositionDao().forWork(peerId, "sha:$sha")
        assertEquals(1, observedOnB.size)
        assertEquals(0.80, observedOnB.single().progression, 0.0001)
        assertEquals("dev-a", observedOnB.single().origin)

        // Device B forgets furthest position -> emits reset record with floors
        val preview = deviceB.positionSync.previewKnownBook(stubUrl)
        assertTrue(preview is com.chmouel.liseur.data.remote.PreviewOutcome.Ready)
        val dest = (preview as com.chmouel.liseur.data.remote.PreviewOutcome.Ready).preview.furthest!!
        assertEquals(ResolveOutcome.Done, deviceB.positionSync.forgetFurthestPosition(stubUrl, dest, uptoSeq = 10L))
        assertTrue(deviceB.liseurDb.furthestPositionDao().forWork(peerId, "sha:$sha").isEmpty())

        // Publish reset from B and pull on A -> A's local peak resets and floor suppresses stale peaks
        deviceB.changeEngine.sync()
        deviceA.changeEngine.sync()
        assertEquals(0.20, deviceA.liseurDb.readingProgressDao().get(bookA.url)!!.peakProgression!!, 0.0001)
        assertTrue(deviceA.positionSync.getPersistedFloor(PERM_ID, "sha:$sha", "dev-a") >= 1L)
    }

    private fun peakBook(name: String, isbn: String? = null) = Book(
        url = "file:///$name.epub", title = "The Hobbit", author = "J.R.R. Tolkien",
        coverPath = null, source = null, addedAt = 100L, lastOpenedAt = null,
        localUri = "file:///$name.epub", downloadState = DownloadState.DOWNLOADED,
        fileTitle = "The Hobbit", fileAuthor = "J.R.R. Tolkien", fileMetadataChecked = true,
        workId = isbn?.let { "urn:isbn:$it" },
    )

    private suspend fun DeviceHarness.addBook(book: Book, sha: String) {
        liseurDb.bookDao().upsert(book)
        liseurDb.workIdentityDao().upsert(BookFingerprintRow(book.url, sha, "md5-$sha", 10L, book.fileModifiedAt, 1L))
    }

    private suspend fun DeviceHarness.read(bookUrl: String, progression: Double, at: Long) {
        liseurDb.readingProgressDao().recordLocal(
            bookUrl = bookUrl, locatorJson = """{"href":"p$at.xhtml"}""", progression = progression,
            readingSecondsPerPosition = null, readingPaceSamples = null, readingPaceElapsedMs = null,
            readingPaceEvidence = null, status = "Reading", updatedAt = at,
        )
    }

    private suspend fun DeviceHarness.forget(bookUrl: String): ResolveOutcome {
        val ready = positionSync.previewKnownBook(bookUrl) as com.chmouel.liseur.data.remote.PreviewOutcome.Ready
        return positionSync.forgetFurthestPosition(bookUrl, ready.preview.furthest!!, uptoSeq = 0L)
    }

    private suspend fun DeviceHarness.observed(workKey: String) =
        liseurDb.furthestPositionDao().forWork(DriveLibrarySync.accountPeerId(PERM_ID), workKey)

    private fun DriveWireRecord.peakSeq(): Long =
        (DriveWireCodec.decodeFurthestBody(requireNotNull(body)) as DriveFurthestBody.Peak).seq

    private suspend fun DeviceHarness.collectPeaks(): DrivePositionSync.OutboundBundle {
        var n = 0
        return positionSync.collectDirtyRecords(PERM_ID, deviceId) { "0000000${n++}-0000-$deviceId" }
    }

    @Test
    fun `acknowledging our upload does not hide a concurrent remote position`() = runTest {
        deviceA.connect()
        deviceB.connect()
        val bookA = peakBook("concurrent-a", "9780547928227")
        val bookB = peakBook("concurrent-b", "9780547928227")
        val sha = "a".repeat(64)
        deviceA.addBook(bookA, sha)
        deviceB.addBook(bookB, sha)

        deviceA.read(bookA.url, 0.4, 1_000L)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)

        deviceA.read(bookA.url, 0.6, 2_000L)
        deviceB.read(bookB.url, 0.7, 2_500L)
        val remote = deviceA.positionSync.collectDirtyRecords(PERM_ID, deviceA.deviceId) { "9999999999999-0000-dev-a" }
        val local = deviceB.positionSync.collectDirtyRecords(PERM_ID, deviceB.deviceId) { "9999999999999-0000-dev-b" }
        deviceB.positionSync.acknowledgePublished(PERM_ID, local.records, local.positionAcks, local.peakAcks)
        val remotePosition = remote.records.single { it.d == DriveWireCodec.DOMAIN_POSITION }
        deviceB.positionSync.applyRemotePosition(PERM_ID, deviceB.deviceId, remotePosition, deviceA.deviceId, 10L)

        val conflict = deviceB.positionSync.preservedConflict(bookB.url)
        assertNotNull(conflict)
        assertEquals(0.7, conflict!!.local!!, 0.0001)
        assertEquals(0.6, conflict.remote!!, 0.0001)
    }

    @Test
    fun `an agreeing pending position does not hide another writer's conflict`() = runTest {
        deviceB.connect()
        val book = peakBook("pending-writers", "9780547928227")
        deviceB.addBook(book, "a".repeat(64))
        deviceB.read(book.url, 0.4, 1_000L)
        deviceB.workIdentity.resolveBook(PERM_ID, book)
        val account = DriveLibrarySync.accountPeerId(PERM_ID)
        val peers = deviceB.liseurDb.syncPeerStateDao()
        peers.persistPending(book.url, "$account/dev-a", 0.4, "Reading", 1_000L)
        peers.persistPending(book.url, "$account/dev-c", 0.8, "Reading", 2_000L)

        val conflict = deviceB.positionSync.preservedConflict(book.url)!!
        assertEquals(0.8, conflict.remote!!, 0.0001)
        assertEquals("$account/dev-c", conflict.accountKey)
        val preview = deviceB.positionSync.previewKnownBook(book.url) as com.chmouel.liseur.data.remote.PreviewOutcome.Ready
        assertEquals(conflict.remote, preview.preview.remote)
    }

    @Test
    fun `older position from the same writer cannot win through a merged work key`() = runTest {
        deviceB.connect()
        val sha = "a".repeat(64)
        val isbn = "9780547928227"
        val book = peakBook("merged-writer", isbn)
        deviceB.addBook(book, sha)
        deviceB.workIdentity.resolveBook(PERM_ID, book)
        deviceB.workIdentity.confirmMerge(PERM_ID, book.url, "isbn:$isbn", "sha:$sha", "0000000001000-0000-dev-b")

        fun record(work: String, position: Double, at: Long): DriveWireRecord {
            val body = DrivePositionBody(
                workKey = work, deviceId = "dev-a", editionSha = null, locatorJson = "{}",
                totalProgression = position, status = "Reading", finishedOverride = "automatic",
                coverage = null, unreadResetAt = null, readAt = at, updatedAt = at,
            )
            return DriveWireCodec.putRecord(
                DriveWireCodec.DOMAIN_POSITION, "$work/dev-a", String.format(java.util.Locale.ROOT, "%013d-0000-dev-a", at),
                DriveWireCodec.encodePositionBody(body),
            )
        }

        deviceB.positionSync.applyRemotePosition(PERM_ID, "dev-b", record("isbn:$isbn", 0.8, 2_000L), "dev-a", 2L)
        deviceB.positionSync.applyRemotePosition(PERM_ID, "dev-b", record("sha:$sha", 0.2, 1_000L), "dev-a", 1L)
        assertEquals(0.8, deviceB.liseurDb.readingProgressDao().get(book.url)!!.totalProgression!!, 0.0001)
    }

    @Test
    fun `another writer cannot move the local position while a conflict is waiting`() = runTest {
        deviceB.connect()
        val book = peakBook("pending-pull", "9780547928227")
        deviceB.addBook(book, "a".repeat(64))
        deviceB.read(book.url, 0.4, 1_000L)
        val alias = requireNotNull(deviceB.workIdentity.resolveBook(PERM_ID, book))
        val account = DriveLibrarySync.accountPeerId(PERM_ID)
        val peers = deviceB.liseurDb.syncPeerStateDao()
        val revision = deviceB.liseurDb.readingProgressDao().get(book.url)!!.localRevision
        peers.persistPending(book.url, "$account/dev-a", 0.6, "Reading", 1_500L)
        peers.settle(book.url, "$account/dev-c", revision, 0.4, "Reading", 1_000L)
        val body = DrivePositionBody(
            workKey = alias.workId, deviceId = "dev-c", editionSha = null, locatorJson = "{}",
            totalProgression = 0.8, status = "Reading", finishedOverride = "automatic",
            coverage = null, unreadResetAt = null, readAt = 2_000L, updatedAt = 2_000L,
        )
        val record = DriveWireCodec.putRecord(
            DriveWireCodec.DOMAIN_POSITION, "${alias.workId}/dev-c", "0000000002000-0000-dev-c",
            DriveWireCodec.encodePositionBody(body),
        )
        deviceB.positionSync.applyRemotePosition(PERM_ID, "dev-b", record, "dev-c", 1L)

        assertEquals(0.4, deviceB.liseurDb.readingProgressDao().get(book.url)!!.totalProgression!!, 0.0001)
        assertEquals(0.6, peers.get(book.url, "$account/dev-a")!!.pendingProgression!!, 0.0001)
        assertEquals(0.8, peers.get(book.url, "$account/dev-c")!!.pendingProgression!!, 0.0001)
    }

    @Test
    fun `an unread reset cannot move the local position while a conflict is waiting`() = runTest {
        deviceB.connect()
        val book = peakBook("pending-reset", "9780547928227")
        deviceB.addBook(book, "a".repeat(64))
        deviceB.read(book.url, 0.4, 1_000L)
        val alias = requireNotNull(deviceB.workIdentity.resolveBook(PERM_ID, book))
        val account = DriveLibrarySync.accountPeerId(PERM_ID)
        val peers = deviceB.liseurDb.syncPeerStateDao()
        val revision = deviceB.liseurDb.readingProgressDao().get(book.url)!!.localRevision
        peers.settlePositionRevision(book.url, account, revision)
        peers.persistPending(book.url, "$account/dev-a", 0.6, "Reading", 1_500L)
        peers.settle(book.url, "$account/dev-c", revision, 0.4, "Reading", 1_000L)
        val body = DrivePositionBody(
            workKey = alias.workId, deviceId = "dev-c", editionSha = null, locatorJson = "{}",
            totalProgression = 0.0, status = "ReadyToRead", finishedOverride = "unread",
            coverage = null, unreadResetAt = 2_000L, readAt = 2_000L, updatedAt = 2_000L,
        )
        val record = DriveWireCodec.putRecord(
            DriveWireCodec.DOMAIN_POSITION, "${alias.workId}/dev-c", "0000000002000-0000-dev-c",
            DriveWireCodec.encodePositionBody(body),
        )
        deviceB.positionSync.applyRemotePosition(PERM_ID, "dev-b", record, "dev-c", 1L)

        assertEquals(0.4, deviceB.liseurDb.readingProgressDao().get(book.url)!!.totalProgression!!, 0.0001)
        assertEquals(0.6, peers.get(book.url, "$account/dev-a")!!.pendingProgression!!, 0.0001)
        assertEquals(0.0, peers.get(book.url, "$account/dev-c")!!.pendingProgression!!, 0.0001)
    }

    @Test
    fun `choosing furthest does not clear a newly arrived writer conflict`() = runTest {
        deviceB.connect()
        val book = peakBook("pending-furthest", "9780547928227")
        deviceB.addBook(book, "a".repeat(64))
        deviceB.read(book.url, 0.4, 1_000L)
        val alias = requireNotNull(deviceB.workIdentity.resolveBook(PERM_ID, book))
        val account = DriveLibrarySync.accountPeerId(PERM_ID)
        val peers = deviceB.liseurDb.syncPeerStateDao()
        peers.persistPending(book.url, "$account/dev-c", 0.6, "Reading", 1_500L)
        deviceB.liseurDb.furthestPositionDao().observe(
            com.chmouel.liseur.data.db.FurthestPosition(account, alias.workId, "", "dev-c", 0.8, 1L, "{}"),
        )
        val preview = (deviceB.positionSync.previewKnownBook(book.url) as com.chmouel.liseur.data.remote.PreviewOutcome.Ready).preview
        peers.persistPending(book.url, "$account/dev-a", 0.7, "Reading", 2_000L)

        assertEquals(ResolveOutcome.Done, deviceB.positionSync.takeFurthestPosition(book.url, preview.localRevision!!, preview.furthest!!))
        assertTrue(peers.get(book.url, "$account/dev-a")!!.hasPending)
        assertTrue(peers.get(book.url, "$account/dev-c")!!.hasPending)
    }

    @Test
    fun `choosing a matching furthest position settles it before a backward move`() = runTest {
        deviceB.connect()
        val book = peakBook("settled-furthest", "9780547928227")
        deviceB.addBook(book, "a".repeat(64))
        deviceB.read(book.url, 0.4, 1_000L)
        val alias = requireNotNull(deviceB.workIdentity.resolveBook(PERM_ID, book))
        val account = DriveLibrarySync.accountPeerId(PERM_ID)
        val peers = deviceB.liseurDb.syncPeerStateDao()
        peers.persistPending(book.url, "$account/dev-a", 0.8, "Reading", 1_500L)
        peers.persistPending(book.url, "$account/dev-c", 0.4, "Reading", 1_500L)
        deviceB.liseurDb.furthestPositionDao().observe(
            com.chmouel.liseur.data.db.FurthestPosition(account, alias.workId, "", "dev-a", 0.8, 1L, "{}"),
        )
        val preview = (deviceB.positionSync.previewKnownBook(book.url) as com.chmouel.liseur.data.remote.PreviewOutcome.Ready).preview
        assertEquals(ResolveOutcome.Done, deviceB.positionSync.takeFurthestPosition(book.url, preview.localRevision!!, preview.furthest!!))
        deviceB.read(book.url, 0.3, 2_000L)

        assertFalse(peers.get(book.url, "$account/dev-a")!!.hasPending)
        assertFalse(peers.get(book.url, "$account/dev-c")!!.hasPending)
        assertNull(deviceB.positionSync.preservedConflict(book.url))
        assertEquals(0.3, deviceB.liseurDb.readingProgressDao().get(book.url)!!.totalProgression!!, 0.0001)
    }

    @Test
    fun `accepting one position settles other writers at the same place`() = runTest {
        deviceB.connect()
        val book = peakBook("matching-conflicts", "9780547928227")
        deviceB.addBook(book, "a".repeat(64))
        deviceB.read(book.url, 0.4, 1_000L)
        deviceB.workIdentity.resolveBook(PERM_ID, book)
        val account = DriveLibrarySync.accountPeerId(PERM_ID)
        val peers = deviceB.liseurDb.syncPeerStateDao()
        peers.persistPending(book.url, "$account/dev-a", 0.6, "Reading", 1_500L)
        peers.persistPending(book.url, "$account/dev-c", 0.6, "Reading", 1_600L)
        peers.persistPending(book.url, "$account/dev-e", 0.4, "Reading", 1_700L)
        val preview = deviceB.positionSync.preservedConflict(book.url)!!

        assertEquals(
            ResolveOutcome.Done,
            deviceB.positionSync.takeRemotePosition(book.url, preview.localRevision!!, expectedAccountKey = preview.accountKey),
        )
        deviceB.read(book.url, 0.3, 2_000L)
        assertFalse(peers.get(book.url, "$account/dev-c")!!.hasPending)
        assertFalse(peers.get(book.url, "$account/dev-e")!!.hasPending)
        assertNull(deviceB.positionSync.preservedConflict(book.url))
    }

    @Test
    fun `adopting a status does not acknowledge a position not yet uploaded`() = runTest {
        deviceB.connect()
        val book = peakBook("status-position-debt", "9780547928227")
        deviceB.addBook(book, "a".repeat(64))
        deviceB.read(book.url, 0.2, 1_000L)
        val first = deviceB.collectPeaks()
        deviceB.positionSync.acknowledgePublished(PERM_ID, first.records, first.positionAcks, first.peakAcks)
        val alias = requireNotNull(deviceB.workIdentity.resolveBook(PERM_ID, book))

        suspend fun receive(position: Double, choice: String, at: Long) {
            val body = DrivePositionBody(
                workKey = alias.workId, deviceId = "dev-a", editionSha = null, locatorJson = "{}",
                totalProgression = position, status = "Reading", finishedOverride = choice,
                coverage = null, unreadResetAt = null, readAt = at, updatedAt = at,
            )
            val record = DriveWireCodec.putRecord(
                DriveWireCodec.DOMAIN_POSITION, "${alias.workId}/dev-a",
                String.format(java.util.Locale.ROOT, "%013d-0000-dev-a", at), DriveWireCodec.encodePositionBody(body),
            )
            deviceB.positionSync.applyRemotePosition(PERM_ID, "dev-b", record, "dev-a", at)
        }
        receive(0.2, "automatic", 2_000L)
        receive(0.4, "automatic", 3_000L)
        receive(0.4, "paused", 4_000L)

        assertEquals(FinishedOverride.PAUSED, deviceB.liseurDb.readingProgressDao().get(book.url)!!.override)
        val next = deviceB.collectPeaks()
        val position = next.records.single { it.d == DriveWireCodec.DOMAIN_POSITION }
        assertEquals(0.4, DriveWireCodec.decodePositionBody(position.body!!)!!.totalProgression!!, 0.0001)
    }

    @Test
    fun `a page turn during status adoption preserves the incoming position`() = runTest {
        deviceB.connect()
        val book = peakBook("status-race", "9780547928227")
        deviceB.addBook(book, "a".repeat(64))
        deviceB.read(book.url, 0.4, 1_000L)
        val alias = requireNotNull(deviceB.workIdentity.resolveBook(PERM_ID, book))
        val account = DriveLibrarySync.accountPeerId(PERM_ID)
        val peers = deviceB.liseurDb.syncPeerStateDao()
        val revision = deviceB.liseurDb.readingProgressDao().get(book.url)!!.localRevision
        peers.settlePositionRevision(book.url, account, revision)
        peers.settle(book.url, "$account/dev-a", revision, 0.4, "Reading", 1_000L)
        var raced = false
        val finished = FinishedState(
            bookDao = deviceB.liseurDb.bookDao(),
            progressDao = deviceB.liseurDb.readingProgressDao(),
            inTransaction = { work ->
                if (!raced) {
                    raced = true
                    deviceB.read(book.url, 0.3, 1_500L)
                }
                deviceB.liseurDb.withTransaction { work() }
            },
        )
        val sync = DrivePositionSync(
            database = deviceB.liseurDb,
            storeProvider = { deviceB.driveDb },
            workIdentityProvider = { deviceB.workIdentity },
            finishedState = finished,
            runSync = { SyncOutcome.Success },
            isActive = { true },
            activeAccount = { deviceB.driveDb.accounts().active() },
            localDeviceId = { deviceB.deviceId },
            nextHlc = { "0000000003000-0000-dev-b" },
        )
        val body = DrivePositionBody(
            workKey = alias.workId, deviceId = "dev-a", editionSha = null, locatorJson = "{}",
            totalProgression = 0.6, status = "Reading", finishedOverride = "paused",
            coverage = null, unreadResetAt = null, readAt = 2_000L, updatedAt = 2_000L,
        )
        val record = DriveWireCodec.putRecord(
            DriveWireCodec.DOMAIN_POSITION, "${alias.workId}/dev-a", "0000000002000-0000-dev-a",
            DriveWireCodec.encodePositionBody(body),
        )
        sync.applyRemotePosition(PERM_ID, deviceB.deviceId, record, "dev-a", 1L)
        assertEquals(0.3, deviceB.liseurDb.readingProgressDao().get(book.url)!!.totalProgression!!, 0.0001)
        assertEquals(0.6, sync.preservedConflict(book.url)!!.remote!!, 0.0001)
        assertEquals(record.fp, deviceB.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_POSITION, record.k)!!.versionFp)
    }

    @Test
    fun `fresh device accepts status when it has no local status changes`() = runTest {
        deviceB.connect()
        val book = peakBook("fresh-status", "9780547928227")
        deviceB.addBook(book, "a".repeat(64))
        val alias = requireNotNull(deviceB.workIdentity.resolveBook(PERM_ID, book))

        suspend fun receive(device: String, choice: String, at: Long) {
            val body = DrivePositionBody(
                workKey = alias.workId, deviceId = device, editionSha = null, locatorJson = "{}",
                totalProgression = 0.4, status = "Reading", finishedOverride = choice,
                coverage = null, unreadResetAt = null, readAt = at, updatedAt = at,
            )
            val record = DriveWireCodec.putRecord(
                DriveWireCodec.DOMAIN_POSITION, "${alias.workId}/$device",
                String.format(java.util.Locale.ROOT, "%013d-0000-%s", at, device), DriveWireCodec.encodePositionBody(body),
            )
            deviceB.positionSync.applyRemotePosition(PERM_ID, "dev-b", record, device, at)
        }
        receive("dev-a", "automatic", 1_000L)
        receive("dev-c", "paused", 2_000L)

        assertEquals(FinishedOverride.PAUSED, deviceB.liseurDb.readingProgressDao().get(book.url)!!.override)
    }

    @Test
    fun `the furthest pending position is offered first`() = runTest {
        deviceB.connect()
        val book = peakBook("furthest-pending", "9780547928227")
        deviceB.addBook(book, "a".repeat(64))
        deviceB.read(book.url, 0.4, 1_000L)
        deviceB.workIdentity.resolveBook(PERM_ID, book)
        val account = DriveLibrarySync.accountPeerId(PERM_ID)
        val peers = deviceB.liseurDb.syncPeerStateDao()
        peers.persistPending(book.url, "$account/dev-a", 0.2, "Reading", 1_500L)
        peers.persistPending(book.url, "$account/dev-c", 0.8, "Reading", 2_000L)
        val preview = deviceB.positionSync.preservedConflict(book.url)!!
        assertEquals(0.8, preview.remote!!, 0.0001)
        assertEquals("$account/dev-c", preview.accountKey)
        assertTrue(peers.get(book.url, "$account/dev-a")!!.hasPending)
    }

    @Test
    fun `keeping local clears duplicate rejected positions but preserves other choices`() = runTest {
        deviceB.connect()
        val book = peakBook("keep-duplicates", "9780547928227")
        deviceB.addBook(book, "a".repeat(64))
        deviceB.read(book.url, 0.4, 1_000L)
        deviceB.workIdentity.resolveBook(PERM_ID, book)
        val account = DriveLibrarySync.accountPeerId(PERM_ID)
        val peers = deviceB.liseurDb.syncPeerStateDao()
        peers.persistPending(book.url, "$account/dev-a", 0.8, "Reading", 1_500L)
        peers.persistPending(book.url, "$account/dev-c", 0.8, "Reading", 1_600L)
        peers.persistPending(book.url, "$account/dev-d", 0.6, "Reading", 1_700L)
        peers.persistPending(book.url, "$account/dev-e", 0.4, "Reading", 1_800L)

        assertEquals(ResolveOutcome.Done, deviceB.positionSync.keepLocalPosition(book.url))
        deviceB.read(book.url, 0.3, 2_000L)
        assertFalse(peers.get(book.url, "$account/dev-a")!!.hasPending)
        assertFalse(peers.get(book.url, "$account/dev-c")!!.hasPending)
        assertFalse(peers.get(book.url, "$account/dev-e")!!.hasPending)
        assertTrue(peers.get(book.url, "$account/dev-d")!!.hasPending)
        assertEquals(0.6, deviceB.positionSync.preservedConflict(book.url)!!.remote!!, 0.0001)
    }

    @Test
    fun `settling an agreeing legacy conflict does not acknowledge an unsent position`() = runTest {
        deviceB.connect()
        val book = peakBook("legacy-delivery", "9780547928227")
        deviceB.addBook(book, "a".repeat(64))
        deviceB.read(book.url, 0.4, 1_000L)
        deviceB.workIdentity.resolveBook(PERM_ID, book)
        val account = DriveLibrarySync.accountPeerId(PERM_ID)
        val peers = deviceB.liseurDb.syncPeerStateDao()
        peers.persistPending(book.url, account, 0.4, "Reading", 1_500L)
        peers.persistPending(book.url, "$account/dev-a", 0.8, "Reading", 1_600L)

        assertEquals(ResolveOutcome.Done, deviceB.positionSync.keepLocalPosition(book.url))
        assertEquals(0L, peers.get(book.url, account)!!.ackedRevision)
        val bundle = deviceB.collectPeaks()
        assertTrue(bundle.records.any { it.d == DriveWireCodec.DOMAIN_POSITION })
    }

    @Test
    fun `accepting a legacy conflict does not acknowledge the adopted position`() = runTest {
        deviceB.connect()
        val book = peakBook("legacy-adopt-delivery", "9780547928227")
        deviceB.addBook(book, "a".repeat(64))
        deviceB.read(book.url, 0.4, 1_000L)
        deviceB.workIdentity.resolveBook(PERM_ID, book)
        val account = DriveLibrarySync.accountPeerId(PERM_ID)
        val peers = deviceB.liseurDb.syncPeerStateDao()
        val revision = deviceB.liseurDb.readingProgressDao().get(book.url)!!.localRevision
        peers.settlePositionRevision(book.url, account, revision)
        peers.persistPending(book.url, account, 0.6, "Reading", 1_500L)
        val preview = deviceB.positionSync.preservedConflict(book.url)!!

        assertEquals(
            ResolveOutcome.Done,
            deviceB.positionSync.takeRemotePosition(book.url, revision, expectedAccountKey = preview.accountKey),
        )
        assertEquals(revision, peers.get(book.url, account)!!.ackedRevision)
        assertTrue(deviceB.collectPeaks().records.any { it.d == DriveWireCodec.DOMAIN_POSITION })
    }

    @Test
    fun `keeping local preserves a different exact position at the same percentage`() = runTest {
        deviceB.connect()
        val book = peakBook("keep-exact", "9780547928227")
        val sha = "a".repeat(64)
        deviceB.addBook(book, sha)
        deviceB.read(book.url, 0.4, 1_000L)
        deviceB.workIdentity.resolveBook(PERM_ID, book)
        val account = DriveLibrarySync.accountPeerId(PERM_ID)
        val peers = deviceB.liseurDb.syncPeerStateDao()
        val first = """{"href":"ch.xhtml","type":"application/xhtml+xml","locations":{"liseurAnchor":1,"cssSelector":"p#a","totalProgression":0.8},"text":{"highlight":"first"}}"""
        val second = """{"href":"ch.xhtml","type":"application/xhtml+xml","locations":{"liseurAnchor":1,"cssSelector":"p#b","totalProgression":0.8},"text":{"highlight":"second"}}"""
        peers.persistPending(book.url, "$account/dev-a", 0.8, "Reading", 1_500L, locatorJson = first, editionSha = sha)
        peers.persistPending(book.url, "$account/dev-c", 0.8, "Reading", 1_600L, locatorJson = second, editionSha = sha)

        assertEquals(ResolveOutcome.Done, deviceB.positionSync.keepLocalPosition(book.url))
        assertFalse(peers.get(book.url, "$account/dev-a")!!.hasPending)
        assertTrue(peers.get(book.url, "$account/dev-c")!!.hasPending)
    }

    @Test
    fun `a newer writer position replaces its identifiable legacy conflict`() = runTest {
        deviceB.connect()
        val book = peakBook("legacy-conflict", "9780547928227")
        deviceB.addBook(book, "a".repeat(64))
        deviceB.read(book.url, 0.4, 1_000L)
        val alias = requireNotNull(deviceB.workIdentity.resolveBook(PERM_ID, book))
        val account = DriveLibrarySync.accountPeerId(PERM_ID)
        val peers = deviceB.liseurDb.syncPeerStateDao()
        peers.persistPending(book.url, account, 0.6, "Reading", 1_500L)
        val oldBody = DrivePositionBody(
            workKey = alias.workId, deviceId = "dev-a", editionSha = null, locatorJson = "{}",
            totalProgression = 0.6, status = "Reading", finishedOverride = "automatic",
            coverage = null, unreadResetAt = null, readAt = 1_500L, updatedAt = 1_500L,
        )
        val oldMap = DriveWireCodec.encodePositionBody(oldBody)
        val oldRecord = DriveWireCodec.putRecord(
            DriveWireCodec.DOMAIN_POSITION, "${alias.workId}/dev-a", "0000000001500-0000-dev-a", oldMap,
        )
        deviceB.driveDb.recordStates().upsert(
            DriveRecordState(
                permissionId = PERM_ID, domain = DriveWireCodec.DOMAIN_POSITION, recordKey = oldRecord.k,
                versionFp = oldRecord.fp, versionOp = DriveRecordState.OP_PUT, publishedFp = oldRecord.fp,
                contentFp = DriveCanonicalJson.contentFp(oldMap), originDeviceId = "dev-a", originSeq = 1L,
                ancestorsJson = "[]", resRefsJson = "[]", lastHlc = oldRecord.hlc,
                bodyJson = DriveCanonicalJson.encode(oldMap),
            ),
        )
        val newBody = oldBody.copy(totalProgression = 0.4, readAt = 2_000L, updatedAt = 2_000L)
        val newRecord = DriveWireCodec.putRecord(
            DriveWireCodec.DOMAIN_POSITION, oldRecord.k, "0000000002000-0000-dev-a", DriveWireCodec.encodePositionBody(newBody),
        )
        deviceB.positionSync.applyRemotePosition(PERM_ID, "dev-b", newRecord, "dev-a", 2L)

        assertFalse(peers.get(book.url, account)!!.hasPending)
        assertNull(deviceB.positionSync.preservedConflict(book.url))
        assertEquals(0.4, deviceB.liseurDb.readingProgressDao().get(book.url)!!.totalProgression!!, 0.0001)
    }

    @Test
    fun `identifiable legacy conflict is retired when the writer already has a pending position`() = runTest {
        deviceB.connect()
        val book = peakBook("legacy-writer-pending", "9780547928227")
        deviceB.addBook(book, "a".repeat(64))
        deviceB.read(book.url, 0.4, 1_000L)
        val alias = requireNotNull(deviceB.workIdentity.resolveBook(PERM_ID, book))
        val account = DriveLibrarySync.accountPeerId(PERM_ID)
        val peers = deviceB.liseurDb.syncPeerStateDao()
        peers.persistPending(book.url, account, 0.6, "Reading", 1_500L)
        peers.persistPending(book.url, "$account/dev-a", 0.8, "Reading", 1_800L)
        val oldBody = DrivePositionBody(
            workKey = alias.workId, deviceId = "dev-a", editionSha = null, locatorJson = "{}",
            totalProgression = 0.6, status = "Reading", finishedOverride = "automatic",
            coverage = null, unreadResetAt = null, readAt = 1_500L, updatedAt = 1_500L,
        )
        val oldMap = DriveWireCodec.encodePositionBody(oldBody)
        val oldRecord = DriveWireCodec.putRecord(
            DriveWireCodec.DOMAIN_POSITION, "${alias.workId}/dev-a", "0000000001500-0000-dev-a", oldMap,
        )
        deviceB.driveDb.recordStates().upsert(
            DriveRecordState(
                permissionId = PERM_ID, domain = DriveWireCodec.DOMAIN_POSITION, recordKey = oldRecord.k,
                versionFp = oldRecord.fp, versionOp = DriveRecordState.OP_PUT, publishedFp = oldRecord.fp,
                contentFp = DriveCanonicalJson.contentFp(oldMap), originDeviceId = "dev-a", originSeq = 1L,
                ancestorsJson = "[]", resRefsJson = "[]", lastHlc = oldRecord.hlc,
                bodyJson = DriveCanonicalJson.encode(oldMap),
            ),
        )
        deviceB.positionSync.seedUnseededAliases(PERM_ID, "dev-b")
        assertTrue(peers.get(book.url, account)!!.hasPending)
        assertEquals(0.8, peers.get(book.url, "$account/dev-a")!!.pendingProgression!!, 0.0001)
        val newBody = oldBody.copy(totalProgression = 0.4, readAt = 2_000L, updatedAt = 2_000L)
        val newRecord = DriveWireCodec.putRecord(
            DriveWireCodec.DOMAIN_POSITION, oldRecord.k, "0000000002000-0000-dev-a", DriveWireCodec.encodePositionBody(newBody),
        )
        deviceB.positionSync.applyRemotePosition(PERM_ID, "dev-b", newRecord, "dev-a", 2L)
        deviceB.read(book.url, 0.3, 3_000L)

        assertFalse(peers.get(book.url, account)!!.hasPending)
        assertNull(deviceB.positionSync.preservedConflict(book.url))
    }

    @Test
    fun `an ambiguous legacy conflict is retained`() = runTest {
        deviceB.connect()
        val book = peakBook("ambiguous-legacy", "9780547928227")
        deviceB.addBook(book, "a".repeat(64))
        deviceB.read(book.url, 0.4, 1_000L)
        val alias = requireNotNull(deviceB.workIdentity.resolveBook(PERM_ID, book))
        val account = DriveLibrarySync.accountPeerId(PERM_ID)
        val peers = deviceB.liseurDb.syncPeerStateDao()
        peers.persistPending(book.url, account, 0.6, "Reading", 1_500L)
        fun body(device: String, position: Double, at: Long) = DrivePositionBody(
            workKey = alias.workId, deviceId = device, editionSha = null, locatorJson = "{}",
            totalProgression = position, status = "Reading", finishedOverride = "automatic",
            coverage = null, unreadResetAt = null, readAt = at, updatedAt = at,
        )
        for (device in listOf("dev-a", "dev-c")) {
            val map = DriveWireCodec.encodePositionBody(body(device, 0.6, 1_500L))
            val record = DriveWireCodec.putRecord(
                DriveWireCodec.DOMAIN_POSITION, "${alias.workId}/$device", "0000000001500-0000-$device", map,
            )
            deviceB.driveDb.recordStates().upsert(
                DriveRecordState(
                    permissionId = PERM_ID, domain = DriveWireCodec.DOMAIN_POSITION, recordKey = record.k,
                    versionFp = record.fp, versionOp = DriveRecordState.OP_PUT, publishedFp = record.fp,
                    contentFp = DriveCanonicalJson.contentFp(map), originDeviceId = device, originSeq = 1L,
                    ancestorsJson = "[]", resRefsJson = "[]", lastHlc = record.hlc,
                    bodyJson = DriveCanonicalJson.encode(map),
                ),
            )
        }
        val update = DriveWireCodec.putRecord(
            DriveWireCodec.DOMAIN_POSITION, "${alias.workId}/dev-a", "0000000002000-0000-dev-a",
            DriveWireCodec.encodePositionBody(body("dev-a", 0.4, 2_000L)),
        )
        deviceB.positionSync.applyRemotePosition(PERM_ID, "dev-b", update, "dev-a", 2L)
        assertTrue(peers.get(book.url, account)!!.hasPending)
        assertEquals(0.6, deviceB.positionSync.preservedConflict(book.url)!!.remote!!, 0.0001)
    }

    @Test
    fun `seeding compares cached and pending writer versions`() = runTest {
        deviceB.connect()
        val account = DriveLibrarySync.accountPeerId(PERM_ID)
        val peers = deviceB.liseurDb.syncPeerStateDao()
        val books = listOf(
            peakBook("seed-newer-cache", "9780547928227"),
            peakBook("seed-newer-pending", "9780618391110"),
        )
        for ((index, book) in books.withIndex()) {
            deviceB.addBook(book, if (index == 0) "a".repeat(64) else "b".repeat(64))
            deviceB.read(book.url, 0.4, 1_000L)
            val alias = requireNotNull(deviceB.workIdentity.resolveBook(PERM_ID, book))
            peers.persistPending(
                book.url, "$account/dev-a", 0.6, "Reading", 1_500L,
                hlc = if (index == 0) "0000000001500-0000-dev-a" else "0000000002500-0000-dev-a",
            )
            val body = DrivePositionBody(
                workKey = alias.workId, deviceId = "dev-a", editionSha = null, locatorJson = "{}",
                totalProgression = 0.8, status = "Reading", finishedOverride = "automatic",
                coverage = null, unreadResetAt = null, readAt = 2_000L, updatedAt = 2_000L,
            )
            val map = DriveWireCodec.encodePositionBody(body)
            val record = DriveWireCodec.putRecord(
                DriveWireCodec.DOMAIN_POSITION, "${alias.workId}/dev-a", "0000000002000-0000-dev-a", map,
            )
            deviceB.driveDb.recordStates().upsert(
                DriveRecordState(
                    permissionId = PERM_ID, domain = DriveWireCodec.DOMAIN_POSITION, recordKey = record.k,
                    versionFp = record.fp, versionOp = DriveRecordState.OP_PUT, publishedFp = record.fp,
                    contentFp = DriveCanonicalJson.contentFp(map), originDeviceId = "dev-a", originSeq = 1L,
                    ancestorsJson = "[]", resRefsJson = "[]", lastHlc = record.hlc,
                    bodyJson = DriveCanonicalJson.encode(map),
                ),
            )
        }
        deviceB.positionSync.seedUnseededAliases(PERM_ID, "dev-b")
        assertEquals(0.8, peers.get(books[0].url, "$account/dev-a")!!.pendingProgression!!, 0.0001)
        assertEquals(0.6, peers.get(books[1].url, "$account/dev-a")!!.pendingProgression!!, 0.0001)
    }

    @Test
    fun `a clean remote move settles pending writers at the previous position`() = runTest {
        deviceB.connect()
        val book = peakBook("pending-before-pull", "9780547928227")
        deviceB.addBook(book, "a".repeat(64))
        deviceB.read(book.url, 0.4, 1_000L)
        val alias = requireNotNull(deviceB.workIdentity.resolveBook(PERM_ID, book))
        val account = DriveLibrarySync.accountPeerId(PERM_ID)
        val peers = deviceB.liseurDb.syncPeerStateDao()
        val revision = deviceB.liseurDb.readingProgressDao().get(book.url)!!.localRevision
        peers.persistPending(book.url, "$account/dev-a", 0.4, "Reading", 1_000L)
        peers.settle(book.url, "$account/dev-c", revision, 0.4, "Reading", 1_000L)
        val body = DrivePositionBody(
            workKey = alias.workId, deviceId = "dev-c", editionSha = null, locatorJson = "{}",
            totalProgression = 0.2, status = "Reading", finishedOverride = "automatic",
            coverage = null, unreadResetAt = null, readAt = 2_000L, updatedAt = 2_000L,
        )
        val record = DriveWireCodec.putRecord(
            DriveWireCodec.DOMAIN_POSITION, "${alias.workId}/dev-c", "0000000002000-0000-dev-c",
            DriveWireCodec.encodePositionBody(body),
        )
        deviceB.positionSync.applyRemotePosition(PERM_ID, "dev-b", record, "dev-c", 1L)
        assertEquals(0.2, deviceB.liseurDb.readingProgressDao().get(book.url)!!.totalProgression!!, 0.0001)
        assertFalse(peers.get(book.url, "$account/dev-a")!!.hasPending)
        assertNull(deviceB.positionSync.preservedConflict(book.url))
    }

    @Test
    fun `an imported unread reset does not block a later reading position`() = runTest {
        deviceB.connect()
        val book = peakBook("imported-reset", "9780547928227")
        deviceB.addBook(book, "a".repeat(64))
        deviceB.read(book.url, 0.4, 1_000L)
        val alias = requireNotNull(deviceB.workIdentity.resolveBook(PERM_ID, book))
        val account = DriveLibrarySync.accountPeerId(PERM_ID)
        val peers = deviceB.liseurDb.syncPeerStateDao()
        val revision = deviceB.liseurDb.readingProgressDao().get(book.url)!!.localRevision
        peers.settlePositionRevision(book.url, account, revision)
        peers.settle(book.url, "$account/dev-a", revision, 0.4, "Reading", 1_000L)
        peers.persistPending(book.url, "$account/dev-c", 0.4, "Reading", 1_000L)

        suspend fun receive(position: Double, choice: String, at: Long) {
            val body = DrivePositionBody(
                workKey = alias.workId, deviceId = "dev-a", editionSha = null, locatorJson = "{}",
                totalProgression = position, status = "Reading", finishedOverride = choice,
                coverage = null, unreadResetAt = at.takeIf { choice == "unread" }, readAt = at, updatedAt = at,
            )
            val record = DriveWireCodec.putRecord(
                DriveWireCodec.DOMAIN_POSITION, "${alias.workId}/dev-a",
                String.format(java.util.Locale.ROOT, "%013d-0000-dev-a", at), DriveWireCodec.encodePositionBody(body),
            )
            deviceB.positionSync.applyRemotePosition(PERM_ID, "dev-b", record, "dev-a", at)
        }
        receive(0.0, "unread", 2_000L)
        assertEquals(0.0, deviceB.liseurDb.readingProgressDao().get(book.url)!!.totalProgression!!, 0.0001)
        assertFalse(peers.get(book.url, "$account/dev-c")!!.hasPending)
        receive(0.3, "automatic", 3_000L)
        assertEquals(0.3, deviceB.liseurDb.readingProgressDao().get(book.url)!!.totalProgression!!, 0.0001)
    }

    @Test
    fun `a concurrent move survives an imported unread reset in either order`() = runTest {
        for ((device, resetFirst) in listOf(deviceA to true, deviceB to false)) {
            device.connect()
            val book = peakBook("reset-order-${device.deviceId}", "9780547928227")
            device.addBook(book, "a".repeat(64))
            device.read(book.url, 0.4, 1_000L)
            val alias = requireNotNull(device.workIdentity.resolveBook(PERM_ID, book))
            val account = DriveLibrarySync.accountPeerId(PERM_ID)
            val peers = device.liseurDb.syncPeerStateDao()
            val revision = device.liseurDb.readingProgressDao().get(book.url)!!.localRevision
            peers.settlePositionRevision(book.url, account, revision)
            peers.settle(book.url, "$account/writer-a", revision, 0.4, "Reading", 1_000L)
            peers.settle(book.url, "$account/writer-c", revision, 0.4, "Reading", 1_000L)
            suspend fun receive(writer: String, position: Double, choice: String, at: Long) {
                val body = DrivePositionBody(
                    workKey = alias.workId, deviceId = writer, editionSha = null, locatorJson = "{}",
                    totalProgression = position, status = "Reading", finishedOverride = choice,
                    coverage = null, unreadResetAt = at.takeIf { choice == "unread" }, readAt = at, updatedAt = at,
                )
                val record = DriveWireCodec.putRecord(
                    DriveWireCodec.DOMAIN_POSITION, "${alias.workId}/$writer",
                    String.format(java.util.Locale.ROOT, "%013d-0000-%s", at, writer), DriveWireCodec.encodePositionBody(body),
                )
                device.positionSync.applyRemotePosition(PERM_ID, device.deviceId, record, writer, at)
            }
            if (resetFirst) {
                receive("writer-a", 0.0, "unread", 3_000L)
                receive("writer-c", 0.7, "automatic", 2_000L)
                assertEquals(0.0, device.liseurDb.readingProgressDao().get(book.url)!!.totalProgression!!, 0.0001)
                assertEquals(0.7, device.positionSync.preservedConflict(book.url)!!.remote!!, 0.0001)
            } else {
                receive("writer-c", 0.7, "automatic", 2_000L)
                receive("writer-a", 0.0, "unread", 3_000L)
                assertEquals(0.7, device.liseurDb.readingProgressDao().get(book.url)!!.totalProgression!!, 0.0001)
                assertEquals(0.0, device.positionSync.preservedConflict(book.url)!!.remote!!, 0.0001)
            }
        }
    }

    @Test
    fun `a legacy unread reset keeps a competing position when its origin is unknown`() = runTest {
        deviceB.connect()
        val book = peakBook("legacy-reset-origin", "9780547928227")
        deviceB.addBook(book, "a".repeat(64))
        deviceB.read(book.url, 0.4, 1_000L)
        val alias = requireNotNull(deviceB.workIdentity.resolveBook(PERM_ID, book))
        val account = DriveLibrarySync.accountPeerId(PERM_ID)
        val peers = deviceB.liseurDb.syncPeerStateDao()
        val revision = deviceB.liseurDb.readingProgressDao().get(book.url)!!.localRevision
        peers.settlePositionRevision(book.url, account, revision)
        peers.settle(book.url, "$account/writer-a", revision, 0.4, "Reading", 1_000L)
        peers.settle(book.url, "$account/writer-c", revision, 0.4, "Reading", 1_000L)
        val reset = DrivePositionBody(
            workKey = alias.workId, deviceId = "writer-a", editionSha = null, locatorJson = "{}",
            totalProgression = 0.0, status = "ReadyToRead", finishedOverride = "unread",
            coverage = null, unreadResetAt = 3_000L, readAt = 3_000L, updatedAt = 3_000L,
        )
        val resetRecord = DriveWireCodec.putRecord(
            DriveWireCodec.DOMAIN_POSITION, "${alias.workId}/writer-a", "0000000003000-0000-writer-a",
            DriveWireCodec.encodePositionBody(reset),
        )
        deviceB.positionSync.applyRemotePosition(PERM_ID, "dev-b", resetRecord, "writer-a", 1L)
        val resetRevision = deviceB.liseurDb.readingProgressDao().get(book.url)!!.unreadResetRevision!!
        peers.setDriveImportedResetRevision(book.url, account, -resetRevision - 1)
        val move = reset.copy(
            deviceId = "writer-c", totalProgression = 0.7, status = "Reading", finishedOverride = "automatic",
            unreadResetAt = null, readAt = 2_000L, updatedAt = 2_000L,
        )
        val moveRecord = DriveWireCodec.putRecord(
            DriveWireCodec.DOMAIN_POSITION, "${alias.workId}/writer-c", "0000000002000-0000-writer-c",
            DriveWireCodec.encodePositionBody(move),
        )
        deviceB.positionSync.applyRemotePosition(PERM_ID, "dev-b", moveRecord, "writer-c", 1L)
        assertEquals(0.0, deviceB.liseurDb.readingProgressDao().get(book.url)!!.totalProgression!!, 0.0001)
        assertEquals(0.7, deviceB.positionSync.preservedConflict(book.url)!!.remote!!, 0.0001)
    }

    @Test
    fun `an unread reset preserves a position that has not been uploaded`() = runTest {
        deviceB.connect()
        val book = peakBook("undelivered-reset", "9780547928227")
        deviceB.addBook(book, "a".repeat(64))
        deviceB.read(book.url, 0.4, 1_000L)
        val alias = requireNotNull(deviceB.workIdentity.resolveBook(PERM_ID, book))
        val account = DriveLibrarySync.accountPeerId(PERM_ID)
        val peers = deviceB.liseurDb.syncPeerStateDao()
        val revision = deviceB.liseurDb.readingProgressDao().get(book.url)!!.localRevision
        peers.settle(book.url, "$account/dev-a", revision, 0.4, "Reading", 1_000L)
        val body = DrivePositionBody(
            workKey = alias.workId, deviceId = "dev-a", editionSha = null, locatorJson = "{}",
            totalProgression = 0.0, status = "ReadyToRead", finishedOverride = "unread",
            coverage = null, unreadResetAt = 2_000L, readAt = 2_000L, updatedAt = 2_000L,
        )
        val record = DriveWireCodec.putRecord(
            DriveWireCodec.DOMAIN_POSITION, "${alias.workId}/dev-a", "0000000002000-0000-dev-a",
            DriveWireCodec.encodePositionBody(body),
        )
        deviceB.positionSync.applyRemotePosition(PERM_ID, "dev-b", record, "dev-a", 1L)
        assertEquals(0.4, deviceB.liseurDb.readingProgressDao().get(book.url)!!.totalProgression!!, 0.0001)
        assertEquals(0.0, peers.get(book.url, "$account/dev-a")!!.pendingProgression!!, 0.0001)
        assertEquals(0L, peers.get(book.url, account)?.ackedRevision ?: 0L)
    }

    @Test
    fun `a deferred unread reset does not move the position without its status`() = runTest {
        deviceB.connect()
        val book = peakBook("deferred-unread", "9780547928227")
        deviceB.addBook(book, "a".repeat(64))
        deviceB.read(book.url, 0.4, 1_000L)
        val alias = requireNotNull(deviceB.workIdentity.resolveBook(PERM_ID, book))
        val account = DriveLibrarySync.accountPeerId(PERM_ID)
        val peers = deviceB.liseurDb.syncPeerStateDao()
        val before = deviceB.liseurDb.readingProgressDao().get(book.url)!!
        peers.settle(book.url, "$account/dev-a", before.localRevision, 0.4, "Reading", 1_000L)
        deviceB.finishedState.setReadingChoice(book.url, ReadingStatusChoice.FINISHED)
        val after = deviceB.liseurDb.readingProgressDao().get(book.url)!!
        peers.settlePositionRevision(book.url, account, after.localRevision)
        peers.settleStatus(book.url, account, after.statusRevision)
        val body = DrivePositionBody(
            workKey = alias.workId, deviceId = "dev-a", editionSha = null, locatorJson = "{}",
            totalProgression = 0.0, status = "ReadyToRead", finishedOverride = "unread",
            coverage = null, unreadResetAt = 2_000L, readAt = 2_000L, updatedAt = 2_000L,
        )
        val record = DriveWireCodec.putRecord(
            DriveWireCodec.DOMAIN_POSITION, "${alias.workId}/dev-a", "0000000002000-0000-dev-a",
            DriveWireCodec.encodePositionBody(body),
        )
        deviceB.positionSync.applyRemotePosition(PERM_ID, "dev-b", record, "dev-a", 1L)
        val progress = deviceB.liseurDb.readingProgressDao().get(book.url)!!
        assertEquals(0.4, progress.totalProgression!!, 0.0001)
        assertEquals(FinishedOverride.FINISHED, progress.override)
        assertEquals(0.0, peers.get(book.url, "$account/dev-a")!!.pendingProgression!!, 0.0001)
        assertTrue(peers.get(book.url, account)!!.hasPendingStatus)
    }

    @Test
    fun `an older unread reset does not replace the position under a newer status`() = runTest {
        deviceB.connect()
        val book = peakBook("older-unread", "9780547928227")
        deviceB.addBook(book, "a".repeat(64))
        deviceB.read(book.url, 0.4, 1_000L)
        val alias = requireNotNull(deviceB.workIdentity.resolveBook(PERM_ID, book))
        val account = DriveLibrarySync.accountPeerId(PERM_ID)
        val peers = deviceB.liseurDb.syncPeerStateDao()
        val revision = deviceB.liseurDb.readingProgressDao().get(book.url)!!.localRevision
        peers.settlePositionRevision(book.url, account, revision)
        peers.settle(book.url, "$account/dev-a", revision, 0.4, "Reading", 1_000L)
        fun record(writer: String, position: Double, choice: String, at: Long): DriveWireRecord {
            val body = DrivePositionBody(
                workKey = alias.workId, deviceId = writer, editionSha = null, locatorJson = "{}",
                totalProgression = position, status = "Reading", finishedOverride = choice,
                coverage = null, unreadResetAt = at.takeIf { choice == "unread" }, readAt = at, updatedAt = at,
            )
            return DriveWireCodec.putRecord(
                DriveWireCodec.DOMAIN_POSITION, "${alias.workId}/$writer",
                String.format(java.util.Locale.ROOT, "%013d-0000-%s", at, writer), DriveWireCodec.encodePositionBody(body),
            )
        }
        deviceB.positionSync.applyRemotePosition(PERM_ID, "dev-b", record("dev-c", 0.4, "finished", 3_000L), "dev-c", 1L)
        deviceB.positionSync.applyRemotePosition(PERM_ID, "dev-b", record("dev-a", 0.0, "unread", 2_000L), "dev-a", 1L)

        val progress = deviceB.liseurDb.readingProgressDao().get(book.url)!!
        assertEquals(0.4, progress.totalProgression!!, 0.0001)
        assertEquals(FinishedOverride.FINISHED, progress.override)
        assertEquals(0.0, peers.get(book.url, "$account/dev-a")!!.pendingProgression!!, 0.0001)
    }

    @Test
    fun `forgetting persists its reset before local cleanup and the next sync completes an interrupted forget`() = runTest {
        deviceA.connect()
        deviceB.connect()
        val sha = "d".repeat(64)
        val book = peakBook("crash")
        deviceA.addBook(book, sha)
        deviceA.read(book.url, 0.80, 1_000L)
        deviceA.read(book.url, 0.20, 2_000L)
        deviceA.changeEngine.sync()
        deviceB.changeEngine.sync()
        assertEquals(1, deviceB.observed("sha:$sha").size)

        val sqlite = deviceA.liseurDb.openHelper.writableDatabase
        sqlite.execSQL(
            "CREATE TRIGGER crash_reset BEFORE UPDATE OF peak_progression ON reading_progress " +
                "BEGIN SELECT RAISE(ABORT, 'crash'); END",
        )
        val failure = runCatching { deviceA.forget(book.url) }.exceptionOrNull()
        sqlite.execSQL("DROP TRIGGER crash_reset")
        assertNotNull(failure)

        // The reset and its floor are durable while the local peak is still the forgotten one.
        assertEquals(0.80, deviceA.liseurDb.readingProgressDao().get(book.url)!!.peakProgression!!, 0.0001)
        assertTrue(deviceA.positionSync.getPersistedFloor(PERM_ID, "sha:$sha", "dev-a") >= 1L)
        assertEquals(1, deviceA.driveDb.recordStates().forDomain(PERM_ID, "_furthest_reset_pending").size)

        deviceA.changeEngine.sync()
        assertEquals(0.20, deviceA.liseurDb.readingProgressDao().get(book.url)!!.peakProgression!!, 0.0001)
        assertEquals(0, deviceA.driveDb.recordStates().forDomain(PERM_ID, "_furthest_reset_pending").size)
        deviceB.changeEngine.sync()
        // The reset drops the forgotten 80% peak; the post-reset peak (seq above the floor) stays.
        val after = deviceB.observed("sha:$sha").single()
        assertEquals(0.20, after.progression, 0.0001)
        assertTrue(after.seq > deviceB.positionSync.getPersistedFloor(PERM_ID, "sha:$sha", "dev-a"))

        // A completed replay must not run again: turning further back leaves the new peak alone.
        deviceA.read(book.url, 0.10, 3_000L)
        deviceA.changeEngine.sync()
        assertEquals(0.20, deviceA.liseurDb.readingProgressDao().get(book.url)!!.peakProgression!!, 0.0001)
    }

    private suspend fun forgetAtPeakThenCrashBeforeMarkerCleanup(sha: String, book: Book) {
        deviceA.connect()
        deviceB.connect()
        deviceA.addBook(book, sha)
        deviceA.read(book.url, 0.80, 1_000L)
        deviceA.changeEngine.sync()
        deviceB.changeEngine.sync()

        val driveSqlite = deviceA.driveDb.openHelper.writableDatabase
        driveSqlite.execSQL(
            "CREATE TRIGGER crash_cleanup BEFORE DELETE ON drive_record_state " +
                "WHEN OLD.domain = '_furthest_reset_pending' " +
                "BEGIN SELECT RAISE(ABORT, 'crash'); END",
        )
        val failure = runCatching { deviceA.forget(book.url) }.exceptionOrNull()
        driveSqlite.execSQL("DROP TRIGGER crash_cleanup")
        assertNotNull(failure)
        assertEquals(1, deviceA.driveDb.recordStates().forDomain(PERM_ID, "_furthest_reset_pending").size)
    }

    @Test
    fun `replaying a forget after the reader advanced keeps the new peak and its sync`() = runTest {
        val sha = "e".repeat(64)
        val book = peakBook("replay-forward")
        forgetAtPeakThenCrashBeforeMarkerCleanup(sha, book)

        deviceA.read(book.url, 0.95, 3_000L)
        deviceA.changeEngine.sync()
        deviceB.changeEngine.sync()

        assertEquals(0.95, deviceA.liseurDb.readingProgressDao().get(book.url)!!.peakProgression!!, 0.0001)
        assertEquals(0, deviceA.driveDb.recordStates().forDomain(PERM_ID, "_furthest_reset_pending").size)
        assertTrue(deviceB.observed("sha:$sha").any { it.origin == "dev-a" && it.progression == 0.95 })
    }

    @Test
    fun `replaying a forget after the reader moved back does not reset the peak`() = runTest {
        val sha = "f".repeat(64)
        val book = peakBook("replay-back")
        forgetAtPeakThenCrashBeforeMarkerCleanup(sha, book)

        deviceA.read(book.url, 0.50, 3_000L)
        deviceA.changeEngine.sync()

        assertEquals(0.80, deviceA.liseurDb.readingProgressDao().get(book.url)!!.peakProgression!!, 0.0001)
        assertEquals(0, deviceA.driveDb.recordStates().forDomain(PERM_ID, "_furthest_reset_pending").size)
    }

    @Test
    fun `a forgotten work resets every local edition and a later low-revision peak on another edition still publishes`() = runTest {
        deviceA.connect()
        deviceB.connect()
        val isbn = "9780547928227"
        val older = peakBook("older", isbn)
        val newer = peakBook("newer", isbn)
        val other = peakBook("unrelated")
        val shaOlder = "1".repeat(64)
        val shaNewer = "2".repeat(64)
        val shaOther = "3".repeat(64)
        deviceA.addBook(older, shaOlder)
        deviceA.addBook(newer, shaNewer)
        deviceA.addBook(other, shaOther)
        for (i in 1..10) deviceA.read(older.url, i / 100.0, 1_000L + i)
        deviceA.read(newer.url, 0.30, 5_000L)
        deviceA.read(other.url, 0.60, 6_000L)
        assertEquals(10L, deviceA.liseurDb.readingProgressDao().get(older.url)!!.peakRevision)

        deviceA.changeEngine.sync()
        deviceB.changeEngine.sync()
        val before = deviceB.observed("isbn:$isbn")
        assertEquals(2, before.size)
        assertTrue("wire seq must not be the per-book revision", before.all { it.seq < 10L })
        val otherSeq = deviceB.observed("sha:$shaOther").single().seq
        val otherPeakRevision = deviceA.liseurDb.readingProgressDao().get(other.url)!!.peakRevision

        assertEquals(ResolveOutcome.Done, deviceA.forget(older.url))

        val newerAfter = deviceA.liseurDb.readingProgressDao().get(newer.url)!!
        assertEquals(newerAfter.totalProgression, newerAfter.peakProgression)
        val olderAfter = deviceA.liseurDb.readingProgressDao().get(older.url)!!
        assertEquals(olderAfter.totalProgression, olderAfter.peakProgression)
        assertEquals(otherPeakRevision, deviceA.liseurDb.readingProgressDao().get(other.url)!!.peakRevision)
        assertEquals(0L, deviceA.positionSync.getPersistedFloor(PERM_ID, "sha:$shaOther", "dev-a"))
        assertEquals(0L, deviceA.positionSync.getPersistedFloor("other-account", "isbn:$isbn", "dev-a"))

        deviceA.changeEngine.sync()
        deviceB.changeEngine.sync()
        assertTrue(deviceB.observed("isbn:$isbn").isEmpty())
        assertEquals(otherSeq, deviceB.observed("sha:$shaOther").single().seq)

        // Revision 2 on the other edition is far below the forgotten edition's revision 10.
        deviceA.read(newer.url, 0.90, 7_000L)
        assertEquals(2L, deviceA.liseurDb.readingProgressDao().get(newer.url)!!.peakRevision)
        deviceA.changeEngine.sync()
        deviceB.changeEngine.sync()
        val republished = deviceB.observed("isbn:$isbn").single()
        assertEquals(shaNewer, republished.edition)
        assertEquals(0.90, republished.progression, 0.0001)
        assertTrue(republished.seq > before.maxOf { it.seq })
        assertTrue(republished.seq > otherSeq)
    }

    @Test
    fun `a peak keeps its wire seq across retries and a reimported book gets a higher one`() = runTest {
        deviceA.connect()
        deviceB.connect()
        val sha = "e".repeat(64)
        val book = peakBook("reimport")
        deviceA.addBook(book, sha)
        deviceA.read(book.url, 0.50, 1_000L)
        deviceA.changeEngine.sync()

        deviceA.read(book.url, 0.55, 2_000L)
        val first = deviceA.collectPeaks().records.single { it.d == DriveWireCodec.DOMAIN_FURTHEST }
        val retry = deviceA.collectPeaks().records.single { it.d == DriveWireCodec.DOMAIN_FURTHEST }
        assertEquals(DriveCanonicalJson.putFp(requireNotNull(first.body), first.hlc), first.fp)
        assertEquals(first.peakSeq(), retry.peakSeq())
        assertEquals(first.fp, retry.fp)
        assertEquals(first.hlc, retry.hlc)
        assertEquals(deviceB.also { it.changeEngine.sync() }.observed("sha:$sha").single().seq + 1L, first.peakSeq())

        deviceA.liseurDb.readingProgressDao().forget(book.url)
        deviceA.liseurDb.bookDao().deleteByUrls(listOf(book.url))
        assertNull(deviceA.liseurDb.readingProgressDao().get(book.url))
        deviceA.addBook(book, sha)
        deviceA.read(book.url, 0.70, 3_000L)
        assertEquals(1L, deviceA.liseurDb.readingProgressDao().get(book.url)!!.peakRevision)

        val reimported = deviceA.collectPeaks().records.single { it.d == DriveWireCodec.DOMAIN_FURTHEST }
        assertTrue(reimported.peakSeq() > first.peakSeq())
        assertEquals(reimported.peakSeq(), deviceA.collectPeaks().records.single { it.d == DriveWireCodec.DOMAIN_FURTHEST }.peakSeq())
        deviceA.changeEngine.sync()
        deviceB.changeEngine.sync()
        val seen = deviceB.observed("sha:$sha").single()
        assertEquals(0.70, seen.progression, 0.0001)
        assertEquals(reimported.peakSeq(), seen.seq)
    }

    @Test
    fun `a remote reset leaves another work's peak and its publishing untouched`() = runTest {
        deviceA.connect()
        deviceB.connect()
        val shaX = "4".repeat(64)
        val shaY = "5".repeat(64)
        val x = peakBook("x")
        val y = peakBook("y")
        deviceA.addBook(x, shaX)
        deviceA.addBook(y, shaY)
        deviceA.read(x.url, 0.80, 1_000L)
        deviceA.read(x.url, 0.20, 2_000L)
        deviceA.read(y.url, 0.60, 3_000L)
        deviceA.changeEngine.sync()
        deviceB.changeEngine.sync()

        assertEquals(ResolveOutcome.Done, deviceB.forget("drive:sha:$shaX"))
        deviceB.changeEngine.sync()
        deviceA.changeEngine.sync()

        assertEquals(0.20, deviceA.liseurDb.readingProgressDao().get(x.url)!!.peakProgression!!, 0.0001)
        assertEquals(0.60, deviceA.liseurDb.readingProgressDao().get(y.url)!!.peakProgression!!, 0.0001)
        assertEquals(0L, deviceA.positionSync.getPersistedFloor(PERM_ID, "sha:$shaY", "dev-a"))
        assertEquals(0.60, deviceB.observed("sha:$shaY").single().progression, 0.0001)

        deviceA.read(y.url, 0.90, 4_000L)
        deviceA.changeEngine.sync()
        deviceB.changeEngine.sync()
        assertEquals(0.90, deviceB.observed("sha:$shaY").single().progression, 0.0001)
    }

    private fun remotePeak(workKey: String, sha: String, origin: String, seq: Long, progression: Double) =
        DriveWireCodec.putRecord(
            DriveWireCodec.DOMAIN_FURTHEST,
            DrivePositionSync.peakRecordKey(workKey, sha, origin),
            "0017450000000-%04d-$origin".format(seq),
            DriveWireCodec.encodeFurthestBody(
                DriveFurthestBody.Peak(
                    workKey = workKey, editionSha = sha, deviceId = origin, seq = seq,
                    progression = progression, locatorJson = """{"href":"s$seq.xhtml"}""",
                    coverage = null, peakAt = 1_000L + seq,
                ),
            ),
        )

    private fun remoteReset(workKey: String, resetId: String, floors: Map<String, Long>) =
        DriveWireCodec.putRecord(
            DriveWireCodec.DOMAIN_FURTHEST,
            DrivePositionSync.resetRecordKey(workKey, resetId),
            "0017450009999-0001-dev-c",
            DriveWireCodec.encodeFurthestBody(DriveFurthestBody.Reset(workKey, resetId, floors, resetAt = 9_000L)),
        )

    private suspend fun DeviceHarness.applyFurthest(record: DriveWireRecord, origin: String) =
        positionSync.applyRemoteFurthest(PERM_ID, deviceId, record, origin, remoteSeq = 1L)

    @Test
    fun `a newer lower peak survives a reset that covers the higher peak whatever the delivery order`() = runTest {
        deviceB.connect()
        val sha = "6".repeat(64)
        val workKey = "sha:$sha"
        val high = remotePeak(workKey, sha, "dev-a", seq = 10L, progression = 0.80)
        val low = remotePeak(workKey, sha, "dev-a", seq = 11L, progression = 0.30)
        val reset = remoteReset(workKey, "r1", mapOf("dev-a" to 10L))

        deviceB.applyFurthest(high, "dev-a")
        deviceB.applyFurthest(low, "dev-a")
        assertEquals(0.80, deviceB.observed(workKey).single().progression, 0.0001)
        deviceB.applyFurthest(reset, "dev-c")
        val afterInOrder = deviceB.observed(workKey).single()
        assertEquals(0.30, afterInOrder.progression, 0.0001)
        assertEquals(11L, afterInOrder.seq)

        // A reset that also covers the lower peak removes it.
        deviceB.applyFurthest(remoteReset(workKey, "r2", mapOf("dev-a" to 11L)), "dev-c")
        assertTrue(deviceB.observed(workKey).isEmpty())

        // Out of order: the lower peak lands first, then the older higher one, then the reset.
        val sha2 = "7".repeat(64)
        val workKey2 = "sha:$sha2"
        deviceB.applyFurthest(remotePeak(workKey2, sha2, "dev-a", seq = 11L, progression = 0.30), "dev-a")
        deviceB.applyFurthest(remotePeak(workKey2, sha2, "dev-a", seq = 10L, progression = 0.80), "dev-a")
        assertEquals(0.80, deviceB.observed(workKey2).single().progression, 0.0001)
        deviceB.applyFurthest(remoteReset(workKey2, "r3", mapOf("dev-a" to 10L)), "dev-c")
        val afterReordered = deviceB.observed(workKey2).single()
        assertEquals(0.30, afterReordered.progression, 0.0001)
        assertEquals(11L, afterReordered.seq)

        // A reset seen first still lets the newer lower peak through and drops the covered one.
        val sha3 = "8".repeat(64)
        val workKey3 = "sha:$sha3"
        deviceB.applyFurthest(remoteReset(workKey3, "r4", mapOf("dev-a" to 10L)), "dev-c")
        deviceB.applyFurthest(remotePeak(workKey3, sha3, "dev-a", seq = 10L, progression = 0.80), "dev-a")
        deviceB.applyFurthest(remotePeak(workKey3, sha3, "dev-a", seq = 11L, progression = 0.30), "dev-a")
        assertEquals(0.30, deviceB.observed(workKey3).single().progression, 0.0001)
    }

    @Test
    fun `two local books of one edition publish one deterministic peak and a forget resets both`() = runTest {
        deviceA.connect()
        deviceB.connect()
        val sha = "9".repeat(64)
        val first = peakBook("dup-a")
        val second = peakBook("dup-b")
        deviceA.addBook(first, sha)
        deviceA.addBook(second, sha)
        deviceA.read(first.url, 0.80, 1_000L)
        deviceA.read(first.url, 0.20, 2_000L)
        deviceA.read(second.url, 0.50, 3_000L)

        val published = deviceA.collectPeaks().records.filter { it.d == DriveWireCodec.DOMAIN_FURTHEST }
        val peak = DriveWireCodec.decodeFurthestBody(requireNotNull(published.single().body)) as DriveFurthestBody.Peak
        assertEquals(0.80, peak.progression, 0.0001)
        assertEquals(
            published.single().peakSeq(),
            deviceA.collectPeaks().records.single { it.d == DriveWireCodec.DOMAIN_FURTHEST }.peakSeq(),
        )

        deviceA.changeEngine.sync()
        deviceB.changeEngine.sync()
        assertEquals(0.80, deviceB.observed("sha:$sha").single().progression, 0.0001)

        assertEquals(ResolveOutcome.Done, deviceA.forget(second.url))
        assertEquals(0.20, deviceA.liseurDb.readingProgressDao().get(first.url)!!.peakProgression!!, 0.0001)
        assertEquals(0.50, deviceA.liseurDb.readingProgressDao().get(second.url)!!.peakProgression!!, 0.0001)

        deviceA.changeEngine.sync()
        deviceB.changeEngine.sync()
        val republished = deviceB.observed("sha:$sha").single()
        assertEquals(0.50, republished.progression, 0.0001)
        assertTrue(republished.seq > deviceA.positionSync.getPersistedFloor(PERM_ID, "sha:$sha", "dev-a"))
        assertTrue(deviceA.collectPeaks().records.none { it.d == DriveWireCodec.DOMAIN_FURTHEST && it.k.startsWith("peak/") })
    }

    @Test
    fun `a remote reset clears every pre-reset peak of duplicate local rows and keeps one authored after the floor`() = runTest {
        deviceA.connect()
        deviceB.connect()
        val sha = "a".repeat(64)
        val workKey = "sha:$sha"
        val first = peakBook("rr-a")
        val second = peakBook("rr-b")
        deviceA.addBook(first, sha)
        deviceA.addBook(second, sha)
        deviceA.read(first.url, 0.80, 1_000L)
        deviceA.read(first.url, 0.20, 2_000L)
        deviceA.read(second.url, 0.60, 3_000L)
        deviceA.read(second.url, 0.40, 4_000L)

        deviceA.changeEngine.sync()
        deviceB.changeEngine.sync()
        val published = deviceB.observed(workKey).single()
        assertEquals(0.80, published.progression, 0.0001)

        // Device C (not modelled) forgot after seeing only the published 80% peak.
        deviceA.applyFurthest(remoteReset(workKey, "rr1", mapOf("dev-a" to published.seq)), "dev-c")

        val progress = deviceA.liseurDb.readingProgressDao()
        assertEquals(0.20, progress.get(first.url)!!.peakProgression!!, 0.0001)
        assertEquals(0.40, progress.get(second.url)!!.peakProgression!!, 0.0001)

        // Nothing from before the floor can be published again, even across a restarted sync object.
        val afterReset = deviceA.collectPeaks().records.filter { it.d == DriveWireCodec.DOMAIN_FURTHEST && it.k.startsWith("peak/") }
        for (record in afterReset) {
            val peak = DriveWireCodec.decodeFurthestBody(requireNotNull(record.body)) as DriveFurthestBody.Peak
            assertTrue(peak.progression < 0.5)
            assertTrue(peak.seq > published.seq)
        }

        // A peak the reader authors after the floor survives and publishes above it.
        deviceA.read(second.url, 0.90, 5_000L)
        deviceA.changeEngine.sync()
        deviceB.changeEngine.sync()
        val genuine = deviceB.observed(workKey).single()
        assertEquals(0.90, genuine.progression, 0.0001)
        assertTrue(genuine.seq > published.seq)
        assertEquals(0.90, progress.get(second.url)!!.peakProgression!!, 0.0001)
    }

    private suspend fun DeviceHarness.seedDownloadable(): Pair<String, BookFingerprint> {
        val bytes = "DEACTIVATION-RACE-EPUB-BYTES-1234567890".toByteArray()
        val fp = BookFingerprints.of(ByteArrayInputStream(bytes))
        val fileId = driveServer.seedEpubFile("race.epub", bytes, fp.sha256, "sha:${fp.sha256}")
        val body = DriveCatalogBody(
            workKey = "sha:${fp.sha256}", title = "Race Book", author = "Author", identifiers = emptyList(),
            editions = listOf(DriveCatalogEdition(fp.sha256, fp.size, fileId, "race.epub", 100L, "0017450000000-0001-dev-a")),
            fileSeriesName = null, fileSeriesIndex = null, addedAt = 100L, archivedAt = null, updatedAt = 100L,
        )
        catalogSync.applyRemoteRecord(
            PERM_ID,
            DriveWireCodec.putRecord(DriveWireCodec.DOMAIN_CATALOG, body.workKey, "0017450000000-0001-dev-a", DriveWireCodec.encodeCatalogBody(body)),
            "dev-a",
            1L,
        ) { changeEngine.nextHlc(PERM_ID) }
        return "drive:sha:${fp.sha256}" to fp
    }

    @Test
    fun `deactivating drains an in-flight EPUB download before returning`() = runTest {
        deviceB.connect()
        val (stubUrl, fp) = deviceB.seedDownloadable()
        val entered = CompletableDeferred<Unit>()
        driveServer.onDownloadEntered = entered
        driveServer.downloadGate = CompletableDeferred()
        val download = async { deviceB.driveSync.epubTransfers.downloadBook(stubUrl) }
        entered.await()

        deviceB.driveSync.deactivate()

        assertTrue("deactivate must not return while the download is still running", download.isCompleted)
        assertTrue(download.isCancelled)
        val installed = DriveEpubTransferManager.downloadTargetFile(File(context.filesDir, "books"), stubUrl, fp.sha256)
        assertFalse(installed.exists())
        assertFalse(deviceB.liseurDb.bookDao().getByUrl(stubUrl)!!.downloadState == DownloadState.DOWNLOADED)
    }

    @Test
    fun `a verified download is not installed once the account stopped being active`() = runTest {
        deviceB.connect()
        val (stubUrl, fp) = deviceB.seedDownloadable()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        driveServer.onDownloadEntered = entered
        driveServer.downloadGate = gate
        driveServer.downloadGateIgnoresCancellation = true
        val download = async { deviceB.epubTransfers.downloadBook(stubUrl) }
        entered.await()
        deviceB.driveDb.accounts().pauseAllActive()
        gate.complete(Unit)

        assertTrue(download.await() is DriveEpubDownloadOutcome.RetryableFailure)
        assertFalse(DriveEpubTransferManager.downloadTargetFile(deviceB.booksDir, stubUrl, fp.sha256).exists())
        assertEquals(DownloadState.QUEUED, deviceB.liseurDb.bookDao().getByUrl(stubUrl)!!.downloadState)
    }

    @Test
    fun `resumable download verifies SHA-256 before moving into place and resumes partial downloads`() = runTest {
        deviceA.connect()
        deviceB.connect()

        val validBytes = "VALID-EPUB-BYTES-FOR-SHA-VERIFICATION-1234567890".toByteArray()
        val fp = BookFingerprints.of(ByteArrayInputStream(validBytes))
        val stubUrl = "drive:sha:${fp.sha256}"
        val fileId = driveServer.seedEpubFile("valid.epub", validBytes, fp.sha256, "sha:${fp.sha256}")

        // Seed catalog state & remote stub on Device B
        val body = DriveCatalogBody(
            workKey = "sha:${fp.sha256}", title = "Valid Book", author = "Author",
            identifiers = emptyList(),
            editions = listOf(DriveCatalogEdition(fp.sha256, fp.size, fileId, "valid.epub", 100L, "0017450000000-0001-dev-a")),
            fileSeriesName = null, fileSeriesIndex = null, addedAt = 100L, archivedAt = null, updatedAt = 100L,
        )
        deviceB.catalogSync.applyRemoteRecord(
            PERM_ID,
            DriveWireCodec.putRecord(DriveWireCodec.DOMAIN_CATALOG, body.workKey, "0017450000000-0001-dev-a", DriveWireCodec.encodeCatalogBody(body)),
            "dev-a",
            1L,
        ) { deviceB.changeEngine.nextHlc(PERM_ID) }

        // Case 1: Server returns corrupted bytes -> SHA-256 fails, .part deleted, book NOT moved into place
        driveServer.corruptNextDownload = true
        val failed = deviceB.epubTransfers.downloadBook(stubUrl)
        assertTrue(failed is DriveEpubDownloadOutcome.PermanentFailure)
        assertEquals(DownloadState.FAILED, deviceB.liseurDb.bookDao().getByUrl(stubUrl)!!.downloadState)
        assertFalse(DriveEpubTransferManager.downloadTargetFile(deviceB.booksDir, stubUrl, fp.sha256).exists())
        assertFalse(DriveEpubTransferManager.downloadPartFile(deviceB.booksDir, stubUrl, fp.sha256).exists())

        // Case 2: Pre-existing .part file resumes from offset, passes SHA-256, moves atomically into place
        driveServer.corruptNextDownload = false
        deviceB.booksDir.mkdirs()
        deviceB.liseurDb.bookDao().setFileMetadataChecked(stubUrl, true)
        DriveEpubTransferManager.downloadPartFile(deviceB.booksDir, stubUrl, fp.sha256).writeBytes(validBytes.copyOfRange(0, 10))
        val resumed = deviceB.epubTransfers.downloadBook(stubUrl)
        assertTrue(resumed is DriveEpubDownloadOutcome.Downloaded)
        assertEquals(10L, driveServer.lastDownloadStartOffset)
        val finalFile = DriveEpubTransferManager.downloadTargetFile(deviceB.booksDir, stubUrl, fp.sha256)
        assertTrue(finalFile.isFile)
        assertEquals(validBytes.toList(), finalFile.readBytes().toList())
        assertEquals(DownloadState.DOWNLOADED, deviceB.liseurDb.bookDao().getByUrl(stubUrl)!!.downloadState)
        // Arriving bytes invalidate what was derived from the previous file, then store the verified fingerprint.
        assertFalse(deviceB.liseurDb.bookDao().getByUrl(stubUrl)!!.fileMetadataChecked)
        assertEquals(fp.sha256, deviceB.liseurDb.workIdentityDao().fingerprint(stubUrl)?.sha256)
        assertEquals(
            deviceB.liseurDb.bookDao().getByUrl(stubUrl)!!.fileModifiedAt,
            deviceB.liseurDb.workIdentityDao().fingerprint(stubUrl)?.fileModifiedAt,
        )
    }

    @Test
    fun `crash recovery replays exact position, status, and peak delivery ACKs without false dirty state or false conflicts`() = runTest {
        deviceA.connect()
        deviceB.connect()

        // Duplicate Liseur/ folder race: older createdTime wins canonically
        driveServer.seedFolder("folder-newer", "2026-04-02T12:00:00Z")
        driveServer.seedFolder("folder-older", "2026-04-01T12:00:00Z")
        val resolved = driveServer.ensureLiseurFolder("token-ok")
        assertEquals("folder-older", resolved.canonical.id)
        assertEquals(2, resolved.allFolders.size)

        val isbn = "9780547928227"
        val sha = "e".repeat(64)
        val bookA = Book(
            url = "file:///crash-a.epub", title = "Crash Book", author = "Author",
            coverPath = null, source = null, addedAt = 100L, lastOpenedAt = null,
            localUri = "file:///crash-a.epub", downloadState = DownloadState.DOWNLOADED,
            fileTitle = "Crash Book", fileAuthor = "Author", fileMetadataChecked = true,
        )
        val bookB = bookA.copy(url = "file:///crash-b.epub", localUri = "file:///crash-b.epub")
        deviceA.liseurDb.bookDao().upsert(bookA)
        deviceA.liseurDb.workIdentityDao().upsert(BookFingerprintRow(bookA.url, sha, "m1", 100L, null, 1L))
        deviceA.liseurDb.bookMetadataDao().upsert(
            BookMetadata.of(bookA.url, MetadataSource.FILE, MetadataSnapshot(identifiers = listOf(Identifier("isbn", isbn)))),
        )
        deviceB.liseurDb.bookDao().upsert(bookB)
        deviceB.liseurDb.workIdentityDao().upsert(BookFingerprintRow(bookB.url, sha, "m1", 100L, null, 1L))
        deviceB.liseurDb.bookMetadataDao().upsert(
            BookMetadata.of(bookB.url, MetadataSource.FILE, MetadataSnapshot(identifiers = listOf(Identifier("isbn", isbn)))),
        )

        // Create REAL position (0.40), peak (0.60), and status override (PAUSED) on Device A.
        deviceA.liseurDb.readingProgressDao().recordLocal(
            bookUrl = bookA.url, locatorJson = """{"href":"ch6.xhtml"}""", progression = 0.60,
            readingSecondsPerPosition = null, readingPaceSamples = null, readingPaceElapsedMs = null,
            readingPaceEvidence = null, status = "Reading", updatedAt = 1_000L,
        )
        deviceA.liseurDb.readingProgressDao().recordLocal(
            bookUrl = bookA.url, locatorJson = """{"href":"ch4.xhtml"}""", progression = 0.40,
            readingSecondsPerPosition = null, readingPaceSamples = null, readingPaceElapsedMs = null,
            readingPaceEvidence = null, status = "Reading", updatedAt = 2_000L,
        )
        deviceA.finishedState.setReadingChoice(bookA.url, ReadingStatusChoice.PAUSED)
        val progressBeforeCrash = deviceA.liseurDb.readingProgressDao().get(bookA.url)!!

        // Simulate crash immediately after batch upload lands on Drive (before markUploaded/ACK).
        driveServer.failAfterNextBatchUpload = true
        val crashed = deviceA.changeEngine.sync()
        assertEquals(SyncOutcome.Failure(SyncFailure.Offline), crashed.outcome)

        val inFlight = deviceA.driveDb.outbox().inFlight(PERM_ID)
        assertNotNull(inFlight)
        assertEquals(DriveOutbox.STATE_BUILT, inFlight!!.state)
        assertNotNull(inFlight.ackMetadataJson)
        val peerId = DriveLibrarySync.accountPeerId(PERM_ID)
        assertNull(deviceA.liseurDb.syncPeerStateDao().get(bookA.url, peerId))
        assertNull(deviceA.liseurDb.furthestPositionDao().delivered(peerId, bookA.url, "isbn:$isbn"))
        val filesBeforeRecovery = driveServer.appDataFileCount()

        // Recovery must deduplicate on batchId AND replay the exact original position/status/peak ACKs!
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(DriveOutbox.STATE_ACKED, deviceA.driveDb.outbox().get(PERM_ID, inFlight.seq)?.state)
        assertEquals(filesBeforeRecovery + 1, driveServer.appDataFileCount()) // only manifest added, no duplicate delta

        val peerStateAfter = deviceA.liseurDb.syncPeerStateDao().get(bookA.url, peerId)!!
        assertEquals(progressBeforeCrash.localRevision, peerStateAfter.ackedRevision)
        assertEquals(progressBeforeCrash.statusRevision, peerStateAfter.ackedStatusRevision)
        assertFalse(peerStateAfter.isDirty(progressBeforeCrash.localRevision))
        assertFalse(peerStateAfter.owesStatus(progressBeforeCrash.statusRevision))
        val peakDelivery = deviceA.liseurDb.furthestPositionDao().delivered(peerId, bookA.url, "isbn:$isbn")
        assertNotNull(peakDelivery)
        assertTrue(peakDelivery!!.acknowledged)
        assertEquals(progressBeforeCrash.peakRevision, peakDelivery.revision)

        // Device B pulls A's recovered batch, reads on to 0.75, and syncs -> A must pull cleanly with NO false conflict!
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertEquals(0.40, deviceB.liseurDb.readingProgressDao().get(bookB.url)!!.totalProgression!!, 0.0001)
        deviceB.liseurDb.readingProgressDao().recordLocal(
            bookUrl = bookB.url, locatorJson = """{"href":"ch7.xhtml"}""", progression = 0.75,
            readingSecondsPerPosition = null, readingPaceSamples = null, readingPaceElapsedMs = null,
            readingPaceEvidence = null, status = "Reading", updatedAt = 5_000L,
        )
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertNull(deviceA.positionSync.preservedConflict(bookA.url))
        assertEquals(0.75, deviceA.liseurDb.readingProgressDao().get(bookA.url)!!.totalProgression!!, 0.0001)

        // Also verify recovery replays ORIGINAL revisions (not current rows) when user turns a page after crash!
        deviceA.liseurDb.readingProgressDao().recordLocal(
            bookUrl = bookA.url, locatorJson = """{"href":"ch8.xhtml"}""", progression = 0.80,
            readingSecondsPerPosition = null, readingPaceSamples = null, readingPaceElapsedMs = null,
            readingPaceEvidence = null, status = "Reading", updatedAt = 6_000L,
        )
        driveServer.failAfterNextBatchUpload = true
        assertEquals(SyncOutcome.Failure(SyncFailure.Offline), deviceA.changeEngine.sync().outcome)
        // Reader advances again locally BEFORE crash recovery runs!
        deviceA.liseurDb.readingProgressDao().recordLocal(
            bookUrl = bookA.url, locatorJson = """{"href":"ch9.xhtml"}""", progression = 0.88,
            readingSecondsPerPosition = null, readingPaceSamples = null, readingPaceElapsedMs = null,
            readingPaceEvidence = null, status = "Reading", updatedAt = 7_000L,
        )
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertEquals(0.88, deviceB.liseurDb.readingProgressDao().get(bookB.url)!!.totalProgression!!, 0.0001)
    }

    @Test
    fun `adopting remote status preserves unsent local backward position move`() = runTest {
        deviceA.connect()
        deviceB.connect()

        val isbn = "9780441172726"
        val sha = "f".repeat(64)
        val bookA = Book(
            url = "file:///status-a.epub", title = "Status Book", author = "Author",
            coverPath = null, source = null, addedAt = 100L, lastOpenedAt = null,
            localUri = "file:///status-a.epub", downloadState = DownloadState.DOWNLOADED,
            fileTitle = "Status Book", fileAuthor = "Author", fileMetadataChecked = true,
        )
        val bookB = bookA.copy(url = "file:///status-b.epub", localUri = "file:///status-b.epub")
        deviceA.liseurDb.bookDao().upsert(bookA)
        deviceA.liseurDb.workIdentityDao().upsert(BookFingerprintRow(bookA.url, sha, "m1", 100L, null, 1L))
        deviceA.liseurDb.bookMetadataDao().upsert(
            BookMetadata.of(bookA.url, MetadataSource.FILE, MetadataSnapshot(identifiers = listOf(Identifier("isbn", isbn)))),
        )
        deviceB.liseurDb.bookDao().upsert(bookB)
        deviceB.liseurDb.workIdentityDao().upsert(BookFingerprintRow(bookB.url, sha, "m1", 100L, null, 1L))
        deviceB.liseurDb.bookMetadataDao().upsert(
            BookMetadata.of(bookB.url, MetadataSource.FILE, MetadataSnapshot(identifiers = listOf(Identifier("isbn", isbn)))),
        )

        // Baseline: both A and B synced at 0.40.
        deviceA.liseurDb.readingProgressDao().recordLocal(
            bookUrl = bookA.url, locatorJson = """{"href":"ch4.xhtml","locations":{"totalProgression":0.4}}""",
            progression = 0.40, readingSecondsPerPosition = null, readingPaceSamples = null,
            readingPaceElapsedMs = null, readingPaceEvidence = null, status = "Reading", updatedAt = 1_000L,
        )
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertEquals(0.40, deviceB.liseurDb.readingProgressDao().get(bookB.url)!!.totalProgression!!, 0.0001)

        // Offline: B rereads backward to 0.18 (position-dirty, status-clean), while A moves to 0.65 AND sets PAUSED.
        val backwardLocator = """{"href":"ch2.xhtml","locations":{"liseurAnchor":1,"cssSelector":"p#back","totalProgression":0.18},"text":{"highlight":"Earlier"},"type":"application/xhtml+xml"}"""
        deviceB.liseurDb.readingProgressDao().recordLocal(
            bookUrl = bookB.url, locatorJson = backwardLocator, progression = 0.18,
            readingSecondsPerPosition = null, readingPaceSamples = null, readingPaceElapsedMs = null,
            readingPaceEvidence = null, status = "Reading", updatedAt = 2_000L,
        )
        deviceA.liseurDb.readingProgressDao().recordLocal(
            bookUrl = bookA.url, locatorJson = """{"href":"ch6.xhtml","locations":{"liseurAnchor":1,"cssSelector":"p#fwd","totalProgression":0.65},"text":{"highlight":"Later"},"type":"application/xhtml+xml"}""",
            progression = 0.65, readingSecondsPerPosition = null, readingPaceSamples = null,
            readingPaceElapsedMs = null, readingPaceEvidence = null, status = "Reading", updatedAt = 2_500L,
        )
        deviceA.finishedState.setReadingChoice(bookA.url, ReadingStatusChoice.PAUSED)

        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)

        // B MUST adopt PAUSED status WITHOUT silently overwriting its backward move to 0.18!
        val progressB = deviceB.liseurDb.readingProgressDao().get(bookB.url)!!
        assertEquals(FinishedOverride.PAUSED, progressB.override)
        assertEquals(0.18, progressB.totalProgression!!, 0.0001)
        assertTrue(progressB.locatorJson.contains("p#back"))
        val conflict = deviceB.positionSync.preservedConflict(bookB.url)
        assertNotNull(conflict)
        assertEquals(0.18, conflict!!.local!!, 0.0001)
        assertEquals(0.65, conflict.remote!!, 0.0001)
    }

    @Test
    fun `returning reading status to Automatic clears remote Finished and Paused overrides`() = runTest {
        deviceA.connect()
        deviceB.connect()

        val isbn = "9780441172733"
        val sha = "1".repeat(64)
        val bookA = Book(
            url = "file:///auto-a.epub", title = "Auto Book", author = "Author",
            coverPath = null, source = null, addedAt = 100L, lastOpenedAt = null,
            localUri = "file:///auto-a.epub", downloadState = DownloadState.DOWNLOADED,
            fileTitle = "Auto Book", fileAuthor = "Author", fileMetadataChecked = true,
        )
        val bookB = bookA.copy(url = "file:///auto-b.epub", localUri = "file:///auto-b.epub")
        deviceA.liseurDb.bookDao().upsert(bookA)
        deviceA.liseurDb.workIdentityDao().upsert(BookFingerprintRow(bookA.url, sha, "m1", 100L, null, 1L))
        deviceA.liseurDb.bookMetadataDao().upsert(
            BookMetadata.of(bookA.url, MetadataSource.FILE, MetadataSnapshot(identifiers = listOf(Identifier("isbn", isbn)))),
        )
        deviceB.liseurDb.bookDao().upsert(bookB)
        deviceB.liseurDb.workIdentityDao().upsert(BookFingerprintRow(bookB.url, sha, "m1", 100L, null, 1L))
        deviceB.liseurDb.bookMetadataDao().upsert(
            BookMetadata.of(bookB.url, MetadataSource.FILE, MetadataSnapshot(identifiers = listOf(Identifier("isbn", isbn)))),
        )

        // Start synced at 0.40 (Reading).
        deviceA.liseurDb.readingProgressDao().recordLocal(
            bookUrl = bookA.url, locatorJson = """{"href":"ch4.xhtml"}""", progression = 0.40,
            readingSecondsPerPosition = null, readingPaceSamples = null, readingPaceElapsedMs = null,
            readingPaceEvidence = null, status = "Reading", updatedAt = 1_000L,
        )
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)

        // Case 1: Finished -> Automatic clears finished_override and library finishedAt on B.
        deviceA.finishedState.setReadingChoice(bookA.url, ReadingStatusChoice.FINISHED)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val afterFinished = deviceB.liseurDb.readingProgressDao().get(bookB.url)!!
        assertEquals(FinishedOverride.FINISHED, afterFinished.override)
        assertEquals(ReadingStatus.FINISHED, readingStatusFor(afterFinished.totalProgression, afterFinished.override, afterFinished.partialCoverage))
        assertNotNull(deviceB.liseurDb.bookDao().getByUrl(bookB.url)!!.finishedAt)

        deviceA.finishedState.setReadingChoice(bookA.url, ReadingStatusChoice.AUTOMATIC)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val afterFinishedAuto = deviceB.liseurDb.readingProgressDao().get(bookB.url)!!
        assertEquals(FinishedOverride.NONE, afterFinishedAuto.override)
        assertEquals(ReadingStatus.READING, readingStatusFor(afterFinishedAuto.totalProgression, afterFinishedAuto.override, afterFinishedAuto.partialCoverage))
        assertNull(deviceB.liseurDb.bookDao().getByUrl(bookB.url)!!.finishedAt)

        // Case 2: Paused -> Automatic clears finished_override on B.
        deviceA.finishedState.setReadingChoice(bookA.url, ReadingStatusChoice.PAUSED)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val afterPaused = deviceB.liseurDb.readingProgressDao().get(bookB.url)!!
        assertEquals(FinishedOverride.PAUSED, afterPaused.override)
        assertEquals(ReadingStatus.PAUSED, readingStatusFor(afterPaused.totalProgression, afterPaused.override, afterPaused.partialCoverage))

        deviceA.finishedState.setReadingChoice(bookA.url, ReadingStatusChoice.AUTOMATIC)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val afterPausedAuto = deviceB.liseurDb.readingProgressDao().get(bookB.url)!!
        assertEquals(FinishedOverride.NONE, afterPausedAuto.override)
        assertEquals(ReadingStatus.READING, readingStatusFor(afterPausedAuto.totalProgression, afterPausedAuto.override, afterPausedAuto.partialCoverage))
    }

    @Test
    fun `book-scoped sync respects open-reader fence and preserves pending locator`() = runTest {
        deviceA.connect()
        deviceB.connect()

        val isbn = "9780441172740"
        val sha = "2".repeat(64)
        val bookA = Book(
            url = "file:///open-a.epub", title = "Open Book", author = "Author",
            coverPath = null, source = null, addedAt = 100L, lastOpenedAt = null,
            localUri = "file:///open-a.epub", downloadState = DownloadState.DOWNLOADED,
            fileTitle = "Open Book", fileAuthor = "Author", fileMetadataChecked = true,
        )
        val bookB = bookA.copy(url = "file:///open-b.epub", localUri = "file:///open-b.epub")
        deviceA.liseurDb.bookDao().upsert(bookA)
        deviceA.liseurDb.workIdentityDao().upsert(BookFingerprintRow(bookA.url, sha, "m1", 100L, null, 1L))
        deviceA.liseurDb.bookMetadataDao().upsert(
            BookMetadata.of(bookA.url, MetadataSource.FILE, MetadataSnapshot(identifiers = listOf(Identifier("isbn", isbn)))),
        )
        deviceB.liseurDb.bookDao().upsert(bookB)
        deviceB.liseurDb.workIdentityDao().upsert(BookFingerprintRow(bookB.url, sha, "m1", 100L, null, 1L))
        deviceB.liseurDb.bookMetadataDao().upsert(
            BookMetadata.of(bookB.url, MetadataSource.FILE, MetadataSnapshot(identifiers = listOf(Identifier("isbn", isbn)))),
        )

        // Sync both at 0.30.
        deviceA.liseurDb.readingProgressDao().recordLocal(
            bookUrl = bookA.url, locatorJson = """{"href":"ch3.xhtml"}""", progression = 0.30,
            readingSecondsPerPosition = null, readingPaceSamples = null, readingPaceElapsedMs = null,
            readingPaceEvidence = null, status = "Reading", updatedAt = 1_000L,
        )
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertEquals(0.30, deviceB.liseurDb.readingProgressDao().get(bookB.url)!!.totalProgression!!, 0.0001)

        // Device A advances to 0.70 and publishes.
        deviceA.liseurDb.readingProgressDao().recordLocal(
            bookUrl = bookA.url, locatorJson = """{"href":"ch7.xhtml"}""", progression = 0.70,
            readingSecondsPerPosition = null, readingPaceSamples = null, readingPaceElapsedMs = null,
            readingPaceEvidence = null, status = "Reading", updatedAt = 2_000L,
        )
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)

        // Reader opens the book on Device B BEFORE a delayed book-scoped sync arrives.
        deviceB.liseurDb.readingProgressDao().openBooks.enter(bookB.url)
        try {
            assertEquals(SyncOutcome.Success, deviceB.positionSync.syncBook(bookB.url))
            // Open reader's persisted locator MUST NOT be clobbered!
            val progressB = deviceB.liseurDb.readingProgressDao().get(bookB.url)!!
            assertEquals(0.30, progressB.totalProgression!!, 0.0001)
            assertTrue(progressB.locatorJson.contains("ch3.xhtml"))
            val pending = deviceB.liseurDb.syncPeerStateDao().get(bookB.url, "${DriveLibrarySync.accountPeerId(PERM_ID)}/dev-a")!!
            assertTrue(pending.hasPending)
            assertEquals(0.70, pending.pendingProgression!!, 0.0001)
        } finally {
            deviceB.liseurDb.readingProgressDao().openBooks.leave(bookB.url)
        }
    }

    @Test
    fun `expired pageToken full rescan reconciles Drive-deleted EPUBs and clears stale availability`() = runTest {
        deviceA.connect()
        deviceB.connect()

        val epubBytes = "EPUB-DELETED-DURING-EXPIRED-TOKEN".toByteArray()
        val bookFile = File(tempDir, "deleted-token.epub").apply { writeBytes(epubBytes) }
        val fp = BookFingerprints.of(ByteArrayInputStream(epubBytes))
        val bookA = Book(
            url = bookFile.toURI().toString(), title = "Vanishing Book", author = "Author",
            coverPath = null, source = null, addedAt = 1_000L, lastOpenedAt = null,
            localUri = bookFile.toURI().toString(), downloadState = DownloadState.DOWNLOADED,
            sizeBytes = fp.size, fileTitle = "Vanishing Book", fileAuthor = "Author", fileMetadataChecked = true,
        )
        deviceA.liseurDb.bookDao().upsert(bookA)
        deviceA.liseurDb.workIdentityDao().upsert(BookFingerprintRow(bookA.url, fp.sha256, fp.partialMd5, fp.size, bookFile.lastModified(), 1L))
        deviceA.liseurDb.bookMetadataDao().upsert(
            BookMetadata.of(bookA.url, MetadataSource.FILE, MetadataSnapshot(identifiers = listOf(Identifier("isbn", "9780441172757")))),
        )

        // Upload EPUB from A and pull catalog stub on B.
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(bookA.url)))
        assertEquals(1, deviceA.epubTransfers.uploadPending().uploadedCount)
        val uploadedFileId = deviceA.driveDb.uploads().get(PERM_ID, fp.sha256)!!.fileId!!

        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val stubUrl = "drive:isbn:9780441172757"
        assertEquals(DriveCatalogSync.downloadHrefFor(uploadedFileId), deviceB.liseurDb.bookDao().getByUrl(stubUrl)!!.downloadHref)

        // EPUB is deleted directly in Drive AND B's pageToken expires before B syncs again.
        driveServer.deleteFile("token-ok", uploadedFileId)
        deviceB.driveDb.files().setTrashed(PERM_ID, uploadedFileId, true)
        driveServer.expireNextPageToken = true

        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertTrue(deviceB.driveDb.files().get(PERM_ID, uploadedFileId)!!.trashed)
        assertNull(deviceB.liseurDb.bookDao().getByUrl(stubUrl)!!.downloadHref)
        assertNull(deviceB.catalogSync.resolveDownloadSource(PERM_ID, deviceB.liseurDb.bookDao().getByUrl(stubUrl)!!))
    }

    @Test
    fun `changed upload source discards the old resumable session`() = runTest {
        deviceA.connect()
        val oldBytes = "old EPUB contents for upload".toByteArray()
        val file = File(tempDir, "changed-upload.epub").apply { writeBytes(oldBytes) }
        val oldFp = BookFingerprints.of(ByteArrayInputStream(oldBytes))
        val book = Book(
            url = file.toURI().toString(), title = "Changed", author = "Author", coverPath = null,
            source = null, addedAt = 1L, lastOpenedAt = null, localUri = file.toURI().toString(),
            downloadState = DownloadState.DOWNLOADED, fileMetadataChecked = true,
        )
        deviceA.liseurDb.bookDao().upsert(book)
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(book.url)))
        driveServer.failNextResumableUploadAfterBytes = 8L to IOException("interrupted")
        assertTrue(deviceA.epubTransfers.uploadPending().retryableFailure)
        assertNotNull(deviceA.driveDb.uploads().get(PERM_ID, oldFp.sha256)?.sessionUri)

        val newBytes = "new and different EPUB contents".toByteArray()
        val newFp = BookFingerprints.of(ByteArrayInputStream(newBytes))
        file.writeBytes(newBytes)
        assertTrue(deviceA.epubTransfers.uploadPending().retryableFailure)
        assertNull(deviceA.driveDb.uploads().get(PERM_ID, oldFp.sha256))
        val replacement = requireNotNull(deviceA.driveDb.uploads().get(PERM_ID, newFp.sha256))
        assertNull(replacement.sessionUri)
        assertEquals(0L, replacement.bytesSent)
        driveServer.failNextResumableUploadAfterBytes = null
        assertEquals(1, deviceA.epubTransfers.uploadPending().uploadedCount)
    }

    @Test
    fun `mid-upload failures preserve resumable session URI and byte offset across retries`() = runTest {
        deviceA.connect()

        val epubBytes = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-RESUMABLE-EPUB-CONTENT".toByteArray()
        val bookFile = File(tempDir, "resumable-upload.epub").apply { writeBytes(epubBytes) }
        val fp = BookFingerprints.of(ByteArrayInputStream(epubBytes))
        val bookA = Book(
            url = bookFile.toURI().toString(), title = "Resumable Book", author = "Author",
            coverPath = null, source = null, addedAt = 1_000L, lastOpenedAt = null,
            localUri = bookFile.toURI().toString(), downloadState = DownloadState.DOWNLOADED,
            sizeBytes = fp.size, fileTitle = "Resumable Book", fileAuthor = "Author", fileMetadataChecked = true,
        )
        deviceA.liseurDb.bookDao().upsert(bookA)
        deviceA.liseurDb.workIdentityDao().upsert(BookFingerprintRow(bookA.url, fp.sha256, fp.partialMd5, fp.size, bookFile.lastModified(), 1L))

        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(bookA.url)))

        // Attempt 1: fail with IOException after session URI is issued and 16 bytes are persisted.
        driveServer.failNextResumableUploadAfterBytes = 16L to IOException("connection reset")
        val attempt1 = deviceA.epubTransfers.uploadPending()
        assertEquals(0, attempt1.uploadedCount)
        assertEquals(1, attempt1.failedCount)
        assertTrue(attempt1.retryableFailure)

        val afterIoFail = deviceA.driveDb.uploads().get(PERM_ID, fp.sha256)!!
        assertEquals(DriveUpload.STATE_FAILED, afterIoFail.state)
        val savedSessionUri = afterIoFail.sessionUri
        assertNotNull(savedSessionUri)
        assertEquals(16L, afterIoFail.bytesSent)

        // Attempt 2: resumes with saved session URI, advances to 32 bytes, then fails with quota exception.
        driveServer.failNextResumableUploadAfterBytes = 32L to DriveQuotaException("rate limit", 429, isStorageQuota = false)
        val attempt2 = deviceA.epubTransfers.uploadPending()
        assertEquals(0, attempt2.uploadedCount)
        assertTrue(attempt2.retryableFailure)
        assertEquals(savedSessionUri, driveServer.lastResumedUploadSessionUri)

        val afterQuotaFail = deviceA.driveDb.uploads().get(PERM_ID, fp.sha256)!!
        assertEquals(DriveUpload.STATE_FAILED, afterQuotaFail.state)
        assertEquals(savedSessionUri, afterQuotaFail.sessionUri)
        assertEquals(32L, afterQuotaFail.bytesSent)

        // Attempt 3: succeeds using the exact same preserved resumable session URI!
        driveServer.failNextResumableUploadAfterBytes = null
        val attempt3 = deviceA.epubTransfers.uploadPending()
        assertEquals(1, attempt3.uploadedCount)
        assertEquals(savedSessionUri, driveServer.lastResumedUploadSessionUri)
        val finalRow = deviceA.driveDb.uploads().get(PERM_ID, fp.sha256)!!
        assertEquals(DriveUpload.STATE_UPLOADED, finalRow.state)
        assertNotNull(finalRow.fileId)
    }

    @Test
    fun `Drive-deleted EPUB invalidates upload queue row without auto-reuploading and allows explicit re-selection`() = runTest {
        deviceA.connect()

        val epubBytes = "EPUB-REUPLOAD-AFTER-DRIVE-DELETE".toByteArray()
        val bookFile = File(tempDir, "reupload.epub").apply { writeBytes(epubBytes) }
        val fp = BookFingerprints.of(ByteArrayInputStream(epubBytes))
        val bookA = Book(
            url = bookFile.toURI().toString(), title = "Reupload Book", author = "Author",
            coverPath = null, source = null, addedAt = 1_000L, lastOpenedAt = null,
            localUri = bookFile.toURI().toString(), downloadState = DownloadState.DOWNLOADED,
            sizeBytes = fp.size, fileTitle = "Reupload Book", fileAuthor = "Author", fileMetadataChecked = true,
        )
        deviceA.liseurDb.bookDao().upsert(bookA)
        deviceA.liseurDb.workIdentityDao().upsert(BookFingerprintRow(bookA.url, fp.sha256, fp.partialMd5, fp.size, bookFile.lastModified(), 1L))

        // Initial upload succeeds.
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(bookA.url)))
        assertEquals(1, deviceA.epubTransfers.uploadPending().uploadedCount)
        val firstFileId = deviceA.driveDb.uploads().get(PERM_ID, fp.sha256)!!.fileId!!
        // Ensure A has a startPageToken for incremental changes.list.
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        val workKey = requireNotNull(deviceA.liseurDb.workIdentityDao().alias(bookA.url, DriveLibrarySync.accountPeerId(PERM_ID))).workId
        val before = requireNotNull(deviceA.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_CATALOG, workKey))
        val beforeEdition = requireNotNull(DriveWireCodec.decodeCatalogBody(org.json.JSONObject(requireNotNull(before.bodyJson)))).editions.single()

        // Reader deletes the EPUB directly in Google Drive, then Device A syncs.
        driveServer.deleteFile("token-ok", firstFileId)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        val after = requireNotNull(deviceA.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_CATALOG, workKey))
        val afterEdition = requireNotNull(DriveWireCodec.decodeCatalogBody(org.json.JSONObject(requireNotNull(after.bodyJson)))).editions.single()
        assertNull(afterEdition.driveFileId)
        assertNull(afterEdition.fileName)
        assertTrue(requireNotNull(afterEdition.availabilityHlc) > requireNotNull(beforeEdition.availabilityHlc))
        assertEquals(DriveCanonicalJson.putFp(org.json.JSONObject(after.bodyJson), after.lastHlc), after.versionFp)

        // The queue row becomes a suppression: not UPLOADED, not queued, and the shelf's
        // automatic offer leaves it alone until the reader asks again.
        val suppressedRow = requireNotNull(deviceA.driveDb.uploads().get(PERM_ID, fp.sha256))
        assertEquals(DriveUpload.STATE_SUPPRESSED, suppressedRow.state)
        assertNull(suppressedRow.fileId)
        assertTrue(deviceA.driveDb.uploads().pendingForAccount(PERM_ID).isEmpty())
        val candidateAfterDelete = deviceA.epubTransfers.listUploadCandidates(PERM_ID).single()
        assertFalse(candidateAfterDelete.isUploaded)
        assertFalse(candidateAfterDelete.isPendingOrUploading)

        // Reader explicitly re-selects the book in "Upload EPUB files" -> queues and uploads a fresh Drive file!
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(bookA.url)))
        val reuploadSummary = deviceA.epubTransfers.uploadPending()
        assertEquals(1, reuploadSummary.uploadedCount)
        val secondFileId = deviceA.driveDb.uploads().get(PERM_ID, fp.sha256)!!.fileId!!
        assertTrue(secondFileId != firstFileId)
        assertEquals(DriveUpload.STATE_UPLOADED, deviceA.driveDb.uploads().get(PERM_ID, fp.sha256)!!.state)
    }

    /** A restart must not resurrect what the reader deleted in Drive, whatever the policy. */
    @Test
    fun `a Drive-deleted EPUB stays suppressed across a restart`() = runTest {
        deviceA.connect()

        val epubBytes = "EPUB-STAY-DELETED".toByteArray()
        val bookFile = File(tempDir, "stay-deleted.epub").apply { writeBytes(epubBytes) }
        val fp = BookFingerprints.of(ByteArrayInputStream(epubBytes))
        val book = Book(
            url = bookFile.toURI().toString(), title = "Stay Deleted", author = "Author",
            coverPath = null, source = null, addedAt = 1_000L, lastOpenedAt = null,
            localUri = bookFile.toURI().toString(), downloadState = DownloadState.DOWNLOADED,
            sizeBytes = fp.size, fileTitle = "Stay Deleted", fileAuthor = "Author", fileMetadataChecked = true,
        )
        deviceA.liseurDb.bookDao().upsert(book)
        deviceA.liseurDb.workIdentityDao().upsert(
            BookFingerprintRow(book.url, fp.sha256, fp.partialMd5, fp.size, bookFile.lastModified(), 1L),
        )
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(book.url)))
        assertEquals(1, deviceA.epubTransfers.uploadPending().uploadedCount)
        val fileId = deviceA.driveDb.uploads().get(PERM_ID, fp.sha256)!!.fileId!!
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)

        driveServer.deleteFile("token-ok", fileId)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)

        // After a restart the target still withholds the book, so no policy offers it again.
        val target = requireNotNull(deviceA.driveSync.uploadTarget.first { it != null })
        assertTrue(book.url in target.withheld)
        assertTrue(target.queued.isEmpty())

        // Asking again by hand clears the suppression and uploads fresh bytes.
        assertEquals(setOf(book.url), deviceA.epubTransfers.queueBooks(PERM_ID, listOf(book.url)))
        assertEquals(1, deviceA.epubTransfers.uploadPending().uploadedCount)
        assertNotEquals(fileId, deviceA.driveDb.uploads().get(PERM_ID, fp.sha256)!!.fileId)
    }

    @Test
    fun `a supported paused domain replays indexed deltas without new Drive changes`() = runTest {
        deviceB.connect()
        val key = "isbn:replay"
        val hlc = "0017450000000-0001-dev-other"
        val body = DriveCatalogBody(key, "Replay", null, emptyList(), emptyList(), null, null, 1L, null, 1L)
        val record = DriveWireCodec.putRecord(DriveWireCodec.DOMAIN_CATALOG, key, hlc, DriveWireCodec.encodeCatalogBody(body))
        val batch = DriveDeltaBatch(batchId = DriveCanonicalJson.batchId("dev-other", 1L), deviceId = "dev-other", seq = 1L,
            createdHlc = hlc, domains = mapOf(DriveWireCodec.DOMAIN_CATALOG to 1), records = listOf(record))
        driveServer.uploadAppDataJsonBatch("token-ok", DriveWireCodec.deltaFileName("dev-other", 1L),
            DriveWireCodec.encodeDeltaBatch(batch).toByteArray(), DriveWireCodec.deltaAppProperties("dev-other", 1L, batch.batchId))
        deviceB.changeEngine.sync(forceManifestRescan = true)
        assertNotNull(deviceB.liseurDb.bookDao().getByUrl("drive:$key"))
        deviceB.liseurDb.bookDao().deleteByUrls(listOf("drive:$key"))
        deviceB.liseurDb.workIdentityDao().forgetAliases(listOf("drive:$key"))
        deviceB.driveDb.recordStates().delete(PERM_ID, DriveWireCodec.DOMAIN_CATALOG, key)
        deviceB.driveDb.cursors().upsert(DriveCursor(PERM_ID, "dev-other", DriveWireCodec.DOMAIN_CATALOG, 0L, 1))

        deviceB.changeEngine.sync()
        assertNotNull(deviceB.liseurDb.bookDao().getByUrl("drive:$key"))
        val cursor = requireNotNull(deviceB.driveDb.cursors().get(PERM_ID, "dev-other", DriveWireCodec.DOMAIN_CATALOG))
        assertNull(cursor.pausedSchemaVersion)
        assertEquals(1L, cursor.appliedSeq)
    }

    @Test
    fun `unsupported schemaVersion pauses remote device stream and surfaces upgrade notice without corrupting local state`() = runTest {
        deviceA.connect()

        // Publish a future v99 manifest from another device directly into Drive
        val futureManifest = """{"schemaVersion":99,"kind":"manifest","deviceId":"dev-future","deviceLabel":"Future Tablet","appVersionCode":999,"updatedHlc":"0017450000000-0001-dev-future","domains":{"catalog":99}}"""
        driveServer.createAppDataFile(
            "token-ok",
            DriveWireCodec.manifestFileName("dev-future"),
            "application/json",
            futureManifest.toByteArray(),
            mapOf("liseur" to "1", "kind" to "manifest", "schemaVersion" to "99", "deviceId" to "dev-future"),
        )
        val summary = deviceA.changeEngine.sync()
        assertEquals(SyncOutcome.Success, summary.outcome)
        assertTrue(summary.pausedBySchemaVersion)

        val uiState = deviceA.driveSync.accountController.uiState.first()
        assertEquals(listOf("Future Tablet"), uiState.pausedSchemaDeviceLabels)
        // Local books remain completely untouched
        assertTrue(deviceA.liseurDb.bookDao().allOnce().isEmpty())

        // Also test per-domain pause: catalog v2 paused while position v1 still imports!
        val book = Book(
            url = "file:///local.epub", title = "Local Title", author = "Author",
            coverPath = null, source = null, addedAt = 100L, lastOpenedAt = null,
            localUri = "file:///local.epub", downloadState = DownloadState.DOWNLOADED,
            fileTitle = "Local Title", fileAuthor = "Author", fileMetadataChecked = true,
        )
        val sha = "d".repeat(64)
        deviceA.liseurDb.bookDao().upsert(book)
        deviceA.liseurDb.workIdentityDao().upsert(BookFingerprintRow(book.url, sha, "m", 100L, null, 1L))
        deviceA.workIdentity.resolveBook(PERM_ID, book, computeFingerprintIfMissing = false)

        val mixedBatch = DriveDeltaBatch(
            schemaVersion = 1,
            batchId = DriveCanonicalJson.batchId("dev-mixed", 1L),
            deviceId = "dev-mixed",
            seq = 1L,
            createdHlc = "0017450000000-0005-dev-mixed",
            domains = mapOf("catalog" to 2, "position" to 1),
            records = listOf(
                DriveWireCodec.putRecord(
                    DriveWireCodec.DOMAIN_CATALOG,
                    "sha:$sha",
                    "0017450000000-0005-dev-mixed",
                    DriveWireCodec.encodeCatalogBody(
                        DriveCatalogBody("sha:$sha", "SHOULD NOT APPLY", null, emptyList(), emptyList(), null, null, 1L, 999L, 999L),
                    ),
                ),
                DriveWireCodec.putRecord(
                    DriveWireCodec.DOMAIN_POSITION,
                    "sha:$sha/dev-mixed",
                    "0017450000000-0005-dev-mixed",
                    DriveWireCodec.encodePositionBody(
                        DrivePositionBody("sha:$sha", "dev-mixed", sha, "{}", 0.33, "Reading", "automatic", null, null, 500L, 500L),
                    ),
                ),
            ),
        )
        driveServer.uploadAppDataJsonBatch(
            "token-ok",
            DriveWireCodec.deltaFileName("dev-mixed", 1L),
            DriveWireCodec.encodeDeltaBatch(mixedBatch).toByteArray(),
            DriveWireCodec.deltaAppProperties("dev-mixed", 1L, mixedBatch.batchId),
        )
        deviceA.changeEngine.sync(forceManifestRescan = true)
        // `catalog` v2 was skipped (archivedAt untouched), while `position` v1 applied cleanly!
        assertNull(deviceA.liseurDb.bookDao().getByUrl(book.url)!!.archivedAt)
        assertEquals(0.33, deviceA.liseurDb.readingProgressDao().get(book.url)!!.totalProgression!!, 0.0001)
        assertEquals(2, deviceA.driveDb.cursors().get(PERM_ID, "dev-mixed", "catalog")?.pausedSchemaVersion)
        assertEquals(0L, deviceA.driveDb.cursors().get(PERM_ID, "dev-mixed", "catalog")?.appliedSeq)
    }

    @Test
    fun `mixed position v1 and unimplemented annotation v1 batch applies position while pausing annotation without advancing its cursor`() = runTest {
        deviceA.connect()

        val sha = "3".repeat(64)
        val book = Book(
            url = "file:///mixed-domain.epub", title = "Mixed Domain Book", author = "Author",
            coverPath = null, source = null, addedAt = 100L, lastOpenedAt = null,
            localUri = "file:///mixed-domain.epub", downloadState = DownloadState.DOWNLOADED,
            fileTitle = "Mixed Domain Book", fileAuthor = "Author", fileMetadataChecked = true,
        )
        deviceA.liseurDb.bookDao().upsert(book)
        deviceA.liseurDb.workIdentityDao().upsert(BookFingerprintRow(book.url, sha, "m", 100L, null, 1L))
        deviceA.workIdentity.resolveBook(PERM_ID, book, computeFingerprintIfMissing = false)

        // Newer app installation publishes manifest + delta with implemented position v1 AND an unimplemented future domain.
        val newerManifest = DriveManifest(
            schemaVersion = 1,
            deviceId = "dev-future-domain",
            deviceLabel = "Future Reader",
            appVersionCode = 200,
            updatedHlc = "0017450000000-0001-dev-future-domain",
            domains = mapOf("position" to 1, "futureStats" to 1),
        )
        driveServer.createAppDataFile(
            "token-ok",
            DriveWireCodec.manifestFileName("dev-future-domain"),
            "application/json",
            DriveWireCodec.encodeManifest(newerManifest).toByteArray(),
            DriveWireCodec.manifestAppProperties("dev-future-domain"),
        )
        val mixedBatch = DriveDeltaBatch(
            schemaVersion = 1,
            batchId = DriveCanonicalJson.batchId("dev-future-domain", 1L),
            deviceId = "dev-future-domain",
            seq = 1L,
            createdHlc = "0017450000000-0002-dev-future-domain",
            domains = mapOf("position" to 1, "futureStats" to 1),
            records = listOf(
                DriveWireCodec.putRecord(
                    DriveWireCodec.DOMAIN_POSITION,
                    "sha:$sha/dev-future-domain",
                    "0017450000000-0002-dev-future-domain",
                    DriveWireCodec.encodePositionBody(
                        DrivePositionBody("sha:$sha", "dev-future-domain", sha, "{}", 0.48, "Reading", "automatic", null, null, 500L, 500L),
                    ),
                ),
                DriveWireCodec.putRecord(
                    "futureStats",
                    "stat-1",
                    "0017450000000-0002-dev-future-domain",
                    mapOf("workKey" to "sha:$sha", "durationMs" to 60_000L),
                ),
            ),
        )
        driveServer.uploadAppDataJsonBatch(
            "token-ok",
            DriveWireCodec.deltaFileName("dev-future-domain", 1L),
            DriveWireCodec.encodeDeltaBatch(mixedBatch).toByteArray(),
            DriveWireCodec.deltaAppProperties("dev-future-domain", 1L, mixedBatch.batchId),
        )

        val summary = deviceA.changeEngine.sync()
        assertEquals(SyncOutcome.Success, summary.outcome)
        assertTrue(summary.pausedBySchemaVersion)

        // Implemented position v1 applied & advanced its cursor.
        assertEquals(0.48, deviceA.liseurDb.readingProgressDao().get(book.url)!!.totalProgression!!, 0.0001)
        val posCursor = deviceA.driveDb.cursors().get(PERM_ID, "dev-future-domain", DriveWireCodec.DOMAIN_POSITION)!!
        assertEquals(1L, posCursor.appliedSeq)
        assertNull(posCursor.pausedSchemaVersion)

        // Unimplemented future domain is paused at appliedSeq = 0 (never destructively acknowledged) & notice is surfaced.
        val futureCursor = deviceA.driveDb.cursors().get(PERM_ID, "dev-future-domain", "futureStats")!!
        assertEquals(0L, futureCursor.appliedSeq)
        assertEquals(1, futureCursor.pausedSchemaVersion)
        val uiState = deviceA.driveSync.accountController.uiState.first()
        assertEquals(listOf("Future Reader"), uiState.pausedSchemaDeviceLabels)
    }

    @Test
    fun `WorkManager requests separate state sync from bulk EPUB transfers and default EPUBs to UNMETERED`() {
        val stateReq = DriveSyncWorkers.buildStateSyncRequest()
        assertEquals(NetworkType.CONNECTED, stateReq.workSpec.constraints.requiredNetworkType)

        val epubUploadDefault = DriveSyncWorkers.buildEpubUploadRequest(allowMetered = false)
        assertEquals(NetworkType.UNMETERED, epubUploadDefault.workSpec.constraints.requiredNetworkType)

        val epubUploadMetered = DriveSyncWorkers.buildEpubUploadRequest(allowMetered = true)
        assertEquals(NetworkType.CONNECTED, epubUploadMetered.workSpec.constraints.requiredNetworkType)

        val epubDownloadDefault = DriveSyncWorkers.buildEpubDownloadRequest("drive:isbn:123", allowMetered = false)
        assertEquals(NetworkType.UNMETERED, epubDownloadDefault.workSpec.constraints.requiredNetworkType)
        assertTrue(DriveSyncWorkers.STATE_SYNC_WORK_NAME != DriveSyncWorkers.EPUB_UPLOAD_WORK_NAME)
        assertTrue(DriveSyncWorkers.epubDownloadWorkName("drive:isbn:123") != DriveSyncWorkers.STATE_SYNC_WORK_NAME)
    }

    @Test
    fun `auth expiry during sync triggers reconnect state without corrupting local data`() = runTest {
        deviceA.connect()
        driveServer.rejectTokenWith401 = true
        deviceA.authClient.silentResult = DriveAuthResult.NeedsResolution(null)

        val summary = deviceA.changeEngine.sync()
        assertEquals(SyncOutcome.Failure(SyncFailure.Unauthorised), summary.outcome)
        assertEquals(DriveAccount.STATE_NEEDS_RECONNECT, deviceA.driveDb.accounts().get(PERM_ID)?.state)
    }

    @Test
    fun `rate-limit and storage quota failures map to retryable offline vs forbidden outcomes`() = runTest {
        deviceA.connect()

        driveServer.nextApiException = DriveQuotaException(
            message = "Rate limited",
            httpCode = 429,
            retryAfterMs = 120_000L,
            reason = "rateLimitExceeded",
            isStorageQuota = false,
        )
        val rateLimited = deviceA.changeEngine.sync(bookUrlFilter = "drive:isbn:9780000000000")
        assertEquals(SyncOutcome.Failure(SyncFailure.Offline), rateLimited.outcome)

        driveServer.nextApiException = DriveQuotaException(
            message = "Storage full",
            httpCode = 403,
            reason = "storageQuotaExceeded",
            isStorageQuota = true,
        )
        val storageQuota = deviceA.changeEngine.sync(bookUrlFilter = "drive:isbn:9780000000000")
        assertEquals(SyncOutcome.Failure(SyncFailure.Forbidden), storageQuota.outcome)
    }

    @Test
    fun `foreground open-book pull cancels cleanly while in flight and coordinator bounds await with late-response fence`() = runTest {
        deviceA.connect()
        deviceB.connect()

        // 1. Offline failure maps cleanly to SyncFailure.Offline.
        driveServer.nextApiException = DriveHttpException("service unavailable", 503, reason = "backendError")
        val offline = deviceA.changeEngine.sync(bookUrlFilter = "drive:isbn:9780000000000")
        assertEquals(SyncOutcome.Failure(SyncFailure.Offline), offline.outcome)

        // 2. Explicit in-flight cancellation: wait until fake transport confirms request entered before cancelling.
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        driveServer.onApiEntered = entered
        driveServer.apiGate = gate
        val slowForegroundPull = async {
            deviceA.changeEngine.sync(bookUrlFilter = "drive:isbn:9780000000000")
        }
        entered.await()
        slowForegroundPull.cancel()
        slowForegroundPull.join()
        driveServer.onApiEntered = null
        driveServer.apiGate = null

        val recovered = deviceA.changeEngine.sync(bookUrlFilter = "drive:isbn:9780000000000")
        assertEquals(SyncOutcome.Success, recovered.outcome)

        // 3. Real PositionSyncCoordinator bounded await + late-response open-book fence.
        val isbn = "9780441172764"
        val sha = "4".repeat(64)
        val bookA = Book(
            url = "file:///coord-a.epub", title = "Coordinator Book", author = "Author",
            coverPath = null, source = null, addedAt = 100L, lastOpenedAt = null,
            localUri = "file:///coord-a.epub", downloadState = DownloadState.DOWNLOADED,
            fileTitle = "Coordinator Book", fileAuthor = "Author", fileMetadataChecked = true,
        )
        val bookB = bookA.copy(url = "file:///coord-b.epub", localUri = "file:///coord-b.epub")
        deviceA.liseurDb.bookDao().upsert(bookA)
        deviceA.liseurDb.workIdentityDao().upsert(BookFingerprintRow(bookA.url, sha, "m1", 100L, null, 1L))
        deviceA.liseurDb.bookMetadataDao().upsert(
            BookMetadata.of(bookA.url, MetadataSource.FILE, MetadataSnapshot(identifiers = listOf(Identifier("isbn", isbn)))),
        )
        deviceB.liseurDb.bookDao().upsert(bookB)
        deviceB.liseurDb.workIdentityDao().upsert(BookFingerprintRow(bookB.url, sha, "m1", 100L, null, 1L))
        deviceB.liseurDb.bookMetadataDao().upsert(
            BookMetadata.of(bookB.url, MetadataSource.FILE, MetadataSnapshot(identifiers = listOf(Identifier("isbn", isbn)))),
        )
        deviceA.liseurDb.readingProgressDao().recordLocal(
            bookUrl = bookA.url, locatorJson = """{"href":"ch2.xhtml"}""", progression = 0.25,
            readingSecondsPerPosition = null, readingPaceSamples = null, readingPaceElapsedMs = null,
            readingPaceEvidence = null, status = "Reading", updatedAt = 1_000L,
        )
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)

        // Device A advances to 0.72.
        deviceA.liseurDb.readingProgressDao().recordLocal(
            bookUrl = bookA.url, locatorJson = """{"href":"ch7.xhtml"}""", progression = 0.72,
            readingSecondsPerPosition = null, readingPaceSamples = null, readingPaceElapsedMs = null,
            readingPaceEvidence = null, status = "Reading", updatedAt = 2_000L,
        )
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)

        // Device B starts a bounded pre-open request via PositionSyncCoordinator while Drive stalls.
        val coordEntered = CompletableDeferred<Unit>()
        val coordGate = CompletableDeferred<Unit>()
        val lateCompleted = CompletableDeferred<Unit>()
        driveServer.onApiEntered = coordEntered
        driveServer.apiGate = coordGate

        val coordinator = PositionSyncCoordinator(
            sync = CompositePositionSync(listOf(deviceB.positionSync)),
            onSynced = { lateCompleted.complete(Unit) },
        )
        val boundedOutcome = withContext(Dispatchers.Default) {
            val req = async {
                withTimeoutOrNull(150L) {
                    coordinator.request(SyncScope.Book(bookB.url))
                }
            }
            withTimeout(5_000L) { coordEntered.await() }
            req.await()
        }
        assertNull(boundedOutcome) // Caller returned cleanly when timeout expired while run stays alive.

        // Reader now opens the book BEFORE the slow response returns.
        deviceB.liseurDb.readingProgressDao().openBooks.enter(bookB.url)
        try {
            driveServer.onApiEntered = null
            driveServer.apiGate = null
            coordGate.complete(Unit)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { lateCompleted.await() }
            }

            // Late response MUST NOT clobber the open reader's locator; remote 0.72 stays pending.
            val progressB = deviceB.liseurDb.readingProgressDao().get(bookB.url)!!
            assertEquals(0.25, progressB.totalProgression!!, 0.0001)
            val peerState = deviceB.liseurDb.syncPeerStateDao().get(bookB.url, "${DriveLibrarySync.accountPeerId(PERM_ID)}/dev-a")!!
            assertTrue(peerState.hasPending)
            assertEquals(0.72, peerState.pendingProgression!!, 0.0001)
        } finally {
            deviceB.liseurDb.readingProgressDao().openBooks.leave(bookB.url)
        }
    }

    // -- Harness & Fake In-Memory Google Drive Server -------------------------

    private inner class DeviceHarness(
        val deviceId: String,
        val booksDir: File,
        val liseurDb: LiseurDatabase,
        val driveDb: DriveSyncDatabase,
        val authClient: SimpleFakeAuthClient,
        val driveSync: DriveLibrarySync,
        val finishedState: FinishedState,
        val workIdentity: DriveWorkIdentity,
        val catalogSync: DriveCatalogSync,
        val positionSync: DrivePositionSync,
        val changeEngine: DriveChangeEngine,
        val epubTransfers: DriveEpubTransferManager,
    ) {
        suspend fun connect() {
            driveSync.activateAccount(PERM_ID, "reader@example.com", now = 1_000L)
        }

        fun close() {
            driveDb.close()
            liseurDb.close()
        }
    }

    private fun createDevice(deviceId: String, booksDir: File): DeviceHarness {
        val liseurDb = Room.inMemoryDatabaseBuilder(context, LiseurDatabase::class.java).build()
        val driveDb = Room.inMemoryDatabaseBuilder(context, DriveSyncDatabase::class.java).build()
        val removal = BookRemoval(
            bookDao = liseurDb.bookDao(),
            sessionDao = liseurDb.readingSessionDao(),
            peerStateDao = liseurDb.syncPeerStateDao(),
            identityDao = liseurDb.workIdentityDao(),
            progressDao = liseurDb.readingProgressDao(),
            annotationDao = liseurDb.annotationDao(),
            annotationSyncDao = liseurDb.annotationSyncDao(),
            evaluationDao = liseurDb.bookEvaluationDao(),
            metadataDao = liseurDb.bookMetadataDao(),
            inTransaction = { work -> liseurDb.withTransaction { work() } },
        )
        val remoteAccount = RemoteAccountRepository(
            dao = liseurDb.remoteServerDao(),
            bookDao = liseurDb.bookDao(),
            progressDao = liseurDb.readingProgressDao(),
            bookRemoval = removal,
            seriesExtraDao = liseurDb.seriesExtraDao(),
            peerStateDao = liseurDb.syncPeerStateDao(),
            furthestDao = liseurDb.furthestPositionDao(),
            identityDao = liseurDb.workIdentityDao(),
            sessionDao = liseurDb.readingSessionDao(),
            annotationSyncDao = liseurDb.annotationSyncDao(),
            evaluationDao = liseurDb.bookEvaluationDao(),
            metadataOverrideDao = liseurDb.bookMetadataOverrideDao(),
            setups = emptyMap(),
            inTransaction = { work -> removal.together(work) },
            switchCoordinator = LibrarySyncSwitchCoordinator(),
        )
        val auth = SimpleFakeAuthClient()
        val driveSync = DriveLibrarySync(
            context = context,
            database = liseurDb,
            remoteAccount = remoteAccount,
            deviceIdGenerator = { deviceId },
            openStore = { driveDb },
            authClient = auth,
            driveApi = driveServer,
        )
        val fpStore = BookFingerprintStore(context, liseurDb.workIdentityDao())
        val finishedState = FinishedState(
            bookDao = liseurDb.bookDao(),
            progressDao = liseurDb.readingProgressDao(),
            inTransaction = { work -> liseurDb.withTransaction { work() } },
        )
        val workIdentity = DriveWorkIdentity(liseurDb, driveDb, fpStore)
        val catalogSync = DriveCatalogSync(liseurDb, driveDb, workIdentity)
        lateinit var engineRef: DriveChangeEngine
        val positionSync = DrivePositionSync(
            database = liseurDb,
            storeProvider = { driveDb },
            workIdentityProvider = { workIdentity },
            finishedState = finishedState,
            runSync = { bookUrl -> engineRef.sync(bookUrlFilter = bookUrl).outcome },
            isActive = { driveSync.isActive() },
            activeAccount = { driveDb.accounts().active() },
            localDeviceId = { deviceId },
            nextHlc = { perm -> engineRef.nextHlc(perm) },
        )
        engineRef = DriveChangeEngine(
            store = driveDb,
            driveApi = driveServer,
            workIdentity = workIdentity,
            catalogSync = catalogSync,
            positionSync = positionSync,
            acquireToken = { stale -> driveSync.accountController.acquireSilentAccessToken(stale) },
            ensureDeviceId = { deviceId },
        )
        val epubTransfers = DriveEpubTransferManager(
            context = context,
            database = liseurDb,
            store = driveDb,
            driveApi = driveServer,
            workIdentity = workIdentity,
            catalogSync = catalogSync,
            fingerprintStore = fpStore,
            nextHlc = { perm -> engineRef.nextHlc(perm) },
            acquireToken = { stale -> driveSync.accountController.acquireSilentAccessToken(stale) },
            onCatalogAvailabilityChanged = { engineRef.sync() },
            booksDirProvider = { booksDir.apply { mkdirs() } },
        )
        return DeviceHarness(
            deviceId = deviceId,
            booksDir = booksDir,
            liseurDb = liseurDb,
            driveDb = driveDb,
            authClient = auth,
            driveSync = driveSync,
            finishedState = finishedState,
            workIdentity = workIdentity,
            catalogSync = catalogSync,
            positionSync = positionSync,
            changeEngine = engineRef,
            epubTransfers = epubTransfers,
        )
    }

    private class SimpleFakeAuthClient : DriveAuthClient {
        var silentResult: DriveAuthResult = DriveAuthResult.Authorized("token-ok")
        override suspend fun authorize(accountEmail: String?, selectAccount: Boolean): DriveAuthResult = silentResult
        override suspend fun authorizeSilently(accountEmail: String?): DriveAuthResult = silentResult
        override fun parseAuthorizationResult(data: Intent?): DriveAuthResult = silentResult
        override suspend fun clearCachedToken(accessToken: String) = Unit
    }

    private class InMemoryGoogleDriveServer : DriveRemoteApi {
        private val idGen = AtomicInteger(1)
        private val appDataFiles = linkedMapOf<String, Pair<DriveRemoteFile, ByteArray>>()
        private val driveFiles = linkedMapOf<String, Pair<DriveRemoteFile, ByteArray>>()
        val liseurFolders = mutableListOf<DriveRemoteFile>()
        private val changeLog = mutableListOf<DriveChange>()

        var corruptNextDownload = false
        var expireNextPageToken = false
        var rejectTokenWith401 = false
        var failAfterNextBatchUpload = false
        var apiDelayMs: Long = 0L
        var onApiEntered: CompletableDeferred<Unit>? = null
        var apiGate: CompletableDeferred<Unit>? = null
        var onDownloadEntered: CompletableDeferred<Unit>? = null
        var downloadGate: CompletableDeferred<Unit>? = null
        var downloadGateIgnoresCancellation = false
        var nextApiException: DriveApiException? = null
        var failNextResumableUploadAfterBytes: Pair<Long, Exception>? = null
        var lastResumedUploadSessionUri: String? = null
        var lastDownloadStartOffset: Long = -1L

        fun appDataFileCount(): Int = appDataFiles.size

        fun deltaPayloads(): List<ByteArray> =
            appDataFiles.values.map { it.second }.filter { String(it).contains("\"records\"") }

        fun seedFolder(id: String, createdTime: String) {
            val folder = DriveRemoteFile(
                id = id,
                name = DriveRestClient.LISEUR_FOLDER_NAME,
                mimeType = DriveRestClient.FOLDER_MIME_TYPE,
                createdTime = createdTime,
                appProperties = mapOf("liseur" to "1", "kind" to DriveFile.KIND_FOLDER),
            )
            liseurFolders.add(folder)
            recordChange(DriveChange(folder.id, removed = false, file = folder))
        }

        fun seedEpubFile(name: String, bytes: ByteArray, sha256: String, workKey: String): String {
            val id = "epub-${idGen.getAndIncrement()}"
            val file = DriveRemoteFile(
                id = id,
                name = name,
                mimeType = DriveEpubTransferManager.EPUB_MIME_TYPE,
                size = bytes.size.toLong(),
                appProperties = DriveWireCodec.epubAppProperties(sha256, workKey),
            )
            driveFiles[id] = file to bytes
            recordChange(DriveChange(id, removed = false, file = file))
            return id
        }

        private suspend fun checkAuth(includeInjectedFailure: Boolean = true) {
            onApiEntered?.complete(Unit)
            apiGate?.await()
            if (apiDelayMs > 0L) delay(apiDelayMs)
            if (includeInjectedFailure) {
                nextApiException?.let {
                    nextApiException = null
                    throw it
                }
            }
            if (rejectTokenWith401) throw DriveAuthExpiredException("Token expired")
        }

        private fun recordChange(change: DriveChange) {
            changeLog.add(change)
        }

        override suspend fun getAbout(accessToken: String): DriveUserAbout {
            checkAuth(includeInjectedFailure = false)
            return DriveUserAbout(PERM_ID, "reader@example.com")
        }

        override suspend fun listLiseurFolders(accessToken: String): List<DriveRemoteFile> {
            checkAuth()
            return liseurFolders.sortedWith(compareBy({ it.createdTime ?: "" }, { it.id }))
        }

        override suspend fun ensureLiseurFolder(accessToken: String): LiseurFolderResolution {
            checkAuth()
            if (liseurFolders.isEmpty()) {
                seedFolder("folder-${idGen.getAndIncrement()}", "2026-04-01T12:00:00Z")
            }
            val sorted = listLiseurFolders(accessToken)
            return LiseurFolderResolution(canonical = sorted.first(), allFolders = sorted)
        }

        override suspend fun listDriveEpubFiles(accessToken: String): List<DriveRemoteFile> {
            checkAuth()
            return driveFiles.values.map { it.first }.filter { !it.trashed }
        }

        override suspend fun getStartPageToken(accessToken: String): String {
            checkAuth()
            return changeLog.size.toString()
        }

        override suspend fun listChanges(accessToken: String, pageToken: String, pageSize: Int): DriveChangesPage {
            checkAuth()
            if (expireNextPageToken) {
                expireNextPageToken = false
                throw DriveInvalidPageTokenException("Expired page token", 400)
            }
            val start = pageToken.toIntOrNull() ?: 0
            val slice = if (start < changeLog.size) changeLog.subList(start, changeLog.size).toList() else emptyList()
            return DriveChangesPage(
                changes = slice,
                nextPageToken = null,
                newStartPageToken = changeLog.size.toString(),
            )
        }

        override suspend fun listAppDataFiles(accessToken: String, query: String?, pageSize: Int): List<DriveRemoteFile> {
            checkAuth()
            val all = appDataFiles.values.map { it.first }.filter { !it.trashed }
            if (query.isNullOrBlank()) return all
            val propMatches = Regex("""key='([^']+)'\s+and\s+value='([^']+)'""").findAll(query).toList()
            if (propMatches.isEmpty()) return all
            return all.filter { file ->
                propMatches.all { match ->
                    file.appProperties[match.groupValues[1]] == match.groupValues[2]
                }
            }
        }

        override suspend fun findAppDataFileByBatchId(accessToken: String, batchId: String): DriveRemoteFile? {
            checkAuth()
            return appDataFiles.values.map { it.first }
                .firstOrNull { !it.trashed && it.appProperties["batchId"] == batchId }
        }

        override suspend fun downloadMedia(accessToken: String, fileId: String): ByteArray {
            checkAuth()
            return (appDataFiles[fileId] ?: driveFiles[fileId])?.second
                ?: throw DriveNotFoundException("Not found: $fileId", fileId)
        }

        override suspend fun uploadAppDataJsonBatch(
            accessToken: String,
            name: String,
            jsonPayload: ByteArray,
            appProperties: Map<String, String>,
        ): DriveRemoteFile {
            checkAuth()
            appProperties["batchId"]?.let { bid ->
                findAppDataFileByBatchId(accessToken, bid)?.let { return it }
            }
            val uploaded = createAppDataFile(accessToken, name, "application/json", jsonPayload, appProperties)
            if (failAfterNextBatchUpload) {
                failAfterNextBatchUpload = false
                throw DriveApiException("Simulated crash after batch upload")
            }
            return uploaded
        }

        override suspend fun createAppDataFile(
            accessToken: String,
            name: String,
            mimeType: String,
            content: ByteArray,
            appProperties: Map<String, String>,
        ): DriveRemoteFile {
            checkAuth()
            val id = "app-${idGen.getAndIncrement()}"
            val file = DriveRemoteFile(
                id = id,
                name = name,
                mimeType = mimeType,
                parents = listOf(DriveRestClient.SPACE_APPDATA),
                appProperties = appProperties,
                size = content.size.toLong(),
            )
            appDataFiles[id] = file to content.copyOf()
            recordChange(DriveChange(id, removed = false, file = file))
            return file
        }

        override suspend fun updateFileMedia(
            accessToken: String,
            fileId: String,
            mimeType: String,
            content: ByteArray,
            appProperties: Map<String, String>?,
        ): DriveRemoteFile {
            checkAuth()
            val existing = appDataFiles[fileId]?.first ?: throw DriveNotFoundException("Not found", fileId)
            val updated = existing.copy(
                appProperties = appProperties ?: existing.appProperties,
                size = content.size.toLong(),
            )
            appDataFiles[fileId] = updated to content.copyOf()
            recordChange(DriveChange(fileId, removed = false, file = updated))
            return updated
        }

        override suspend fun deleteFile(accessToken: String, fileId: String) {
            checkAuth()
            appDataFiles.remove(fileId)
            driveFiles.remove(fileId)
            recordChange(DriveChange(fileId, removed = true, file = null))
        }

        override suspend fun trashFile(accessToken: String, fileId: String): DriveRemoteFile {
            checkAuth()
            val pair = driveFiles[fileId] ?: throw DriveNotFoundException("Not found", fileId)
            val trashed = pair.first.copy(trashed = true)
            driveFiles[fileId] = trashed to pair.second
            recordChange(DriveChange(fileId, removed = false, file = trashed))
            return trashed
        }

        override suspend fun initiateResumableUpload(
            accessToken: String, name: String, mimeType: String, totalBytes: Long,
            parents: List<String>, appProperties: Map<String, String>,
        ): String = "https://upload.example/session-${idGen.getAndIncrement()}"

        override suspend fun queryResumableUploadOffset(
            accessToken: String, sessionUri: String, totalBytes: Long,
        ): ResumableUploadStatus = ResumableUploadStatus.InProgress(0L)

        override suspend fun uploadResumableChunk(
            accessToken: String, sessionUri: String, chunk: ByteArray, offset: Long, totalBytes: Long, mimeType: String,
        ): ResumableUploadStatus = ResumableUploadStatus.InProgress(offset + chunk.size)

        override suspend fun uploadResumableFile(
            accessToken: String, name: String, mimeType: String, totalBytes: Long,
            parents: List<String>, appProperties: Map<String, String>, existingSessionUri: String?,
            chunkSizeBytes: Int, openStream: () -> InputStream,
            onSessionUri: suspend (String) -> Unit, onProgress: suspend (Long, String) -> Unit,
        ): DriveRemoteFile {
            checkAuth()
            lastResumedUploadSessionUri = existingSessionUri
            val uri = existingSessionUri ?: "https://upload.example/s-${idGen.getAndIncrement()}".also {
                onSessionUri(it)
            }
            failNextResumableUploadAfterBytes?.let { (bytesSentBeforeFail, exception) ->
                failNextResumableUploadAfterBytes = null
                onProgress(bytesSentBeforeFail, uri)
                throw exception
            }
            val bytes = openStream().use { it.readBytes() }
            val id = "epub-${idGen.getAndIncrement()}"
            val file = DriveRemoteFile(
                id = id,
                name = name,
                mimeType = mimeType,
                parents = parents,
                appProperties = appProperties,
                size = bytes.size.toLong(),
            )
            driveFiles[id] = file to bytes
            onProgress(bytes.size.toLong(), uri)
            recordChange(DriveChange(id, removed = false, file = file))
            return file
        }

        override suspend fun downloadResumable(
            accessToken: String, fileId: String, destination: OutputStream, startOffset: Long,
            onRestartFromZero: () -> Unit, onProgress: (Long) -> Unit,
        ): DriveDownloadResult {
            checkAuth()
            onDownloadEntered?.complete(Unit)
            downloadGate?.let { gate ->
                if (downloadGateIgnoresCancellation) withContext(NonCancellable) { gate.await() } else gate.await()
            }
            lastDownloadStartOffset = startOffset
            val bytes = (driveFiles[fileId] ?: appDataFiles[fileId])?.second
                ?: throw DriveNotFoundException("Not found: $fileId", fileId)
            val payload = if (corruptNextDownload) {
                "CORRUPTED-BYTES".toByteArray()
            } else {
                bytes.copyOfRange(startOffset.toInt(), bytes.size)
            }
            destination.write(payload)
            destination.flush()
            onProgress(startOffset + payload.size)
            return DriveDownloadResult(payload.size.toLong(), bytes.size.toLong(), restartedFromZero = false)
        }
    }

    companion object {
        private const val PERM_ID = "perm-test-account"
    }
}
