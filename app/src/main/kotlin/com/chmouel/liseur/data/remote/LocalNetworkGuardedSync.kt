package com.chmouel.liseur.data.remote

/**
 * A partner that will not be dialled while the phone is blocking the
 * network it lives on.
 *
 * Android 17 drops local network traffic below the HTTP client, so a
 * LAN server does not refuse a connection — it swallows it until the
 * timeout. Left alone, every scheduled run spends that timeout, fails
 * for a reason worth retrying, and comes back to spend it again. A
 * worker cannot put a permission prompt in front of anyone, so the
 * useful thing it can do is stop and say why.
 *
 * Wrapped around each peer rather than around the composite, because a
 * public catalog paired with a kosync server in the spare room has one
 * blocked partner and one perfectly healthy one, and refusing the whole
 * run would silence the healthy one and file the failure under nobody's
 * name.
 *
 * [PeerPositionSync.dialledAddress] answers null when this peer has
 * nothing to do anyway — a connection that does not sync positions, a
 * kosync partner stranded on an account that no longer uses it, a
 * credential that cannot be read back — because a stored address that
 * was never going to be dialled must not manufacture a failure. The
 * peer answers it from the same state its own run consults, so the two
 * cannot drift apart.
 *
 * That address is the whole test, and asking one book's [canSync] as
 * well would be a mistake: a book the peer does not carry is not a book
 * the peer declines to dial about. A page turn in a side-loaded book on
 * a catalog account still opens the connection, discovers there is no
 * matching remote book, and returns nothing — so it still spends the
 * timeout, and the guard has to reach it.
 */
class LocalNetworkGuardedSync(
    private val delegate: PeerPositionSync,
    private val access: LocalNetworkAccess,
    private val reporting: SyncReporting,
) : PeerPositionSync by delegate {

    override val peerId: String get() = delegate.peerId

    override suspend fun syncAll(snapshot: SyncSnapshot?): SyncOutcome =
        if (blocked()) refuse() else delegate.syncAll(snapshot)

    override suspend fun syncBook(bookUrl: String): SyncOutcome =
        if (blocked()) refuse() else delegate.syncBook(bookUrl)

    /**
     * A preview is one reader asking one book a question, so it answers
     * and nothing more. Writing the account's status from here would
     * let a glance at one book overwrite what the last real run did.
     */
    override suspend fun previewBook(bookUrl: String): PreviewOutcome =
        if (blocked()) {
            PreviewOutcome.Failed(SyncFailure.LocalNetworkBlocked)
        } else {
            delegate.previewBook(bookUrl)
        }

    /**
     * Keeping this device's position sends it to the server, so it is
     * guarded; taking the server's is not.
     *
     * The asymmetry is the two operations', not this class's. Taking
     * the server's position applies an answer an earlier run already
     * brought back and wrote down, and touches nothing but this
     * phone's database — which is why it works on a plane, and must go
     * on working behind a blocked network for the same reason. Keeping
     * this device's dials, and already answers `Failed(Offline)` when
     * there is no network at all; a blocked one is that said
     * differently, and a timeout the reader sits and watches is the
     * worst way to say it. The status line is left alone, as it is for
     * a preview: one book being settled is not a run.
     */
    override suspend fun keepLocalPosition(bookUrl: String, peerId: String?): ResolveOutcome =
        if (blocked()) {
            ResolveOutcome.Failed(SyncFailure.LocalNetworkBlocked)
        } else {
            delegate.keepLocalPosition(bookUrl, peerId)
        }

    private suspend fun blocked(): Boolean = access.blocks(delegate.dialledAddress())

    private fun refuse(): SyncOutcome {
        reporting.report(PositionSyncStatus.Failed(SyncFailure.LocalNetworkBlocked), peerId)
        return SyncOutcome.Failure(SyncFailure.LocalNetworkBlocked)
    }
}
