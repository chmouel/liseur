package com.chmouel.liseur.data.remote

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.RemoteServerDao
import com.chmouel.liseur.data.library.BookRemoval
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
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

    private fun repository(dao: RemoteServerDao) = RemoteAccountRepository(
        dao = dao,
        bookDao = db.bookDao(),
        progressDao = db.readingProgressDao(),
        bookRemoval = BookRemoval(
            db.bookDao(),
            db.readingSessionDao(),
            db.syncPeerStateDao(),
            db.workIdentityDao(),
        ),
        seriesExtraDao = db.seriesExtraDao(),
        setups = mapOf(ServerKind.KOMGA to AlwaysConnects),
    )

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
