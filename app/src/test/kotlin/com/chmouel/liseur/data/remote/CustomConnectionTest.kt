package com.chmouel.liseur.data.remote

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.KosyncPeer
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.kosync.KosyncAccountRepository
import com.chmouel.liseur.data.kosync.KosyncCredentials
import com.chmouel.liseur.data.kosync.KosyncPairing
import com.chmouel.liseur.data.kosync.KosyncProbe
import com.chmouel.liseur.data.kosync.ProvedKosyncPairing
import com.chmouel.liseur.data.library.BookRemoval
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
 * A Custom connection: an OPDS catalog, a KOReader sync server, or one
 * of the two.
 *
 * The half-connected states are what these are about. Two addresses
 * mean two servers that can refuse independently, and either half
 * landing on its own is a connection the reader did not ask for — a
 * catalog with last week's pairing still attached, or a pairing with no
 * account behind it.
 */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class CustomConnectionTest {

    private lateinit var db: LiseurDatabase
    private lateinit var account: RemoteAccountRepository
    private var catalogRefusal: SetupFailure? = null
    private var kosyncAnswer: SetupFailure? = null

    @Before
    fun open() {
        CredentialCipher.keyForTesting =
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(context, LiseurDatabase::class.java).build()
        account = repository()
    }

    @After
    fun close() {
        db.close()
        CredentialCipher.keyForTesting = null
    }

    @Test
    fun `both halves land together`() = runTest {
        val result = connect(catalog = CATALOG, kosyncUrl = SYNC)

        assertTrue(result.connected)
        val server = db.remoteServerDao().get()!!
        assertEquals(ServerKind.CUSTOM, server.kind)
        assertEquals(CATALOG, server.catalogUrl)
        assertEquals(SYNC, db.kosyncPeerDao().get()?.baseUrl)
    }

    @Test
    fun `a catalog the server refused publishes nothing`() = runTest {
        catalogRefusal = SetupFailure.BadCredentials

        val result = connect(catalog = CATALOG, kosyncUrl = SYNC)

        assertEquals(SetupFailure.BadCredentials, result.catalog)
        assertNull(result.kosync)
        assertNull("a refused catalog still connected", db.remoteServerDao().get())
        assertNull("a refused catalog still paired", db.kosyncPeerDao().get())
    }

    @Test
    fun `a sync address the server refused publishes nothing either`() = runTest {
        // Including the catalog half, which answered perfectly well.
        // Half a connection is not what the reader filled in.
        kosyncAnswer = SetupFailure.BadCredentials

        val result = connect(catalog = CATALOG, kosyncUrl = SYNC)

        assertEquals(SetupFailure.BadCredentials, result.kosync)
        assertNull(result.catalog)
        assertNull(db.remoteServerDao().get())
    }

    @Test
    fun `a connection with a catalog and no sync address has no pairing`() = runTest {
        val result = connect(catalog = CATALOG, kosyncUrl = "")

        assertTrue(result.connected)
        assertNull(db.kosyncPeerDao().get())
        assertEquals(CATALOG, db.remoteServerDao().get()?.catalogUrl)
    }

    @Test
    fun `a connection with only a sync address catalogs nothing`() = runTest {
        // Null `catalog_url` is the whole point: every catalog path
        // reads it and finds nothing to do, rather than trying the base
        // URL and parsing a kosync endpoint as a feed.
        val result = connect(catalog = "", kosyncUrl = SYNC)

        assertTrue(result.connected)
        val server = db.remoteServerDao().get()!!
        assertNull(server.catalogUrl)
        assertEquals(SYNC, server.baseUrl)
        assertFalse(server.canDownload)
        assertNotNull(db.kosyncPeerDao().get())
    }

    @Test
    fun `neither address filled in is not a connection`() = runTest {
        val result = connect(catalog = "", kosyncUrl = "")

        assertFalse(result.connected)
        assertNull(db.remoteServerDao().get())
    }

    @Test
    fun `an empty sync address takes down the pairing left by the last server`() = runTest {
        // Without this, choosing a catalog-only Custom after Grimmory
        // would leave Grimmory's pairing running against a field the
        // reader deliberately left blank.
        account.connectGrimmory(GRIMMORY, "ada", "pw")
        pairKosync()

        connect(catalog = CATALOG, kosyncUrl = "")

        assertNull(db.kosyncPeerDao().get())
    }

    @Test
    fun `a filled sync address replaces the pairing left by the last server`() = runTest {
        account.connectGrimmory(GRIMMORY, "ada", "pw")
        pairKosync()

        connect(catalog = CATALOG, kosyncUrl = SYNC, kosyncUsername = "bob")

        assertEquals(SYNC, db.kosyncPeerDao().get()?.baseUrl)
        assertEquals("bob", db.kosyncPeerDao().get()?.username)
    }

    @Test
    fun `a failed custom connection leaves the server that was already there`() = runTest {
        account.connectGrimmory(GRIMMORY, "ada", "pw")
        pairKosync()
        kosyncAnswer = SetupFailure.BadCredentials

        connect(catalog = CATALOG, kosyncUrl = SYNC)

        assertEquals(ServerKind.GRIMMORY, db.remoteServerDao().get()?.kind)
        assertNotNull("a failed attempt took the working pairing down", db.kosyncPeerDao().get())
    }

    @Test
    fun `an open catalog is asked anonymously, not reported as broken`() = runTest {
        // A null credential already means "the stored secret cannot be
        // read". An open catalog spelled that way would be reported as a
        // lost account for ever.
        connect(catalog = CATALOG, kosyncUrl = "", username = "", password = "")

        assertEquals(RemoteCredentials.Anonymous, db.remoteServerDao().get()?.credentials)
    }

    @Test
    fun `a catalog behind a password is signed for`() = runTest {
        connect(catalog = CATALOG, kosyncUrl = "", username = "ada", password = "secret")

        assertEquals(
            RemoteCredentials.Basic("ada", "secret"),
            db.remoteServerDao().get()?.credentials,
        )
    }

    @Test
    fun `a sync-only connection stores no catalog password`() = runTest {
        // kosync keeps a derived key and never a password, and there is
        // no catalog to sign for.
        connect(catalog = "", kosyncUrl = SYNC)

        assertEquals(RemoteCredentials.Anonymous, db.remoteServerDao().get()?.credentials)
    }

    @Test
    fun `a custom connection carries no position sync of its own`() = runTest {
        connect(catalog = CATALOG, kosyncUrl = SYNC)

        assertFalse(db.remoteServerDao().get()!!.canSync)
    }

    @Test
    fun `two custom catalogs are two accounts`() = runTest {
        connect(catalog = CATALOG, kosyncUrl = "")
        val first = db.remoteServerDao().get()!!.accountKey

        connect(catalog = "https://other.example/opds", kosyncUrl = "")

        assertTrue(first != db.remoteServerDao().get()!!.accountKey)
    }

    private suspend fun connect(
        catalog: String,
        kosyncUrl: String,
        username: String = "",
        password: String = "",
        kosyncUsername: String = "ada",
    ) = account.connectCustom(
        catalogUrl = catalog,
        username = username,
        password = password,
        kosyncUrl = kosyncUrl,
        kosyncUsername = kosyncUsername,
        kosyncPassword = "pw",
    )

    private suspend fun pairKosync() {
        db.kosyncPeerDao().upsert(
            KosyncPeer(
                baseUrl = "$GRIMMORY/api/koreader",
                username = "ada",
                keyCipher = KosyncPeer.seal(KosyncCredentials.keyFor("pw")),
                addedAt = 0L,
            ),
        )
    }

    /** A kosync server that agrees, or refuses, on command. */
    private inner class ScriptedPairing : KosyncPairing {
        private val real = KosyncAccountRepository(db.kosyncPeerDao(), db.syncPeerStateDao())

        override suspend fun verify(url: String, username: String, password: String): KosyncProbe {
            kosyncAnswer?.let { return KosyncProbe.Failure(it) }
            return KosyncProbe.Proved(
                ProvedKosyncPairing(
                    KosyncPeer(
                        baseUrl = url,
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

    private fun repository() = RemoteAccountRepository(
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
        kosync = { ScriptedPairing() },
        setups = mapOf(
            ServerKind.CUSTOM to object : ServerSetup {
                override suspend fun connect(
                    rawUrl: String,
                    credentials: RemoteCredentials,
                    allowHttp: Boolean,
                ): SetupResult = catalogRefusal
                    ?.let { SetupResult.Failure(it) }
                    ?: success(rawUrl)
            },
            ServerKind.GRIMMORY to object : ServerSetup {
                override suspend fun connect(
                    rawUrl: String,
                    credentials: RemoteCredentials,
                    allowHttp: Boolean,
                ): SetupResult = success(rawUrl)
            },
        ),
    )

    private companion object {
        const val CATALOG = "https://books.example/opds"
        const val SYNC = "https://sync.example/kosync"
        const val GRIMMORY = "https://grimmory.example"

        fun success(url: String = CATALOG) = SetupResult.Success(
            ServerCapabilities(
                baseUrl = url,
                canDownload = true,
                accountId = null,
                displayName = "The Shelf",
            ),
        )
    }
}
