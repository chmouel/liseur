package com.chmouel.liseur.data.bookorbit

import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.RemoteServerDao
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteHttp
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.RemoteUrl
import com.chmouel.liseur.data.remote.SyncFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The one place a BookOrbit access token is minted, renewed and spent.
 *
 * BookOrbit is the first kind whose credential expires on its own. An
 * access token lasts about fifteen minutes and a refresh token lasts a
 * week, and every refresh replaces the refresh token, so there is no
 * value that can be handed around as "the password" the way Komga's API
 * key is. Two rules fall out of that, and both are load-bearing:
 *
 * 1. **Renewal is serialised.** Two refreshes spending the same token
 *    leave one caller holding a session the server has already
 *    forgotten. Everything that would refresh goes through [renewing].
 * 2. **A write is conditional.** A refresh that finishes after the
 *    reader switched accounts must not put its tokens onto the new
 *    account. The row it writes carries the epoch it was read under, and
 *    a write that no longer matches is dropped rather than retried.
 *
 * Nothing here logs or quotes a token. The failures it raises are the
 * ordinary remote-layer ones, which carry a reason but never a body:
 * a sign-in error page can echo what was sent to it.
 */
class BookOrbitSession(
    private val serverDao: RemoteServerDao,
    private val http: RemoteHttp = RemoteHttp(RemoteHttp.forAuthentication()),
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** The tokens in hand, and which connection they belong to. */
    private data class Cached(
        val accountKey: String,
        val accountId: String,
        val epoch: Long,
        val baseUrl: String,
        val access: String,
        val accessExpiresAt: Long,
        val refresh: String,
        val sessionId: Int?,
    )

    @Volatile
    private var cached: Cached? = null

    /**
     * A token the server has actually refused.
     *
     * Its stated expiry may still be in the future — a revoked session
     * or a clock that disagrees will do that — and reading the row again
     * would hand the same rejected token straight back. Only a newly
     * minted token clears this.
     */
    @Volatile
    private var rejected: String? = null

    private val renewing = Mutex()

    /**
     * An access token that is good now, renewing it if it is not.
     *
     * Throws [SyncFailure.Unauthorised] when there is no BookOrbit
     * account, when its secrets cannot be read back, or when the server
     * has revoked the session. All three mean the same thing to a
     * caller: this device cannot talk to that server until the reader
     * signs in again.
     */
    suspend fun token(context: BookOrbitRequestContext): String {
        val server = account(context) ?: throw RemoteHttpFailure(SyncFailure.Unauthorised)
        inHand(server)?.let { return it }
        return refresh(context, server)
    }

    /**
     * A context for [baseUrl] and [expectedAccountKey], or null when the
     * connection changed before the request was built.
     */
    suspend fun contextFor(
        baseUrl: String,
        expectedAccountKey: String? = null,
    ): BookOrbitRequestContext? {
        val server = account() ?: return null
        if (expectedAccountKey != null && server.accountKey != expectedAccountKey) return null
        val context = BookOrbitRequestContext.from(server) ?: return null
        return context.takeIf {
            com.chmouel.liseur.data.remote.RemoteUrl.sameAddress(it.baseUrl, baseUrl)
        }
    }

    /** The current connection, but only when it owns [url]. */
    suspend fun contextForUrl(url: String): BookOrbitRequestContext? {
        val server = account() ?: return null
        val context = BookOrbitRequestContext.from(server)?.takeIf { it.covers(url) }
            ?: return null
        val scoped = BookOrbitUrl.coverRemoteId(url) ?: return context
        val accountId = server.accountId ?: return null
        val expected = "bo_${BookOrbitScope.fingerprint(server.baseUrl, accountId)}_"
        return context.takeIf { scoped.startsWith(expected) }
    }

    /**
     * Reads the row's stored access token when it is still good.
     *
     * A process that has just started has no cache and no reason to
     * ask for a renewal: the token it wrote down on the way out is
     * still the one the server will accept. Answering from the cache
     * alone would make every cold start spend a refresh, and a refresh
     * rotates the token it spends.
     */
    private fun inHand(server: RemoteServer): String? {
        cached?.takeIf {
            it.accountKey == server.accountKey &&
                it.epoch == server.orbitEpoch &&
                RemoteUrl.sameAddress(it.baseUrl, server.baseUrl) &&
                fresh(it.access, it.accessExpiresAt)
        }?.let { if (it.access != rejected) return it.access }
        val stored = server.orbitAccessCipher?.let(CredentialCipher::decrypt) ?: return null
        if (stored == rejected) return null
        if (!fresh(stored, server.orbitAccessExpires)) return null
        val refresh = readRefresh(server) ?: return null
        cached = Cached(
            accountKey = server.accountKey,
            accountId = server.accountId ?: return null,
            epoch = server.orbitEpoch,
            baseUrl = server.baseUrl,
            access = stored,
            accessExpiresAt = server.orbitAccessExpires,
            refresh = refresh,
            sessionId = server.orbitSessionId,
        )
        return stored
    }

    /**
     * Fills the cache from the row, so the first cover need not wait.
     *
     * Called when an account is already connected — the account
     * repository primes the image loader the same way it does for the
     * other kinds — and harmless when there is nothing to read.
     */
    suspend fun prime() {
        account()?.let { inHand(it) }
    }

    private suspend fun refresh(
        context: BookOrbitRequestContext,
        server: RemoteServer,
    ): String =
        renewing.withLock {
            // Somebody may have renewed while this was waiting its turn.
            val again = account(context) ?: throw RemoteHttpFailure(SyncFailure.Unauthorised)
            inHand(again)?.let { return@withLock it }

            val refresh = readRefresh(again) ?: throw RemoteHttpFailure(SyncFailure.Unauthorised)
            val result = BookOrbitAuth.refresh(again.baseUrl, refresh, http)
            persist(again, result)
            result.accessToken
        }

    /**
     * Runs [block] with a token, and once more with a fresh one if the
     * server refused the first.
     *
     * A token can expire between the check and the request, and one
     * retry is the difference between a pass that works and a pass that
     * fails whenever the fifteen minutes land badly. Only the token that
     * was actually refused is thrown away: another caller may have
     * replaced it already, and discarding that would start a second
     * session to answer the first one's problem.
     *
     * The block must be safe to repeat. Callers that mutate must hand in
     * something they can reconstruct, and a mutation whose answer was
     * ambiguous is their problem to reconcile, not this method's to
     * decide.
     */
    suspend fun <T> authorized(
        context: BookOrbitRequestContext,
        block: suspend (RemoteCredentials.Bearer) -> T,
    ): T {
        val first = token(context)
        return try {
            block(RemoteCredentials.Bearer(first))
        } catch (e: RemoteHttpFailure) {
            if (e.reason != SyncFailure.Unauthorised) throw e
            invalidate(first)
            block(RemoteCredentials.Bearer(token(context)))
        }
    }

    /**
     * Re-signs a request after the server refused [rejectedToken].
     *
     * Used by OkHttp's download and image authenticators. The context is
     * checked again after renewal, so a switch between the 401 and this
     * call terminates the old request instead of handing it the new
     * account's token.
     */
    suspend fun afterRejection(
        context: BookOrbitRequestContext,
        rejectedToken: String,
    ): String {
        account(context) ?: throw RemoteHttpFailure(SyncFailure.Unauthorised)
        invalidate(rejectedToken)
        return token(context)
    }

    /**
     * What the image loader can sign a cover with, right now.
     *
     * Synchronous because it is called from an OkHttp interceptor, and
     * allowed to return null: a cover that arrives a moment before the
     * session is renewed is a blank tile, not a lost batch. The token is
     * only handed out while it is still good, so a stale one is never
     * sent.
     */
    fun cachedBearer(): RemoteCredentials? =
        cached?.takeIf { fresh(it.access, it.accessExpiresAt) }?.let { RemoteCredentials.Bearer(it.access) }

    /** A cached bearer, only for the server and account it belongs to. */
    fun cachedCredentialsForUrl(url: String): RemoteCredentials? {
        val current = cached ?: return null
        val context = BookOrbitRequestContext(current.accountKey, current.epoch, current.baseUrl)
        if (!context.covers(url)) return null
        BookOrbitUrl.coverRemoteId(url)?.let { remoteId ->
            val expected = "bo_${BookOrbitScope.fingerprint(current.baseUrl, current.accountId)}_"
            if (!remoteId.startsWith(expected)) return null
        }
        return current.takeIf { fresh(it.access, it.accessExpiresAt) }
            ?.let { RemoteCredentials.Bearer(it.access) }
    }

    /** The address a request would go to, or null when there is no account. */
    suspend fun baseUrl(): String? = account()?.baseUrl

    /**
     * Takes on a session that setup has just minted.
     *
     * Called after the account row is written, so the epoch passed here
     * is the one the row now carries. Until this runs, nothing on this
     * device is signed in.
     */
    fun adopt(
        server: RemoteServer,
        access: String,
        accessExpiresAt: Long,
        refresh: String,
        sessionId: Int?,
    ) {
        rejected = null
        cached = Cached(
            accountKey = server.accountKey,
            accountId = server.accountId ?: return,
            epoch = server.orbitEpoch,
            baseUrl = server.baseUrl,
            access = access,
            accessExpiresAt = accessExpiresAt,
            refresh = refresh,
            sessionId = sessionId,
        )
    }

    /** Forgets everything in hand, e.g. on a disconnect. */
    fun clear() {
        cached = null
        rejected = null
    }

    /**
     * Ends the session on the server, best effort.
     *
     * Reads the refresh token from the row rather than the cache so that
     * a logout still works after a restart, which is when a trail of
     * live sessions would otherwise be left behind.
     */
    suspend fun close() {
        val server = account() ?: return
        val refresh = readRefresh(server)
        clear()
        if (refresh != null) {
            withContext(Dispatchers.IO) {
                BookOrbitAuth.logout(server.baseUrl, refresh, http)
            }
        }
    }

    private suspend fun account(): RemoteServer? =
        serverDao.get()?.takeIf { it.kind == com.chmouel.liseur.data.remote.ServerKind.BOOKORBIT }

    private suspend fun account(context: BookOrbitRequestContext): RemoteServer? =
        account()?.takeIf(context::matches)

    private fun readRefresh(server: RemoteServer): String? =
        server.orbitRefreshCipher?.let(CredentialCipher::decrypt)?.takeIf { it.isNotEmpty() }

    private fun fresh(access: String, expiresAt: Long): Boolean =
        access.isNotEmpty() && expiresAt - now() > SKEW_MS

    private fun invalidate(token: String) {
        synchronized(this) {
            rejected = token
            if (cached?.access == token) cached = null
        }
    }

    /**
     * Writes the renewed pair down, and only if this connection still owns
     * the row.
     *
     * Zero rows means a switch, a reconnect or another refresh moved the
     * session on while this one was in the air. Dropping the answer is
     * the whole point: writing it back would put this phone's old
     * session onto whoever is connected now.
     */
    private suspend fun persist(server: RemoteServer, result: BookOrbitAuth.Result) {
        val accessCipher = CredentialCipher.encrypt(result.accessToken)
        val refreshCipher = result.refreshToken.takeIf { it.isNotEmpty() }
            ?.let(CredentialCipher::encrypt)
        val written = serverDao.rotateOrbitTokens(
            access = accessCipher,
            refresh = refreshCipher ?: server.orbitRefreshCipher,
            expires = result.accessExpiresAt,
            session = result.sessionId,
            epoch = server.orbitEpoch,
            expected = server.orbitRefreshCipher,
        )
        if (written == 0) {
            synchronized(this) { cached = null }
            throw RemoteHttpFailure(SyncFailure.Unauthorised)
        }
        rejected = null
        cached = Cached(
            accountKey = server.accountKey,
            accountId = server.accountId ?: throw RemoteHttpFailure(SyncFailure.Unauthorised),
            epoch = server.orbitEpoch,
            baseUrl = server.baseUrl,
            access = result.accessToken,
            accessExpiresAt = result.accessExpiresAt,
            refresh = result.refreshToken.ifEmpty { readRefresh(server).orEmpty() },
            sessionId = result.sessionId,
        )
    }

    private companion object {
        /**
         * How long before its stated expiry a token is treated as spent.
         *
         * A request that leaves valid and arrives expired costs a round
         * trip through the refusal path; a minute is longer than any
         * request this app makes should take.
         */
        const val SKEW_MS = 60_000L
    }
}
