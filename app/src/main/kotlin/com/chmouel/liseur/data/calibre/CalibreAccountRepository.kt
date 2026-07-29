package com.chmouel.liseur.data.calibre

import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.CalibreServer
import com.chmouel.liseur.data.db.CalibreServerDao
import kotlinx.coroutines.flow.Flow

/** Stores the calibre-web account and hands out its credentials. */
class CalibreAccountRepository(
    private val dao: CalibreServerDao,
    private val bookDao: BookDao,
    private val setupClient: CalibreSetupClient = CalibreSetupClient(),
) {
    val server: Flow<CalibreServer?> = dao.observe()

    /**
     * Last known base URL and login, so callers that cannot suspend —
     * the image loader, mainly — can still authenticate.
     */
    @Volatile
    private var cached: Pair<String, CalibreCredentials>? = null

    suspend fun current(): CalibreServer? = dao.get()

    suspend fun credentials(): CalibreCredentials? {
        val server = dao.get() ?: run {
            cached = null
            return null
        }
        val password = CredentialCipher.decrypt(server.passwordCipher) ?: return null
        return CalibreCredentials(server.username, password).also {
            cached = server.baseUrl to it
        }
    }

    /** The login to use for [url], or null when it is not our server. */
    fun credentialsForUrl(url: String): CalibreCredentials? {
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
        val result = setupClient.connect(url, username, password, allowHttp)
        if (result is SetupResult.Success) {
            val capabilities = result.capabilities
            // Reconnecting to the same server is a refresh, not a new
            // account: keeping the sync token means the next sync asks
            // for what changed rather than replaying the whole library.
            val existing = dao.get()?.takeIf { it.baseUrl == capabilities.baseUrl }
            dao.upsert(
                CalibreServer(
                    baseUrl = capabilities.baseUrl,
                    username = username,
                    passwordCipher = CredentialCipher.encrypt(password),
                    userId = capabilities.userId,
                    koboTokenCipher = CalibreServer.sealToken(capabilities.koboToken)
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

    /** Re-runs the probes for the saved account, e.g. after a permission change. */
    suspend fun refreshCapabilities(): SetupResult? {
        val server = dao.get() ?: return null
        val password = CredentialCipher.decrypt(server.passwordCipher) ?: return null
        return connect(server.baseUrl, server.username, password, allowHttp = true)
    }

    /** Sets the Kobo sync token by hand when it could not be picked up. */
    suspend fun setKoboToken(tokenOrUrl: String?) {
        val token = tokenOrUrl?.let {
            CalibreParsing.koboToken(it) ?: it.trim().takeIf { value ->
                value.length == 32 && value.all(Character::isLetterOrDigit)
            }?.lowercase()
        }
        dao.setKoboTokenCipher(CalibreServer.sealToken(token))
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
