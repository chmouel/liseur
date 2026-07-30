package com.chmouel.liseur.data.calibre

import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.RemoteServerDao
import com.chmouel.liseur.data.db.ReadingProgressDao
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.remote.SetupResult
import kotlinx.coroutines.flow.Flow

/** Stores the calibre-web account and hands out its credentials. */
class CalibreAccountRepository(
    private val dao: RemoteServerDao,
    private val bookDao: BookDao,
    private val progressDao: ReadingProgressDao,
    private val setupClient: CalibreSetupClient = CalibreSetupClient(),
) {
    val server: Flow<RemoteServer?> = dao.observe()

    /**
     * Last known base URL and login, so callers that cannot suspend —
     * the image loader, mainly — can still authenticate.
     */
    @Volatile
    private var cached: Pair<String, RemoteCredentials.Basic>? = null

    suspend fun current(): RemoteServer? = dao.get()

    suspend fun credentials(): RemoteCredentials.Basic? {
        val server = dao.get() ?: run {
            cached = null
            return null
        }
        val password = server.passwordCipher?.let(CredentialCipher::decrypt) ?: return null
        return RemoteCredentials.Basic(server.username.orEmpty(), password).also {
            cached = server.baseUrl to it
        }
    }

    /** The login to use for [url], or null when it is not our server. */
    fun credentialsForUrl(url: String): RemoteCredentials.Basic? {
        val (baseUrl, credentials) = cached ?: return null
        return credentials.takeIf { url.startsWith(baseUrl) }
    }

    /** Probes the server and, if it answers, saves it as the account. */
    suspend fun connect(
        url: String,
        username: String,
        password: String,
        allowHttp: Boolean = false,
    ): SetupResult {
        val result = setupClient.connect(url, RemoteCredentials.Basic(username, password), allowHttp)
        if (result is SetupResult.Success) {
            val capabilities = result.capabilities
            val stored = dao.get()
            // Reconnecting as the *same person* to the same server is a
            // refresh: keeping the sync token means the next sync asks for
            // what changed rather than replaying the whole library.
            //
            // Signing in as someone else is not. calibre-web keeps Kobo
            // reading state per user, so the previous account's token and
            // sync marker describe a world this login cannot see — reusing
            // them would have this device syncing one person's reading
            // into another person's account.
            val sameAccount = stored != null &&
                stored.baseUrl == capabilities.baseUrl &&
                stored.username == username &&
                (stored.userId == capabilities.calibreUserId || capabilities.calibreUserId == null)
            val existing = stored?.takeIf { sameAccount }

            if (stored != null && !sameAccount) retireForAccountSwitch()

            dao.upsert(
                RemoteServer(
                    kind = ServerKind.CALIBRE,
                    baseUrl = capabilities.baseUrl,
                    username = username,
                    passwordCipher = CredentialCipher.encrypt(password),
                    apiKeyCipher = null,
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
        return result
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
        val password = server.passwordCipher?.let(CredentialCipher::decrypt) ?: return null
        return connect(server.baseUrl, server.username.orEmpty(), password, allowHttp = true)
    }

    /**
     * Forgets an account whose password can no longer be read.
     *
     * Credentials are encrypted with a key that lives in this device's
     * Keystore and cannot leave it, so a database restored from a backup
     * or moved to a new phone arrives with ciphertext nothing can open.
     * Asking for the password again is better than looking connected
     * while every request quietly fails.
     */
    suspend fun forgetUnreadableAccount(): Boolean {
        val server = dao.get() ?: return false
        if (server.passwordCipher?.let(CredentialCipher::decrypt) != null) return false
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
