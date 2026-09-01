package com.chmouel.liseur.data.remote

/**
 * One partner this device keeps reading positions in step with.
 *
 * Today there is exactly one: whichever server the library is connected
 * to, reached through [RoutedPositionSync]. The interface stays because
 * the coordinator's ordering rules are written against the composite,
 * and a partner added later — a dedicated sync server again, say — is
 * one list entry away.
 *
 * A peer is a [PositionSync] with a name. The name is for reporting and
 * for logs, and is deliberately *not* the key reconciliation state is
 * stored under: that key identifies the account, so that signing in as
 * somebody else strands their reading rather than adopting it.
 */
interface PeerPositionSync : PositionSync {
    /** A stable, account-independent name for this partner. */
    val peerId: String

    companion object {
        /** The server the library browses and downloads from. */
        const val CATALOG = "catalog"

        /** The KOReader kosync partner, configured alongside it. */
        const val KOSYNC = "kosync"
    }
}

/**
 * Every partner at once, behind the single [PositionSync] the rest of
 * the app knows about.
 *
 * `PositionSyncCoordinator` still sees one thing to sync, so its
 * ordering rules — one run at a time, never answering a request with a
 * run that started too early — keep holding without being told there is
 * now more than one server involved.
 *
 * Peers run one after another rather than at once. They write to the
 * same rows, and two of them reconciling the same book in parallel is a
 * race over the reader's place; the network cost of doing them in turn
 * is paid in the background.
 */
class CompositePositionSync(private val peers: List<PeerPositionSync>) : PositionSync {

    override suspend fun syncAll(snapshot: SyncSnapshot?): SyncOutcome =
        fold(peers.map { it.syncAll(snapshot) })

    override suspend fun syncBook(bookUrl: String): SyncOutcome =
        fold(peers.map { it.syncBook(bookUrl) })

    override suspend fun canSync(bookUrl: String): Boolean =
        peers.any { it.canSync(bookUrl) }

    /**
     * The first peer with something to say, said in its own name.
     *
     * A preview answers "where does the server think you are", and with
     * several servers there is no single answer. The first peer that can
     * answer is used, which puts the catalog server first because that
     * is the one whose own interface shows the position too. A failure
     * is only reported when nobody could answer, since one unreachable
     * partner should not hide another's perfectly good answer.
     *
     * A peer that answers but has no position for this book is not an
     * answer worth stopping at either: it is kept in reserve and the
     * search goes on, so a partner that has never heard of the book
     * cannot silence one that has.
     *
     * Whoever answered is named on the preview. A choice made about one
     * partner's position must reach that partner and no other, and by
     * the time it is made this composite is long out of the picture.
     */
    override suspend fun previewBook(bookUrl: String): PreviewOutcome {
        var failure: SyncFailure? = null
        var empty: PreviewOutcome.Ready? = null
        for (peer in peers) {
            when (val outcome = peer.previewBook(bookUrl)) {
                is PreviewOutcome.Ready -> {
                    val named = PreviewOutcome.Ready(outcome.preview.from(peer))
                    if (named.preview.remote != null) return named
                    empty = empty ?: named
                }

                is PreviewOutcome.Failed -> failure = failure ?: outcome.reason
                PreviewOutcome.NotSynced -> Unit
            }
        }
        return empty ?: failure?.let(PreviewOutcome::Failed) ?: PreviewOutcome.NotSynced
    }

    override suspend fun preservedConflict(bookUrl: String, peerId: String?): SyncPreview? =
        holders(peerId).firstNotNullOfOrNull { peer ->
            peer.preservedConflict(bookUrl)?.from(peer)
        }

    override suspend fun takeRemotePosition(
        bookUrl: String,
        atRevision: Long,
        peerId: String?,
    ): ResolveOutcome =
        resolve(bookUrl, peerId) { it.takeRemotePosition(bookUrl, atRevision) }

    override suspend fun keepLocalPosition(bookUrl: String, peerId: String?): ResolveOutcome =
        resolve(bookUrl, peerId) { it.keepLocalPosition(bookUrl) }

    override suspend fun refreshUnresolved() {
        peers.forEach { it.refreshUnresolved() }
    }

    /**
     * Who positions on this device belong to.
     *
     * The first peer that knows. This is shown to explain why a
     * particular book will not sync, and naming every partner at once
     * would answer a question nobody asked; the peer order puts the
     * catalog server first, which is the account the reader signed into
     * by hand.
     */
    override suspend fun identity(): SyncIdentity? =
        peers.firstNotNullOfOrNull { it.identity() }

    /**
     * Sends a choice only to the peer that preserved the disagreement it
     * is about.
     *
     * Handing it to every peer would tell partners that never had a
     * conflict to act on one, and "keep what is here" against a peer
     * that already agreed is a write for no reason.
     *
     * [peerId] narrows it further, to the partner a question was actually
     * asked about. Without that, a dialog showing calibre-web's page
     * settles a kosync disagreement nobody was shown — and worse, one
     * peer adopting a position bumps the revision the next peer is
     * guarding against, so the second answers `Superseded` and the
     * combined answer reports that nothing happened after something
     * already has.
     */
    private suspend fun resolve(
        bookUrl: String,
        peerId: String?,
        act: suspend (PeerPositionSync) -> ResolveOutcome,
    ): ResolveOutcome {
        val holders = holders(peerId).filter { it.preservedConflict(bookUrl) != null }
        if (holders.isEmpty()) return ResolveOutcome.Done
        return holders
            .map { act(it) }
            .fold(ResolveOutcome.Done as ResolveOutcome) { worst, outcome ->
                when {
                    worst is ResolveOutcome.Superseded -> worst
                    outcome is ResolveOutcome.Superseded -> outcome
                    worst is ResolveOutcome.Failed -> worst
                    else -> outcome
                }
            }
    }

    /** The peers a request is about: one named, or all of them. */
    private fun holders(peerId: String?): List<PeerPositionSync> =
        if (peerId == null) peers else peers.filter { it.peerId == peerId }

    /** Names this peer on what it said, so a choice can find its way back. */
    private fun SyncPreview.from(peer: PeerPositionSync): SyncPreview =
        copy(peerId = peer.peerId)

    /**
     * What several partners' outcomes add up to.
     *
     * The distinction that matters is whether a retry is worth
     * scheduling, so anything that got some of the way and then failed
     * has to keep saying so. One peer succeeding does not make a
     * failure elsewhere go away: it makes the run partial, which is
     * retried while still leaving what did settle settled.
     */
    private fun fold(outcomes: List<SyncOutcome>): SyncOutcome {
        if (outcomes.isEmpty()) return SyncOutcome.NotApplicable
        val reason = outcomes.firstNotNullOfOrNull {
            when (it) {
                is SyncOutcome.Failure -> it.reason
                is SyncOutcome.Partial -> it.reason
                else -> null
            }
        }
        val anySuccess = outcomes.any { it == SyncOutcome.Success || it is SyncOutcome.Partial }
        return when {
            reason == null ->
                if (anySuccess) SyncOutcome.Success else SyncOutcome.NotApplicable

            anySuccess -> SyncOutcome.Partial(reason)
            else -> SyncOutcome.Failure(reason)
        }
    }
}
