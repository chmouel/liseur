package com.chmouel.liseur.data.remote

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.AnnotationSync
import com.chmouel.liseur.data.db.KosyncPeer
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.RemoteServerDao
import com.chmouel.liseur.data.db.WorkAlias
import com.chmouel.liseur.data.kosync.KosyncAccountRepository
import com.chmouel.liseur.data.kosync.KosyncCredentials
import com.chmouel.liseur.data.library.BookRemoval
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.KeyGenerator

/**
 * Handing out the account's credentials to callers that cannot suspend.
 *
 * This is the path Coil's cover requests take. It went wrong once
 * already: the answer was cached as a side effect of an unrelated call,
 * so when the catalog and the download worker stopped making that call
 * every Komga cover started coming back unauthenticated. The first test
 * here is that regression, and most of the rest are about the cache not
 * outliving what it describes.
 */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class RemoteAccountRepositoryTest {

    private lateinit var db: LiseurDatabase
    private lateinit var account: RemoteAccountRepository

    @Before
    fun open() {
        // Robolectric has no Android Keystore, and how the key was
        // stored is not what these tests are about.
        CredentialCipher.keyForTesting =
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(context, LiseurDatabase::class.java).build()
        account = repository(db.remoteServerDao())
    }

    @After
    fun close() {
        db.close()
        CredentialCipher.keyForTesting = null
    }

    @Test
    fun `a komga cover is signed without anything having asked for the account first`() = runTest {
        connectKomga()

        // Deliberately not calling credentials() or current() beforehand.
        // That is the whole regression: covers must not depend on some
        // other part of the app having warmed a cache.
        val credentials = account.credentialsForUrl("$BASE/api/v1/books/1/thumbnail")

        assertEquals(RemoteCredentials.ApiKey(KEY), credentials)
    }

    @Test
    fun `another server is never handed the key`() = runTest {
        connectKomga()

        assertNull(account.credentialsForUrl("https://books.example.evil.test/api/v1/books/1"))
        assertNull(account.credentialsForUrl("http://books.example/api/v1/books/1"))
        assertNull(account.credentialsForUrl("https://books.example:8443/api/v1/books/1"))
    }

    @Test
    fun `disconnecting stops the credentials immediately`() = runTest {
        connectKomga()
        assertTrue(account.credentialsForUrl("$BASE/cover") != null)

        account.disconnect()

        assertNull(account.credentialsForUrl("$BASE/cover"))
    }

    @Test
    fun `signing in as someone else stops handing out the old key`() = runTest {
        connectKomga()
        assertEquals(RemoteCredentials.ApiKey(KEY), account.credentialsForUrl("$BASE/cover"))

        account.connectKomga(BASE, "a-rotated-key")

        assertEquals(
            RemoteCredentials.ApiKey("a-rotated-key"),
            account.credentialsForUrl("$BASE/cover"),
        )
    }

    @Test
    fun `an account whose secret cannot be read is not signed with`() = runTest {
        connectKomga()
        CredentialCipher.keyForTesting =
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        assertTrue(account.forgetUnreadableAccount())

        assertNull(account.credentialsForUrl("$BASE/cover"))
        assertNull(db.remoteServerDao().get())
    }

    @Test
    fun `a burst of covers reads the account once`() = runTest {
        val counting = CountingDao(db.remoteServerDao())
        val repository = repository(counting)
        connectKomga(repository)
        counting.reads = 0

        withContext(Dispatchers.IO) {
            List(8) { async { repository.credentialsForUrl("$BASE/cover") } }.map { it.await() }
        }.forEach { assertEquals(RemoteCredentials.ApiKey(KEY), it) }

        assertEquals(1, counting.reads)
    }

    @Test
    fun `a read straddling a disconnect does not publish what it read`() = runTest {
        val blocking = BlockingDao(db.remoteServerDao())
        val repository = repository(blocking)
        connectKomga(repository)

        // Hold the very next read inside the DAO, disconnect underneath
        // it, then let it finish. What it loaded describes an account
        // that no longer exists, so it must not become the answer.
        blocking.holdNextRead()
        val reading = async(Dispatchers.IO) { repository.credentialsForUrl("$BASE/cover") }
        blocking.reachedRead.await()
        repository.disconnect()
        blocking.release()

        assertNull(reading.await())
        assertNull(repository.credentialsForUrl("$BASE/cover"))
    }

    private suspend fun connectKomga(
        repository: RemoteAccountRepository = account,
        key: String = KEY,
    ) {
        val result = repository.connectKomga(BASE, key)
        assertTrue("expected the fake setup to succeed, got $result", result is SetupResult.Success)
    }

    @Test
    fun `a grimmory password survives being written down and read back`() = runTest {
        // The regression for an account that connects happily and is
        // unusable from the next refresh on. Grimmory signs every
        // request with this password -- there is no token to fall back
        // to -- so a row that comes back without it is an account that
        // has to be set up again from nothing.
        account.connectGrimmory(BASE, "liseur-opds", "opds-secret")

        // Read through a repository of its own, so the answer cannot
        // come from a cache warmed while connecting.
        val reloaded = repository(db.remoteServerDao())

        assertEquals(
            RemoteCredentials.Basic("liseur-opds", "opds-secret"),
            reloaded.credentialsForUrl("$BASE/komga/api/v1/books/1/thumbnail"),
        )
        assertEquals(ServerKind.GRIMMORY, reloaded.current()?.kind)
    }

    @Test
    fun `grimmory never offers to keep a reader's place`() = runTest {
        // The shim has no progression route at all. Saying so on the row
        // is what keeps sync from being offered and then failing.
        account.connectGrimmory(BASE, "liseur-opds", "opds-secret")

        assertFalse(db.remoteServerDao().get()!!.canSync)
    }

    @Test
    fun `refreshing an https account does not authorise a plain http retry`() = runTest {
        // A refresh runs unattended, so it cannot be the thing that
        // decides to send the password in the clear. Every kind here
        // signs its requests with a stored secret and every setup client
        // will fall back to http when told it may, so an https server
        // that is merely down for the afternoon would leak it.
        val watching = RecordsAllowHttp()
        val repository = repositoryUsing(db.remoteServerDao(), watching)
        repository.connectGrimmory(BASE, "liseur-opds", "opds-secret")

        repository.refreshCapabilities()

        assertEquals(listOf(false, false), watching.allowed)
    }

    @Test
    fun `refreshing an http account still allows plain http`() = runTest {
        // The other half: an account stored as http:// has already been
        // agreed to, and refusing it here would break every refresh on a
        // home server that has no certificate.
        val watching = RecordsAllowHttp()
        val repository = repositoryUsing(db.remoteServerDao(), watching)
        repository.connectGrimmory("http://books.example", "liseur-opds", "opds-secret", allowHttp = true)

        repository.refreshCapabilities()

        assertEquals(listOf(true, true), watching.allowed)
    }

    /** Remembers what each connect was allowed to do. */
    private class RecordsAllowHttp : ServerSetup {
        val allowed = mutableListOf<Boolean>()

        override suspend fun connect(
            rawUrl: String,
            credentials: RemoteCredentials,
            allowHttp: Boolean,
        ): SetupResult {
            allowed += allowHttp
            return SetupResult.Success(
                ServerCapabilities(
                    baseUrl = rawUrl,
                    canDownload = true,
                    accountId = "user-1",
                    displayName = "reader",
                ),
            )
        }
    }

    private fun repository(dao: RemoteServerDao) = repositoryUsing(dao, AlwaysConnects)

    private fun repositoryUsing(dao: RemoteServerDao, setup: ServerSetup) = RemoteAccountRepository(
        dao = dao,
        bookDao = db.bookDao(),
        progressDao = db.readingProgressDao(),
        bookRemoval = BookRemoval(
            db.bookDao(),
            db.readingSessionDao(),
            db.syncPeerStateDao(),
            db.workIdentityDao(),
            db.readingProgressDao(),
            db.annotationDao(),
            db.annotationSyncDao(),
        ),
        seriesExtraDao = db.seriesExtraDao(),
        peerStateDao = db.syncPeerStateDao(),
        kosync = { kosync() },
        setups = mapOf(
            ServerKind.KOMGA to setup,
            ServerKind.GRIMMORY to setup,
        ),
    )

    private fun kosync() = KosyncAccountRepository(
        dao = db.kosyncPeerDao(),
        peerStateDao = db.syncPeerStateDao(),
    )

    private suspend fun pairKosync() {
        db.kosyncPeerDao().upsert(
            KosyncPeer(
                baseUrl = "https://books.example/api/koreader",
                username = "ada",
                keyCipher = KosyncPeer.seal(KosyncCredentials.keyFor("pw")),
                addedAt = 0L,
            ),
        )
    }

    /**
     * The pairing belongs to the server it was made next to. Connecting
     * one that carries positions itself would otherwise leave it running
     * out of sight, against a library it knows nothing about.
     */
    @Test
    fun `connecting a server that cannot host the pairing puts it down`() = runTest {
        account.connectGrimmory(BASE, "ada", "pw")
        pairKosync()

        connectKomga()

        assertNull(db.kosyncPeerDao().get())
    }

    /**
     * Only once a connection has landed. An attempt that fails leaves
     * the old server standing, and taking the pairing with it would
     * strand a working Grimmory setup on a typo.
     */
    @Test
    fun `a failed connection leaves the pairing alone`() = runTest {
        account.connectGrimmory(BASE, "ada", "pw")
        pairKosync()

        val refusing = repositoryUsing(db.remoteServerDao(), NeverConnects)
        val result = refusing.connectKomga(BASE, KEY)

        assertTrue(result is SetupResult.Failure)
        assertEquals("ada", db.kosyncPeerDao().get()?.username)
        assertEquals(ServerKind.GRIMMORY, db.remoteServerDao().get()?.kind)
    }

    @Test
    fun `reconnecting grimmory keeps the pairing`() = runTest {
        account.connectGrimmory(BASE, "ada", "pw")
        pairKosync()

        account.connectGrimmory(BASE, "ada", "pw")

        assertEquals("ada", db.kosyncPeerDao().get()?.username)
    }

    /** A server that refuses, so a connection can be seen not to land. */
    private object NeverConnects : ServerSetup {
        override suspend fun connect(
            rawUrl: String,
            credentials: RemoteCredentials,
            allowHttp: Boolean,
        ): SetupResult = SetupResult.Failure(SetupFailure.BadCredentials)
    }

    /** A server that is always there, so the tests are about the account. */
    private object AlwaysConnects : ServerSetup {
        override suspend fun connect(
            rawUrl: String,
            credentials: RemoteCredentials,
            allowHttp: Boolean,
        ): SetupResult = SetupResult.Success(
            ServerCapabilities(
                baseUrl = rawUrl,
                canDownload = true,
                accountId = "user-1",
                displayName = "reader",
            ),
        )
    }

    /**
     * A liseur-sync setup whose answers are scripted: each connect mints
     * a fresh device id (as a real token rotation does) but keeps the
     * account id it was given.
     */
    private class RotatingLiseurSync(private val accountId: String) : ServerSetup {
        var mints = 0

        override suspend fun connect(
            rawUrl: String,
            credentials: RemoteCredentials,
            allowHttp: Boolean,
        ): SetupResult = SetupResult.Success(
            ServerCapabilities(
                baseUrl = rawUrl,
                canDownload = true,
                accountId = "device-${++mints}",
                displayName = "reader",
                liseurToken = "token-$mints",
                liseurAccountId = accountId,
            ),
        )
    }

    @Test
    fun `a rotated liseur-sync token is a refresh, not a new account`() = runTest {
        val setup = RotatingLiseurSync(accountId = "acc-1")
        val repository = repository(db.remoteServerDao(), setup)
        repository.connectLiseurSyncToken(BASE, "token-1")
        db.remoteServerDao().setSyncCursor(41)
        val addedAt = db.remoteServerDao().get()!!.addedAt

        // The credential is replaced, as after a revocation. The device
        // id changes; the account does not.
        repository.connectLiseurSyncToken(BASE, "token-2")

        val stored = db.remoteServerDao().get()!!
        assertEquals("device-2", stored.accountId)
        assertEquals(41, stored.syncCursorSeq)
        assertEquals(addedAt, stored.addedAt)
        assertEquals(
            RemoteCredentials.Bearer("token-2"),
            account.credentialsForUrl("$BASE/v1/libraries"),
        )
    }

    @Test
    fun `disconnect clears durable locator state for the liseur sync account`() = runTest {
        val repository = repository(db.remoteServerDao(), RotatingLiseurSync(accountId = "acc-1"))
        repository.connectLiseurSyncToken(BASE, "token-1")
        val peer = requireNotNull(db.remoteServerDao().get()).accountKey
        db.syncPeerStateDao().persistPending(
            bookUrl = "file:///book.epub",
            peerId = peer,
            progression = 0.8,
            status = "Reading",
            remoteUpdatedAt = 1_000,
            locatorJson = """{"href":"chapter","locations":{"liseurAnchor":1}}""",
            editionSha = "sha",
        )

        repository.disconnect()

        assertNull(db.syncPeerStateDao().get("file:///book.epub", peer))
    }

    @Test
    fun `a different account behind the same address still switches`() = runTest {
        val repository = repository(db.remoteServerDao(), RotatingLiseurSync(accountId = "acc-1"))
        repository.connectLiseurSyncToken(BASE, "token-1")
        db.remoteServerDao().setSyncCursor(41)

        val other = RemoteAccountRepository(
            dao = db.remoteServerDao(),
            bookDao = db.bookDao(),
            progressDao = db.readingProgressDao(),
            bookRemoval = BookRemoval(
                db.bookDao(),
                db.readingSessionDao(),
                db.syncPeerStateDao(),
                db.workIdentityDao(),
                db.readingProgressDao(),
                db.annotationDao(),
                db.annotationSyncDao(),
            ),
            seriesExtraDao = db.seriesExtraDao(),
            peerStateDao = db.syncPeerStateDao(),
            identityDao = db.workIdentityDao(),
            setups = mapOf(ServerKind.LISEUR_SYNC to RotatingLiseurSync(accountId = "acc-2")),
        )
        other.connectLiseurSyncToken(BASE, "token-x")

        // A genuinely different account starts at the beginning of its
        // own log.
        assertEquals(0, db.remoteServerDao().get()!!.syncCursorSeq)
    }

    /**
     * A liseur-sync server as it stood before and after it learnt to say
     * who the account is: the first connect reports only a device id, the
     * next ones add `account_id`. Reconnecting keeps the device id when
     * the repository offers it back, as a real server does.
     */
    private class UpgradingLiseurSync : ServerSetup {
        var connects = 0
        var offered: String? = null

        override suspend fun connect(rawUrl: String, credentials: RemoteCredentials, allowHttp: Boolean) =
            answer(rawUrl, keep = null)

        override suspend fun reconnect(
            rawUrl: String,
            credentials: RemoteCredentials,
            allowHttp: Boolean,
            prior: PriorConnection,
        ): SetupResult {
            offered = prior.deviceId
            return answer(rawUrl, keep = prior.deviceId)
        }

        private fun answer(rawUrl: String, keep: String?): SetupResult {
            connects++
            return SetupResult.Success(
                ServerCapabilities(
                    baseUrl = rawUrl,
                    canDownload = true,
                    accountId = keep ?: "device-$connects",
                    displayName = "ada",
                    liseurToken = "token-$connects",
                    liseurAccountId = if (connects > 1) "acc-1" else null,
                ),
            )
        }
    }

    @Test
    fun `reconnecting offers the stored device id back to the same server`() = runTest {
        val setup = UpgradingLiseurSync()
        val repository = repository(db.remoteServerDao(), setup)
        repository.connectLiseurSync(BASE, "ada", "pw")

        repository.connectLiseurSync(BASE, "ada", "pw")

        assertEquals("device-1", setup.offered)
        assertEquals("device-1", db.remoteServerDao().get()!!.accountId)
    }

    @Test
    fun `the first server that names the account moves sync state to the new key`() = runTest {
        // Before the server reported account ids the key was spelled with
        // the device id. The upgrade must not read as an account switch,
        // and every table keyed by the old spelling has to follow.
        val setup = UpgradingLiseurSync()
        val repository = fullRepository(db.remoteServerDao(), setup)
        repository.connectLiseurSync(BASE, "ada", "pw")
        val oldKey = db.remoteServerDao().get()!!.accountKey
        assertEquals("liseursync|$BASE|device-1", oldKey)
        db.remoteServerDao().setSyncCursor(41)
        db.syncPeerStateDao().persistPending(
            bookUrl = "file:///book.epub", peerId = oldKey, progression = 0.8, status = "Reading",
            remoteUpdatedAt = 1_000, locatorJson = """{"href":"c"}""", editionSha = "sha",
        )
        db.workIdentityDao().upsert(
            WorkAlias(
                bookUrl = "file:///book.epub", peerId = oldKey, workId = "w-1",
                confidence = "high", resolvedAt = 1,
            ),
        )
        db.annotationSyncDao().upsert(
            AnnotationSync(
                id = "a-1", peerId = oldKey, bookId = "file:///book.epub", workId = "w-1",
                pendingKind = "write", pendingJson = """{"id":"a-1"}""",
            ),
        )
        db.readingProgressDao().markDirtyFor(listOf("file:///book.epub"), oldKey)

        repository.connectLiseurSync(BASE, "ada", "pw")

        val stored = db.remoteServerDao().get()!!
        val newKey = stored.accountKey
        assertEquals("liseursync|$BASE|acc-1", newKey)
        assertEquals(41, stored.syncCursorSeq)
        assertEquals(0.8, db.syncPeerStateDao().get("file:///book.epub", newKey)!!.pendingProgression)
        assertNull(db.syncPeerStateDao().get("file:///book.epub", oldKey))
        assertEquals("w-1", db.workIdentityDao().alias("file:///book.epub", newKey)!!.workId)
        assertEquals("""{"id":"a-1"}""", db.annotationSyncDao().get(newKey, "a-1")!!.pendingJson)
        assertEquals(0, db.annotationSyncDao().countForPeer(oldKey))

        // From here on the key is stable across token rotations.
        repository.connectLiseurSync(BASE, "ada", "pw")
        assertEquals(newKey, db.remoteServerDao().get()!!.accountKey)
    }

    private fun fullRepository(dao: RemoteServerDao, liseurSyncSetup: ServerSetup) = RemoteAccountRepository(
        dao = dao,
        bookDao = db.bookDao(),
        progressDao = db.readingProgressDao(),
        bookRemoval = BookRemoval(
            db.bookDao(),
            db.readingSessionDao(),
            db.syncPeerStateDao(),
            db.workIdentityDao(),
            db.readingProgressDao(),
            db.annotationDao(),
            db.annotationSyncDao(),
        ),
        seriesExtraDao = db.seriesExtraDao(),
        peerStateDao = db.syncPeerStateDao(),
        identityDao = db.workIdentityDao(),
        sessionDao = db.readingSessionDao(),
        annotationSyncDao = db.annotationSyncDao(),
        uploadRefusalDao = db.uploadRefusalDao(),
        setups = mapOf(ServerKind.LISEUR_SYNC to liseurSyncSetup),
    )

    private fun repository(
        dao: RemoteServerDao,
        liseurSyncSetup: ServerSetup,
    ) = RemoteAccountRepository(
        dao = dao,
        bookDao = db.bookDao(),
        progressDao = db.readingProgressDao(),
        bookRemoval = BookRemoval(
            db.bookDao(),
            db.readingSessionDao(),
            db.syncPeerStateDao(),
            db.workIdentityDao(),
            db.readingProgressDao(),
            db.annotationDao(),
            db.annotationSyncDao(),
        ),
        seriesExtraDao = db.seriesExtraDao(),
        peerStateDao = db.syncPeerStateDao(),
        identityDao = db.workIdentityDao(),
        setups = mapOf(ServerKind.LISEUR_SYNC to liseurSyncSetup),
    )

    private open class DelegatingDao(private val dao: RemoteServerDao) :
        RemoteServerDao by dao

    private class CountingDao(private val dao: RemoteServerDao) : DelegatingDao(dao) {
        @Volatile
        var reads = 0

        override suspend fun get(id: Long): RemoteServer? {
            reads++
            return dao.get(id)
        }
    }

    /** Lets a test stop time inside a single read. */
    private class BlockingDao(private val dao: RemoteServerDao) : DelegatingDao(dao) {
        var reachedRead = CompletableDeferred<Unit>()
        private val gate = AtomicReference<CompletableDeferred<Unit>?>()
        private val holding = AtomicBoolean(false)

        fun holdNextRead() {
            reachedRead = CompletableDeferred()
            gate.set(CompletableDeferred())
            holding.set(true)
        }

        fun release() {
            gate.get()?.complete(Unit)
        }

        override suspend fun get(id: Long): RemoteServer? {
            if (!holding.compareAndSet(true, false)) return dao.get(id)
            val row = dao.get(id)
            reachedRead.complete(Unit)
            gate.get()?.await()
            return row
        }
    }

    private companion object {
        const val BASE = "https://books.example"
        const val KEY = "a-secret-api-key"
    }
}
