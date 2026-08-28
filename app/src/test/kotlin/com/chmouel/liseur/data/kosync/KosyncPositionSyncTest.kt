package com.chmouel.liseur.data.kosync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.KosyncPeer
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.library.BookFingerprintStore
import com.chmouel.liseur.data.library.FinishedState
import com.chmouel.liseur.data.remote.DeviceIdentity
import com.chmouel.liseur.data.remote.PreviewOutcome
import com.chmouel.liseur.data.remote.RemoteHttp
import com.chmouel.liseur.data.remote.RemoteResult
import com.chmouel.liseur.data.remote.ResolveOutcome
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.remote.SetupFailure
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.SyncOutcome
import java.io.File
import java.net.InetAddress
import javax.crypto.KeyGenerator
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Keeping a reader's place in step with a KOReader kosync server.
 *
 * kosync names a book by KOReader's partial MD5 of its bytes and speaks
 * only in percentages, so the tests watch two things above all: the
 * document addressed on the wire is the file's hash, and every decision
 * about whose position wins goes through the one shared merge.
 */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class KosyncPositionSyncTest {

    private lateinit var server: MockWebServer
    private lateinit var db: LiseurDatabase
    private lateinit var file: File

    @Before
    fun open() {
        CredentialCipher.keyForTesting =
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(context, LiseurDatabase::class.java).build()
        file = File.createTempFile("book", ".epub").apply { writeBytes(ByteArray(4096) { 3 }) }
    }

    @After
    fun close() {
        server.close()
        db.close()
        file.delete()
        CredentialCipher.keyForTesting = null
    }

    // -- Whose server it is ------------------------------------------------

    /**
     * A saved pairing is not permission to sync. An account switch
     * interrupted halfway, a crash, or a database restored onto a phone
     * that never made the pairing all leave a row behind, and a server
     * that carries positions itself must not have a second, invisible
     * source disagreeing with it.
     */
    @Test
    fun `a pairing stays quiet under a server that carries positions itself`() = runTest {
        for (kind in listOf(ServerKind.CALIBRE, ServerKind.KOMGA, ServerKind.LISEUR_SYNC)) {
            pair()
            db.bookDao().upsert(book())

            val outcome = sync(connectedKind = kind).syncAll(null)

            assertEquals(SyncOutcome.NotApplicable, outcome)
            assertEquals("$kind must not be spoken to", 0, server.requestCount)
        }
    }

    @Test
    fun `a pairing with nothing connected stays quiet`() = runTest {
        pair()
        db.bookDao().upsert(book())

        assertEquals(SyncOutcome.NotApplicable, sync(connectedKind = null).syncAll(null))
        assertEquals(0, server.requestCount)
    }

    /**
     * Not just the run: the answers the reader is shown come from the
     * same test, so an ineligible pairing reports no position rather
     * than one it has no business fetching.
     */
    @Test
    fun `an ineligible pairing offers no preview and syncs no book`() = runTest {
        pair()
        db.bookDao().upsert(book())
        val komga = sync(connectedKind = ServerKind.KOMGA)

        assertEquals(false, komga.canSync(BOOK))
        assertEquals(PreviewOutcome.NotSynced, komga.previewBook(BOOK))
        assertNull(komga.identity())
        assertEquals(0, server.requestCount)
    }

    // -- The credential ----------------------------------------------------

    @Test
    fun `the auth key is the hex MD5 of the password, as KOReader derives it`() {
        // md5("secret") — a fixed vector, not a computed one.
        assertEquals("5ebe2294ecd0e0f08eab7690d2a6ee69", KosyncCredentials.keyFor("secret"))
    }

    // -- The wire ----------------------------------------------------------

    @Test
    fun `every request carries the kosync auth headers`() = runTest {
        pair()
        db.bookDao().upsert(book())
        server.enqueue(json("{}"))

        sync().syncAll(null)

        val request = requests().single()
        assertEquals("ada", request.headers["x-auth-user"])
        assertEquals(KosyncCredentials.keyFor("pw"), request.headers["x-auth-key"])
    }

    @Test
    fun `a document that is not a hex hash is refused rather than addressed`() = runTest {
        val client = KosyncClient()
        // `..` would resolve in the URL before the server saw it; the
        // rest are not KOReader's partial-MD5 shape: too short, too
        // long, a sha-length value, uppercase.
        for (
            document in listOf(
                "../users/auth",
                "a".repeat(31),
                "a".repeat(33),
                "a".repeat(64),
                "A".repeat(32),
            )
        ) {
            val result = client.getProgress(
                "http://127.0.0.1:${server.port}",
                KosyncCredentials("ada", KosyncCredentials.keyFor("pw")),
                document,
            )
            assertEquals(SyncFailure.Malformed, (result as RemoteResult.Failed).reason)
        }
        assertEquals(0, server.requestCount)
    }

    // -- Redirects ---------------------------------------------------------

    @Test
    fun `a redirect is refused, and the auth headers never reach the other host`() = runTest {
        val elsewhere = MockWebServer()
        elsewhere.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            server.enqueue(
                MockResponse(
                    code = 301,
                    headers = okhttp3.Headers.headersOf(
                        "Location",
                        "http://127.0.0.1:${elsewhere.port}/users/auth",
                    ),
                ),
            )
            val result = KosyncClient().authorize(
                "http://127.0.0.1:${server.port}",
                KosyncCredentials("ada", KosyncCredentials.keyFor("pw")),
            )
            assertEquals(SyncFailure.Malformed, (result as RemoteResult.Failed).reason)
            assertEquals(0, elsewhere.requestCount)
        } finally {
            elsewhere.close()
        }
    }

    @Test
    fun `a redirected registration never carries the password onward`() = runTest {
        val elsewhere = MockWebServer()
        elsewhere.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            // 307 preserves the method and body — exactly the redirect
            // that would replay the raw password if it were followed.
            server.enqueue(
                MockResponse(
                    code = 307,
                    headers = okhttp3.Headers.headersOf(
                        "Location",
                        "http://127.0.0.1:${elsewhere.port}/users/create",
                    ),
                ),
            )
            val result = KosyncClient().register(
                "http://127.0.0.1:${server.port}",
                "ada",
                "raw-pairing-code",
            )
            assertEquals(SyncFailure.Malformed, (result as RemoteResult.Failed).reason)
            assertEquals(0, elsewhere.requestCount)
        } finally {
            elsewhere.close()
        }
    }

    // -- Push --------------------------------------------------------------

    @Test
    fun `reading done here is pushed as a percentage under the file's hash`() = runTest {
        pair()
        db.bookDao().upsert(book())
        record(progression = 0.4)
        server.enqueue(json("{}"))
        server.enqueue(json("""{"document":"x"}"""))

        assertEquals(SyncOutcome.Success, sync().syncAll(null))

        val put = requests().last()
        assertEquals("PUT", put.method)
        assertTrue(put.target.endsWith("/syncs/progress"))
        val sent = JSONObject(put.body!!.utf8())
        val document = requests().first().target.substringAfterLast("/")
        assertEquals(document, sent.getString("document"))
        assertEquals(0.4, sent.getDouble("percentage"), 0.0)
        assertEquals("0.4", sent.getString("progress"))
        assertEquals(NOW / 1000, sent.getLong("timestamp"))
        assertEquals("Test Phone", sent.getString("device"))
        assertEquals("dev-1", sent.getString("device_id"))
        // Settled at the revision that was compared.
        assertEquals(1L, db.syncPeerStateDao().get(BOOK, peerKey())?.ackedRevision)
    }

    @Test
    fun `an agreed position is not pushed again`() = runTest {
        pair()
        db.bookDao().upsert(book())
        record(progression = 0.4)
        server.enqueue(json("{}"))
        server.enqueue(json("{}"))
        sync().syncAll(null)
        val before = requests().count { it.method == "PUT" }

        // Nothing moved on either side since.
        server.enqueue(progress(0.4))
        assertEquals(SyncOutcome.Success, sync().syncAll(null))

        assertEquals(before, requests().count { it.method == "PUT" })
    }

    // -- Pull --------------------------------------------------------------

    @Test
    fun `a position read elsewhere lands here`() = runTest {
        pair()
        db.bookDao().upsert(book())
        server.enqueue(progress(0.6))

        assertEquals(SyncOutcome.Success, sync().syncAll(null))

        val stored = db.readingProgressDao().get(BOOK)
        assertEquals(0.6, stored?.totalProgression ?: 0.0, 0.0001)
        assertEquals("Reading", stored?.status)
    }

    // -- Conflict ------------------------------------------------------------

    @Test
    fun `both sides having moved is preserved, not decided`() = runTest {
        pair()
        db.bookDao().upsert(book())
        record(progression = 0.4)
        server.enqueue(progress(0.9))

        assertEquals(SyncOutcome.Success, sync().syncAll(null))

        // Neither side changed; the disagreement is on disk for the reader.
        assertEquals(0.4, db.readingProgressDao().get(BOOK)?.totalProgression ?: 0.0, 0.0001)
        val state = db.syncPeerStateDao().get(BOOK, peerKey())
        assertEquals(true, state?.hasPending)
        assertEquals(0.9, state?.pendingProgression ?: 0.0, 0.0001)
        assertTrue(requests().none { it.method == "PUT" })
    }

    @Test
    fun `taking the remote side of a conflict lands it`() = runTest {
        pair()
        db.bookDao().upsert(book())
        record(progression = 0.4)
        server.enqueue(progress(0.9))
        sync().syncAll(null)

        val revision = db.readingProgressDao().get(BOOK)!!.localRevision
        assertEquals(ResolveOutcome.Done, sync().takeRemotePosition(BOOK, revision))
        assertEquals(0.9, db.readingProgressDao().get(BOOK)?.totalProgression ?: 0.0, 0.0001)
        assertEquals(false, db.syncPeerStateDao().get(BOOK, peerKey())?.hasPending)
    }

    @Test
    fun `keeping the local side clears the question and pushes next run`() = runTest {
        pair()
        db.bookDao().upsert(book())
        record(progression = 0.4)
        server.enqueue(progress(0.9))
        sync().syncAll(null)

        assertEquals(ResolveOutcome.Done, sync().keepLocalPosition(BOOK))
        assertEquals(false, db.syncPeerStateDao().get(BOOK, peerKey())?.hasPending)

        // Still dirty, so the next run sends the page that is actually open.
        server.enqueue(json("{}"))
        server.enqueue(json("{}"))
        sync().syncAll(null)
        assertTrue(requests().any { it.method == "PUT" })
    }

    @Test
    fun `keeping the local side is not asked again by a server that has not moved`() = runTest {
        // kosync has no feed, so the next run asks outright and gets the
        // same answer back. With the old agreement still standing both
        // sides read as moved, and the reader is asked the same question
        // on every sync until they happen to turn a page.
        pair()
        db.bookDao().upsert(book())
        record(progression = 0.4)
        server.enqueue(progress(0.9))
        sync().syncAll(null)
        sync().keepLocalPosition(BOOK)
        seen.clear()

        server.enqueue(progress(0.9))
        server.enqueue(json("{}"))
        sync().syncAll(null)

        val put = requests().singleOrNull { it.method == "PUT" }
        assertNotNull(put)
        assertEquals(0.4, JSONObject(put!!.body!!.utf8()).getDouble("percentage"), 0.0001)
        assertEquals(0, db.syncPeerStateDao().countPending(peerKey()))
    }

    // -- Who is spoken about -------------------------------------------------

    @Test
    fun `a book that never came from the catalog server is not asked about`() = runTest {
        pair()
        db.bookDao().upsert(book(url = BOOK, remoteUuid = null))
        record(progression = 0.4)

        assertEquals(SyncOutcome.Success, sync().syncAll(null))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a local book adopted after an upload keeps out of kosync`() = runTest {
        // Adoption writes a remote_uuid onto a book whose url stays
        // local, deliberately — its positions travel natively through
        // liseur-sync, and kosync speaking about it too would have two
        // partners disagreeing over one book.
        pair()
        val local = "content://downloads/adopted.epub"
        db.bookDao().upsert(book(url = local, remoteUuid = "uuid-adopted"))
        db.readingProgressDao().recordLocal(
            bookUrl = local,
            locatorJson = LOCATOR,
            progression = 0.4,
            readingSpeed = null,
            status = "Reading",
            updatedAt = NOW,
        )

        assertEquals(SyncOutcome.Success, sync().syncAll(null))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a book whose file is gone has no name here and is skipped in silence`() = runTest {
        pair()
        db.bookDao().upsert(book(localUri = "file:///nowhere/gone.epub"))
        record(progression = 0.4)

        assertEquals(SyncOutcome.Success, sync().syncAll(null))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `with no partner paired there is nothing to do`() = runTest {
        db.bookDao().upsert(book())
        record(progression = 0.4)
        assertEquals(SyncOutcome.NotApplicable, sync().syncAll(null))
    }

    // -- The account ---------------------------------------------------------

    @Test
    fun `signing into a different kosync account strands the old agreements`() = runTest {
        pair()
        db.bookDao().upsert(book())
        record(progression = 0.4)
        server.enqueue(json("{}"))
        server.enqueue(json("{}"))
        sync().syncAll(null)
        val oldKey = peerKey()

        // Pairing as somebody else goes through the repository's one door.
        server.enqueue(json("""{"authorized":"OK"}"""))
        val outcome = repository().connect(
            url = "http://127.0.0.1:${server.port}",
            username = "grace",
            password = "other",
        )
        assertEquals(KosyncSetupOutcome.Success, outcome)
        assertNull(db.syncPeerStateDao().get(BOOK, oldKey))
        assertEquals("grace", db.kosyncPeerDao().get()?.username)
    }

    @Test
    fun `refused credentials pair nothing`() = runTest {
        server.enqueue(MockResponse(code = 401, body = ""))
        val outcome = repository().connect(
            url = "http://127.0.0.1:${server.port}",
            username = "ada",
            password = "wrong",
        )
        assertTrue(outcome is KosyncSetupOutcome.Failure)
        assertNull(db.kosyncPeerDao().get())
    }

    @Test
    fun `only the derived key is stored, never the password`() = runTest {
        server.enqueue(json("""{"authorized":"OK"}"""))
        repository().connect(
            url = "http://127.0.0.1:${server.port}",
            username = "ada",
            password = "pw",
        )
        val peer = db.kosyncPeerDao().get()!!
        assertEquals(KosyncCredentials.keyFor("pw"), peer.credentials?.key)
        val auth = requests().single()
        assertTrue(auth.target.endsWith("/users/auth"))
    }

    @Test
    fun `registering sends the password as typed, then proves the key works`() = runTest {
        val (https, client) = httpsServer()
        try {
            https.enqueue(MockResponse(code = 201, body = """{"username":"ada"}"""))
            https.enqueue(json("""{"authorized":"OK"}"""))
            val outcome = repository(client).connect(
                url = "https://localhost:${https.port}",
                username = "ada",
                password = "raw-pairing-code",
                register = true,
            )
            assertEquals(KosyncSetupOutcome.Success, outcome)
            val create = https.takeRequest()
            assertTrue(create.target.endsWith("/users/create"))
            assertEquals(
                "raw-pairing-code",
                JSONObject(create.body!!.utf8()).getString("password"),
            )
            val auth = https.takeRequest()
            assertEquals(KosyncCredentials.keyFor("raw-pairing-code"), auth.headers["x-auth-key"])
            assertEquals("ada", db.kosyncPeerDao().get()?.username)
        } finally {
            https.close()
        }
    }

    @Test
    fun `registering over plain http is refused before the password leaves`() = runTest {
        val outcome = repository().connect(
            url = "http://127.0.0.1:${server.port}",
            username = "ada",
            password = "raw-pairing-code",
            register = true,
        )
        assertEquals(
            KosyncSetupOutcome.Failure(SetupFailure.InsecureTransport),
            outcome,
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a root pasted with a query is stored without it`() = runTest {
        // Endpoints are built by appending, so a query left on the root
        // would produce `…/koreader?x=1/users/auth`, which addresses
        // nothing.
        server.enqueue(json("""{"authorized":"OK"}"""))

        repository().connect(
            url = "http://127.0.0.1:${server.port}/koreader?x=1#top",
            username = "ada",
            password = "pw",
        )

        assertEquals(
            "http://127.0.0.1:${server.port}/koreader",
            db.kosyncPeerDao().get()?.baseUrl,
        )
        assertTrue(requests().single().target.endsWith("/koreader/users/auth"))
    }

    @Test
    fun `an upper case scheme is read as the scheme it is`() = runTest {
        // Not a bypass today, because normalise refused the whole
        // address, but "wrong server" is the wrong thing to tell someone
        // who typed HTTP:// and needs to hear why it will not be used.
        val outcome = repository().connect(
            url = "HTTP://sync.example.com",
            username = "ada",
            password = "pw",
            register = true,
        )

        assertEquals(
            KosyncSetupOutcome.Failure(SetupFailure.InsecureTransport),
            outcome,
        )
    }

    @Test
    fun `a root nothing could address is refused before networking`() = runTest {
        // Scheme with no host, a host with a space in it, scheme only:
        // OkHttp would throw on each outside the client's error mapping.
        for (root in listOf("https://", "https://exa mple.com", "http://")) {
            val outcome = repository().connect(root, "ada", "pw")
            assertEquals(KosyncSetupOutcome.Failure(SetupFailure.WrongServer), outcome)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a server that lost the record is sent the position again`() = runTest {
        // Reset, evicted, restored from an older backup: the server
        // answers with nothing for a book it had already agreed a
        // position for. Left alone this device sits on a position the
        // server does not have until the reader happens to turn a page.
        pair()
        db.bookDao().upsert(book())
        record(progression = 0.4)
        server.enqueue(json("{}"))
        server.enqueue(json("{}"))
        sync().syncAll(null)
        seen.clear()
        server.enqueue(json("{}"))

        sync().syncAll(null)

        val put = requests().last()
        assertEquals("PUT", put.method)
        assertEquals(0.4, JSONObject(put.body!!.utf8()).getDouble("percentage"), 0.0001)
    }

    @Test
    fun `a book the server never knew about is not pushed twice`() = runTest {
        // The other half of the rule: with no agreement behind it there
        // is nothing to conclude from silence, so a second run with
        // nothing read in between says nothing.
        pair()
        db.bookDao().upsert(book())
        server.enqueue(json("{}"))

        sync().syncAll(null)
        val after = server.requestCount

        server.enqueue(json("{}"))
        sync().syncAll(null)

        assertEquals(after + 1, server.requestCount)
    }

    @Test
    fun `disconnecting forgets the agreements along with the partner`() = runTest {
        pair()
        db.bookDao().upsert(book())
        record(progression = 0.4)
        server.enqueue(json("{}"))
        server.enqueue(json("{}"))
        sync().syncAll(null)
        val key = peerKey()

        repository().disconnect()

        assertNull(db.kosyncPeerDao().get())
        assertNull(db.syncPeerStateDao().get(BOOK, key))
    }

    // -- Preview ---------------------------------------------------------------

    @Test
    fun `a preview says where both sides are without moving either`() = runTest {
        pair()
        db.bookDao().upsert(book())
        record(progression = 0.4)
        server.enqueue(progress(0.6))

        val outcome = sync().previewBook(BOOK)

        val preview = (outcome as PreviewOutcome.Ready).preview
        assertEquals(0.4, preview.local ?: 0.0, 0.0001)
        assertEquals(0.6, preview.remote ?: 0.0, 0.0001)
        assertEquals(0.4, db.readingProgressDao().get(BOOK)?.totalProgression ?: 0.0, 0.0001)
    }

    // -- Helpers -----------------------------------------------------------

    private suspend fun pair(baseUrl: String = "http://127.0.0.1:${server.port}") {
        db.kosyncPeerDao().upsert(
            KosyncPeer(
                baseUrl = baseUrl,
                username = "ada",
                keyCipher = KosyncPeer.seal(KosyncCredentials.keyFor("pw")),
                addedAt = NOW,
            ),
        )
    }

    private suspend fun peerKey(): String = db.kosyncPeerDao().get()!!.accountKey

    private fun sync(
        online: Boolean = true,
        connectedKind: ServerKind? = ServerKind.GRIMMORY,
    ): KosyncPositionSync {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        return KosyncPositionSync(
            kosyncDao = db.kosyncPeerDao(),
            bookDao = db.bookDao(),
            progressDao = db.readingProgressDao(),
            peerStateDao = db.syncPeerStateDao(),
            fingerprints = BookFingerprintStore(context, db.workIdentityDao()) { NOW },
            device = { DeviceIdentity(id = "dev-1", name = "Test Phone") },
            finishedState = FinishedState(db.bookDao(), db.readingProgressDao()),
            connectedKind = { connectedKind },
            networkAvailability = { online },
            now = { NOW },
        )
    }

    private fun repository(client: KosyncClient = KosyncClient()) = KosyncAccountRepository(
        dao = db.kosyncPeerDao(),
        peerStateDao = db.syncPeerStateDao(),
        client = client,
        now = { NOW },
    )

    /**
     * A TLS MockWebServer and a client that trusts it — registration
     * refuses plain http, so its happy path needs a real https server.
     */
    private fun httpsServer(): Pair<MockWebServer, KosyncClient> {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverSide = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientSide = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        val https = MockWebServer()
        https.useHttps(serverSide.sslSocketFactory())
        https.start(InetAddress.getByName("127.0.0.1"), 0)
        val ok = RemoteHttp.default().newBuilder()
            .sslSocketFactory(clientSide.sslSocketFactory(), clientSide.trustManager)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        return https to KosyncClient(RemoteHttp(ok))
    }

    private suspend fun record(progression: Double) {
        db.readingProgressDao().recordLocal(
            bookUrl = BOOK,
            locatorJson = LOCATOR,
            progression = progression,
            readingSpeed = null,
            status = "Reading",
            updatedAt = NOW,
        )
    }

    private fun book(
        url: String = BOOK,
        remoteUuid: String? = "uuid-1",
        localUri: String? = file.toURI().toString(),
    ) = Book(
        url = url,
        title = "A Memory Called Empire",
        author = "Arkady Martine",
        coverPath = null,
        source = null,
        addedAt = NOW,
        lastOpenedAt = NOW,
        localUri = localUri,
        fileModifiedAt = NOW,
        remoteUuid = remoteUuid,
    )

    private fun progress(percentage: Double) = json(
        """{"document":"d","progress":"$percentage","percentage":$percentage,
            "device":"other","device_id":"dev-2","timestamp":${NOW / 1000}}
        """.trimIndent(),
    )

    private fun json(body: String) = MockResponse(code = 200, body = body)

    private fun requests(): List<RecordedRequest> {
        while (seen.size < server.requestCount) seen += server.takeRequest()
        return seen.toList()
    }

    private val seen = mutableListOf<RecordedRequest>()

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val BOOK = "calibre://books.example/12"
        const val LOCATOR = """{"href":"/c1.xhtml"}"""
    }
}
