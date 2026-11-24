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

        assertEquals(0.65, deviceB.liseurDb.readingProgressDao().get(bookB.url)!!.totalProgression!!, 0.0001)
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
            editions = listOf(DriveCatalogEdition(fp.sha256, fp.size, fileId, "valid.epub", 100L)),
            fileSeriesName = null, fileSeriesIndex = null, addedAt = 100L, archivedAt = null, updatedAt = 100L,
        )
        deviceB.catalogSync.applyRemoteRecord(
            PERM_ID,
            DriveWireCodec.putRecord(DriveWireCodec.DOMAIN_CATALOG, body.workKey, "0017450000000-0001-dev-a", DriveWireCodec.encodeCatalogBody(body)),
            "dev-a",
            1L,
        )

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
            val pending = deviceB.liseurDb.syncPeerStateDao().get(bookB.url, DriveLibrarySync.accountPeerId(PERM_ID))!!
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
        driveServer.expireNextPageToken = true

        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertTrue(deviceB.driveDb.files().get(PERM_ID, uploadedFileId)!!.trashed)
        assertNull(deviceB.liseurDb.bookDao().getByUrl(stubUrl)!!.downloadHref)
        assertNull(deviceB.catalogSync.resolveDownloadSource(PERM_ID, deviceB.liseurDb.bookDao().getByUrl(stubUrl)!!))
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

        // Reader deletes the EPUB directly in Google Drive, then Device A syncs.
        driveServer.deleteFile("token-ok", firstFileId)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)

        // Upload row MUST be invalidated (not UPLOADED and NOT auto-queued for re-upload).
        assertNull(deviceA.driveDb.uploads().get(PERM_ID, fp.sha256))
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

        // Newer app installation publishes manifest + delta with implemented position v1 AND unimplemented annotation v1.
        val newerManifest = DriveManifest(
            schemaVersion = 1,
            deviceId = "dev-phase4",
            deviceLabel = "Phase 4 Reader",
            appVersionCode = 200,
            updatedHlc = "0017450000000-0001-dev-phase4",
            domains = mapOf("position" to 1, "annotation" to 1),
        )
        driveServer.createAppDataFile(
            "token-ok",
            DriveWireCodec.manifestFileName("dev-phase4"),
            "application/json",
            DriveWireCodec.encodeManifest(newerManifest).toByteArray(),
            DriveWireCodec.manifestAppProperties("dev-phase4"),
        )
        val mixedBatch = DriveDeltaBatch(
            schemaVersion = 1,
            batchId = DriveCanonicalJson.batchId("dev-phase4", 1L),
            deviceId = "dev-phase4",
            seq = 1L,
            createdHlc = "0017450000000-0002-dev-phase4",
            domains = mapOf("position" to 1, "annotation" to 1),
            records = listOf(
                DriveWireCodec.putRecord(
                    DriveWireCodec.DOMAIN_POSITION,
                    "sha:$sha/dev-phase4",
                    "0017450000000-0002-dev-phase4",
                    DriveWireCodec.encodePositionBody(
                        DrivePositionBody("sha:$sha", "dev-phase4", sha, "{}", 0.48, "Reading", "automatic", null, null, 500L, 500L),
                    ),
                ),
                DriveWireCodec.putRecord(
                    DriveWireCodec.DOMAIN_ANNOTATION,
                    "ann-1",
                    "0017450000000-0002-dev-phase4",
                    mapOf("workKey" to "sha:$sha", "note" to "Important quote"),
                ),
            ),
        )
        driveServer.uploadAppDataJsonBatch(
            "token-ok",
            DriveWireCodec.deltaFileName("dev-phase4", 1L),
            DriveWireCodec.encodeDeltaBatch(mixedBatch).toByteArray(),
            DriveWireCodec.deltaAppProperties("dev-phase4", 1L, mixedBatch.batchId),
        )

        val summary = deviceA.changeEngine.sync()
        assertEquals(SyncOutcome.Success, summary.outcome)
        assertTrue(summary.pausedBySchemaVersion)

        // Implemented position v1 applied & advanced its cursor.
        assertEquals(0.48, deviceA.liseurDb.readingProgressDao().get(book.url)!!.totalProgression!!, 0.0001)
        val posCursor = deviceA.driveDb.cursors().get(PERM_ID, "dev-phase4", DriveWireCodec.DOMAIN_POSITION)!!
        assertEquals(1L, posCursor.appliedSeq)
        assertNull(posCursor.pausedSchemaVersion)

        // Unimplemented annotation v1 is paused at appliedSeq = 0 (never destructively acknowledged) & notice is surfaced.
        val annCursor = deviceA.driveDb.cursors().get(PERM_ID, "dev-phase4", DriveWireCodec.DOMAIN_ANNOTATION)!!
        assertEquals(0L, annCursor.appliedSeq)
        assertEquals(1, annCursor.pausedSchemaVersion)
        val uiState = deviceA.driveSync.accountController.uiState.first()
        assertEquals(listOf("Phase 4 Reader"), uiState.pausedSchemaDeviceLabels)
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
            val peerState = deviceB.liseurDb.syncPeerStateDao().get(bookB.url, DriveLibrarySync.accountPeerId(PERM_ID))!!
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
        var nextApiException: DriveApiException? = null
        var failNextResumableUploadAfterBytes: Pair<Long, Exception>? = null
        var lastResumedUploadSessionUri: String? = null
        var lastDownloadStartOffset: Long = -1L

        fun appDataFileCount(): Int = appDataFiles.size

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
