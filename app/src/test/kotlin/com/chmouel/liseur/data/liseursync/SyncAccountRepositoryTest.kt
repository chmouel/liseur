package com.chmouel.liseur.data.liseursync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.ReadingProgress
import com.chmouel.liseur.data.db.SyncPeerState
import com.chmouel.liseur.data.remote.DeviceIdentityRepository
import javax.crypto.KeyGenerator
import kotlinx.coroutines.test.runTest
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
 * Connecting, swapping and dropping a liseur-sync account.
 *
 * The rule under test throughout is the one that would be unforgivable
 * to get wrong: what a server had agreed is that server's and goes when
 * it does, but the reading itself is this device's and stays. Nobody
 * should lose their place in every book because they signed out of a
 * sync server.
 */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class SyncAccountRepositoryTest {

    private lateinit var db: LiseurDatabase
    private lateinit var repository: SyncAccountRepository

    @Before
    fun open() {
        // Robolectric has no Android Keystore, and how the secret was
        // stored is not what these tests are about.
        CredentialCipher.keyForTesting =
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(context, LiseurDatabase::class.java).build()
        repository = SyncAccountRepository(
            dao = db.syncAccountDao(),
            peerStateDao = db.syncPeerStateDao(),
            device = DeviceIdentityRepository(context),
            setup = FakeSetup(),
            now = { NOW },
        )
    }

    @After
    fun close() {
        db.close()
        CredentialCipher.keyForTesting = null
    }

    @Test
    fun `connecting keeps the token sealed and readable back`() = runTest {
        connect("ada")

        val stored = repository.current()!!
        assertEquals("https://sync.example", stored.baseUrl)
        assertEquals("ada", stored.username)
        // Sealed at rest: the secret must not be sitting in the column.
        assertFalse(stored.tokenCipher.contains("token-ada"))
        assertEquals("token-ada", CredentialCipher.decrypt(stored.tokenCipher))
        assertEquals("stats-ada", CredentialCipher.decrypt(stored.insightsTokenCipher!!))
        // A fresh account has agreed nothing, so it starts from the top.
        assertEquals(0L, stored.cursorSeq)
    }

    @Test
    fun `signing in as somebody else does not inherit what the last account agreed`() = runTest {
        connect("ada")
        val first = repository.current()!!.peerId
        agreedWith(first)

        connect("bob")

        // The old baseline described an agreement with a different
        // person; carrying it over would have the new account start from
        // a place it never confirmed.
        assertTrue(db.syncPeerStateDao().forPeer(first).isEmpty())
        assertEquals("bob", repository.current()!!.username)
    }

    @Test
    fun `reconnecting the same account keeps its baseline`() = runTest {
        connect("ada")
        val peer = repository.current()!!.peerId
        agreedWith(peer)

        // Re-entering the same details, which is what happens when a
        // token is replaced or the screen is used twice.
        connect("ada")

        assertEquals(1, db.syncPeerStateDao().forPeer(peer).size)
    }

    @Test
    fun `disconnecting forgets the agreement but never the reading`() = runTest {
        connect("ada")
        val peer = repository.current()!!.peerId
        agreedWith(peer)
        db.readingProgressDao().upsert(
            ReadingProgress(
                bookUrl = BOOK,
                locatorJson = "{}",
                totalProgression = 0.42,
                updatedAt = NOW,
            ),
        )

        repository.disconnect()

        assertNull(repository.current())
        assertTrue(db.syncPeerStateDao().forPeer(peer).isEmpty())
        val kept = db.readingProgressDao().get(BOOK)
        assertNotNull(kept)
        assertEquals(0.42, kept!!.totalProgression!!, 0.0001)
    }

    @Test
    fun `another peer's agreement survives this one being disconnected`() = runTest {
        connect("ada")
        val peer = repository.current()!!.peerId
        agreedWith(peer)
        agreedWith("komga|https://books.example|ada")

        repository.disconnect()

        assertEquals(1, db.syncPeerStateDao().forPeer("komga|https://books.example|ada").size)
    }

    @Test
    fun `a refused connection changes nothing`() = runTest {
        connect("ada")
        val peer = repository.current()!!.peerId
        agreedWith(peer)

        val result = repository.connect(
            rawUrl = "https://sync.example",
            username = "refused",
            password = "wrong",
        )

        assertEquals(SyncSetupResult.Failure(SyncSetupFailure.BadCredentials), result)
        assertEquals("ada", repository.current()!!.username)
        assertEquals(1, db.syncPeerStateDao().forPeer(peer).size)
    }

    private suspend fun connect(username: String) {
        repository.connect(
            rawUrl = "https://sync.example",
            username = username,
            password = "hunter2",
        )
    }

    /** A book this peer has confirmed a position for. */
    private suspend fun agreedWith(peerId: String) = db.syncPeerStateDao().upsert(
        SyncPeerState(
            bookUrl = BOOK,
            peerId = peerId,
            agreedProgression = 0.42,
        ),
    )

    /**
     * A server that says yes to everyone but "refused", so the tests can
     * be about what the repository writes down rather than about HTTP.
     */
    private class FakeSetup : SyncSetup {
        override suspend fun signIn(
            rawUrl: String,
            username: String,
            password: String,
            deviceName: String,
            wantInsights: Boolean,
            allowHttp: Boolean,
        ): SyncSetupResult = if (username == "refused") {
            SyncSetupResult.Failure(SyncSetupFailure.BadCredentials)
        } else {
            SyncSetupResult.Success(connectionFor(username))
        }

        override suspend fun verifyToken(
            rawUrl: String,
            username: String,
            token: String,
            deviceName: String,
            allowHttp: Boolean,
        ): SyncSetupResult = SyncSetupResult.Success(connectionFor(username))
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val BOOK = "content://media/books/1"

        fun connectionFor(username: String) = SyncConnection(
            baseUrl = "https://sync.example",
            username = username,
            token = "token-$username",
            insightsToken = "stats-$username",
            deviceName = "Test Phone",
        )
    }
}
