package com.chmouel.liseur.data.remote

import com.chmouel.liseur.data.calibre.CalibreParsing
import com.chmouel.liseur.data.calibre.CalibreSetupClient
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.ReadingProgressDao
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.RemoteServerDao
import com.chmouel.liseur.data.library.BookRemoval
import com.chmouel.liseur.data.komga.KomgaSetupClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicLong

/**
 * Stores the one connected server and hands out its credentials.
 *
 * The kinds of server differ in how they are signed into and in what
 * they can be asked for, but not in what an account *is*, so this holds
 * whichever one is connected and leaves the differences to
 * [ServerSetup] and to [RemoteServer.credentials].
 */
class RemoteAccountRepository(
    private val dao: RemoteServerDao,
    private val bookDao: BookDao,
    private val progressDao: ReadingProgressDao,
    private val bookRemoval: BookRemoval,
    private val setups: Map<ServerKind, ServerSetup> = mapOf(
        ServerKind.CALIBRE to CalibreSetupClient(),
        ServerKind.KOMGA to KomgaSetupClient(),
    ),
    /**
     * Connecting and disconnecting each touch several tables, and a sync
     * or a catalog refresh may be running at the same time. Doing them in
     * one go is what lets those runs check whose account they are writing
     * for and be sure the answer is still true when they write.
     */
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
) {
    val server: Flow<RemoteServer?> = dao.observe()

    /**
     * What the account looks like to a caller that cannot suspend — the
     * image loader, mainly, which signs Coil's cover requests from an
     * OkHttp interceptor.
     */
    private sealed interface Snapshot {
        /** There is no connected account. */
        data object None : Snapshot

        data class Account(
            val origin: RemoteOrigin,
            val credentials: RemoteCredentials,
        ) : Snapshot
    }

    /**
     * The account as last read, or null when it has not been read yet.
     *
     * "Not read yet" and "no account" are kept apart on purpose: they
     * are the same absence to a caller but opposite instructions to this
     * class, and conflating them is how a cold cache turns into a
     * permanent one.
     *
     * This used to be filled in as a side effect of [credentials], which
     * meant covers only loaded if something else had happened to ask for
     * the account first. That held until the catalog and the download
     * worker stopped calling it, at which point every Komga cover
     * started coming back 401. A cache maintained by a caller that has
     * no idea it is maintaining one is not a cache, so this one keeps
     * itself.
     */
    @Volatile
    private var cached: Snapshot? = null

    /**
     * Bumped on the way into a mutation and again on the way out.
     *
     * A read that began before a change and finished after it holds a
     * row that is no longer true. Doing the first bump *before* the
     * mutation starts is what makes that detectable: the reader compares
     * the counter across its own query and throws away anything it
     * loaded across a change, so a disconnected account can never be
     * published back into the cache by a read that was already in
     * flight.
     */
    private val generation = AtomicLong()

    private val loading = Any()

    suspend fun current(): RemoteServer? = dao.get()

    suspend fun credentials(): RemoteCredentials? = dao.get()?.credentials

    /** Reads the account into the cache, so the first cover need not wait. */
    suspend fun prime() {
        val at = generation.get()
        val snapshot = snapshotOf(dao.get())
        if (generation.get() == at) cached = snapshot
    }

    /** How to sign a request to [url], or null when it is not our server. */
    fun credentialsForUrl(url: String): RemoteCredentials? {
        val account = (cached ?: load()) as? Snapshot.Account ?: return null
        return account.credentials.takeIf { account.origin.covers(url) }
    }

    /**
     * Fills a cold cache, blocking until it has an answer.
     *
     * There is nowhere to suspend to: this is called from an OkHttp
     * interceptor. It is one indexed single-row query, it happens at
     * most once between changes, and the lock makes a burst of cover
     * requests at startup share a single read rather than each doing
     * their own. [prime] normally gets there first.
     */
    private fun load(): Snapshot = synchronized(loading) {
        cached?.let { return it }
        repeat(LOAD_ATTEMPTS) {
            val at = generation.get()
            val snapshot = snapshotOf(runBlocking { dao.get() })
            if (generation.get() == at) {
                cached = snapshot
                return snapshot
            }
        }
        // Something is changing the account faster than it can be read.
        // Answering "no account" only costs a cover; answering with what
        // was read could cost the credentials.
        return Snapshot.None
    }

    private fun snapshotOf(server: RemoteServer?): Snapshot {
        val credentials = server?.credentials ?: return Snapshot.None
        val origin = RemoteOrigin.of(server.baseUrl) ?: return Snapshot.None
        return Snapshot.Account(origin, credentials)
    }

    /**
     * Runs a change to the account with the cache emptied around it.
     *
     * Nothing here writes the new value into the cache; it only clears
     * it, so the next reader goes and looks. That is deliberate — the
     * cache should hold what the database actually committed, not what
     * this call believed it was about to write.
     */
    private suspend fun <T> changingAccount(block: suspend () -> T): T {
        invalidate()
        return try {
            block()
        } finally {
            invalidate()
        }
    }

    private fun invalidate() {
        generation.incrementAndGet()
        cached = null
    }

    /** Probes a calibre-web server and, if it answers, saves it as the account. */
    suspend fun connectCalibre(
        url: String,
        username: String,
        password: String,
        allowHttp: Boolean = false,
    ): SetupResult = connect(
        kind = ServerKind.CALIBRE,
        url = url,
        credentials = RemoteCredentials.Basic(username, password),
        allowHttp = allowHttp,
    )

    /** Probes a Komga server and, if it answers, saves it as the account. */
    suspend fun connectKomga(
        url: String,
        apiKey: String,
        allowHttp: Boolean = false,
    ): SetupResult = connect(
        kind = ServerKind.KOMGA,
        url = url,
        credentials = RemoteCredentials.ApiKey(apiKey),
        allowHttp = allowHttp,
    )

    private suspend fun connect(
        kind: ServerKind,
        url: String,
        credentials: RemoteCredentials,
        allowHttp: Boolean,
    ): SetupResult {
        val setup = setups[kind] ?: return SetupResult.Failure(SetupFailure.WrongServer)
        val result = setup.connect(url, credentials, allowHttp)
        if (result is SetupResult.Success) store(kind, credentials, result.capabilities)
        return result
    }

    /**
     * Saves what the probe found, deciding first whether this is the same
     * account coming back or a different one arriving.
     *
     * Reconnecting as the *same person* to the same server is a refresh:
     * keeping the sync token means the next sync asks for what changed
     * rather than replaying the whole library.
     *
     * Signing in as someone else is not. Both servers keep reading state
     * per user, so the previous account's token and sync marker describe
     * a world this login cannot see — reusing them would have this device
     * syncing one person's reading into another person's account.
     */
    private suspend fun store(
        kind: ServerKind,
        credentials: RemoteCredentials,
        capabilities: ServerCapabilities,
    ) {
        changingAccount { inTransaction { storeLocked(kind, credentials, capabilities) } }
    }

    private suspend fun storeLocked(
        kind: ServerKind,
        credentials: RemoteCredentials,
        capabilities: ServerCapabilities,
    ) {
        val username = when (credentials) {
            is RemoteCredentials.Basic -> credentials.username
            is RemoteCredentials.ApiKey -> capabilities.displayName
            // No catalog server signs in this way, so nothing reaches
            // here; the name the server gave is still the honest answer.
            is RemoteCredentials.Bearer -> capabilities.displayName
        }
        val stored = dao.get()
        val sameAccount = stored != null &&
            stored.kind == kind &&
            stored.baseUrl == capabilities.baseUrl &&
            stored.username == username &&
            (stored.userId == capabilities.calibreUserId || capabilities.calibreUserId == null) &&
            (stored.accountId == capabilities.accountId || capabilities.accountId == null)
        val existing = stored?.takeIf { sameAccount }

        if (stored != null && !sameAccount) retireForAccountSwitch()

        dao.upsert(
            RemoteServer(
                kind = kind,
                baseUrl = capabilities.baseUrl,
                username = username,
                passwordCipher = (credentials as? RemoteCredentials.Basic)
                    ?.let { CredentialCipher.encrypt(it.password) },
                apiKeyCipher = (credentials as? RemoteCredentials.ApiKey)
                    ?.let { CredentialCipher.encrypt(it.key) },
                accountId = capabilities.accountId,
                userId = capabilities.calibreUserId,
                koboTokenCipher = RemoteServer.seal(capabilities.koboToken)
                    ?: existing?.koboTokenCipher,
                canDownload = capabilities.canDownload,
                addedAt = existing?.addedAt ?: System.currentTimeMillis(),
                catalogSyncedAt = existing?.catalogSyncedAt,
                positionSyncedAt = existing?.positionSyncedAt,
                syncToken = existing?.syncToken,
            ),
        )
    }

    /**
     * Puts down everything that belonged to the account being left.
     *
     * The saved position stays for books that remain, and so does the note
     * of who wrote it: that is exactly what the reader has to be asked about
     * before anything is handed over. What goes is the agreed baseline and
     * any unsettled remote state, because they describe a library this new
     * login cannot see. Nothing is left looking unsent, so signing in as
     * someone else never quietly uploads the last person's reading into
     * their account.
     *
     * The books go the same way they go on a disconnect. A remote id
     * means something only to the server that issued it, so leaving one
     * behind would have the next sync ask a different server about a
     * book it has never heard of — or, worse, about one of its own that
     * happens to answer to that id.
     */
    private suspend fun retireForAccountSwitch() {
        progressDao.retireAccountState()
        bookRemoval.deleteRemoteNotDownloaded()
        bookDao.unlinkDownloadedFromRemote()
    }

    /** Re-runs the probes for the saved account, e.g. after a permission change. */
    suspend fun refreshCapabilities(): SetupResult? {
        val server = dao.get() ?: return null
        val credentials = server.credentials ?: return null
        return connect(server.kind, server.baseUrl, credentials, allowHttp = true)
    }

    /**
     * Forgets an account whose secret can no longer be read.
     *
     * Credentials are encrypted with a key that lives in this device's
     * Keystore and cannot leave it, so a database restored from a backup
     * or moved to a new phone arrives with ciphertext nothing can open.
     * Asking for the password again is better than looking connected
     * while every request quietly fails.
     */
    suspend fun forgetUnreadableAccount(): Boolean = changingAccount {
        val server = dao.get() ?: return@changingAccount false
        if (server.credentials != null) return@changingAccount false
        dao.delete()
        true
    }

    /** Sets the Kobo sync token by hand when it could not be picked up. */
    suspend fun setKoboToken(tokenOrUrl: String?) {
        val token = tokenOrUrl?.let {
            CalibreParsing.koboToken(it) ?: it.trim().takeIf { value ->
                value.length == 32 && value.all(Character::isLetterOrDigit)
            }?.lowercase()
        }
        dao.setKoboTokenCipher(RemoteServer.seal(token))
    }

    /**
     * Forgets the account. Books that only ever lived on the server go
     * with it, including its local statistics; anything downloaded stays
     * on the device as an ordinary book with its statistics intact, cut
     * loose so it cannot be synced against a different server later on.
     */
    suspend fun disconnect() {
        changingAccount {
            inTransaction {
                bookRemoval.deleteRemoteNotDownloaded()
                bookDao.unlinkDownloadedFromRemote()
                dao.delete()
            }
        }
    }

    private companion object {
        /**
         * How many times a cold read will retry when the account changes
         * underneath it. A handful, because a change that keeps landing
         * mid-read is a change that will be read correctly a moment
         * later anyway.
         */
        const val LOAD_ATTEMPTS = 3
    }
}
