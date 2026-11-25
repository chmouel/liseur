package com.chmouel.liseur.drive

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class DriveRestClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: DriveRestClient
    private val sleepDelays = mutableListOf<Long>()
    private var nowMs: Long = 1_750_000_000_000L

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        sleepDelays.clear()
        val base = server.url("/").toString().trimEnd('/')
        client = DriveRestClient(
            apiBaseUrl = "$base/drive/v3",
            uploadBaseUrl = "$base/upload/drive/v3",
            maxRetries = 3,
            baseBackoffMs = 100L,
            maxBackoffMs = 1_000L,
            clock = { nowMs },
            sleep = { sleepDelays += it },
            jitterFactor = { 1.0 },
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `getAbout requests user permissionId and emailAddress with bearer auth`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"user":{"permissionId":"perm-abc-123","emailAddress":"reader@example.com"}}""")
                .build(),
        )

        val about = client.getAbout("tok-xyz")
        assertEquals(DriveUserAbout("perm-abc-123", "reader@example.com"), about)

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("Bearer tok-xyz", req.headers["Authorization"])
        assertEquals("/drive/v3/about", req.url.encodedPath)
        assertEquals("user(permissionId,emailAddress)", req.url.queryParameter("fields"))
    }

    @Test
    fun `ensureLiseurFolder creates folder when absent and resolves concurrent duplicate race by oldest createdTime then id`() = runTest {
        // 1) Initial list returns empty.
        server.enqueue(MockResponse.Builder().code(200).body("""{"files":[]}""").build())
        // 2) Create returns our newly created folder.
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """{
                    "id":"folder-device-b",
                    "name":"Liseur",
                    "mimeType":"application/vnd.google-apps.folder",
                    "createdTime":"2026-04-01T12:00:05.000Z"
                }""",
                )
                .build(),
        )
        // 3) Re-list discovers both our folder and an older folder created concurrently by Device A,
        // plus a third tie-breaker folder at the same timestamp as A.
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """{"files":[
                    {"id":"folder-device-b","name":"Liseur","mimeType":"application/vnd.google-apps.folder","createdTime":"2026-04-01T12:00:05.000Z"},
                    {"id":"folder-device-c","name":"Liseur","mimeType":"application/vnd.google-apps.folder","createdTime":"2026-04-01T12:00:01.000Z"},
                    {"id":"folder-device-a","name":"Liseur","mimeType":"application/vnd.google-apps.folder","createdTime":"2026-04-01T12:00:01.000Z"}
                ]}""",
                )
                .build(),
        )

        val resolution = client.ensureLiseurFolder("tok")
        assertEquals("folder-device-a", resolution.canonical.id)
        assertEquals(
            listOf("folder-device-a", "folder-device-c", "folder-device-b"),
            resolution.allFolderIds,
        )

        val listReq1 = server.takeRequest()
        assertEquals("drive", listReq1.url.queryParameter("spaces"))
        val createReq = server.takeRequest()
        assertEquals("POST", createReq.method)
        assertTrue(createReq.body!!.utf8().contains("application/vnd.google-apps.folder"))
        val listReq2 = server.takeRequest()
        assertEquals("GET", listReq2.method)
    }

    @Test
    fun `getStartPageToken sends NO spaces query parameter`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"startPageToken":"4299"}""").build())

        val token = client.getStartPageToken("tok")
        assertEquals("4299", token)

        val req = server.takeRequest()
        assertEquals("/drive/v3/changes/startPageToken", req.url.encodedPath)
        assertNull(req.url.queryParameter("spaces"))
        assertEquals(0, req.url.querySize)
    }

    @Test
    fun `listChanges queries both appDataFolder and drive spaces with includeRemoved and parses changes`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """{
                    "newStartPageToken":"4305",
                    "changes":[
                        {
                            "fileId":"f-1",
                            "removed":false,
                            "file":{
                                "id":"f-1",
                                "name":"device-dev1-seq-0000000001.json",
                                "mimeType":"application/json",
                                "parents":["appDataFolder"],
                                "trashed":false,
                                "size":"512",
                                "md5Checksum":"abc123",
                                "createdTime":"2026-04-01T10:00:00.000Z",
                                "appProperties":{"liseur":"1","kind":"delta","batchId":"b-1","seq":"1"}
                            }
                        },
                        {"fileId":"f-2","removed":true}
                    ]
                }""",
                )
                .build(),
        )

        val page = client.listChanges("tok", pageToken = "4299")
        assertNull(page.nextPageToken)
        assertEquals("4305", page.newStartPageToken)
        assertEquals(2, page.changes.size)
        assertEquals("f-1", page.changes[0].fileId)
        assertFalse(page.changes[0].removed)
        assertEquals("delta", page.changes[0].file?.appProperties?.get("kind"))
        assertEquals(512L, page.changes[0].file?.size)
        assertEquals("f-2", page.changes[1].fileId)
        assertTrue(page.changes[1].removed)
        assertNull(page.changes[1].file)

        val req = server.takeRequest()
        assertEquals("/drive/v3/changes", req.url.encodedPath)
        assertEquals("4299", req.url.queryParameter("pageToken"))
        assertEquals("appDataFolder,drive", req.url.queryParameter("spaces"))
        assertEquals("true", req.url.queryParameter("includeRemoved"))
        assertNotNull(req.url.queryParameter("fields"))
    }

    @Test
    fun `listChanges throws DriveInvalidPageTokenException on 400 and 404`() = runTest {
        server.enqueue(MockResponse.Builder().code(400).body("""{"error":{"message":"Invalid pageToken"}}""").build())
        try {
            client.listChanges("tok", pageToken = "bad")
            fail("Expected DriveInvalidPageTokenException")
        } catch (e: DriveInvalidPageTokenException) {
            assertEquals(400, e.httpCode)
        }

        server.enqueue(MockResponse.Builder().code(404).body("""{"error":{"message":"Start page token expired"}}""").build())
        try {
            client.listChanges("tok", pageToken = "expired")
            fail("Expected DriveInvalidPageTokenException")
        } catch (e: DriveInvalidPageTokenException) {
            assertEquals(404, e.httpCode)
        }
    }

    @Test
    fun `uploadAppDataJsonBatch deduplicates by batchId before create and on retry after transient failure`() = runTest {
        val props = mapOf(
            "liseur" to "1",
            "kind" to "delta",
            "schemaVersion" to "1",
            "deviceId" to "dev-1",
            "seq" to "1",
            "batchId" to "batch-uuid-1",
        )

        // Scenario 1: File already exists on pre-check -> no POST is issued.
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"files":[{"id":"existing-id","name":"device-dev-1-seq-0000000001.json","appProperties":{"batchId":"batch-uuid-1"}}]}""")
                .build(),
        )
        val existing = client.uploadAppDataJsonBatch(
            accessToken = "tok",
            name = "device-dev-1-seq-0000000001.json",
            jsonPayload = """{"ok":true}""".toByteArray(),
            appProperties = props,
        )
        assertEquals("existing-id", existing.id)
        assertEquals(1, server.requestCount)
        val findReq = server.takeRequest()
        assertEquals("appDataFolder", findReq.url.queryParameter("spaces"))
        assertTrue(findReq.url.queryParameter("q")!!.contains("batch-uuid-1"))

        // Scenario 2: Pre-check empty, POST hits transient 503 (even though Drive persisted it),
        // retry pre-check finds the persisted file -> no duplicate POST.
        server.enqueue(MockResponse.Builder().code(200).body("""{"files":[]}""").build())
        server.enqueue(MockResponse.Builder().code(503).body("""{"error":{"message":"Backend error"}}""").build())
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"files":[{"id":"committed-id","name":"device-dev-1-seq-0000000001.json","appProperties":{"batchId":"batch-uuid-1"}}]}""")
                .build(),
        )
        val recovered = client.uploadAppDataJsonBatch(
            accessToken = "tok",
            name = "device-dev-1-seq-0000000001.json",
            jsonPayload = """{"ok":true}""".toByteArray(),
            appProperties = props,
        )
        assertEquals("committed-id", recovered.id)
        assertEquals(4, server.requestCount) // 1 from scenario 1 + (list, POST 503, list)
    }

    @Test
    fun `uploadAppDataJsonBatch without a batchId does not repost after an ambiguous 503`() = runTest {
        server.enqueue(MockResponse.Builder().code(503).body("""{"error":{"message":"Backend error"}}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"id":"duplicate"}""").build())

        val failure = runCatching {
            client.uploadAppDataJsonBatch(
                accessToken = "tok",
                name = "device-dev-1-seq-0000000001.json",
                jsonPayload = """{"ok":true}""".toByteArray(),
                appProperties = mapOf("batchId" to "  "),
            )
        }.exceptionOrNull()

        assertTrue(failure is DriveApiException)
        assertEquals(1, server.requestCount)
        assertTrue(sleepDelays.isEmpty())
    }

    @Test
    fun `uploadAppDataJsonBatch without a batchId still retries a rate limited post`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(429)
                .addHeader("Retry-After", "5")
                .body("""{"error":{"message":"Rate limit exceeded"}}""")
                .build(),
        )
        server.enqueue(MockResponse.Builder().code(200).body("""{"id":"created-id"}""").build())

        val uploaded = client.uploadAppDataJsonBatch(
            accessToken = "tok",
            name = "device-dev-1-seq-0000000001.json",
            jsonPayload = """{"ok":true}""".toByteArray(),
            appProperties = mapOf("liseur" to "1"),
        )

        assertEquals("created-id", uploaded.id)
        assertEquals(2, server.requestCount)
        assertEquals(listOf(5_000L), sleepDelays)
    }

    @Test
    fun `resumable upload reads sequential chunks from one stream and reopens when retrying a chunk`() = runTest {
        val payload = ByteArray(12) { (it + 1).toByte() }
        val sessionUrl = server.url("/upload/session/reuse").toString()
        var opens = 0
        val openStream = { opens++; ByteArrayInputStream(payload) }
        suspend fun upload() = client.uploadResumableFile(
            accessToken = "tok",
            name = "book.epub",
            mimeType = "application/epub+zip",
            totalBytes = payload.size.toLong(),
            parents = listOf("folder-1"),
            chunkSizeBytes = 4,
            openStream = openStream,
        )

        server.enqueue(MockResponse.Builder().code(200).addHeader("Location", sessionUrl).build())
        server.enqueue(MockResponse.Builder().code(308).addHeader("Range", "bytes=0-3").build())
        server.enqueue(MockResponse.Builder().code(308).addHeader("Range", "bytes=0-7").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"id":"seq-id","name":"book.epub"}""").build())
        assertEquals("seq-id", upload().id)
        assertEquals(1, opens)
        repeat(4) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }

        opens = 0
        server.enqueue(MockResponse.Builder().code(200).addHeader("Location", sessionUrl).build())
        server.enqueue(MockResponse.Builder().code(308).addHeader("Range", "bytes=0-3").build())
        server.enqueue(MockResponse.Builder().code(503).body("""{"error":{"message":"Backend error"}}""").build())
        server.enqueue(MockResponse.Builder().code(308).addHeader("Range", "bytes=0-3").build())
        server.enqueue(MockResponse.Builder().code(308).addHeader("Range", "bytes=0-7").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"id":"retry-id","name":"book.epub"}""").build())
        assertEquals("retry-id", upload().id)
        assertEquals(2, opens)
        val bodies = (1..6).map { server.takeRequest(5, TimeUnit.SECONDS)?.body?.toByteArray()?.toList() }
        assertEquals(payload.sliceArray(4..7).toList(), bodies[2])
        assertEquals(payload.sliceArray(4..7).toList(), bodies[4])
        assertEquals(payload.sliceArray(8..11).toList(), bodies[5])
    }

    private class TrackedStream(
        payload: ByteArray,
        private val failRead: Boolean = false,
        private val failClose: Boolean = false,
    ) : ByteArrayInputStream(payload) {
        var closes = 0
        var closeThread: String? = null

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (failRead) throw java.io.IOException("read failed")
            return super.read(b, off, len)
        }

        override fun close() {
            closes++
            closeThread = Thread.currentThread().name
            if (failClose) throw java.io.IOException("close failed")
        }
    }

    private fun enqueueSession(path: String): Unit = server.enqueue(
        MockResponse.Builder().code(200).addHeader("Location", server.url(path).toString()).build(),
    )

    private suspend fun uploadTracked(payload: ByteArray, stream: () -> TrackedStream) = client.uploadResumableFile(
        accessToken = "tok",
        name = "book.epub",
        mimeType = "application/epub+zip",
        totalBytes = payload.size.toLong(),
        parents = listOf("folder-1"),
        chunkSizeBytes = 4,
        openStream = stream,
    )

    @Test
    fun `resumable upload closes its stream on success even when close throws`() = runTest {
        val payload = ByteArray(8) { it.toByte() }
        val streams = mutableListOf<TrackedStream>()
        enqueueSession("/upload/session/close-ok")
        server.enqueue(MockResponse.Builder().code(308).addHeader("Range", "bytes=0-3").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"id":"done","name":"book.epub"}""").build())

        val file = uploadTracked(payload) { TrackedStream(payload, failClose = true).also(streams::add) }

        assertEquals("done", file.id)
        assertEquals(1, streams.size)
        assertEquals(1, streams[0].closes)
    }

    @Test
    fun `resumable upload closes its stream on failure and keeps the primary error`() = runTest {
        val payload = ByteArray(8) { it.toByte() }
        val streams = mutableListOf<TrackedStream>()
        enqueueSession("/upload/session/close-fail")
        server.enqueue(MockResponse.Builder().code(400).body("""{"error":{"message":"bad"}}""").build())

        val error = try {
            uploadTracked(payload) { TrackedStream(payload, failClose = true).also(streams::add) }
            null
        } catch (e: DriveApiException) {
            e
        }

        assertNotNull(error)
        assertTrue(error!!.suppressed.any { it.message == "close failed" })
        assertEquals(1, streams.size)
        assertTrue(streams[0].closes >= 1)
        assertTrue(streams[0].closeThread, streams[0].closeThread!!.contains("DefaultDispatcher"))
    }

    @Test
    fun `resumable upload read failure is not masked by a throwing close`() = runTest {
        val payload = ByteArray(8) { it.toByte() }
        val streams = mutableListOf<TrackedStream>()
        enqueueSession("/upload/session/read-fail")

        val error = try {
            uploadTracked(payload) { TrackedStream(payload, failRead = true, failClose = true).also(streams::add) }
            null
        } catch (e: java.io.IOException) {
            e
        }

        assertEquals("read failed", error?.message)
        // Debug stack-trace recovery may hand back a copy whose cause is the original.
        val chain = generateSequence<Throwable>(error) { it.cause }
        assertTrue(chain.any { t -> t.suppressed.any { it.message == "close failed" } })
        assertEquals(1, streams.size)
        assertEquals(1, streams[0].closes)
    }

    @Test
    fun `createAppDataFile never retries a 503 so an ambiguous create stays with the caller`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(503)
                .body("""{"error":{"message":"Backend error","errors":[{"reason":"backendError"}]}}""")
                .build(),
        )

        val failure = runCatching {
            client.createAppDataFile(
                accessToken = "tok",
                name = "device-dev-1-seq-0000000001.json",
                mimeType = "application/json",
                content = """{"ok":true}""".toByteArray(),
                appProperties = mapOf("batchId" to "batch-uuid-1"),
            )
        }.exceptionOrNull()

        assertTrue(failure is DriveHttpException)
        assertEquals(503, (failure as DriveHttpException).httpCode)
        assertEquals(1, server.requestCount)
        assertTrue(sleepDelays.isEmpty())
    }

    @Test
    fun `createAppDataFile sends one POST when a 503 carries Retry-After zero`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(503)
                .addHeader("Retry-After", "0")
                .body("""{"error":{"message":"Backend error","errors":[{"reason":"backendError"}]}}""")
                .build(),
        )
        server.enqueue(MockResponse.Builder().code(200).body("""{"id":"dup","name":"f.json"}""").build())

        val failure = runCatching {
            client.createAppDataFile(
                accessToken = "tok",
                name = "f.json",
                mimeType = "application/json",
                content = """{"ok":true}""".toByteArray(),
                appProperties = mapOf("batchId" to "batch-uuid-1"),
            )
        }.exceptionOrNull()

        assertTrue(failure is DriveHttpException)
        assertEquals(503, (failure as DriveHttpException).httpCode)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `folder create sends one POST when a 503 carries Retry-After zero`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"files":[]}""").build())
        server.enqueue(
            MockResponse.Builder()
                .code(503)
                .addHeader("Retry-After", "0")
                .body("""{"error":{"message":"Backend error"}}""")
                .build(),
        )
        server.enqueue(MockResponse.Builder().code(200).body("""{"id":"dup","name":"Liseur"}""").build())

        val failure = runCatching { client.ensureLiseurFolder("tok") }.exceptionOrNull()

        assertTrue(failure is DriveHttpException)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `resumable chunk fatal quota reasons fail at once instead of probing`() = runTest {
        for (reason in listOf("quotaExceeded", "dailyLimitExceeded")) {
            val before = server.requestCount
            val sessionUrl = server.url("/upload/session/$reason").toString()
            server.enqueue(MockResponse.Builder().code(200).addHeader("Location", sessionUrl).build())
            server.enqueue(
                MockResponse.Builder()
                    .code(403)
                    .body("""{"error":{"message":"quota","errors":[{"reason":"$reason"}]}}""")
                    .build(),
            )

            val failure = runCatching {
                client.uploadResumableFile(
                    accessToken = "tok",
                    name = "book.epub",
                    mimeType = "application/epub+zip",
                    totalBytes = 8L,
                    parents = listOf("folder-1"),
                    chunkSizeBytes = 4,
                    openStream = { ByteArrayInputStream(ByteArray(8)) },
                )
            }.exceptionOrNull()

            assertTrue(failure is DriveQuotaException)
            assertEquals(2, server.requestCount - before)
            assertTrue(sleepDelays.isEmpty())
        }
    }

    @Test
    fun `resumable chunk rate limit 429 still sleeps and probes before continuing`() = runTest {
        val sessionUrl = server.url("/upload/session/rate").toString()
        server.enqueue(MockResponse.Builder().code(200).addHeader("Location", sessionUrl).build())
        server.enqueue(MockResponse.Builder().code(429).body("""{"error":{"message":"slow down"}}""").build())
        server.enqueue(MockResponse.Builder().code(308).addHeader("Range", "bytes=0-3").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"id":"done","name":"book.epub"}""").build())

        val uploaded = client.uploadResumableFile(
            accessToken = "tok",
            name = "book.epub",
            mimeType = "application/epub+zip",
            totalBytes = 8L,
            parents = listOf("folder-1"),
            chunkSizeBytes = 4,
            openStream = { ByteArrayInputStream(ByteArray(8)) },
        )

        assertEquals("done", uploaded.id)
        assertEquals(1, sleepDelays.size)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `cancelling a download while the request is in flight closes the response`() {
        val seen = mutableListOf<okhttp3.Response>()
        val spying = okhttp3.OkHttpClient.Builder()
            .addNetworkInterceptor { chain -> chain.proceed(chain.request()).also { seen += it } }
            .build()
        val base = server.url("/").toString().trimEnd('/')
        val spied = DriveRestClient(
            apiBaseUrl = "$base/drive/v3",
            uploadBaseUrl = "$base/upload/drive/v3",
            client = spying,
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("0123456789")
                .headersDelay(500, TimeUnit.MILLISECONDS)
                .build(),
        )

        kotlinx.coroutines.runBlocking {
            val job = launch(kotlinx.coroutines.Dispatchers.Default) {
                spied.downloadResumable("tok", "f", ByteArrayOutputStream(), startOffset = 0L, onRestartFromZero = {})
            }
            server.takeRequest()
            job.cancelAndJoin()
        }

        assertEquals(1, seen.size)
        val failure = runCatching { seen.single().body.source().readByte() }.exceptionOrNull()
        assertTrue("response body must be closed, got $failure", failure is IllegalStateException)
    }

    @Test
    fun `cancelling a download after body copying starts stops the copy before EOF`() {
        val bodySize = 64 * 1024
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(okio.Buffer().write(ByteArray(bodySize)))
                .throttleBody(1024, 100, TimeUnit.MILLISECONDS)
                .build(),
        )
        val progress = mutableListOf<Long>()
        val copyingStarted = CountDownLatch(1)

        kotlinx.coroutines.runBlocking {
            val job = launch(kotlinx.coroutines.Dispatchers.Default) {
                client.downloadResumable(
                    accessToken = "tok",
                    fileId = "f-slow",
                    destination = ByteArrayOutputStream(),
                    startOffset = 0L,
                    onRestartFromZero = {},
                    onProgress = {
                        progress += it
                        copyingStarted.countDown()
                    },
                )
            }
            assertTrue("body copying must begin", copyingStarted.await(5, TimeUnit.SECONDS))
            assertTrue(job.isActive)
            val joined = withTimeoutOrNull(5_000) { job.cancelAndJoin() }
            assertNotNull(
                "copy must stop promptly on cancellation, at ${progress.lastOrNull()} of $bodySize bytes",
                joined,
            )
            assertTrue("a cancelled download must not complete successfully", job.isCancelled)
        }

        val copied = progress.lastOrNull() ?: 0L
        val reportsAtCancel = progress.size
        Thread.sleep(200)
        assertTrue("copy must stop before EOF, copied $copied of $bodySize bytes", copied < bodySize)
        assertEquals("no progress may be reported after cancellation", reportsAtCancel, progress.size)
    }

    @Test
    fun `createAppDataFile still retries a 429 because nothing was committed`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(429)
                .body("""{"error":{"message":"Rate Limit Exceeded"}}""")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"id":"created-id","name":"file.json"}""")
                .build(),
        )

        val created = client.createAppDataFile(
            accessToken = "tok",
            name = "file.json",
            mimeType = "application/json",
            content = """{"ok":true}""".toByteArray(),
            appProperties = mapOf("batchId" to "batch-uuid-1"),
        )

        assertEquals("created-id", created.id)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `uploadAppDataJsonBatch rejects appProperties exceeding 124 UTF-8 bytes`() = runTest {
        try {
            client.uploadAppDataJsonBatch(
                accessToken = "tok",
                name = "batch.json",
                jsonPayload = "{}".toByteArray(),
                appProperties = mapOf("batchId" to "x".repeat(118)),
            )
            fail("Expected IllegalArgumentException for oversized appProperty")
        } catch (_: IllegalArgumentException) {
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `appProperties limit counts key and value bytes together`() {
        DriveRestClient.validateAppProperties(mapOf("batchId" to "x".repeat(117)))
        // "é" is two UTF-8 bytes: 7 + 118 = 125.
        val failure = runCatching {
            DriveRestClient.validateAppProperties(mapOf("batchId" to "é".repeat(59)))
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `restart from zero truncates a pre-populated file destination`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("0123456789").build())
        val file = java.io.File.createTempFile("drive-resume", ".part")
        try {
            file.writeText("01234")
            var restarted = false
            val result = java.io.FileOutputStream(file, true).use { out ->
                client.downloadResumable(
                    accessToken = "tok",
                    fileId = "f-epub",
                    destination = out,
                    startOffset = 5L,
                    onRestartFromZero = { restarted = true },
                )
            }
            assertTrue(restarted)
            assertTrue(result.restartedFromZero)
            assertEquals("0123456789", file.readText())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `oversized media bodies are refused, with or without Content-Length`() = runTest {
        val tooBig = okio.Buffer().write(ByteArray(DriveRestClient.MAX_BUFFERED_BODY_BYTES.toInt() + 1))
        server.enqueue(MockResponse.Builder().code(200).body(tooBig).build())
        val declared = runCatching { client.downloadMedia("tok", "f-big") }.exceptionOrNull()
        assertTrue(declared is DriveApiException)

        val chunked = okio.Buffer().write(ByteArray(DriveRestClient.MAX_BUFFERED_BODY_BYTES.toInt() + 1))
        server.enqueue(MockResponse.Builder().code(200).chunkedBody(chunked, 1024 * 1024).build())
        val streamed = runCatching { client.downloadMedia("tok", "f-big") }.exceptionOrNull()
        assertTrue(streamed is DriveApiException)
    }

    @Test
    fun `response bodies are read on the IO dispatcher, never on the caller thread`() = runTest {
        val readThreads = java.util.Collections.synchronizedList(mutableListOf<String>())
        val recording = DriveRestClient.defaultClient().newBuilder()
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val body = response.body
                val source = object : okio.ForwardingSource(body.source()) {
                    override fun read(sink: okio.Buffer, byteCount: Long): Long {
                        readThreads += Thread.currentThread().name
                        return super.read(sink, byteCount)
                    }
                }
                response.newBuilder()
                    .body(source.buffer().asResponseBody(body.contentType(), body.contentLength()))
                    .build()
            }
            .build()
        val base = server.url("/").toString().trimEnd('/')
        val recordingClient = DriveRestClient(
            apiBaseUrl = "$base/drive/v3",
            uploadBaseUrl = "$base/upload/drive/v3",
            client = recording,
            sleep = {},
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"user":{"permissionId":"perm-1","emailAddress":"a@example.com"}}""")
                .build(),
        )
        val caller = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "drive-caller") }
        try {
            val about = kotlinx.coroutines.withContext(caller.asCoroutineDispatcher()) {
                recordingClient.getAbout("tok")
            }
            assertEquals("perm-1", about.permissionId)
            assertTrue(readThreads.isNotEmpty())
            assertFalse(readThreads.any { it == "drive-caller" })
        } finally {
            caller.shutdown()
        }
    }

    @Test
    fun `listAppDataFiles paginates across nextPageToken and downloadMedia returns bytes`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"nextPageToken":"p2","files":[{"id":"f1","name":"a.json"}]}""")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"files":[{"id":"f2","name":"b.json"}]}""")
                .build(),
        )
        val files = client.listAppDataFiles("tok")
        assertEquals(listOf("f1", "f2"), files.map { it.id })

        server.enqueue(MockResponse.Builder().code(200).body("hello-world").build())
        val media = client.downloadMedia("tok", "f1")
        assertArrayEquals("hello-world".toByteArray(), media)
    }

    @Test
    fun `updateFileMedia, deleteFile, and trashFile hit expected Drive endpoints`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"id":"m1","name":"manifest.json"}""").build())
        val updated = client.updateFileMedia("tok", "m1", "application/json", """{"v":1}""".toByteArray())
        assertEquals("m1", updated.id)
        val patchReq = server.takeRequest()
        assertEquals("PATCH", patchReq.method)
        assertEquals("media", patchReq.url.queryParameter("uploadType"))

        server.enqueue(MockResponse.Builder().code(204).build())
        client.deleteFile("tok", "d1")
        val delReq = server.takeRequest()
        assertEquals("DELETE", delReq.method)
        assertEquals("/drive/v3/files/d1", delReq.url.encodedPath)

        server.enqueue(MockResponse.Builder().code(200).body("""{"id":"e1","name":"book.epub","trashed":true}""").build())
        val trashed = client.trashFile("tok", "e1")
        assertTrue(trashed.trashed)
        val trashReq = server.takeRequest()
        assertEquals("PATCH", trashReq.method)
        assertTrue(trashReq.body!!.utf8().contains("\"trashed\":true"))
    }

    @Test
    fun `listLiseurFolders only matches folders created by Liseur`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"files":[]}""").build())

        client.listLiseurFolders("tok")

        val query = server.takeRequest().url.queryParameter("q")!!
        assertTrue(query.contains("appProperties has { key='liseur' and value='1' }"))
        assertTrue(query.contains("appProperties has { key='kind' and value='folder' }"))
    }

    @Test
    fun `resumable upload resets retry budget on each advancing 503 probe recovery`() = runTest {
        val payload = ByteArray(10) { it.toByte() }
        val sessionUrl = server.url("/upload/session/abc").toString()
        server.enqueue(MockResponse.Builder().code(200).addHeader("Location", sessionUrl).build())
        for (end in listOf(1, 3, 5, 7)) {
            server.enqueue(MockResponse.Builder().code(503).body("retry").build())
            server.enqueue(MockResponse.Builder().code(308).addHeader("Range", "bytes=0-$end").build())
        }
        server.enqueue(
            MockResponse.Builder().code(200).body("""{"id":"epub-file-1","name":"book.epub","size":"10"}""").build(),
        )

        val uploaded = client.uploadResumableFile(
            accessToken = "tok",
            name = "book.epub",
            mimeType = "application/epub+zip",
            totalBytes = payload.size.toLong(),
            parents = listOf("folder-1"),
            appProperties = mapOf("liseur" to "1", "liseurKind" to "epub", "sha256" to "abc"),
            chunkSizeBytes = 2,
            openStream = { ByteArrayInputStream(payload) },
            onSessionUri = {},
            onProgress = { _, _ -> },
        )

        assertEquals("epub-file-1", uploaded.id)
    }

    @Test
    fun `resumable upload initiates session, sends chunks, recovers mid-upload 503 via offset probe, and completes`() = runTest {
        val payload = ByteArray(10) { (it + 1).toByte() }
        val sessionUrl = server.url("/upload/session/abc").toString()

        // 1) Initiate resumable session.
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Location", sessionUrl)
                .build(),
        )
        // 2) Chunk 1 (0..3) -> 308 Resume Incomplete with Range: bytes=0-3.
        server.enqueue(
            MockResponse.Builder()
                .code(308)
                .addHeader("Range", "bytes=0-3")
                .build(),
        )
        // 3) Chunk 2 (4..7) -> transient 503.
        server.enqueue(MockResponse.Builder().code(503).body("retry").build())
        // 4) Offset probe (Content-Range: bytes */10) -> server actually received 0..7!
        server.enqueue(
            MockResponse.Builder()
                .code(308)
                .addHeader("Range", "bytes=0-7")
                .build(),
        )
        // 5) Chunk 3 (8..9) -> 200 Completed.
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"id":"epub-file-1","name":"book.epub","size":"10"}""")
                .build(),
        )

        val progressEvents = mutableListOf<Long>()
        var recordedSession: String? = null
        val uploaded = client.uploadResumableFile(
            accessToken = "tok",
            name = "book.epub",
            mimeType = "application/epub+zip",
            totalBytes = payload.size.toLong(),
            parents = listOf("folder-1"),
            appProperties = mapOf("liseur" to "1", "liseurKind" to "epub", "sha256" to "abc"),
            chunkSizeBytes = 4,
            openStream = { ByteArrayInputStream(payload) },
            onSessionUri = { recordedSession = it },
            onProgress = { bytes, _ -> progressEvents += bytes },
        )

        assertEquals("epub-file-1", uploaded.id)
        assertEquals(sessionUrl, recordedSession)
        assertEquals(listOf(4L, 10L), progressEvents)

        val initReq = server.takeRequest()
        assertEquals("resumable", initReq.url.queryParameter("uploadType"))
        assertEquals("application/epub+zip", initReq.headers["X-Upload-Content-Type"])
        assertEquals("10", initReq.headers["X-Upload-Content-Length"])

        val put1 = server.takeRequest()
        assertEquals("bytes 0-3/10", put1.headers["Content-Range"])
        val put2 = server.takeRequest()
        assertEquals("bytes 4-7/10", put2.headers["Content-Range"])
        val probe = server.takeRequest()
        assertEquals("bytes */10", probe.headers["Content-Range"])
        val put3 = server.takeRequest()
        assertEquals("bytes 8-9/10", put3.headers["Content-Range"])
        assertArrayEquals(byteArrayOf(9, 10), put3.body!!.toByteArray())
    }

    @Test
    fun `resumable download supports Range 206, detects 200 restart, and treats 416 at EOF as complete`() = runTest {
        // 206 Partial Content from offset 5.
        server.enqueue(
            MockResponse.Builder()
                .code(206)
                .addHeader("Content-Range", "bytes 5-9/10")
                .body("56789")
                .build(),
        )
        val out1 = ByteArrayOutputStream()
        val r1 = client.downloadResumable("tok", "f-epub", out1, startOffset = 5L, onRestartFromZero = {})
        assertEquals(5L, r1.bytesWritten)
        assertEquals(10L, r1.totalBytes)
        assertFalse(r1.restartedFromZero)
        assertEquals("56789", out1.toString(Charsets.UTF_8.name()))
        val rangeReq = server.takeRequest()
        assertEquals("bytes=5-", rangeReq.headers["Range"])

        // 200 OK when caller asked for offset > 0 triggers onRestartFromZero.
        server.enqueue(MockResponse.Builder().code(200).body("0123456789").build())
        val out2 = ByteArrayOutputStream()
        var restartedCallbackCalled = false
        val r2 = client.downloadResumable(
            accessToken = "tok",
            fileId = "f-epub",
            destination = out2,
            startOffset = 5L,
            onRestartFromZero = { restartedCallbackCalled = true },
        )
        assertTrue(restartedCallbackCalled)
        assertTrue(r2.restartedFromZero)
        assertEquals(10L, r2.bytesWritten)

        // 416 at exact EOF returns 0 bytes written.
        server.enqueue(
            MockResponse.Builder()
                .code(416)
                .addHeader("Content-Range", "bytes */10")
                .build(),
        )
        val r3 = client.downloadResumable("tok", "f-epub", ByteArrayOutputStream(), startOffset = 10L, onRestartFromZero = {})
        assertEquals(0L, r3.bytesWritten)
        assertEquals(10L, r3.totalBytes)
    }

    @Test
    fun `retry handles 429 Retry-After seconds and HTTP date, 403 rateLimitExceeded, and 5xx`() = runTest {
        val futureDate = DateTimeFormatter.RFC_1123_DATE_TIME.format(
            Instant.ofEpochMilli(nowMs + 7_000L).atOffset(ZoneOffset.UTC),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(429)
                .addHeader("Retry-After", "3")
                .body("""{"error":{"message":"Too many requests"}}""")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .addHeader("Retry-After", futureDate)
                .body("""{"error":{"message":"Rate limit","errors":[{"reason":"rateLimitExceeded"}]}}""")
                .build(),
        )
        server.enqueue(MockResponse.Builder().code(503).body("unavailable").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"startPageToken":"99"}""").build())

        val token = client.getStartPageToken("tok")
        assertEquals("99", token)
        assertEquals(listOf(3_000L, 7_000L, 400L), sleepDelays)
    }

    @Test
    fun `typed exceptions for 401 auth expired, 403 storageQuotaExceeded, 404 not found, and no redirects`() = runTest {
        // 401 -> DriveAuthExpiredException immediately (no retry).
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":{"message":"Invalid Credentials"}}""").build())
        try {
            client.getStartPageToken("expired-tok")
            fail("Expected DriveAuthExpiredException")
        } catch (e: DriveAuthExpiredException) {
            assertEquals(401, e.httpCode)
            assertTrue(sleepDelays.isEmpty())
        }

        // 403 storageQuotaExceeded -> DriveQuotaException immediately (no retry).
        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .body("""{"error":{"message":"Drive storage full","errors":[{"reason":"storageQuotaExceeded"}]}}""")
                .build(),
        )
        try {
            client.getStartPageToken("tok")
            fail("Expected DriveQuotaException")
        } catch (e: DriveQuotaException) {
            assertEquals(403, e.httpCode)
            assertTrue(e.isStorageQuota)
            assertTrue(sleepDelays.isEmpty())
        }

        // 404 on file download -> DriveNotFoundException immediately.
        server.enqueue(MockResponse.Builder().code(404).body("""{"error":{"message":"File not found"}}""").build())
        try {
            client.downloadMedia("tok", "missing-file")
            fail("Expected DriveNotFoundException")
        } catch (e: DriveNotFoundException) {
            assertEquals("missing-file", e.fileId)
            assertEquals(404, e.httpCode)
        }

        // 302 redirect must NOT be followed so Bearer token never leaks.
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "https://evil.example/steal")
                .build(),
        )
        try {
            client.getStartPageToken("tok")
            fail("Expected DriveHttpException on redirect")
        } catch (e: DriveHttpException) {
            assertEquals(302, e.httpCode)
        }
    }

    @Test
    fun `resumable upload 308 without Range header does not skip unacknowledged chunk`() = runTest {
        val payload = ByteArray(8) { (it + 1).toByte() }
        val sessionUrl = server.url("/upload/session/no-range").toString()

        // 1) Initiate resumable session.
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Location", sessionUrl)
                .build(),
        )
        // 2) Chunk 1 (0..3) -> 308 WITHOUT Range header (0 committed bytes).
        server.enqueue(MockResponse.Builder().code(308).build())
        // 3) Retry of Chunk 1 (0..3) -> 308 WITH Range: bytes=0-3.
        server.enqueue(
            MockResponse.Builder()
                .code(308)
                .addHeader("Range", "bytes=0-3")
                .build(),
        )
        // 4) Chunk 2 (4..7) -> 200 Completed.
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"id":"epub-no-range","name":"book.epub","size":"8"}""")
                .build(),
        )

        val progressEvents = mutableListOf<Long>()
        val uploaded = client.uploadResumableFile(
            accessToken = "tok",
            name = "book.epub",
            mimeType = "application/epub+zip",
            totalBytes = payload.size.toLong(),
            parents = listOf("folder-1"),
            chunkSizeBytes = 4,
            openStream = { ByteArrayInputStream(payload) },
            onProgress = { bytes, _ -> progressEvents += bytes },
        )

        assertEquals("epub-no-range", uploaded.id)
        assertEquals(listOf(4L, 8L), progressEvents)

        server.takeRequest() // initiate
        val firstPut = server.takeRequest()
        assertEquals("bytes 0-3/8", firstPut.headers["Content-Range"])
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), firstPut.body!!.toByteArray())

        // Next request MUST re-upload 0..3 rather than skipping ahead to 4..7.
        val retryPut = server.takeRequest()
        assertEquals("bytes 0-3/8", retryPut.headers["Content-Range"])
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), retryPut.body!!.toByteArray())

        val finalPut = server.takeRequest()
        assertEquals("bytes 4-7/8", finalPut.headers["Content-Range"])
        assertArrayEquals(byteArrayOf(5, 6, 7, 8), finalPut.body!!.toByteArray())
    }

    @Test
    fun `resumable upload rewind-replay cycle fails within maxRetries bound`() = runTest {
        val payload = ByteArray(8) { (it + 1).toByte() }
        val sessionUrl = server.url("/upload/session/rewind-cycle").toString()

        // 1) Initiate resumable session.
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Location", sessionUrl)
                .build(),
        )
        // Repeat 0..3 -> 308 Range 0-3, then 4..7 -> headerless 308 (rewinds to 0) more than maxRetries times.
        repeat(6) {
            server.enqueue(
                MockResponse.Builder()
                    .code(308)
                    .addHeader("Range", "bytes=0-3")
                    .build(),
            )
            server.enqueue(MockResponse.Builder().code(308).build())
        }

        var failed: DriveApiException? = null
        try {
            client.uploadResumableFile(
                accessToken = "tok",
                name = "book.epub",
                mimeType = "application/epub+zip",
                totalBytes = payload.size.toLong(),
                parents = listOf("folder-1"),
                chunkSizeBytes = 4,
                openStream = { ByteArrayInputStream(payload) },
            )
        } catch (e: DriveApiException) {
            failed = e
        }

        assertTrue("Expected bounded failure on rewind-replay cycle", failed != null)
        assertTrue(
            "Expected no-progress message, got: ${failed!!.message}",
            failed.message!!.contains("made no progress"),
        )
    }

    @Test
    fun `resumable upload chunk 503 honors Retry-After header before offset probe`() = runTest {
        val payload = ByteArray(8) { (it + 1).toByte() }
        val sessionUrl = server.url("/upload/session/retry-after").toString()

        server.enqueue(MockResponse.Builder().code(200).addHeader("Location", sessionUrl).build())
        server.enqueue(
            MockResponse.Builder()
                .code(503)
                .addHeader("Retry-After", "5")
                .body("""{"error":{"message":"Temporarily unavailable"}}""")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(308)
                .addHeader("Range", "bytes=0-3")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"id":"epub-retry-after","name":"book.epub","size":"8"}""")
                .build(),
        )

        val uploaded = client.uploadResumableFile(
            accessToken = "tok",
            name = "book.epub",
            mimeType = "application/epub+zip",
            totalBytes = payload.size.toLong(),
            parents = listOf("folder-1"),
            chunkSizeBytes = 4,
            openStream = { ByteArrayInputStream(payload) },
        )

        assertEquals("epub-retry-after", uploaded.id)
        assertEquals(listOf(5_000L), sleepDelays)
    }

    @Test
    fun `Retry-After exceeding 120s defers via typed exception carrying retryAfterMs without early retry`() = runTest {
        // 1) Regular 429 with Retry-After: 180 -> immediate DriveQuotaException(retryAfterMs = 180_000L).
        server.enqueue(
            MockResponse.Builder()
                .code(429)
                .addHeader("Retry-After", "180")
                .body("""{"error":{"message":"Rate limit exceeded"}}""")
                .build(),
        )
        try {
            client.getStartPageToken("tok")
            fail("Expected DriveQuotaException for Retry-After: 180")
        } catch (e: DriveQuotaException) {
            assertEquals(429, e.httpCode)
            assertEquals(180_000L, e.retryAfterMs)
            assertFalse(e.isStorageQuota)
            assertTrue(sleepDelays.isEmpty())
            assertEquals(1, server.requestCount)
        }
        server.takeRequest()

        // 2) Resumable chunk 503 with Retry-After: 180 -> immediate DriveHttpException(retryAfterMs = 180_000L).
        val payload = ByteArray(8) { (it + 1).toByte() }
        val sessionUrl = server.url("/upload/session/defer-180").toString()
        server.enqueue(MockResponse.Builder().code(200).addHeader("Location", sessionUrl).build())
        server.enqueue(
            MockResponse.Builder()
                .code(503)
                .addHeader("Retry-After", "180")
                .body("""{"error":{"message":"Backend overloaded"}}""")
                .build(),
        )

        try {
            client.uploadResumableFile(
                accessToken = "tok",
                name = "book.epub",
                mimeType = "application/epub+zip",
                totalBytes = payload.size.toLong(),
                parents = listOf("folder-1"),
                chunkSizeBytes = 4,
                openStream = { ByteArrayInputStream(payload) },
            )
            fail("Expected DriveHttpException for chunk 503 Retry-After: 180")
        } catch (e: DriveHttpException) {
            assertEquals(503, e.httpCode)
            assertEquals(180_000L, e.retryAfterMs)
            assertTrue(sleepDelays.isEmpty())
            // Only initiate + first chunk PUT were sent (no early retry or offset probe).
            assertEquals(3, server.requestCount)
        }
    }
}

