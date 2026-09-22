package com.chmouel.liseur.data.bookorbit

import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.RemoteServerDao
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteHttp
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.remote.SyncFailure
import java.net.InetAddress
import javax.crypto.KeyGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
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
 * The BookOrbit session against a real socket.
 *
 * The store behind it is a fake rather than Room because what these
 * tests are about is the decisions: which token is spent, when a renewal
 * is written, and — the one that matters most — when it is *not*.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BookOrbitSessionTest {

    private lateinit var server: MockWebServer

    @Before
    fun start() {
        CredentialCipher.keyForTesting =
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun stop() {
        server.close()
        CredentialCipher.keyForTesting = null
    }

    private fun address() = "http://127.0.0.1:${server.port}"

    private fun tokens(access: String, refresh: String) = MockResponse(
        body = """
        {"accessToken":"$access","accessTokenExpiresAt":"2099-01-01T00:00:00.000Z",
         "refreshToken":"$refresh","refreshTokenExpiresAt":"2099-01-08T00:00:00.000Z",
         "sessionId":5}
        """.trimIndent(),
    )

    private fun account(
        access: String = "old-access",
        accessExpires: Long = 0,
        refresh: String = "refresh-one",
        epoch: Long = 7,
    ) = RemoteServer(
        kind = ServerKind.BOOKORBIT,
        baseUrl = address(),
        username = "reader",
        passwordCipher = null,
        apiKeyCipher = null,
        accountId = "1",
        userId = null,
        koboTokenCipher = null,
        canDownload = true,
        addedAt = 1L,
        catalogSyncedAt = null,
        positionSyncedAt = null,
        syncToken = null,
        orbitAccessCipher = RemoteServer.seal(access),
        orbitRefreshCipher = RemoteServer.seal(refresh),
        orbitAccessExpires = accessExpires,
        orbitEpoch = epoch,
    )

    @Test
    fun `an expired access token is renewed and written down`() = runBlocking {
        val dao = FakeServerDao(account())
        server.enqueue(tokens("new-access", "refresh-two"))
        val session = BookOrbitSession(dao, RemoteHttp(), now = { 1_000L })

        assertEquals("new-access", session.token(context(dao.row!!)))
        assertEquals("refresh-two", dao.row!!.orbitRefreshCipher?.let(CredentialCipher::decrypt))
        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertEquals("/api/v1/auth/refresh", request.url.encodedPath)
    }

    @Test
    fun `a token that is still good is not renewed`() = runBlocking {
        val dao = FakeServerDao(account(accessExpires = 9_999_999L))
        val session = BookOrbitSession(dao, RemoteHttp(), now = { 1_000L })

        assertEquals("old-access", session.token(context(dao.row!!)))
        assertEquals(0, server.requestCount)
    }

    /**
     * The one that matters: a renewal that lands after the reader has
     * moved on must not put its tokens onto whoever is connected now.
     *
     * The conditional write is what decides this, and a store that
     * refuses it stands in for the account having changed underneath the
     * request.
     */
    @Test
    fun `a renewal that can no longer claim the row is refused`() = runBlocking {
        val dao = FakeServerDao(account(epoch = 7)).apply { claimOnRotate = false }
        val session = BookOrbitSession(dao, RemoteHttp(), now = { 1_000L })
        server.enqueue(tokens("new-access", "refresh-two"))

        val failure = runCatching { session.token(context(dao.row!!)) }.exceptionOrNull()

        assertTrue("expected a refusal, got $failure", failure is RemoteHttpFailure)
        assertEquals(SyncFailure.Unauthorised, (failure as RemoteHttpFailure).reason)
        // Nothing was written over the session that owns the row now.
        assertEquals("refresh-one", dao.row!!.orbitRefreshCipher?.let(CredentialCipher::decrypt))
    }

    @Test
    fun `only the token that was refused is thrown away`() = runBlocking {
        val dao = FakeServerDao(account(access = "stale", accessExpires = 9_999_999L))
        val session = BookOrbitSession(dao, RemoteHttp(), now = { 1_000L })
        server.enqueue(tokens("fresh", "refresh-two"))

        val seen = mutableListOf<String>()
        val result = session.authorized(context(dao.row!!)) { bearer ->
            seen += bearer.token
            if (bearer.token == "stale") throw RemoteHttpFailure(SyncFailure.Unauthorised)
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(listOf("stale", "fresh"), seen)
    }

    @Test
    fun `a failure that is not an authentication one is not retried`() = runBlocking {
        val dao = FakeServerDao(account(access = "stale", accessExpires = 9_999_999L))
        val session = BookOrbitSession(dao, RemoteHttp(), now = { 1_000L })

        var calls = 0
        val failure = runCatching {
            session.authorized(context(dao.row!!)) { _ ->
                calls++
                throw RemoteHttpFailure(SyncFailure.NotFound)
            }
        }.exceptionOrNull()

        assertEquals(SyncFailure.NotFound, (failure as RemoteHttpFailure).reason)
        assertEquals(1, calls)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `there is no account to sign for`() = runBlocking {
        val dao = FakeServerDao(null)
        val session = BookOrbitSession(dao, RemoteHttp(), now = { 1_000L })

        val missing = BookOrbitRequestContext("missing", 1, "https://missing.example")
        val failure = runCatching { session.token(missing) }.exceptionOrNull()

        assertEquals(SyncFailure.Unauthorised, (failure as RemoteHttpFailure).reason)
    }

    @Test
    fun `a request from the previous account never receives the current token`() = runBlocking {
        val old = account(epoch = 7)
        val oldContext = context(old)
        val dao = FakeServerDao(
            old.copy(
                accountId = "2",
                username = "someone-else",
                orbitEpoch = 8,
                orbitAccessCipher = RemoteServer.seal("their-access"),
                orbitRefreshCipher = RemoteServer.seal("their-refresh"),
            ),
        )
        val session = BookOrbitSession(dao, RemoteHttp(), now = { 1_000L })

        val failure = runCatching { session.token(oldContext) }.exceptionOrNull()

        assertEquals(SyncFailure.Unauthorised, (failure as RemoteHttpFailure).reason)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a download renews once after a 401`() = runBlocking {
        val dao = FakeServerDao(account(access = "stale", accessExpires = 9_999_999L))
        val session = BookOrbitSession(dao, RemoteHttp(), now = { 1_000L })
        val context = context(dao.row!!)
        val auth = BookOrbitNetworkAuth(session)
        val client = OkHttpClient.Builder()
            .addInterceptor(auth)
            .build()
        server.enqueue(MockResponse(code = 401))
        server.enqueue(tokens("fresh", "refresh-two"))
        server.enqueue(MockResponse(body = "book"))

        val response = client.newCall(
            Request.Builder()
                .url("${address()}/api/v1/books/files/1/download")
                .tag(BookOrbitRequestContext::class.java, context)
                .build(),
        ).execute()

        response.use { assertEquals("requests=${server.requestCount}", 200, it.code) }
        assertEquals("Bearer stale", server.takeRequest().headers["Authorization"])
        assertEquals("/api/v1/auth/refresh", server.takeRequest().url.encodedPath)
        assertEquals("Bearer fresh", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `an untagged cover renews for its scoped account`() = runBlocking {
        val dao = FakeServerDao(account(access = "stale", accessExpires = 9_999_999L))
        val session = BookOrbitSession(dao, RemoteHttp(), now = { 1_000L })
        val auth = BookOrbitNetworkAuth(session, inferCurrentContext = true)
        val client = OkHttpClient.Builder().addInterceptor(auth).build()
        val remoteId = BookOrbitScope.remoteId(address(), "1", 113)
        val url = "${address()}${BookOrbitUrl.coverHref(113, remoteId)}"
        server.enqueue(MockResponse(code = 401))
        server.enqueue(tokens("fresh", "refresh-two"))
        server.enqueue(MockResponse(body = "cover"))

        client.newCall(Request.Builder().url(url).build()).execute().use {
            assertEquals(200, it.code)
        }

        assertEquals("Bearer stale", server.takeRequest().headers["Authorization"])
        assertEquals("/api/v1/auth/refresh", server.takeRequest().url.encodedPath)
        assertEquals("Bearer fresh", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `a cover scoped to the previous account is sent no token`() = runBlocking {
        val oldRemoteId = BookOrbitScope.remoteId(address(), "1", 113)
        val current = account(
            access = "their-access",
            accessExpires = 9_999_999L,
            refresh = "their-refresh",
            epoch = 8,
        ).copy(accountId = "2", username = "other")
        val dao = FakeServerDao(current)
        val session = BookOrbitSession(dao, RemoteHttp(), now = { 1_000L })
        val auth = BookOrbitNetworkAuth(session, inferCurrentContext = true)
        val client = OkHttpClient.Builder().addInterceptor(auth).build()
        server.enqueue(MockResponse(body = "public-or-refused"))

        client.newCall(
            Request.Builder()
                .url("${address()}${BookOrbitUrl.coverHref(113, oldRemoteId)}")
                .build(),
        ).execute().close()

        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `a cover is signed only while the token is good`() = runBlocking {
        val live = FakeServerDao(account(accessExpires = 9_999_999L))
        val priming = BookOrbitSession(live, RemoteHttp(), now = { 1_000L })
        priming.prime()
        assertNotNull(priming.cachedBearer())

        val expired = FakeServerDao(account(accessExpires = 500L))
        val stale = BookOrbitSession(expired, RemoteHttp(), now = { 1_000L })
        stale.prime()
        assertNull(stale.cachedBearer())
    }

    @Test
    fun `a bearer credential is what the network layer gets`() = runBlocking {
        val dao = FakeServerDao(account(accessExpires = 9_999_999L))
        val session = BookOrbitSession(dao, RemoteHttp(), now = { 1_000L })

        session.prime()
        val credential = session.cachedBearer()
        assertTrue(credential is RemoteCredentials.Bearer)
        assertEquals("old-access", (credential as RemoteCredentials.Bearer).token)
    }

    /**
     * Enough of the row store for a session test.
     *
     * The conditional write is implemented exactly as the Room query is,
     * because a fake that always succeeded would make the test that
     * matters pass for the wrong reason.
     */
    private class FakeServerDao(var row: RemoteServer?) : RemoteServerDao {
        /** When false, the conditional write refuses, as it does after a switch. */
        var claimOnRotate: Boolean = true

        override fun observe(id: Long): Flow<RemoteServer?> = flowOf(row)
        override suspend fun get(id: Long): RemoteServer? = row
        override suspend fun upsert(server: RemoteServer) { row = server }
        override suspend fun setKoboTokenCipher(cipher: String?, id: Long) = Unit
        override suspend fun setCatalogSyncedAt(at: Long, id: Long) = Unit
        override suspend fun setPositionSyncedAt(at: Long, id: Long) = Unit
        override suspend fun setSyncToken(token: String?, id: Long) = Unit
        override suspend fun setCanDownload(allowed: Boolean, id: Long) = Unit
        override suspend fun setCanUpload(allowed: Boolean, id: Long) = Unit
        override suspend fun setCanReadInsights(allowed: Boolean, tokenCipher: String?, id: Long): Int = 0
        override suspend fun setSyncCursor(seq: Long, id: Long) = Unit
        override suspend fun setAnnotationCursor(seq: Long, id: Long) = Unit
        override suspend fun setOrbitEpoch(epoch: Long, id: Long) = Unit
        override suspend fun delete(id: Long) { row = null }

        override suspend fun rotateOrbitTokens(
            access: String?,
            refresh: String?,
            expires: Long,
            session: Int?,
            epoch: Long,
            expected: String?,
            id: Long,
        ): Int {
            val current = row ?: return 0
            if (!claimOnRotate) return 0
            if (current.kind != ServerKind.BOOKORBIT) return 0
            if (current.orbitEpoch != epoch) return 0
            if (current.orbitRefreshCipher != expected) return 0
            row = current.copy(
                orbitAccessCipher = access,
                orbitRefreshCipher = refresh,
                orbitAccessExpires = expires,
                orbitSessionId = session,
            )
            return 1
        }
    }

    private fun context(server: RemoteServer): BookOrbitRequestContext =
        checkNotNull(BookOrbitRequestContext.from(server))
}
