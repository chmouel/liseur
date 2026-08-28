package com.chmouel.liseur.data.kosync

import com.chmouel.liseur.data.db.KosyncPeer
import com.chmouel.liseur.data.db.KosyncPeerDao
import com.chmouel.liseur.data.db.SyncPeerStateDao
import com.chmouel.liseur.data.remote.PeerPositionSync
import com.chmouel.liseur.data.remote.RemoteResult
import com.chmouel.liseur.data.remote.SetupFailure
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.SyncReporting
import kotlinx.coroutines.flow.Flow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What came of trying to pair with a kosync server. */
sealed interface KosyncSetupOutcome {
    data object Success : KosyncSetupOutcome
    data class Failure(val reason: SetupFailure) : KosyncSetupOutcome
}

/**
 * The kosync partner's lifecycle: pairing, forgetting, and the one
 * door per-account state is cleared through.
 *
 * Deliberately separate from `RemoteAccountRepository`: the kosync
 * partner lives alongside whatever catalog server is connected, and
 * disconnecting either one must leave the other standing.
 */
class KosyncAccountRepository(
    private val dao: KosyncPeerDao,
    private val peerStateDao: SyncPeerStateDao,
    private val reporting: SyncReporting = SyncReporting(),
    private val client: KosyncClient = KosyncClient(),
    private val now: () -> Long = System::currentTimeMillis,
) {

    val peer: Flow<KosyncPeer?> = dao.observe()

    suspend fun current(): KosyncPeer? = dao.get()

    /**
     * Pairs with a kosync server, proving the credentials work before
     * anything is stored.
     *
     * The password is hashed into the protocol's auth key here and the
     * key alone is kept. With [register] the credential is created on
     * the server first — liseur-sync redeems a pairing code that way —
     * and registering is an explicit ask rather than a fallback, because
     * against a stock kosync server an automatic retry-as-register would
     * turn a typo into a fresh account.
     */
    suspend fun connect(
        url: String,
        username: String,
        password: String,
        register: Boolean = false,
    ): KosyncSetupOutcome {
        val root = normalise(url) ?: return KosyncSetupOutcome.Failure(SetupFailure.WrongServer)
        val login = username.trim()
        if (login.isEmpty() || password.isEmpty()) {
            return KosyncSetupOutcome.Failure(SetupFailure.BadCredentials)
        }

        if (register) {
            // Registering is the one call that carries the password as
            // typed rather than the derived key. It does not go out in
            // the clear.
            if (root.startsWith("http://")) {
                return KosyncSetupOutcome.Failure(SetupFailure.InsecureTransport)
            }
            when (val created = client.register(root, login, password)) {
                is RemoteResult.Ok -> Unit
                is RemoteResult.Failed -> return KosyncSetupOutcome.Failure(setupReason(created.reason))
            }
        }

        val credentials = KosyncCredentials(login, KosyncCredentials.keyFor(password))
        when (val asked = client.authorize(root, credentials)) {
            is RemoteResult.Ok -> Unit
            is RemoteResult.Failed -> return KosyncSetupOutcome.Failure(setupReason(asked.reason))
        }

        val fresh = KosyncPeer(
            baseUrl = root,
            username = login,
            keyCipher = KosyncPeer.seal(credentials.key),
            addedAt = now(),
        )
        // A different account's agreements are somebody else's: signing
        // in as a new user strands them rather than adopting them, the
        // same rule every other kind of server follows.
        val old = dao.get()
        if (old != null && old.accountKey != fresh.accountKey) {
            forget(old)
        }
        dao.upsert(fresh)
        return KosyncSetupOutcome.Success
    }

    /**
     * Forgets the partner. The reading itself stays — it is this
     * device's — but the record of what this server had agreed goes,
     * because it means nothing once the server is no longer talked to.
     */
    suspend fun disconnect() {
        dao.get()?.let { forget(it) }
        dao.delete()
    }

    /**
     * Forgets a partner whose key can no longer be read back — a
     * database restored onto another phone arrives with ciphertext this
     * Keystore cannot open, and asking for the password again is better
     * than looking connected while every request quietly fails.
     */
    suspend fun forgetUnreadable(): Boolean {
        val peer = dao.get() ?: return false
        if (peer.credentials != null) return false
        forget(peer)
        dao.delete()
        return true
    }

    private suspend fun forget(peer: KosyncPeer) {
        peerStateDao.forgetPeer(peer.accountKey)
        reporting.forget(PeerPositionSync.KOSYNC)
    }

    /**
     * The mount root as stored: scheme defaulted to https, whitespace
     * and trailing slashes gone, and the whole thing proven parseable —
     * OkHttp throws on a malformed URL from outside [KosyncClient]'s
     * error mapping, so a root nothing could ever address is refused
     * here rather than thrown later. The reader's own spelling of the
     * path is kept: mount roots are case-preserving.
     */
    private fun normalise(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        if (!withScheme.startsWith("http://") && !withScheme.startsWith("https://")) return null
        // Parse before trimming slashes: "https://" must fail as
        // host-less here, not survive as the host "https:".
        if (withScheme.toHttpUrlOrNull() == null) return null
        return withScheme.trimEnd('/')
    }

    private fun setupReason(reason: SyncFailure): SetupFailure = when (reason) {
        SyncFailure.Unauthorised, SyncFailure.Forbidden -> SetupFailure.BadCredentials
        // The mount root is part of the address: a 404 here is the
        // wrong path, not a missing book.
        SyncFailure.NotFound, SyncFailure.Malformed -> SetupFailure.WrongServer
        SyncFailure.InsecureTransport -> SetupFailure.InsecureTransport
        else -> SetupFailure.Unreachable(reason.label, httpMayWork = false)
    }
}
