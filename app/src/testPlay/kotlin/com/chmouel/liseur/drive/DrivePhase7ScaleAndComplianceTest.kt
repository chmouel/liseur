package com.chmouel.liseur.drive

import android.app.Application
import android.content.Intent
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.AnnotationKind
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookAnnotation
import com.chmouel.liseur.data.db.BookFingerprintRow
import com.chmouel.liseur.data.db.BookMetadata
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.ReadingSession
import com.chmouel.liseur.data.library.BookRemoval
import com.chmouel.liseur.data.remote.RemoteAccountRepository
import com.chmouel.liseur.data.remote.SyncOutcome
import com.chmouel.liseur.domain.BookFingerprint
import com.chmouel.liseur.domain.BookFingerprints
import com.chmouel.liseur.domain.Identifier
import com.chmouel.liseur.domain.MetadataSnapshot
import com.chmouel.liseur.domain.MetadataSource
import com.chmouel.liseur.sync.LibrarySyncSwitchCoordinator
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.util.Locale
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.KeyGenerator
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okio.Buffer
import org.json.JSONObject
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
 * Phase 7 scale, edge-case stress, and account-switching verification suite
 * (`docs/DRIVE_SYNC.md`, ADR-0078):
 * 1. Interrupted large multi-chunk EPUB resumable upload AND resumable download
 *    over real HTTP (`DriveRestClient` + `MockWebServer`), verifying resume from
 *    persisted offsets without re-transferring acknowledged bytes and strict SHA-256 verification.
 * 2. Drive-side EPUB rename (tracked by Drive file ID -> still linked and downloadable)
 *    and Drive-side trash/deletion (marks media unavailable while retaining catalog history,
 *    reading progress, sessions, and annotations).
 * 3. Large catalog (250 books) + long reading history (300 sessions + annotations +
 *    multiple compactions) with measured, bounded first-sync HTTP requests, bounded
 *    single-edit incremental sync HTTP requests, and bounded `appDataFolder` file count.
 * 4. Full account-switching round-trip: Account A -> Account B (confirmed, zero writes
 *    before confirmation) -> switch back to Account A (resumes cleanly with A's paused
 *    cursors and queues intact).
 */
@Config(sdk = [35], application = Application::class)
@RunWith(RobolectricTestRunner::class)
class DrivePhase7ScaleAndComplianceTest {

    private lateinit var context: Application
    private lateinit var tempDir: File
    private val openDevices = mutableListOf<Phase7Device>()

    @Before
    fun setUp() {
        CredentialCipher.keyForTesting =
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        context = ApplicationProvider.getApplicationContext()
        tempDir = File(context.cacheDir, "drive-p7-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        openDevices.forEach { it.close() }
        openDevices.clear()
        tempDir.deleteRecursively()
        CredentialCipher.keyForTesting = null
    }

    @Test
    fun `interrupted large multi-chunk EPUB resumable upload and download resume cleanly and verify SHA-256`() = runTest {
        // 2.5 MiB deterministic payload -> 3 chunks (1 MiB + 1 MiB + 512 KiB) under DEFAULT_RESUMABLE_CHUNK_BYTES.
        val totalSize = 2_621_440
        val epubBytes = ByteArray(totalSize).also { Random(42L).nextBytes(it) }
        val expectedFp = BookFingerprints.of(ByteArrayInputStream(epubBytes))

        val mockServer = MockWebServer()
        val httpBackend = ResumableMockDriveBackend(expectedTotalBytes = totalSize)
        mockServer.dispatcher = httpBackend.dispatcher(mockServer)
        mockServer.start(InetAddress.getByName("127.0.0.1"), 0)

        try {
            val baseApi = mockServer.url("/drive/v3").toString().trimEnd('/')
            val baseUpload = mockServer.url("/upload/drive/v3").toString().trimEnd('/')
            val restClient = DriveRestClient(
                apiBaseUrl = baseApi,
                uploadBaseUrl = baseUpload,
                maxRetries = 0, // Let interruptions surface cleanly to persist & resume across attempts.
                sleep = {},
            )

            // Share appData/metadata via FakeDriveServer while routing EPUB resumable transfers
            // through the real HTTP DriveRestClient + MockWebServer.
            val metaServer = InstrumentedFakeDriveServer()
            val hybridApiA = HybridResumableDriveApi(
                meta = metaServer.clientFor("dev-a"),
                http = restClient,
                onHttpUploadCompleted = { metaServer.registerExternalDriveFile(it) },
            )
            val hybridApiB = HybridResumableDriveApi(
                meta = metaServer.clientFor("dev-b"),
                http = restClient,
                onHttpUploadCompleted = { metaServer.registerExternalDriveFile(it) },
            )

            val devA = createDevice(deviceId = "dev-a", driveApi = hybridApiA)
            val devB = createDevice(deviceId = "dev-b", driveApi = hybridApiB)
            devA.connect()
            devB.connect()

            val epubFileA = File(devA.dir, "large-volume.epub").apply { writeBytes(epubBytes) }
            val bookA = seedBookFromFile(
                device = devA,
                file = epubFileA,
                isbn = "9780441172719",
                title = "Dune Omnibus",
                author = "Frank Herbert",
                fp = expectedFp,
            )

            // Queue book for opt-in upload; configure MockWebServer to fail chunk 2 on first attempt.
            assertEquals(1, devA.epubTransfers.queueSelectedBooks(PERM_A, setOf(bookA.url)))
            httpBackend.failChunk2Once.set(true)

            val attempt1 = devA.epubTransfers.uploadPending()
            assertEquals(0, attempt1.uploadedCount)
            assertEquals(1, attempt1.failedCount)
            assertTrue(attempt1.retryableFailure)

            // Verify mid-upload state persisted cleanly: chunk 1 (1 MiB) recorded, sessionUri saved, not published yet.
            val persistedUpload = devA.driveDb.uploads().get(PERM_A, expectedFp.sha256.lowercase(Locale.ROOT))
            assertNotNull(persistedUpload)
            assertEquals(DriveUpload.STATE_FAILED, persistedUpload!!.state)
            assertNotNull(persistedUpload.sessionUri)
            assertEquals(DriveRemoteApi.DEFAULT_RESUMABLE_CHUNK_BYTES.toLong(), persistedUpload.bytesSent)
            assertNull(persistedUpload.fileId)
            assertEquals(1, httpBackend.chunk1UploadCount.get())

            // Resume upload attempt 2: probes server offset via Content-Range: bytes */2621440,
            // skips chunk 1, uploads chunk 2 and chunk 3, and completes with verified bytes.
            val attempt2 = devA.epubTransfers.uploadPending()
            assertEquals(1, attempt2.uploadedCount)
            assertEquals(0, attempt2.failedCount)
            assertEquals(1, httpBackend.offsetProbeCount.get())
            assertEquals(1, httpBackend.chunk1UploadCount.get()) // Chunk 1 was NOT re-uploaded!
            assertEquals(totalSize, httpBackend.assembledUploadBytes().size)
            val serverUploadedFp = BookFingerprints.of(ByteArrayInputStream(httpBackend.assembledUploadBytes()))
            assertEquals(expectedFp.sha256, serverUploadedFp.sha256)

            val finishedUpload = devA.driveDb.uploads().get(PERM_A, expectedFp.sha256.lowercase(Locale.ROOT))!!
            assertEquals(DriveUpload.STATE_UPLOADED, finishedUpload.state)
            assertNotNull(finishedUpload.fileId)

            // Device B pulls catalog and discovers the uploaded EPUB.
            assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)
            val stubB = devB.liseurDb.bookDao().getByUrl("drive:isbn:9780441172719")
            assertNotNull(stubB)
            assertEquals("drive-file:${finishedUpload.fileId}", stubB!!.downloadHref)

            // Download attempt 1: interrupt mid-stream after 1,200,000 bytes.
            val truncateAt = 1_200_000
            httpBackend.truncateFirstDownloadAtBytes.set(truncateAt)

            val dl1 = devB.epubTransfers.downloadBook(stubB.url)
            assertTrue("Expected RetryableFailure on truncated stream, got $dl1", dl1 is DriveEpubDownloadOutcome.RetryableFailure)

            val partFile = DriveEpubTransferManager.downloadPartFile(devB.booksDir, stubB.url, expectedFp.sha256)
            val finalFile = DriveEpubTransferManager.downloadTargetFile(devB.booksDir, stubB.url, expectedFp.sha256)
            assertTrue("Partial download file must persist across retry", partFile.isFile)
            assertEquals(truncateAt.toLong(), partFile.length())
            assertFalse("Final EPUB must not exist before SHA-256 verification", finalFile.exists())
            assertEquals(DownloadState.QUEUED, devB.liseurDb.bookDao().getByUrl(stubB.url)?.downloadState)

            // Download attempt 2: resumes with Range: bytes=1200000- (206 Partial Content), verifies SHA-256.
            val dl2 = devB.epubTransfers.downloadBook(stubB.url)
            assertTrue("Expected Downloaded on resume, got $dl2", dl2 is DriveEpubDownloadOutcome.Downloaded)
            val downloaded = dl2 as DriveEpubDownloadOutcome.Downloaded
            assertEquals(expectedFp.sha256.lowercase(Locale.ROOT), downloaded.sha256.lowercase(Locale.ROOT))
            assertEquals("bytes=$truncateAt-", httpBackend.lastDownloadRangeHeader)
            assertFalse("Part file should be renamed away after verification", partFile.exists())
            assertTrue("Final verified EPUB should exist", finalFile.isFile)
            assertEquals(totalSize.toLong(), finalFile.length())
            val diskFp = BookFingerprints.of(ByteArrayInputStream(finalFile.readBytes()))
            assertEquals(expectedFp.sha256, diskFp.sha256)
            assertEquals(DownloadState.DOWNLOADED, devB.liseurDb.bookDao().getByUrl(stubB.url)?.downloadState)
        } finally {
            mockServer.close()
        }
    }

