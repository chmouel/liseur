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

/** A pairing the server has already agreed to, not yet written down. */
class ProvedKosyncPairing internal constructor(internal val peer: KosyncPeer)

/** What came of proving a pairing without storing it. */
sealed interface KosyncProbe {
    data class Proved(val pairing: ProvedKosyncPairing) : KosyncProbe
    data class Failure(val reason: SetupFailure) : KosyncProbe
}

/**
 * Pairing with a kosync server in two halves, for a caller that has
 * something else to publish at the same time.
 *
 * A Custom connection has an OPDS address and a kosync address, and
 * either one may be refused by its server. Proving both before writing
 * either down is what stops a reader being left connected to half of
 * what they typed — and being told so by a form that has already
 * cleared itself.
 */
interface KosyncPairing {

    /** Asks the server, and keeps nothing. */
    suspend fun verify(url: String, username: String, password: String): KosyncProbe

    /** Writes down a pairing [verify] already proved. */
    suspend fun adopt(pairing: ProvedKosyncPairing)

    /** Puts the pairing down, agreements and reported status with it. */
    suspend fun forget()

    /** For tests, and for a build with no pairing wired up. */
    object None : KosyncPairing {
        override suspend fun verify(url: String, username: String, password: String) =
            KosyncProbe.Failure(SetupFailure.WrongServer)

        override suspend fun adopt(pairing: ProvedKosyncPairing) = Unit

        override suspend fun forget() = Unit
    }
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
) : KosyncPairing {

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

        return when (val probe = prove(root, login, password)) {
            is KosyncProbe.Failure -> KosyncSetupOutcome.Failure(probe.reason)
            is KosyncProbe.Proved -> {
                adopt(probe.pairing)
                KosyncSetupOutcome.Success
            }
        }
    }

    /**
     * Proves a pairing and keeps nothing, so a caller with a second
     * address to prove can find out about both before writing down
     * either.
     */
    override suspend fun verify(url: String, username: String, password: String): KosyncProbe {
        val root = normalise(url) ?: return KosyncProbe.Failure(SetupFailure.WrongServer)
        val login = username.trim()
        if (login.isEmpty() || password.isEmpty()) {
            return KosyncProbe.Failure(SetupFailure.BadCredentials)
        }
        return prove(root, login, password)
    }

    private suspend fun prove(root: String, login: String, password: String): KosyncProbe {
        val credentials = KosyncCredentials(login, KosyncCredentials.keyFor(password))
        when (val asked = client.authorize(root, credentials)) {
            is RemoteResult.Ok -> Unit
            is RemoteResult.Failed -> return KosyncProbe.Failure(setupReason(asked.reason))
        }
        return KosyncProbe.Proved(
            ProvedKosyncPairing(
                KosyncPeer(
                    baseUrl = root,
                    username = login,
                    keyCipher = KosyncPeer.seal(credentials.key),
                    addedAt = now(),
                ),
            ),
        )
    }

    override suspend fun adopt(pairing: ProvedKosyncPairing) {
        val fresh = pairing.peer
        // A different account's agreements are somebody else's: signing
        // in as a new user strands them rather than adopting them, the
        // same rule every other kind of server follows.
        val old = dao.get()
        if (old != null && old.accountKey != fresh.accountKey) {
            forget(old)
        }
        dao.upsert(fresh)
    }

    override suspend fun forget() = disconnect()

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
     * The mount root as stored: scheme lowercased and defaulted to
     * https, whitespace, query, fragment and trailing slashes gone, and
     * the whole thing proven parseable — OkHttp throws on a malformed
     * URL from outside [KosyncClient]'s error mapping, so a root nothing
     * could ever address is refused here rather than thrown later. The
     * reader's own spelling of the path is kept: mount roots are
     * case-preserving.
     *
     * A query or fragment is dropped rather than refused. Endpoints are
     * built by appending to this string, so `…/koreader?x=1` would
     * become `…/koreader?x=1/users/auth`, which addresses nothing. There
     * is no kosync route that takes one, and a reader who pasted their
     * address out of a browser bar should not have to notice why.
     */
    private fun normalise(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val scheme = withScheme.substringBefore("://").lowercase()
        if (scheme != "http" && scheme != "https") return null
        val spelled = scheme + withScheme.substring(scheme.length)
        // Parse before trimming slashes: "https://" must fail as
        // host-less here, not survive as the host "https:".
        val parsed = spelled.toHttpUrlOrNull() ?: return null
        return parsed.newBuilder()
            .query(null)
            .fragment(null)
            .build()
            .toString()
            .trimEnd('/')
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
