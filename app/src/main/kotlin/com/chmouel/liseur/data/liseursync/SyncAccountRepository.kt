package com.chmouel.liseur.data.liseursync

import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.SyncAccount
import com.chmouel.liseur.data.db.SyncAccountDao
import com.chmouel.liseur.data.db.SyncPeerStateDao
import com.chmouel.liseur.data.remote.DeviceIdentityRepository
import com.chmouel.liseur.data.remote.PeerPositionSync
import com.chmouel.liseur.data.remote.SyncReporting
import kotlinx.coroutines.flow.Flow

/**
 * The one connected sync server, and the tokens to reach it with.
 *
 * Kept apart from `RemoteAccountRepository` on purpose: connecting a
 * place to keep reading positions is not connecting a library, and
 * folding the two together would put the question "which server are we
 * talking to" back in a single place after the whole point was that
 * there can now be two answers.
 */
class SyncAccountRepository(
    private val dao: SyncAccountDao,
    private val peerStateDao: SyncPeerStateDao,
    private val device: DeviceIdentityRepository,
    private val setup: SyncSetup = LiseurSyncSetupClient(),
    private val reporting: SyncReporting? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
) {
    val account: Flow<SyncAccount?> = dao.observe()

    suspend fun current(): SyncAccount? = dao.get()

    /**
     * Signs in and keeps the device token the server hands back.
     *
     * The password goes no further than this call: it buys an hour-long
     * token that can do nothing but create device tokens, and once those
     * exist neither is needed again. Nothing writes it down.
     */
    suspend fun connect(
        rawUrl: String,
        username: String,
        password: String,
        wantInsights: Boolean = true,
        allowHttp: Boolean = false,
    ): SyncSetupResult = store(
        setup.signIn(
            rawUrl = rawUrl,
            username = username,
            password = password,
            deviceName = device.current().name,
            wantInsights = wantInsights,
            allowHttp = allowHttp,
        ),
    )

    /**
     * Keeps a device token made somewhere else, once the server has
     * confirmed it works.
     *
     * The account name is asked for rather than discovered because a
     * device token does not say whose it is, and reading is bound to
     * whose it is: the same server signed into as two people is two
     * partners, and confusing them would put one person's reading in
     * another's.
     */
    suspend fun connectWithToken(
        rawUrl: String,
        username: String,
        token: String,
        allowHttp: Boolean = false,
    ): SyncSetupResult = store(
        setup.verifyToken(
            rawUrl = rawUrl,
            username = username,
            token = token,
            deviceName = device.current().name,
            allowHttp = allowHttp,
        ),
    )

    /**
     * Disconnects, and forgets what was agreed with this server.
     *
     * The reading itself stays: it is this device's, it was here before
     * any server was, and losing your place in every book because an
     * account was removed would be indefensible. What goes is the record
     * of what that server had confirmed, which means nothing once it is
     * no longer being talked to.
     */
    suspend fun disconnect() {
        val existing = dao.get() ?: return
        inTransaction {
            peerStateDao.forgetPeer(existing.peerId)
            dao.delete()
        }
        reporting?.forget(PeerPositionSync.LISEUR_SYNC)
    }

    private suspend fun store(result: SyncSetupResult): SyncSetupResult {
        val connection = (result as? SyncSetupResult.Success)?.connection ?: return result
        // Connecting as somebody else makes what the old account had
        // confirmed meaningless, and leaving it behind would have the new
        // account inherit a baseline it never agreed to.
        val existing = dao.get()
        inTransaction {
            val fresh = SyncAccount(
                baseUrl = connection.baseUrl,
                username = connection.username,
                tokenCipher = CredentialCipher.encrypt(connection.token),
                insightsTokenCipher = connection.insightsToken?.let(CredentialCipher::encrypt),
                deviceName = connection.deviceName,
                addedAt = now(),
            )
            if (existing != null && existing.peerId != fresh.peerId) {
                peerStateDao.forgetPeer(existing.peerId)
            }
            dao.upsert(fresh)
        }
        return result
    }
}