    @Test
    fun `drive-side EPUB rename keeps file linked by ID and downloadable while trash and deletion mark media unavailable and retain history`() = runTest {
        val server = InstrumentedFakeDriveServer()
        val devA = createDevice("dev-a", driveApi = server.clientFor("dev-a"))
        val devB = createDevice("dev-b", driveApi = server.clientFor("dev-b"))
        val devC = createDevice("dev-c", driveApi = server.clientFor("dev-c"))
        devA.connect()
        devB.connect()
        devC.connect()

        val baseTime = 1_746_000_000_000L
        val book1 = seedBook(devA, "hobbit.epub", "9780547928227", "The Hobbit", "J.R.R. Tolkien", "HOBBIT-EPUB-BYTES")
        val book2 = seedBook(devA, "dune.epub", "9780441172719", "Dune", "Frank Herbert", "DUNE-EPUB-BYTES")
        val fp1 = devA.liseurDb.workIdentityDao().fingerprint(book1.url)!!

        // Seed reading progress, sessions, and annotations for both books on Device A.
        devA.liseurDb.readingProgressDao().recordLocal(
            bookUrl = book1.url,
            locatorJson = """{"href":"ch03.xhtml","locations":{"totalProgression":0.35}}""",
            progression = 0.35,
            readingSecondsPerPosition = null,
            readingPaceSamples = null,
            readingPaceElapsedMs = null,
            readingPaceEvidence = null,
            status = "Reading",
            updatedAt = baseTime,
        )
        devA.liseurDb.readingProgressDao().recordLocal(
            bookUrl = book2.url,
            locatorJson = """{"href":"ch07.xhtml","locations":{"totalProgression":0.62}}""",
            progression = 0.62,
            readingSecondsPerPosition = null,
            readingPaceSamples = null,
            readingPaceElapsedMs = null,
            readingPaceEvidence = null,
            status = "Reading",
            updatedAt = baseTime + 100L,
        )
        devA.liseurDb.annotationDao().upsert(
            BookAnnotation(
                id = "ann-hobbit-1",
                bookId = book1.url,
                kind = AnnotationKind.NOTE.name,
                locatorJson = """{"href":"ch03.xhtml"}""",
                text = "In a hole in the ground",
                note = "Keep this note even if Drive file is trashed",
                createdAt = baseTime,
                updatedAt = baseTime * 1_000L,
            ),
        )
        devA.liseurDb.annotationDao().upsert(
            BookAnnotation(
                id = "ann-dune-1",
                bookId = book2.url,
                kind = AnnotationKind.HIGHLIGHT.name,
                locatorJson = """{"href":"ch07.xhtml"}""",
                text = "Fear is the mind-killer",
                note = "Keep this highlight even if Drive file is permanently deleted",
                createdAt = baseTime + 50L,
                updatedAt = (baseTime + 50L) * 1_000L,
            ),
        )
        devA.liseurDb.readingSessionDao().insert(
            ReadingSession(
                bookUrl = book1.url,
                startedAt = baseTime - 3_600_000L,
                endedAt = baseTime - 1_800_000L,
                lastCheckpointAt = baseTime - 1_800_000L,
                durationMs = 1_800_000L,
                startProgression = 0.10,
                endProgression = 0.35,
            ),
        )
        devA.liseurDb.readingSessionDao().insert(
            ReadingSession(
                bookUrl = book2.url,
                startedAt = baseTime - 7_200_000L,
                endedAt = baseTime - 5_400_000L,
                lastCheckpointAt = baseTime - 5_400_000L,
                durationMs = 1_800_000L,
                startProgression = 0.40,
                endProgression = 0.62,
            ),
        )

        // Upload both EPUBs and sync catalog + reading data to Drive.
        assertEquals(2, devA.epubTransfers.queueSelectedBooks(PERM_A, setOf(book1.url, book2.url)))
        val uploadSummary = devA.epubTransfers.uploadPending()
        assertEquals(2, uploadSummary.uploadedCount)
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)

        val fileId1 = requireNotNull(devA.driveDb.uploads().get(PERM_A, fp1.sha256.lowercase(Locale.ROOT))?.fileId)
        val fp2 = devA.liseurDb.workIdentityDao().fingerprint(book2.url)!!
        val fileId2 = requireNotNull(devA.driveDb.uploads().get(PERM_A, fp2.sha256.lowercase(Locale.ROOT))?.fileId)

