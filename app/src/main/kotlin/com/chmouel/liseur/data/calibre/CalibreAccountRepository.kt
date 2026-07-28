package com.chmouel.liseur.data.calibre

import com.chmouel.liseur.data.db.CalibreServer
import com.chmouel.liseur.data.db.CalibreServerDao
import kotlinx.coroutines.flow.Flow

/** Stores the calibre-web account and hands out its credentials. */
class CalibreAccountRepository(
    private val dao: CalibreServerDao,
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
            dao.upsert(
                CalibreServer(
                    baseUrl = capabilities.baseUrl,
                    username = username,
                    passwordCipher = CredentialCipher.encrypt(password),
                    userId = capabilities.userId,
                    koboToken = capabilities.koboToken,
                    canDownload = capabilities.canDownload,
                    addedAt = System.currentTimeMillis(),
                    catalogSyncedAt = null,
                    positionSyncedAt = null,
                    syncToken = null,
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
        dao.setKoboToken(token)
    }

    suspend fun disconnect() {
        cached = null
        dao.delete()
    }
}
