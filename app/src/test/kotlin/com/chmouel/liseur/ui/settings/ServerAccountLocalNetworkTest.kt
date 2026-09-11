package com.chmouel.liseur.ui.settings

import androidx.room.Room
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.calibre.BookDownloadRepository
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.kosync.KosyncAccountRepository
import com.chmouel.liseur.data.kosync.KosyncCredentials
import com.chmouel.liseur.data.kosync.KosyncPairing
import com.chmouel.liseur.data.kosync.KosyncProbe
import com.chmouel.liseur.data.db.KosyncPeer
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.kosync.ProvedKosyncPairing
import com.chmouel.liseur.data.library.BookRemoval
import com.chmouel.liseur.data.remote.LocalNetworkAccess
import com.chmouel.liseur.data.remote.PositionSync
import com.chmouel.liseur.data.remote.PreviewOutcome
import com.chmouel.liseur.data.remote.RemoteCatalogRepository
import com.chmouel.liseur.data.remote.ResolveOutcome
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteAccountRepository
import com.chmouel.liseur.data.remote.RemoteRouter
import com.chmouel.liseur.data.remote.ServerCapabilities
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.remote.ServerSetup
import com.chmouel.liseur.data.remote.SetupResult
import com.chmouel.liseur.data.remote.SyncIdentity
import com.chmouel.liseur.data.remote.SyncOutcome
import com.chmouel.liseur.data.remote.SyncPreview
import com.chmouel.liseur.data.remote.SyncReporting
import com.chmouel.liseur.data.remote.SyncSnapshot
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.sync.PositionSyncCoordinator
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
 * Connecting to a server the phone is blocking.
 *
 * The whole point of parking a connection is that it can be resumed
 * exactly once, with what was submitted rather than with what the form
 * holds by the time the reader has finished with the system dialog. The
 * ways that goes wrong — a second prompt after a rotation, a second
 * connection from a repeated callback, credentials read back out of a
 * form the reader edited underneath it — are all here.
 */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ServerAccountLocalNetworkTest {

    private lateinit var db: LiseurDatabase
    private val connects = mutableListOf<Triple<String, String, Boolean>>()
    private val kosyncPairings = mutableListOf<String>()
    private var blocked = mutableSetOf<String>()
    private var permitted = false

    @Before
    fun open() {
        CredentialCipher.keyForTesting =
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        // Inline executors throughout: a connection that hops onto
        // Room's own threads finishes whenever they get to it, and
        // these tests are about what the ViewModel did by the time the
        // reader's answer came back.
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LiseurDatabase::class.java,
        )
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        // The download repository asks for it as it is built. Nothing
        // here enqueues anything; it only has to exist.
        runCatching {
            WorkManager.initialize(
                ApplicationProvider.getApplicationContext(),
                Configuration.Builder().setExecutor { it.run() }.build(),
            )
        }
    }

    @After
    fun close() {
        db.close()
        CredentialCipher.keyForTesting = null
    }

    /** A test with the main dispatcher running on its own scheduler. */
    private fun scenario(body: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            body()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a public address connects without asking anything`() = scenario {
        blocked += "http://192.168.1.20:8083"
        val model = model()
        model.setUrl("https://books.example.com")
        model.setUsername("ada")
        model.setPassword("secret")

        model.connect()

        assertNull(model.state.value.localNetworkRequest)
        assertEquals(1, connects.size)
    }

    @Test
    fun `a phone that does not gate the network connects straight through`() = scenario {
        blocked += "http://192.168.1.20:8083"
        val model = model(gated = false)
        model.setUrl("http://192.168.1.20:8083")
        model.setUsername("ada")
        model.setPassword("secret")

        model.connect()

        assertNull(model.state.value.localNetworkRequest)
        assertEquals(1, connects.size)
    }

    @Test
    fun `a local address parks the connection instead of dialling it`() = scenario {
        blocked += "http://192.168.1.20:8083"
        val model = connecting()

        val request = model.state.value.localNetworkRequest
        assertNotNull(request)
        assertEquals(LocalNetworkRequest.Action.CONNECT, request!!.action)
        assertFalse(request.blockedKosync)
        assertTrue(model.state.value.connecting)
        assertEquals(0, connects.size)
    }

    /**
     * The form is live behind the dialog. Reading it again on the way
     * back would connect with whatever is in it now.
     */
    @Test
    fun `a grant connects once, with what was submitted`() = scenario {
        blocked += "http://192.168.1.20:8083"
        val model = connecting()
        val id = model.state.value.localNetworkRequest!!.id
        model.onLocalNetworkRequestLaunched(id)
        model.setUsername("somebody else")
        model.setPassword("another password")

        model.onLocalNetworkResult(granted = true)

        assertEquals(1, connects.size)
        assertEquals(Triple("http://192.168.1.20:8083", "ada", false), connects.single())
        assertNull(model.state.value.localNetworkRequest)
        assertTrue(model.state.value.localNetworkAsked)
    }

    @Test
    fun `a repeated answer does not connect twice`() = scenario {
        blocked += "http://192.168.1.20:8083"
        val model = connecting()
        model.onLocalNetworkRequestLaunched(model.state.value.localNetworkRequest!!.id)

        model.onLocalNetworkResult(granted = true)
        model.onLocalNetworkResult(granted = true)

        assertEquals(1, connects.size)
    }

    /**
     * A rotation while the system dialog is up rebuilds the effect that
     * launched it. The request has to survive; only the launching is
     * spent.
     */
    @Test
    fun `an acknowledged request is not launched again`() = scenario {
        blocked += "http://192.168.1.20:8083"
        val model = connecting()
        val id = model.state.value.localNetworkRequest!!.id

        model.onLocalNetworkRequestLaunched(id)
        model.onLocalNetworkRequestLaunched(id)

        val request = model.state.value.localNetworkRequest
        assertNotNull(request)
        assertTrue(request!!.launched)
        assertEquals(id, request.id)

        model.onLocalNetworkResult(granted = true)
        assertEquals(1, connects.size)
    }

    @Test
    fun `a refusal stops the attempt and says why`() = scenario {
        blocked += "http://192.168.1.20:8083"
        val model = connecting()
        model.onLocalNetworkRequestLaunched(model.state.value.localNetworkRequest!!.id)

        model.onLocalNetworkResult(granted = false)

        assertEquals(0, connects.size)
        assertFalse(model.state.value.connecting)
        assertEquals(AccountError.LOCAL_NETWORK_BLOCKED, model.state.value.error)
        assertNull(model.state.value.localNetworkRequest)
    }

    /**
     * A Custom connection is two servers, and either of them may be the
     * one in the spare room.
     */
    @Test
    fun `a custom connection asks about its sync address too`() = scenario {
        blocked += "http://192.168.1.9:8080"
        val model = model()
        model.setKind(ServerKind.CUSTOM)
        model.setUrl("https://books.example.com/opds")
        model.setKosyncUrl("http://192.168.1.9:8080")
        model.setKosyncUsername("ada")
        model.setKosyncPassword("pw")

        model.connect()

        val request = model.state.value.localNetworkRequest
        assertNotNull(request)
        assertTrue(request!!.blockedKosync)

        model.onLocalNetworkRequestLaunched(request.id)
        model.onLocalNetworkResult(granted = false)

        assertEquals(AccountError.LOCAL_NETWORK_BLOCKED, model.state.value.kosyncError)
        assertNull(model.state.value.error)
        // Neither half of a Custom connection is dialled when one of
        // them is out of reach: it is one connection, not two.
        assertEquals(0, connects.size)
        assertEquals(0, kosyncPairings.size)
    }

    @Test
    fun `pairing a kosync partner on its own parks the same way`() = scenario {
        blocked += "http://192.168.1.9:8080"
        val model = model()
        model.setKosyncUrl("http://192.168.1.9:8080")
        model.setKosyncUsername("ada")
        model.setKosyncPassword("pw")

        model.connectKosync()

        val request = model.state.value.localNetworkRequest
        assertNotNull(request)
        assertEquals(LocalNetworkRequest.Action.KOSYNC, request!!.action)
        assertTrue(model.state.value.kosyncConnecting)
        assertNull(model.state.value.kosyncError)

        model.onLocalNetworkRequestLaunched(request.id)
        model.onLocalNetworkResult(granted = true)

        // Nothing is listening on that address, so the pairing is out
        // on the wire and will fail there. What matters is that it got
        // that far: the request is spent, the attempt is still running
        // and nothing on this side refused it.
        assertNull(model.state.value.localNetworkRequest)
        assertTrue(model.state.value.localNetworkAsked)
        assertTrue(model.state.value.kosyncConnecting)
        assertNull(model.state.value.kosyncError)
    }

    @Test
    fun `a refused kosync pairing reports against the sync address`() = scenario {
        blocked += "http://192.168.1.9:8080"
        val model = model()
        model.setKosyncUrl("http://192.168.1.9:8080")
        model.setKosyncUsername("ada")
        model.setKosyncPassword("pw")
        model.connectKosync()
        model.onLocalNetworkRequestLaunched(model.state.value.localNetworkRequest!!.id)

        model.onLocalNetworkResult(granted = false)

        assertEquals(AccountError.LOCAL_NETWORK_BLOCKED, model.state.value.kosyncError)
        assertFalse(model.state.value.kosyncConnecting)
    }

    /**
     * The account is not in hand when the screen is built: Room
     * delivers it a moment later. A check that only ran when the
     * screen resumed would look before it landed, find nothing stored,
     * and leave the reader with the timeout and nothing to press.
     */
    @Test
    fun `a stored local account raises the notice on its own`() = scenario {
        blocked += "http://192.168.1.20:8083"
        db.remoteServerDao().upsert(server(baseUrl = "http://192.168.1.20:8083"))

        val model = model()

        assertTrue(model.state.value.localNetworkBlocked)
    }

    /**
     * A pairing left behind by an account switch is shown so that it
     * can be disconnected, and nothing will ever dial it. Asking for a
     * permission on its behalf would be a prompt for a machine this
     * account does not talk to.
     */
    @Test
    fun `a stranded kosync pairing raises no notice`() = scenario {
        blocked += "http://192.168.1.9:8080"
        db.remoteServerDao().upsert(
            server(baseUrl = "https://books.example.com", kind = ServerKind.KOMGA),
        )
        db.kosyncPeerDao().upsert(
            KosyncPeer(
                baseUrl = "http://192.168.1.9:8080",
                username = "ada",
                keyCipher = KosyncPeer.seal(KosyncCredentials.keyFor("pw")),
                addedAt = 1L,
            ),
        )

        val model = model()

        assertFalse(model.state.value.localNetworkBlocked)
    }

    /** The same pairing under a server that does use it. */
    @Test
    fun `a kosync partner the account uses raises the notice`() = scenario {
        blocked += "http://192.168.1.9:8080"
        db.remoteServerDao().upsert(
            server(baseUrl = "https://books.example.com", kind = ServerKind.CUSTOM),
        )
        db.kosyncPeerDao().upsert(
            KosyncPeer(
                baseUrl = "http://192.168.1.9:8080",
                username = "ada",
                keyCipher = KosyncPeer.seal(KosyncCredentials.keyFor("pw")),
                addedAt = 1L,
            ),
        )

        val model = model()

        assertTrue(model.state.value.localNetworkBlocked)
    }

    private fun server(baseUrl: String, kind: ServerKind = ServerKind.CALIBRE) = RemoteServer(
        kind = kind,
        baseUrl = baseUrl,
        catalogUrl = baseUrl,
        username = "ada",
        passwordCipher = null,
        apiKeyCipher = null,
        accountId = null,
        userId = null,
        koboTokenCipher = null,
        canDownload = true,
        addedAt = 1L,
        catalogSyncedAt = null,
        positionSyncedAt = null,
        syncToken = null,
    )

    private fun connecting(): ServerAccountViewModel {
        val model = model()
        model.setUrl("http://192.168.1.20:8083")
        model.setUsername("ada")
        model.setPassword("secret")
        model.connect()
        return model
    }

    private fun model(gated: Boolean = true): ServerAccountViewModel {
        val bookRemoval = BookRemoval(
            db.bookDao(),
            db.readingSessionDao(),
            db.syncPeerStateDao(),
            db.workIdentityDao(),
            db.readingProgressDao(),
            db.annotationDao(),
            db.annotationSyncDao(),
        )
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        val account = RemoteAccountRepository(
            dao = db.remoteServerDao(),
            bookDao = db.bookDao(),
            progressDao = db.readingProgressDao(),
            bookRemoval = bookRemoval,
            seriesExtraDao = db.seriesExtraDao(),
            peerStateDao = db.syncPeerStateDao(),
            kosync = { ScriptedPairing() },
            setups = ServerKind.entries.associateWith { Recording() },
        )
        val router = RemoteRouter(
            serverDao = db.remoteServerDao(),
            catalogs = emptyMap(),
            files = emptyMap(),
            positions = emptyMap(),
        )
        return ServerAccountViewModel(
            repository = account,
            downloads = BookDownloadRepository(context, db.bookDao(), bookRemoval, scope),
            reporting = SyncReporting(),
            positionSync = PositionSyncCoordinator(NoSync()),
            catalog = RemoteCatalogRepository(
                router = router,
                serverDao = db.remoteServerDao(),
                bookDao = db.bookDao(),
                bookRemoval = bookRemoval,
                scope = scope,
            ),
            appSettings = AppSettingsRepository(context),
            identityDao = db.workIdentityDao(),
            bookDao = db.bookDao(),
            kosyncAccount = KosyncAccountRepository(db.kosyncPeerDao(), db.syncPeerStateDao()),
            localNetwork = FakeAccess(gated),
        )
    }

    /** A gate whose answers the test writes rather than the platform. */
    private inner class FakeAccess(gated: Boolean) : LocalNetworkAccess {
        override val required = gated
        override val granted get() = !required || permitted
        override suspend fun blocks(url: String?) =
            required && !permitted && url != null && url in blocked
    }

    /** Nothing to sync: these tests never get that far. */
    private class NoSync : PositionSync {
        override suspend fun dialledAddress(): String? = null
        override suspend fun syncAll(snapshot: SyncSnapshot?) = SyncOutcome.Success
        override suspend fun syncBook(bookUrl: String) = SyncOutcome.Success
        override suspend fun canSync(bookUrl: String) = false
        override suspend fun previewBook(bookUrl: String) = PreviewOutcome.NotSynced
        override suspend fun preservedConflict(bookUrl: String, peerId: String?): SyncPreview? = null
        override suspend fun takeRemotePosition(
            bookUrl: String,
            atRevision: Long,
            peerId: String?,
            expectedAccountKey: String?,
        ) = ResolveOutcome.Done

        override suspend fun keepLocalPosition(bookUrl: String, peerId: String?) =
            ResolveOutcome.Done

        override suspend fun refreshUnresolved() = Unit
        override suspend fun identity(): SyncIdentity? = null
    }

    private inner class Recording : ServerSetup {
        override suspend fun connect(
            rawUrl: String,
            credentials: RemoteCredentials,
            allowHttp: Boolean,
        ): SetupResult {
            val username = (credentials as? RemoteCredentials.Basic)?.username.orEmpty()
            connects += Triple(rawUrl, username, allowHttp)
            return SetupResult.Success(
                ServerCapabilities(
                    baseUrl = rawUrl,
                    canDownload = true,
                    accountId = null,
                    displayName = "The Shelf",
                ),
            )
        }
    }

    private inner class ScriptedPairing : KosyncPairing {
        private val real = KosyncAccountRepository(db.kosyncPeerDao(), db.syncPeerStateDao())

        override suspend fun verify(
            url: String,
            username: String,
            password: String,
        ): KosyncProbe {
            kosyncPairings += url
            return KosyncProbe.Proved(
                ProvedKosyncPairing(
                    KosyncPeer(
                        baseUrl = if ("://" in url) url else "https://$url",
                        username = username,
                        keyCipher = KosyncPeer.seal(KosyncCredentials.keyFor(password)),
                        addedAt = 0L,
                    ),
                ),
            )
        }

        override suspend fun adopt(pairing: ProvedKosyncPairing) = real.adopt(pairing)

        override suspend fun forget() = real.forget()
    }
}