        // Devices B and C pull both books + history.
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, devC.changeEngine.sync().outcome)
        val stub1Url = "drive:isbn:9780547928227"
        val stub2Url = "drive:isbn:9780441172719"
        assertEquals("drive-file:$fileId1", devB.liseurDb.bookDao().getByUrl(stub1Url)?.downloadHref)
        assertEquals("drive-file:$fileId2", devC.liseurDb.bookDao().getByUrl(stub2Url)?.downloadHref)

        // 1. User renames Book 1's EPUB directly in Google Drive (same Drive file ID).
        val renamedName = "My Custom Renamed Copy - The Hobbit (2026).epub"
        server.renameFileOnDrive(fileId1, renamedName)

        // Device B pulls the rename via changes.list: file remains tracked by fileId and downloadable!
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)
        val indexedAfterRename = devB.driveDb.files().get(PERM_A, fileId1)
        assertNotNull(indexedAfterRename)
        assertEquals(renamedName, indexedAfterRename!!.name)
        assertFalse(indexedAfterRename.trashed)
        assertEquals("drive-file:$fileId1", devB.liseurDb.bookDao().getByUrl(stub1Url)?.downloadHref)

        val dlRenamed = devB.epubTransfers.downloadBook(stub1Url)
        assertTrue("Renamed EPUB must still download cleanly by fileId, got $dlRenamed", dlRenamed is DriveEpubDownloadOutcome.Downloaded)
        assertEquals(fp1.sha256.lowercase(Locale.ROOT), (dlRenamed as DriveEpubDownloadOutcome.Downloaded).sha256.lowercase(Locale.ROOT))

        // 2. User trashes Book 1's EPUB directly in Google Drive.
        server.trashFileOnDrive(fileId1)
        assertEquals(SyncOutcome.Success, devC.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)

        // Device C retains catalog entry, progress, session, and annotation, while media is marked unavailable.
        val stub1AfterTrash = devC.liseurDb.bookDao().getByUrl(stub1Url)
        assertNotNull("Catalog entry must be retained after Drive-side trash", stub1AfterTrash)
        assertNull("downloadHref must be cleared when Drive EPUB is trashed", stub1AfterTrash!!.downloadHref)
        assertTrue(devC.driveDb.files().get(PERM_A, fileId1)?.trashed == true)
        assertTrue(devC.epubTransfers.downloadBook(stub1Url) is DriveEpubDownloadOutcome.PermanentFailure)
        assertEquals(0.35, devC.liseurDb.readingProgressDao().get(stub1Url)?.totalProgression ?: -1.0, 0.001)
        assertEquals(1, devC.liseurDb.annotationDao().forBook(stub1Url).size)
        assertEquals("Keep this note even if Drive file is trashed", devC.liseurDb.annotationDao().byId("ann-hobbit-1")?.note)
        assertEquals(1, devC.liseurDb.readingSessionDao().closedForBackup().count { it.bookUrl == stub1Url })

        // Device A suppresses its UPLOADED queue entry without auto-reuploading so the user can explicitly re-select.
        assertEquals(
            DriveUpload.STATE_SUPPRESSED,
            devA.driveDb.uploads().get(PERM_A, fp1.sha256.lowercase(Locale.ROOT))?.state,
        )

        // 3. User permanently deletes Book 2's EPUB directly in Google Drive (`removed = true`).
        server.permanentlyDeleteFileOnDrive(fileId2)
        assertEquals(SyncOutcome.Success, devC.changeEngine.sync().outcome)

        val stub2AfterDelete = devC.liseurDb.bookDao().getByUrl(stub2Url)
        assertNotNull("Catalog entry must be retained after Drive-side permanent deletion", stub2AfterDelete)
        assertNull("downloadHref must be cleared when Drive EPUB is permanently deleted", stub2AfterDelete!!.downloadHref)
        assertTrue(devC.epubTransfers.downloadBook(stub2Url) is DriveEpubDownloadOutcome.PermanentFailure)
        assertEquals(0.62, devC.liseurDb.readingProgressDao().get(stub2Url)?.totalProgression ?: -1.0, 0.001)
        assertEquals(1, devC.liseurDb.annotationDao().forBook(stub2Url).size)
        assertEquals("Keep this highlight even if Drive file is permanently deleted", devC.liseurDb.annotationDao().byId("ann-dune-1")?.note)
        assertEquals(1, devC.liseurDb.readingSessionDao().closedForBackup().count { it.bookUrl == stub2Url })
    }

    @Test
    fun `large catalog of 250 books and 300 sessions with multiple compactions bounds HTTP requests and appDataFolder file count`() = runTest(timeout = 3.minutes) {
        val server = InstrumentedFakeDriveServer()
        val devA = createDevice(
            deviceId = "dev-a",
            driveApi = server.clientFor("dev-a"),
            compactionDeltaThreshold = 5,
        )
        val devB = createDevice(
            deviceId = "dev-b",
            driveApi = server.clientFor("dev-b"),
        )
        devA.connect()
        devB.connect()

        val bookCount = 250
        val initialSessionCount = 300
        val initialAnnotationCount = 50
        val baseTime = 1_746_500_000_000L

        // Seed 250 books with deterministic ISBN-13s and fingerprints.
        val books = ArrayList<Book>(bookCount)
        val bookRows = ArrayList<Book>(bookCount)
        val fpRows = ArrayList<BookFingerprintRow>(bookCount)
        val metaRows = ArrayList<BookMetadata>(bookCount)
        for (i in 0 until bookCount) {
            val isbn = "9781000%06d".format(Locale.ROOT, i)
            val content = "EPUB-SCALE-BOOK-$i-$isbn".toByteArray()
            val fp = BookFingerprints.of(ByteArrayInputStream(content))
            val url = "file:///scale/book_$i.epub"
            val book = Book(
                url = url,
                title = "Scale Volume #%03d".format(Locale.ROOT, i + 1),
                author = "Author ${(i % 25) + 1}",
                coverPath = null,
                source = null,
                addedAt = baseTime + i * 1_000L,
                lastOpenedAt = null,
                localUri = url,
                downloadState = DownloadState.DOWNLOADED,
                sizeBytes = fp.size,
                fileMetadataChecked = true,
                fileTitle = "Scale Volume #%03d".format(Locale.ROOT, i + 1),
                fileAuthor = "Author ${(i % 25) + 1}",
            )
            books.add(book)
            bookRows.add(book)
            fpRows.add(BookFingerprintRow(url, fp.sha256, fp.partialMd5, fp.size, book.fileModifiedAt, baseTime))
            metaRows.add(
                BookMetadata.of(
                    url,
                    MetadataSource.FILE,
                    MetadataSnapshot(identifiers = listOf(Identifier("isbn", isbn))),
                ),
            )
        }
        devA.liseurDb.withTransaction {
            devA.liseurDb.bookDao().upsertAll(bookRows)
            fpRows.forEach { devA.liseurDb.workIdentityDao().upsert(it) }
            metaRows.forEach { devA.liseurDb.bookMetadataDao().upsert(it) }
        }

        // Seed 300 reading sessions across the 250 books + 50 annotations.
        devA.liseurDb.withTransaction {
            for (s in 0 until initialSessionCount) {
                val targetBook = books[s % bookCount]
                val start = baseTime + (s + 1) * 600_000L
                val duration = 300_000L + (s % 10) * 60_000L
                val startProg = ((s % 80) / 100.0).coerceIn(0.0, 0.9)
                devA.liseurDb.readingSessionDao().insert(
                    ReadingSession(
                        bookUrl = targetBook.url,
                        startedAt = start,
                        endedAt = start + duration,
                        lastCheckpointAt = start + duration,
                        durationMs = duration,
                        startProgression = startProg,
                        endProgression = (startProg + 0.05).coerceAtMost(1.0),
                    ),
                )
            }
            for (a in 0 until initialAnnotationCount) {
                val targetBook = books[a % bookCount]
                val ts = baseTime + (a + 1) * 1_000L
                devA.liseurDb.annotationDao().upsert(
                    BookAnnotation(
                        id = "scale-ann-$a",
                        bookId = targetBook.url,
                        kind = AnnotationKind.NOTE.name,
                        locatorJson = """{"href":"ch${(a % 12) + 1}.xhtml"}""",
                        text = "Passage #$a",
                        note = "Initial note #$a",
                        createdAt = ts,
                        updatedAt = ts * 1_000L,
                    ),
                )
            }
        }

        // Initial seed sync on Device A.
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)

        // Drive 20 additional incremental syncs on Device A -> triggers >= 3 compactions & prunings.
        var compactions = 0
        var prunedDeltasTotal = 0
        var prunedSnapshotsTotal = 0
        for (step in 1..20) {
            val annIdx = step % initialAnnotationCount
            val targetBook = books[annIdx % bookCount]
            val ts = baseTime + 500_000_000L + step * 5_000L
            devA.liseurDb.annotationDao().upsert(
                BookAnnotation(
                    id = "scale-ann-$annIdx",
                    bookId = targetBook.url,
                    kind = AnnotationKind.NOTE.name,
                    locatorJson = """{"href":"ch${(annIdx % 12) + 1}.xhtml"}""",
                    text = "Passage #$annIdx",
                    note = "Updated note #$annIdx at step $step",
                    createdAt = baseTime + (annIdx + 1) * 1_000L,
                    updatedAt = ts * 1_000L,
                ),
            )
            val run = devA.changeEngine.sync()
            assertEquals(SyncOutcome.Success, run.outcome)
            if (run.compacted) {
                compactions++
                prunedDeltasTotal += run.prunedDeltaCount
                prunedSnapshotsTotal += run.prunedSnapshotPartCount
            }
        }

        assertTrue("Expected at least 3 compactions, got $compactions", compactions >= 3)
        assertTrue("Expected superseded deltas to be pruned, got $prunedDeltasTotal", prunedDeltasTotal > 0)
        assertTrue("Expected older snapshot generation to be pruned, got $prunedSnapshotsTotal", prunedSnapshotsTotal > 0)

        val appDataFilesAfterCompaction = server.liveAppDataFileCount()
        val appDataBytesAfterCompaction = server.liveAppDataTotalBytes()
        val totalDeltasCreated = server.totalDeltaUploads.get()
        // Manifest (1) + latest snapshot part(s) + 1 generation-lagged snapshot part(s) + <= 5 uncompacted deltas.
        assertTrue(
            "appDataFolder file count ($appDataFilesAfterCompaction) must stay tightly bounded (<= 15) and below total deltas created ($totalDeltasCreated)",
            appDataFilesAfterCompaction <= 15 && appDataFilesAfterCompaction < totalDeltasCreated,
        )

        // Measure fresh Device B first-sync latency & HTTP request count.
        server.resetHttpCounts("dev-b")
        val firstSyncStartNs = System.nanoTime()
        val firstSyncRun = devB.changeEngine.sync()
        val firstSyncMs = (System.nanoTime() - firstSyncStartNs) / 1_000_000L
        assertEquals(SyncOutcome.Success, firstSyncRun.outcome)
        val firstSyncHttp = server.httpCountFor("dev-b")

        // Verify complete fidelity on Device B across all 250 books, 300 sessions, and 50 annotations.
        val booksOnB = devB.liseurDb.bookDao().allOnce()
        val sessionsOnA = devA.liseurDb.readingSessionDao().closedForBackup()
        val sessionsOnB = devB.liseurDb.readingSessionDao().closedForBackup()
        val annotationsOnB = devB.liseurDb.annotationDao().all()
        assertEquals(bookCount, booksOnB.size)
        assertEquals(initialSessionCount, sessionsOnB.size)
        assertEquals(sessionsOnA.sumOf { it.durationMs }, sessionsOnB.sumOf { it.durationMs })
        assertEquals(initialAnnotationCount, annotationsOnB.size)
        assertEquals("Updated note #20 at step 20", devB.liseurDb.annotationDao().byId("scale-ann-20")?.note)

        // First-sync HTTP requests must be O(snapshot_parts + uncompacted_deltas), NOT O(books + sessions)!
        assertTrue(
            "Expected bounded first-sync HTTP requests (<= 20) for 250 books + 300 sessions, got $firstSyncHttp",
            firstSyncHttp <= 20,
        )

        // Measure single-edit incremental sync HTTP request count (push on A, pull on B).
        server.resetHttpCounts("dev-a")
        server.resetHttpCounts("dev-b")
        val singleEditTs = baseTime + 900_000_000L
        devA.liseurDb.annotationDao().upsert(
            BookAnnotation(
                id = "scale-ann-0",
                bookId = books[0].url,
                kind = AnnotationKind.NOTE.name,
                locatorJson = """{"href":"ch1.xhtml"}""",
                text = "Passage #0",
                note = "Single incremental edit check",
                createdAt = baseTime + 1_000L,
                updatedAt = singleEditTs * 1_000L,
            ),
        )
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        val incrementalPushHttp = server.httpCountFor("dev-a")

        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)
        val incrementalPullHttp = server.httpCountFor("dev-b")

        assertEquals("Single incremental edit check", devB.liseurDb.annotationDao().byId("scale-ann-0")?.note)
        assertTrue(
            "Expected bounded single-edit push HTTP requests (<= 5), got $incrementalPushHttp",
            incrementalPushHttp <= 5,
        )
        assertTrue(
            "Expected bounded single-edit pull HTTP requests (<= 5), got $incrementalPullHttp",
            incrementalPullHttp <= 5,
        )

        println(
            "[Phase7Metrics] books=$bookCount sessions=$initialSessionCount annotations=$initialAnnotationCount " +
                "compactions=$compactions totalDeltasCreated=$totalDeltasCreated " +
                "prunedDeltas=$prunedDeltasTotal prunedSnapshotParts=$prunedSnapshotsTotal " +
                "appDataFiles=$appDataFilesAfterCompaction appDataBytes=$appDataBytesAfterCompaction " +
                "firstSyncMs=$firstSyncMs firstSyncHttp=$firstSyncHttp " +
                "incrementalPushHttp=$incrementalPushHttp incrementalPullHttp=$incrementalPullHttp",
        )
    }

    @Test
    fun `full account switching round-trip A to B and back to A enforces zero writes before confirmation and resumes A cleanly`() = runTest {
        val serverA = InstrumentedFakeDriveServer(permissionId = PERM_A, email = EMAIL_A)
        val serverB = InstrumentedFakeDriveServer(permissionId = PERM_B, email = EMAIL_B)
        val multiAccountApi = MultiAccountDriveApi(
            accountsByToken = mapOf(
                TOKEN_A to serverA.clientFor("dev-main"),
                TOKEN_B to serverB.clientFor("dev-main"),
            ),
        )
        val switchableAuth = SwitchableFakeAuthClient(initialToken = TOKEN_A)

        val devMain = createDevice(
            deviceId = "dev-main",
            driveApi = multiAccountApi,
            authClient = switchableAuth,
        )
        val peerOnA = createDevice(
            deviceId = "dev-peer-a",
            driveApi = serverA.clientFor("dev-peer-a"),
            authClient = SwitchableFakeAuthClient(TOKEN_A),
        )
        peerOnA.connect(PERM_A, EMAIL_A)

        // 1. Connect Account A on empty library -> activates immediately.
        val controller = devMain.driveSync.accountController
        controller.beginConnect()
        assertNull(controller.uiState.first().pendingConfirmation)
        assertEquals(PERM_A, devMain.driveDb.accounts().active()?.permissionId)

        // Seed local library on devMain (2 books, 1 note, 1 session) and sync to Account A.
        val baseTime = 1_747_000_000_000L
        val book1 = seedBook(devMain, "roundtrip-1.epub", "9780547928227", "The Hobbit", "J.R.R. Tolkien", "RT-BOOK-1")
        val book2 = seedBook(devMain, "roundtrip-2.epub", "9780441172719", "Dune", "Frank Herbert", "RT-BOOK-2")
        devMain.liseurDb.annotationDao().upsert(
            BookAnnotation(
                id = "ann-main-1",
                bookId = book1.url,
                kind = AnnotationKind.NOTE.name,
                locatorJson = """{"href":"ch01.xhtml"}""",
                text = "Main note",
                note = "Created while on Account A",
                createdAt = baseTime,
                updatedAt = baseTime * 1_000L,
            ),
        )
        devMain.liseurDb.readingSessionDao().insert(
            ReadingSession(
                bookUrl = book1.url,
                startedAt = baseTime - 900_000L,
                endedAt = baseTime,
                lastCheckpointAt = baseTime,
                durationMs = 900_000L,
                startProgression = 0.05,
                endProgression = 0.25,
            ),
        )
        assertEquals(SyncOutcome.Success, devMain.changeEngine.sync().outcome)

        // Peer on Account A pulls and publishes its own annotation so devMain has a remote cursor on Account A.
        assertEquals(SyncOutcome.Success, peerOnA.changeEngine.sync().outcome)
        val peerStub1 = peerOnA.liseurDb.bookDao().getByUrl("drive:isbn:9780547928227")!!
        peerOnA.liseurDb.annotationDao().upsert(
            BookAnnotation(
                id = "ann-peer-a-1",
                bookId = peerStub1.url,
                kind = AnnotationKind.NOTE.name,
                locatorJson = """{"href":"ch02.xhtml"}""",
                text = "Peer A note 1",
                note = "Synced before switching away from A",
                createdAt = baseTime + 1_000L,
                updatedAt = (baseTime + 1_000L) * 1_000L,
            ),
        )
        assertEquals(SyncOutcome.Success, peerOnA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, devMain.changeEngine.sync().outcome)
        assertNotNull(devMain.liseurDb.annotationDao().byId("ann-peer-a-1"))

        // Queue Book 1 as a PENDING EPUB upload on Account A (not uploaded yet).
        assertEquals(1, devMain.epubTransfers.queueSelectedBooks(PERM_A, setOf(book1.url)))

        // Snapshot Account A's persisted cursors, startPageToken, and pending uploads before switching.
        val accountABeforeSwitch = requireNotNull(devMain.driveDb.accounts().get(PERM_A))
        val savedStartPageTokenA = requireNotNull(accountABeforeSwitch.startPageToken)
        val savedCursorsA = devMain.driveDb.cursors().forAccount(PERM_A)
        val savedUploadsA = devMain.driveDb.uploads().pendingForAccount(PERM_A)
        assertTrue(savedCursorsA.isNotEmpty())
        assertEquals(1, savedUploadsA.size)
        val accountAWritesBeforeSwitch = serverA.writeCallCount.get()

        // 2. Switch Account A -> Account B: MUST require confirmation with ZERO writes to B beforehand.
        switchableAuth.currentToken = TOKEN_B
        controller.beginConnect(switchAccount = true)

        val pendingToB = requireNotNull(controller.uiState.first().pendingConfirmation)
        assertEquals(PERM_A, pendingToB.previousAccount?.permissionId)
        assertEquals(PERM_B, pendingToB.targetPermissionId)
        assertEquals(EMAIL_B, pendingToB.targetEmail)
        assertEquals(LocalLibrarySummary(booksCount = 2, notesCount = 2, readingSessionsCount = 1), pendingToB.localSummary)

        // Strict invariant: ZERO writes to Account B before confirmation!
        assertEquals(0, serverB.writeCallCount.get())
        assertEquals(DriveAccount.STATE_PAUSED, devMain.driveDb.accounts().get(PERM_A)?.state)
        assertNull(devMain.driveDb.accounts().active())

        // Confirm Account B -> seeds Account B without touching Account A's Drive or A's paused DB state.
        controller.confirmPendingSwitch()
        assertEquals(PERM_B, devMain.driveDb.accounts().active()?.permissionId)
        assertEquals(DriveAccount.STATE_PAUSED, devMain.driveDb.accounts().get(PERM_A)?.state)
        assertEquals(SyncOutcome.Success, devMain.changeEngine.sync().outcome)
        assertTrue("Account B should receive seeded batches after confirmation", serverB.writeCallCount.get() > 0)
        assertEquals("Account A Drive must receive zero writes while on Account B", accountAWritesBeforeSwitch, serverA.writeCallCount.get())
        assertEquals(savedStartPageTokenA, devMain.driveDb.accounts().get(PERM_A)?.startPageToken)
        assertEquals(savedCursorsA, devMain.driveDb.cursors().forAccount(PERM_A))
        assertEquals(1, devMain.driveDb.uploads().pendingForAccount(PERM_A).size)

        // Meanwhile, peer on Account A publishes a new delta while Account A is paused on devMain.
        peerOnA.liseurDb.annotationDao().upsert(
            BookAnnotation(
                id = "ann-peer-a-while-paused",
                bookId = peerStub1.url,
                kind = AnnotationKind.NOTE.name,
                locatorJson = """{"href":"ch04.xhtml"}""",
                text = "Peer A note while paused",
                note = "Published on A while devMain was on B",
                createdAt = baseTime + 50_000L,
                updatedAt = (baseTime + 50_000L) * 1_000L,
            ),
        )
        assertEquals(SyncOutcome.Success, peerOnA.changeEngine.sync().outcome)

        // 3. Switch back Account B -> Account A: requires confirmation with ZERO writes to A before confirmation.
        val accountAWritesBeforeReturn = serverA.writeCallCount.get()
        val accountBWritesBeforeReturn = serverB.writeCallCount.get()
        switchableAuth.currentToken = TOKEN_A
        controller.beginConnect(switchAccount = true)

        val pendingToA = requireNotNull(controller.uiState.first().pendingConfirmation)
        assertEquals(PERM_B, pendingToA.previousAccount?.permissionId)
        assertEquals(PERM_A, pendingToA.targetPermissionId)
        assertEquals(EMAIL_A, pendingToA.targetEmail)
        assertEquals("Zero writes to Account A before switch-back confirmation", accountAWritesBeforeReturn, serverA.writeCallCount.get())
        assertEquals(DriveAccount.STATE_PAUSED, devMain.driveDb.accounts().get(PERM_B)?.state)

        // Confirm return to Account A -> Account A resumes with its exact paused cursors & pending upload queue intact!
        controller.confirmPendingSwitch()
        assertEquals(PERM_A, devMain.driveDb.accounts().active()?.permissionId)
        assertEquals(DriveAccount.STATE_PAUSED, devMain.driveDb.accounts().get(PERM_B)?.state)
        assertEquals(savedStartPageTokenA, devMain.driveDb.accounts().get(PERM_A)?.startPageToken)
        assertEquals(savedCursorsA, devMain.driveDb.cursors().forAccount(PERM_A))
        assertEquals(1, devMain.driveDb.uploads().pendingForAccount(PERM_A).size)

        // Incremental resume on Account A: does NOT perform a full bootstrap listAppDataFiles rescan!
        val listAppDataBeforeResume = serverA.listAppDataFilesCalls.get()
        assertEquals(SyncOutcome.Success, devMain.changeEngine.sync().outcome)
        assertEquals(
            "Returning to Account A should resume incrementally from saved startPageToken without full appData rescan",
            listAppDataBeforeResume,
            serverA.listAppDataFilesCalls.get(),
        )
        assertEquals(
            "Published on A while devMain was on B",
            devMain.liseurDb.annotationDao().byId("ann-peer-a-while-paused")?.note,
        )
        assertEquals("Account B Drive must remain untouched after returning to A", accountBWritesBeforeReturn, serverB.writeCallCount.get())

        // Account A's preserved queue is held until its fresh picker answer, then drains
        // into Account A's Liseur/ folder.
        assertNull(devMain.driveDb.accounts().get(PERM_A)?.uploadSelectionAt)
        assertEquals(0, devMain.epubTransfers.uploadPending().uploadedCount)
        assertEquals(1, devMain.driveDb.uploads().pendingForAccount(PERM_A).size)
        assertEquals(0, serverA.liseurFolderCount())

        controller.confirmUploadSelection(setOf(book1.url))

        val resumeUploadSummary = devMain.epubTransfers.uploadPending()
        assertEquals(1, resumeUploadSummary.uploadedCount)
        assertTrue(devMain.driveDb.uploads().pendingForAccount(PERM_A).isEmpty())
        assertEquals(1, serverA.liseurFolderCount())
        assertEquals(0, serverB.liseurFolderCount())
    }

    // ---- Test Helpers ----

    private suspend fun seedBook(
        device: Phase7Device,
        fileName: String,
        isbn: String,
        title: String,
        author: String,
        content: String,
    ): Book {
        val bytes = content.toByteArray()
        val file = File(device.dir, fileName).apply { writeBytes(bytes) }
        val fp = BookFingerprints.of(ByteArrayInputStream(bytes))
        return seedBookFromFile(device, file, isbn, title, author, fp)
    }

    private suspend fun seedBookFromFile(
        device: Phase7Device,
        file: File,
        isbn: String,
        title: String,
        author: String,
        fp: BookFingerprint,
    ): Book {
        val url = file.toURI().toString()
        val book = Book(
            url = url,
            title = title,
            author = author,
            coverPath = null,
            source = null,
            addedAt = 1_746_000_000_000L,
            lastOpenedAt = null,
            localUri = url,
            downloadState = DownloadState.DOWNLOADED,
            sizeBytes = fp.size,
            fileMetadataChecked = true,
            fileTitle = title,
            fileAuthor = author,
        )
        device.liseurDb.bookDao().upsert(book)
        device.liseurDb.workIdentityDao().upsert(
            BookFingerprintRow(url, fp.sha256, fp.partialMd5, fp.size, file.lastModified(), 1_746_000_000_000L),
        )
        device.liseurDb.bookMetadataDao().upsert(
            BookMetadata.of(url, MetadataSource.FILE, MetadataSnapshot(identifiers = listOf(Identifier("isbn", isbn)))),
        )
        return book
    }

    private fun createDevice(
        deviceId: String,
        driveApi: DriveRemoteApi,
        authClient: DriveAuthClient = SwitchableFakeAuthClient(TOKEN_A),
        compactionDeltaThreshold: Int = DriveCompactor.DEFAULT_DELTA_COUNT_THRESHOLD,
        compactionBytesThreshold: Long = DriveCompactor.DEFAULT_DELTA_BYTES_THRESHOLD,
    ): Phase7Device {
        val dir = File(tempDir, "$deviceId-${System.nanoTime()}").apply { mkdirs() }
        val booksDir = File(dir, "books").apply { mkdirs() }
        val coversDir = File(dir, "covers").apply { mkdirs() }
        val liseurDb = Room.inMemoryDatabaseBuilder(context, LiseurDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val driveDb = Room.inMemoryDatabaseBuilder(context, DriveSyncDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val clockRef = AtomicLong(1_746_000_000_000L)
        val clock: () -> Long = { clockRef.addAndGet(10L) }
        val bookRemoval = BookRemoval(
            bookDao = liseurDb.bookDao(),
            sessionDao = liseurDb.readingSessionDao(),
            peerStateDao = liseurDb.syncPeerStateDao(),
            identityDao = liseurDb.workIdentityDao(),
            progressDao = liseurDb.readingProgressDao(),
            annotationDao = liseurDb.annotationDao(),
            annotationSyncDao = liseurDb.annotationSyncDao(),
            inTransaction = { work -> liseurDb.withTransaction { work() } },
            metadataDao = liseurDb.bookMetadataDao(),
            evaluationDao = liseurDb.bookEvaluationDao(),
        )
        val remoteAccount = RemoteAccountRepository(
            dao = liseurDb.remoteServerDao(),
            bookDao = liseurDb.bookDao(),
            progressDao = liseurDb.readingProgressDao(),
            seriesExtraDao = liseurDb.seriesExtraDao(),
            bookRemoval = bookRemoval,
            inTransaction = { work -> liseurDb.withTransaction { work() } },
        )
        val driveSync = DriveLibrarySync(
            context = context,
            database = liseurDb,
            remoteAccount = remoteAccount,
            bookRemoval = bookRemoval,
            switchCoordinator = LibrarySyncSwitchCoordinator(),
            deviceIdGenerator = { deviceId },
            openStore = { driveDb },
            authClient = authClient,
            driveApi = driveApi,
            coversDirProvider = { coversDir },
            coverEncoder = FakeWebpEncoder,
            compactionDeltaThreshold = compactionDeltaThreshold,
            compactionBytesThreshold = compactionBytesThreshold,
            clock = clock,
        )
        val epubTransfers = DriveEpubTransferManager(
            context = context,
            database = liseurDb,
            store = driveDb,
            driveApi = driveApi,
            workIdentity = driveSync.workIdentity,
            catalogSync = driveSync.catalogSync,
            fingerprintStore = driveSync.fingerprintStore,
            nextHlc = { perm -> driveSync.changeEngine.nextHlc(perm) },
            acquireToken = { stale -> driveSync.accountController.acquireSilentAccessToken(stale) },
            onCatalogAvailabilityChanged = { driveSync.changeEngine.sync() },
            booksDirProvider = { booksDir },
            clock = clock,
        )
        return Phase7Device(
            deviceId = deviceId,
            dir = dir,
            booksDir = booksDir,
            liseurDb = liseurDb,
            driveDb = driveDb,
            driveSync = driveSync,
            epubTransfers = epubTransfers,
            clockRef = clockRef,
        ).also { openDevices.add(it) }
    }

    private data class Phase7Device(
        val deviceId: String,
        val dir: File,
        val booksDir: File,
        val liseurDb: LiseurDatabase,
        val driveDb: DriveSyncDatabase,
        val driveSync: DriveLibrarySync,
        val epubTransfers: DriveEpubTransferManager,
        val clockRef: AtomicLong,
    ) {
        val changeEngine: DriveChangeEngine get() = driveSync.changeEngine

        suspend fun connect(permissionId: String = PERM_A, email: String = EMAIL_A) {
            driveSync.activateAccount(permissionId, email, now = clockRef.get())
        }

        fun close() {
            liseurDb.close()
            driveDb.close()
        }
    }

    private object FakeWebpEncoder : DriveWebpEncoder {
        override fun encode(sourceBytes: ByteArray, maxLongEdge: Int, quality: Int): EncodedImageFrame =
            EncodedImageFrame(bytes = sourceBytes, width = 600, height = 900)
    }

    private class SwitchableFakeAuthClient(initialToken: String) : DriveAuthClient {
        @Volatile
        var currentToken: String = initialToken

        override suspend fun authorize(accountEmail: String?, selectAccount: Boolean): DriveAuthResult =
            DriveAuthResult.Authorized(currentToken)

        override suspend fun authorizeSilently(accountEmail: String?): DriveAuthResult =
            DriveAuthResult.Authorized(currentToken)

        override fun parseAuthorizationResult(data: Intent?): DriveAuthResult =
            DriveAuthResult.Authorized(currentToken)

        override suspend fun clearCachedToken(accessToken: String) = Unit
    }

    /** Routes `DriveRemoteApi` calls to the isolated fake Google Drive matching the Bearer token. */
    private class MultiAccountDriveApi(
        private val accountsByToken: Map<String, DriveRemoteApi>,
    ) : DriveRemoteApi {
        private fun api(token: String): DriveRemoteApi =
            accountsByToken[token] ?: throw DriveAuthExpiredException("Unknown test token $token")

        override suspend fun getAbout(accessToken: String) = api(accessToken).getAbout(accessToken)
        override suspend fun listLiseurFolders(accessToken: String) = api(accessToken).listLiseurFolders(accessToken)
        override suspend fun ensureLiseurFolder(accessToken: String) = api(accessToken).ensureLiseurFolder(accessToken)
        override suspend fun listDriveEpubFiles(accessToken: String) = api(accessToken).listDriveEpubFiles(accessToken)
        override suspend fun getStartPageToken(accessToken: String) = api(accessToken).getStartPageToken(accessToken)
        override suspend fun listChanges(accessToken: String, pageToken: String, pageSize: Int) =
            api(accessToken).listChanges(accessToken, pageToken, pageSize)
        override suspend fun listAppDataFiles(accessToken: String, query: String?, pageSize: Int) =
            api(accessToken).listAppDataFiles(accessToken, query, pageSize)
        override suspend fun findAppDataFileByBatchId(accessToken: String, batchId: String) =
            api(accessToken).findAppDataFileByBatchId(accessToken, batchId)
        override suspend fun downloadMedia(accessToken: String, fileId: String) =
            api(accessToken).downloadMedia(accessToken, fileId)
        override suspend fun uploadAppDataJsonBatch(
            accessToken: String,
            name: String,
            jsonPayload: ByteArray,
            appProperties: Map<String, String>,
        ) = api(accessToken).uploadAppDataJsonBatch(accessToken, name, jsonPayload, appProperties)
        override suspend fun createAppDataFile(
            accessToken: String,
            name: String,
            mimeType: String,
            content: ByteArray,
            appProperties: Map<String, String>,
        ) = api(accessToken).createAppDataFile(accessToken, name, mimeType, content, appProperties)
        override suspend fun updateFileMedia(
            accessToken: String,
            fileId: String,
            mimeType: String,
            content: ByteArray,
            appProperties: Map<String, String>?,
        ) = api(accessToken).updateFileMedia(accessToken, fileId, mimeType, content, appProperties)
        override suspend fun deleteFile(accessToken: String, fileId: String) =
            api(accessToken).deleteFile(accessToken, fileId)
        override suspend fun trashFile(accessToken: String, fileId: String) =
            api(accessToken).trashFile(accessToken, fileId)
        override suspend fun initiateResumableUpload(
            accessToken: String,
            name: String,
            mimeType: String,
            totalBytes: Long,
            parents: List<String>,
            appProperties: Map<String, String>,
        ) = api(accessToken).initiateResumableUpload(accessToken, name, mimeType, totalBytes, parents, appProperties)
        override suspend fun queryResumableUploadOffset(accessToken: String, sessionUri: String, totalBytes: Long) =
            api(accessToken).queryResumableUploadOffset(accessToken, sessionUri, totalBytes)
        override suspend fun uploadResumableChunk(
            accessToken: String,
            sessionUri: String,
            chunk: ByteArray,
            offset: Long,
            totalBytes: Long,
            mimeType: String,
        ) = api(accessToken).uploadResumableChunk(accessToken, sessionUri, chunk, offset, totalBytes, mimeType)
        override suspend fun uploadResumableFile(
            accessToken: String,
            name: String,
            mimeType: String,
            totalBytes: Long,
            parents: List<String>,
            appProperties: Map<String, String>,
            existingSessionUri: String?,
            chunkSizeBytes: Int,
            openStream: () -> InputStream,
            onSessionUri: suspend (String) -> Unit,
            onProgress: suspend (Long, String) -> Unit,
        ) = api(accessToken).uploadResumableFile(
            accessToken, name, mimeType, totalBytes, parents, appProperties,
            existingSessionUri, chunkSizeBytes, openStream, onSessionUri, onProgress,
        )
        override suspend fun downloadResumable(
            accessToken: String,
            fileId: String,
            destination: OutputStream,
            startOffset: Long,
            onRestartFromZero: () -> Unit,
            onProgress: (Long) -> Unit,
        ) = api(accessToken).downloadResumable(accessToken, fileId, destination, startOffset, onRestartFromZero, onProgress)
    }

    /**
     * Delegates metadata/`appDataFolder` operations to [meta] while exercising the real
     * OkHttp `DriveRestClient` ([http]) for EPUB resumable upload and download!
     */
    private class HybridResumableDriveApi(
        private val meta: DriveRemoteApi,
        private val http: DriveRestClient,
        private val onHttpUploadCompleted: (DriveRemoteFile) -> Unit,
    ) : DriveRemoteApi by meta {
        override suspend fun uploadResumableFile(
            accessToken: String,
            name: String,
            mimeType: String,
            totalBytes: Long,
            parents: List<String>,
            appProperties: Map<String, String>,
            existingSessionUri: String?,
            chunkSizeBytes: Int,
            openStream: () -> InputStream,
            onSessionUri: suspend (String) -> Unit,
            onProgress: suspend (Long, String) -> Unit,
        ): DriveRemoteFile = if (DriveRestClient.SPACE_APPDATA in parents) {
            meta.uploadResumableFile(
                accessToken, name, mimeType, totalBytes, parents, appProperties,
                existingSessionUri, chunkSizeBytes, openStream, onSessionUri, onProgress,
            )
        } else {
            http.uploadResumableFile(
                accessToken, name, mimeType, totalBytes, parents, appProperties,
                existingSessionUri, chunkSizeBytes, openStream, onSessionUri, onProgress,
            ).also(onHttpUploadCompleted)
        }

        override suspend fun downloadResumable(
            accessToken: String,
            fileId: String,
            destination: OutputStream,
            startOffset: Long,
            onRestartFromZero: () -> Unit,
            onProgress: (Long) -> Unit,
        ): DriveDownloadResult = http.downloadResumable(
            accessToken, fileId, destination, startOffset, onRestartFromZero, onProgress,
        )
    }

    /** Stateful HTTP MockWebServer backend implementing Google Drive v3 resumable upload & Range download. */
    private class ResumableMockDriveBackend(private val expectedTotalBytes: Int) {
        val failChunk2Once = AtomicBoolean(false)
        val truncateFirstDownloadAtBytes = AtomicInteger(0)
        val chunk1UploadCount = AtomicInteger(0)
        val offsetProbeCount = AtomicInteger(0)
        @Volatile
        var lastDownloadRangeHeader: String? = null

        private val lock = Any()
        private val uploadedBuffer = ByteArray(expectedTotalBytes)
        private var uploadedLen = 0
        private var uploadedFileId = "epub-http-1"
        private var uploadedName = "large-volume.epub"
        private var uploadedAppProps = JSONObject()

        fun assembledUploadBytes(): ByteArray = synchronized(lock) {
            uploadedBuffer.copyOf(uploadedLen)
        }

        fun dispatcher(server: MockWebServer): Dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: "")
                return when {
                    request.method == "POST" && path.startsWith("/upload/drive/v3/files") && path.contains("uploadType=resumable") -> {
                        val bodyJson = JSONObject(request.body?.utf8().orEmpty())
                        synchronized(lock) {
                            uploadedName = bodyJson.optString("name", uploadedName)
                            uploadedAppProps = bodyJson.optJSONObject("appProperties") ?: JSONObject()
                        }
                        val sessionUrl = server.url("/upload/session/sess-1").toString()
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Location", sessionUrl)
                            .build()
                    }
                    request.method == "PUT" && path == "/upload/session/sess-1" -> {
                        handleResumablePut(request)
                    }
                    request.method == "GET" && path.startsWith("/drive/v3/files/$uploadedFileId") && path.contains("alt=media") -> {
                        handleResumableDownload(request)
                    }
                    else -> MockResponse.Builder()
                        .code(404)
                        .body("""{"error":{"code":404,"message":"Unhandled $path"}}""")
                        .build()
                }
            }
        }

        private fun handleResumablePut(request: RecordedRequest): MockResponse {
            val contentRange = request.headers["Content-Range"].orEmpty()
            // Offset probe: Content-Range: bytes */<total>
            if (contentRange.startsWith("bytes */")) {
                offsetProbeCount.incrementAndGet()
                val received = synchronized(lock) { uploadedLen }
                return if (received == 0) {
                    MockResponse.Builder().code(308).build()
                } else {
                    MockResponse.Builder()
                        .code(308)
                        .addHeader("Range", "bytes=0-${received - 1}")
                        .build()
                }
            }

            val match = Regex("""bytes (\d+)-(\d+)/(\d+)""").matchEntire(contentRange)
                ?: return MockResponse.Builder()
                    .code(400)
                    .body("""{"error":{"code":400,"message":"Bad Content-Range"}}""")
                    .build()
            val start = match.groupValues[1].toInt()
            val end = match.groupValues[2].toInt()
            val total = match.groupValues[3].toInt()

            if (start == 0) {
                chunk1UploadCount.incrementAndGet()
            } else if (failChunk2Once.compareAndSet(true, false)) {
                return MockResponse.Builder()
                    .code(503)
                    .body("""{"error":{"code":503,"message":"Simulated mid-upload interruption"}}""")
                    .build()
            }

            val chunkBytes = request.body?.toByteArray() ?: ByteArray(0)
            val expectedChunkLen = end - start + 1
            if (chunkBytes.size != expectedChunkLen) {
                return MockResponse.Builder()
                    .code(400)
                    .body("""{"error":{"code":400,"message":"Chunk length mismatch"}}""")
                    .build()
            }
            val newLen = synchronized(lock) {
                System.arraycopy(chunkBytes, 0, uploadedBuffer, start, chunkBytes.size)
                uploadedLen = maxOf(uploadedLen, end + 1)
                uploadedLen
            }
            return if (newLen < total) {
                MockResponse.Builder()
                    .code(308)
                    .addHeader("Range", "bytes=0-${newLen - 1}")
                    .build()
            } else {
                val fileJson = JSONObject()
                    .put("id", uploadedFileId)
                    .put("name", uploadedName)
                    .put("mimeType", DriveEpubTransferManager.EPUB_MIME_TYPE)
                    .put("size", total.toString())
                    .put("trashed", false)
                    .put("appProperties", uploadedAppProps)
                    .put("createdTime", "2026-04-01T00:00:00.000Z")
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "application/json")
                    .body(fileJson.toString())
                    .build()
            }
        }

        private fun handleResumableDownload(request: RecordedRequest): MockResponse {
            val allBytes = assembledUploadBytes()
            val rangeHeader = request.headers["Range"]
            lastDownloadRangeHeader = rangeHeader

            val truncateAt = truncateFirstDownloadAtBytes.getAndSet(0)
            if (rangeHeader == null && truncateAt > 0) {
                // Send partial bytes while declaring full Content-Length and closing socket prematurely.
                val partial = Buffer().write(allBytes, 0, truncateAt)
                return MockResponse.Builder()
                    .code(200)
                    .body(partial)
                    .setHeader("Content-Length", allBytes.size.toString())
                    .onResponseEnd(SocketEffect.CloseSocket())
                    .build()
            }

            if (rangeHeader != null) {
                val match = Regex("""bytes=(\d+)-""").matchEntire(rangeHeader)
                    ?: return MockResponse.Builder().code(416).build()
                val start = match.groupValues[1].toInt()
                val remaining = allBytes.size - start
                val body = Buffer().write(allBytes, start, remaining)
                return MockResponse.Builder()
                    .code(206)
                    .addHeader("Content-Range", "bytes $start-${allBytes.size - 1}/${allBytes.size}")
                    .body(body)
                    .build()
            }

            return MockResponse.Builder()
                .code(200)
                .body(Buffer().write(allBytes))
                .build()
        }
    }

    /** In-memory fake Drive server with per-device HTTP request accounting & Drive-side file mutations. */
    private class InstrumentedFakeDriveServer(
        private val permissionId: String = PERM_A,
        private val email: String = EMAIL_A,
    ) {
        private data class StoredFile(
            val meta: DriveRemoteFile,
            val bytes: ByteArray,
            val space: String,
        )

        private val idGen = AtomicInteger(1)
        private val files = linkedMapOf<String, StoredFile>()
        private val changeLog = mutableListOf<DriveChange>()
        private val httpCallsByDevice = ConcurrentHashMap<String, AtomicInteger>()
        val writeCallCount = AtomicInteger(0)
        val listAppDataFilesCalls = AtomicInteger(0)
        val totalDeltaUploads = AtomicInteger(0)

        fun resetHttpCounts(deviceId: String) {
            httpCallsByDevice[deviceId] = AtomicInteger(0)
        }

        fun httpCountFor(deviceId: String): Int = httpCallsByDevice[deviceId]?.get() ?: 0

        fun liveAppDataFileCount(): Int =
            files.values.count { it.space == DriveRestClient.SPACE_APPDATA && !it.meta.trashed }

        fun liveAppDataTotalBytes(): Long =
            files.values
                .filter { it.space == DriveRestClient.SPACE_APPDATA && !it.meta.trashed }
                .sumOf { it.bytes.size.toLong() }

        fun liseurFolderCount(): Int =
            files.values.count {
                it.space == DriveRestClient.SPACE_DRIVE &&
                    !it.meta.trashed &&
                    it.meta.mimeType == DriveRestClient.FOLDER_MIME_TYPE
            }

        fun registerExternalDriveFile(meta: DriveRemoteFile) {
            files[meta.id] = StoredFile(meta, ByteArray(0), DriveRestClient.SPACE_DRIVE)
            changeLog.add(DriveChange(meta.id, removed = false, file = meta))
        }

        fun renameFileOnDrive(fileId: String, newName: String) {
            val existing = requireNotNull(files[fileId]) { "Unknown Drive file $fileId" }
            val renamed = existing.meta.copy(name = newName)
            files[fileId] = existing.copy(meta = renamed)
            changeLog.add(DriveChange(fileId, removed = false, file = renamed))
        }

        fun trashFileOnDrive(fileId: String) {
            val existing = requireNotNull(files[fileId]) { "Unknown Drive file $fileId" }
            val trashed = existing.meta.copy(trashed = true)
            files[fileId] = existing.copy(meta = trashed)
            changeLog.add(DriveChange(fileId, removed = false, file = trashed))
        }

        fun permanentlyDeleteFileOnDrive(fileId: String) {
            files.remove(fileId)
            changeLog.add(DriveChange(fileId, removed = true, file = null))
        }

        private fun countHttp(deviceId: String) {
            httpCallsByDevice.computeIfAbsent(deviceId) { AtomicInteger(0) }.incrementAndGet()
        }

        fun clientFor(callerDeviceId: String): DriveRemoteApi = object : DriveRemoteApi {
            override suspend fun getAbout(accessToken: String): DriveUserAbout {
                countHttp(callerDeviceId)
                return DriveUserAbout(permissionId = permissionId, emailAddress = email)
            }

            override suspend fun listLiseurFolders(accessToken: String): List<DriveRemoteFile> {
                countHttp(callerDeviceId)
                return files.values
                    .filter {
                        it.space == DriveRestClient.SPACE_DRIVE &&
                            !it.meta.trashed &&
                            it.meta.mimeType == DriveRestClient.FOLDER_MIME_TYPE &&
                            it.meta.name == DriveRestClient.LISEUR_FOLDER_NAME
                    }
                    .map { it.meta }
                    .sortedWith(compareBy({ it.createdTime ?: "" }, { it.id }))
            }

            override suspend fun ensureLiseurFolder(accessToken: String): LiseurFolderResolution {
                val existing = listLiseurFolders(accessToken)
                if (existing.isNotEmpty()) return LiseurFolderResolution(existing.first(), existing)
                countHttp(callerDeviceId)
                writeCallCount.incrementAndGet()
                val id = "folder-${idGen.getAndIncrement()}"
                val meta = DriveRemoteFile(
                    id = id,
                    name = DriveRestClient.LISEUR_FOLDER_NAME,
                    mimeType = DriveRestClient.FOLDER_MIME_TYPE,
                    appProperties = mapOf("liseur" to "1", "kind" to DriveFile.KIND_FOLDER, "schemaVersion" to "1"),
                    createdTime = "2026-04-01T00:00:00.000Z",
                )
                files[id] = StoredFile(meta, ByteArray(0), DriveRestClient.SPACE_DRIVE)
                changeLog.add(DriveChange(id, false, meta))
                return LiseurFolderResolution(meta, listOf(meta))
            }

            override suspend fun listDriveEpubFiles(accessToken: String): List<DriveRemoteFile> {
                countHttp(callerDeviceId)
                return files.values
                    .filter {
                        it.space == DriveRestClient.SPACE_DRIVE &&
                            !it.meta.trashed &&
                            it.meta.appProperties["liseurKind"] == DriveFile.KIND_EPUB
                    }
                    .map { it.meta }
            }

            override suspend fun getStartPageToken(accessToken: String): String {
                countHttp(callerDeviceId)
                return changeLog.size.toString()
            }

            override suspend fun listChanges(
                accessToken: String,
                pageToken: String,
                pageSize: Int,
            ): DriveChangesPage {
                countHttp(callerDeviceId)
                val idx = pageToken.toIntOrNull()
                    ?: throw DriveInvalidPageTokenException("invalid token", 400)
                val visible = if (idx < changeLog.size) changeLog.subList(idx, changeLog.size).toList() else emptyList()
                return DriveChangesPage(
                    changes = visible,
                    nextPageToken = null,
                    newStartPageToken = changeLog.size.toString(),
                )
            }

            override suspend fun listAppDataFiles(
                accessToken: String,
                query: String?,
                pageSize: Int,
            ): List<DriveRemoteFile> {
                countHttp(callerDeviceId)
                listAppDataFilesCalls.incrementAndGet()
                return files.values
                    .filter { it.space == DriveRestClient.SPACE_APPDATA && !it.meta.trashed }
                    .map { it.meta }
            }

            override suspend fun findAppDataFileByBatchId(
                accessToken: String,
                batchId: String,
            ): DriveRemoteFile? {
                countHttp(callerDeviceId)
                return files.values.firstOrNull {
                    it.space == DriveRestClient.SPACE_APPDATA &&
                        !it.meta.trashed &&
                        it.meta.appProperties["batchId"] == batchId
                }?.meta
            }

            override suspend fun downloadMedia(accessToken: String, fileId: String): ByteArray {
                countHttp(callerDeviceId)
                val stored = files[fileId] ?: throw DriveNotFoundException("Missing $fileId", fileId)
                if (stored.meta.trashed) throw DriveNotFoundException("Trashed $fileId", fileId)
                return stored.bytes.copyOf()
            }

            override suspend fun uploadAppDataJsonBatch(
                accessToken: String,
                name: String,
                jsonPayload: ByteArray,
                appProperties: Map<String, String>,
            ): DriveRemoteFile {
                val batchId = appProperties["batchId"]
                if (batchId != null) {
                    findAppDataFileByBatchId(accessToken, batchId)?.let { return it }
                }
                if (appProperties["kind"] == DriveFile.KIND_DELTA) {
                    totalDeltaUploads.incrementAndGet()
                }
                return createAppDataFile(accessToken, name, "application/json", jsonPayload, appProperties)
            }

            override suspend fun createAppDataFile(
                accessToken: String,
                name: String,
                mimeType: String,
                content: ByteArray,
                appProperties: Map<String, String>,
            ): DriveRemoteFile {
                countHttp(callerDeviceId)
                writeCallCount.incrementAndGet()
                val id = "appdata-${idGen.getAndIncrement()}"
                val meta = DriveRemoteFile(
                    id = id,
                    name = name,
                    mimeType = mimeType,
                    parents = listOf(DriveRestClient.SPACE_APPDATA),
                    appProperties = appProperties,
                    size = content.size.toLong(),
                )
                files[id] = StoredFile(meta, content.copyOf(), DriveRestClient.SPACE_APPDATA)
                changeLog.add(DriveChange(id, false, meta))
                return meta
            }

            override suspend fun updateFileMedia(
                accessToken: String,
                fileId: String,
                mimeType: String,
                content: ByteArray,
                appProperties: Map<String, String>?,
            ): DriveRemoteFile {
                countHttp(callerDeviceId)
                writeCallCount.incrementAndGet()
                val existing = files[fileId] ?: throw DriveNotFoundException("Missing $fileId", fileId)
                val effectiveProps = appProperties ?: existing.meta.appProperties
                val updatedMeta = existing.meta.copy(
                    mimeType = mimeType,
                    appProperties = effectiveProps,
                    size = content.size.toLong(),
                )
                files[fileId] = existing.copy(meta = updatedMeta, bytes = content.copyOf())
                changeLog.add(DriveChange(fileId, false, updatedMeta))
                return updatedMeta
            }

            override suspend fun deleteFile(accessToken: String, fileId: String) {
                countHttp(callerDeviceId)
                writeCallCount.incrementAndGet()
                files.remove(fileId) ?: throw DriveNotFoundException("Missing $fileId", fileId)
                changeLog.add(DriveChange(fileId, removed = true, file = null))
            }

            override suspend fun trashFile(accessToken: String, fileId: String): DriveRemoteFile {
                countHttp(callerDeviceId)
                writeCallCount.incrementAndGet()
                val existing = files[fileId] ?: throw DriveNotFoundException("Missing $fileId", fileId)
                val trashed = existing.meta.copy(trashed = true)
                files[fileId] = existing.copy(meta = trashed)
                changeLog.add(DriveChange(fileId, removed = false, file = trashed))
                return trashed
            }

            override suspend fun initiateResumableUpload(
                accessToken: String,
                name: String,
                mimeType: String,
                totalBytes: Long,
                parents: List<String>,
                appProperties: Map<String, String>,
            ): String = throw UnsupportedOperationException()

            override suspend fun queryResumableUploadOffset(
                accessToken: String,
                sessionUri: String,
                totalBytes: Long,
            ): ResumableUploadStatus = throw UnsupportedOperationException()

            override suspend fun uploadResumableChunk(
                accessToken: String,
                sessionUri: String,
                chunk: ByteArray,
                offset: Long,
                totalBytes: Long,
                mimeType: String,
            ): ResumableUploadStatus = throw UnsupportedOperationException()

            override suspend fun uploadResumableFile(
                accessToken: String,
                name: String,
                mimeType: String,
                totalBytes: Long,
                parents: List<String>,
                appProperties: Map<String, String>,
                existingSessionUri: String?,
                chunkSizeBytes: Int,
                openStream: () -> InputStream,
                onSessionUri: suspend (String) -> Unit,
                onProgress: suspend (Long, String) -> Unit,
            ): DriveRemoteFile {
                countHttp(callerDeviceId)
                writeCallCount.incrementAndGet()
                val bytes = openStream().use { it.readBytes() }
                val isAppData = DriveRestClient.SPACE_APPDATA in parents
                val space = if (isAppData) DriveRestClient.SPACE_APPDATA else DriveRestClient.SPACE_DRIVE
                val prefix = if (isAppData) "snap" else "epub"
                val id = "$prefix-${idGen.getAndIncrement()}"
                val meta = DriveRemoteFile(
                    id = id,
                    name = name,
                    mimeType = mimeType,
                    parents = parents,
                    appProperties = appProperties,
                    size = bytes.size.toLong(),
                )
                files[id] = StoredFile(meta, bytes, space)
                changeLog.add(DriveChange(id, false, meta))
                onProgress(bytes.size.toLong(), "fake-session-$id")
                return meta
            }

            override suspend fun downloadResumable(
                accessToken: String,
                fileId: String,
                destination: OutputStream,
                startOffset: Long,
                onRestartFromZero: () -> Unit,
                onProgress: (Long) -> Unit,
            ): DriveDownloadResult {
                countHttp(callerDeviceId)
                val stored = files[fileId] ?: throw DriveNotFoundException("Missing $fileId", fileId)
                if (stored.meta.trashed) throw DriveNotFoundException("Trashed $fileId", fileId)
                val slice = stored.bytes.copyOfRange(startOffset.toInt(), stored.bytes.size)
                destination.write(slice)
                onProgress(stored.bytes.size.toLong())
                return DriveDownloadResult(
                    bytesWritten = slice.size.toLong(),
                    totalBytes = stored.bytes.size.toLong(),
                    restartedFromZero = false,
                )
            }
        }
    }

    companion object {
        private const val PERM_A = "perm-account-a"
        private const val EMAIL_A = "alice@example.com"
        private const val TOKEN_A = "tok-account-a"

        private const val PERM_B = "perm-account-b"
        private const val EMAIL_B = "bob@example.com"
        private const val TOKEN_B = "tok-account-b"
    }
}
