package com.chmouel.liseur.data.remote

import com.chmouel.liseur.data.calibre.CalibreParsing
import com.chmouel.liseur.data.calibre.CalibreSetupClient
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.ReadingProgressDao
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.RemoteServerDao
import com.chmouel.liseur.data.komga.KomgaSetupClient
import kotlinx.coroutines.flow.Flow

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
    private val setups: Map<ServerKind, ServerSetup> = mapOf(
        ServerKind.CALIBRE to CalibreSetupClient(),
        ServerKind.KOMGA to KomgaSetupClient(),
    ),
) {
    val server: Flow<RemoteServer?> = dao.observe()

    /**
     * Last known base URL and credentials, so callers that cannot
     * suspend — the image loader, mainly — can still authenticate.
     */
    @Volatile
    private var cached: Pair<String, RemoteCredentials>? = null

    suspend fun current(): RemoteServer? = dao.get()

    suspend fun credentials(): RemoteCredentials? {
        val server = dao.get() ?: run {
            cached = null
            return null
        }
        return server.credentials?.also { cached = server.baseUrl to it }
    }

    /** How to sign a request to [url], or null when it is not our server. */
    fun credentialsForUrl(url: String): RemoteCredentials? {
        val (baseUrl, credentials) = cached ?: return null
        return credentials.takeIf { url.startsWith(baseUrl) }
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
        val username = when (credentials) {
            is RemoteCredentials.Basic -> credentials.username
            is RemoteCredentials.ApiKey -> capabilities.displayName
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
     * The reading itself stays, and so does the note of who did it: that
     * is exactly what the reader has to be asked about before anything is
     * handed over. What goes is the agreed baseline and any unsettled
     * remote state, because they describe a library this new login cannot
     * see. Nothing is left looking unsent, so signing in as someone else
     * never quietly uploads the last person's reading into their account.
     */
    private suspend fun retireForAccountSwitch() {
        progressDao.retireAccountState()
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
    suspend fun forgetUnreadableAccount(): Boolean {
        val server = dao.get() ?: return false
        if (server.credentials != null) return false
        cached = null
        dao.delete()
        return true
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
     * with it; anything downloaded stays on the device as an ordinary
     * book, cut loose so it cannot be synced against a different server
     * later on.
     */
    suspend fun disconnect() {
        cached = null
        bookDao.deleteRemoteNotDownloaded()
        bookDao.unlinkDownloadedFromRemote()
        dao.delete()
    }
}
