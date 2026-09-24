package com.chmouel.liseur.data.bookorbit

import androidx.core.net.toUri
import com.chmouel.liseur.data.library.openableUri
import androidx.room.withTransaction
import com.chmouel.liseur.data.db.BookOrbitPositionAgreement
import com.chmouel.liseur.data.db.BookOrbitLocalCfi
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.ReadingProgress
import com.chmouel.liseur.domain.EPSILON
import com.chmouel.liseur.domain.ExactPositionDecision
import com.chmouel.liseur.domain.reconcileExactPosition
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

enum class BookOrbitAttempt { PREPARED, MAY_HAVE_BEEN_SENT, UNCERTAIN, RETRY_REQUIRED, REJECTED, ACKNOWLEDGED }

/** An opening proposal; only the active navigator can verify its locator. */
data class BookOrbitPullOffer(
    val context: BookOrbitCfiContext,
    val remote: BookOrbitFileProgress,
    val expectedRevision: Long,
    val expectedLocator: String,
    val locatorJson: String,
    val href: String,
    /** Opened with no place of this device's own, so nothing was agreed before. */
    val fresh: Boolean = false,
)

/** A percentage-only place to open at, tied to the local place and agreement it replaces. */
data class BookOrbitApproximateOffer internal constructor(
    val context: BookOrbitCfiContext,
    val remote: BookOrbitFileProgress,
    val progression: Double,
    val replacesLocalPlace: Boolean,
    internal val localRevision: Long?,
    internal val localLocator: String?,
    internal val baseline: BookOrbitPositionAgreement,
)

/** One unresolved answer, tied to the local place and agreement shown to the reader. */
data class BookOrbitConflictPreview internal constructor(
    val context: BookOrbitCfiContext,
    val remote: BookOrbitFileProgress,
    val localRevision: Long,
    val localLocator: String,
    val localProgression: Double?,
    val localCfi: String,
    internal val baseline: BookOrbitPositionAgreement,
) {
    val retryRequired: Boolean get() = baseline.outgoingBytes != null
}

sealed interface BookOrbitReadBack {
    data object Agreed : BookOrbitReadBack
    data object ExplicitRetryRequired : BookOrbitReadBack
    data object Conflict : BookOrbitReadBack
    data class Rejected(val status: Int) : BookOrbitReadBack
}

/**
 * Opt-in agreement boundary, deliberately not scheduled or registered as PositionSync.
 * Each write is one attempt; a lost response can only be resolved by a selected-file GET.
 */
class BookOrbitPositionAgreementRepository(
    private val database: LiseurDatabase,
    private val progress: BookOrbitProgressClient,
    private val transport: BookOrbitProgressMutationTransport,
    private val sources: BookOrbitAdoptedSource = BookOrbitAdoptedSource(null),
) {
    private val dao get() = database.bookOrbitPositionAgreementDao()
    private val attemptMutex get() = database.bookOrbitPositionMutex

    /** The agreement row as it changes; the reader watches it for places saved elsewhere. */
    fun observe(context: BookOrbitCfiContext): Flow<BookOrbitPositionAgreement?> =
        dao.observe(context.request.accountKey, context.bookUrl)

    suspend fun state(context: BookOrbitCfiContext): BookOrbitPositionAgreement =
        database.withTransaction {
            checkCurrent(context)
            current(context)
        }

    /** Never offers a percentage-only or another account's place as a choice. */
    suspend fun previewConflict(context: BookOrbitCfiContext): BookOrbitConflictPreview? =
        attemptMutex.withLock { previewConflictLocked(context) }

    private suspend fun previewConflictLocked(context: BookOrbitCfiContext): BookOrbitConflictPreview? {
        val remote = progress.read(context)
        observe(context, remote)
        return database.withTransaction {
            checkCurrent(context)
            val row = current(context)
            val local = database.readingProgressDao().get(context.bookUrl)
                ?: return@withTransaction null
            val verified = database.bookOrbitLocalCfiDao().get(context.request.accountKey, context.bookUrl)
                ?: return@withTransaction null
            if (!row.canChoose() ||
                local.ownerAccount != null && local.ownerAccount != context.request.accountKey ||
                verified.bookId != context.bookId || verified.fileId != context.fileId ||
                verified.bindingRevision != context.bindingRevision ||
                verified.localRevision != local.positionRevision ||
                verified.locatorJson != local.locatorJson ||
                (row.outgoingBytes == null &&
                    (!remote.isSaved || remote.cfi == null || remote.cfi == verified.rawCfi))
            ) return@withTransaction null
            val observed = row.copy(
                candidateCfi = remote.cfi, candidatePercentage = remote.percentage,
                candidateSaved = remote.isSaved, candidateUpdatedAt = remote.displayTime,
            )
            dao.write(observed)
            BookOrbitConflictPreview(
                context, remote, local.positionRevision, local.locatorJson,
                local.totalProgression, verified.rawCfi, observed,
            )
        }
    }

    /** Explicit keep-local choice only; [send] still preflights and reads back before agreement. */
    suspend fun prepareKeepLocal(preview: BookOrbitConflictPreview): BookOrbitPositionAgreement =
        attemptMutex.withLock { prepareKeepLocalLocked(preview, inReader = false) }

    /**
     * @param inReader The reader showing [preview] asks, with its own
     * position writes paused, so the book must be held open rather than
     * closed for the choice to apply.
     */
    suspend fun keepLocal(
        preview: BookOrbitConflictPreview,
        inReader: Boolean = false,
    ): BookOrbitReadBack = attemptMutex.withLock {
        prepareKeepLocalLocked(preview, inReader)
        sendLocked(preview.context)
    }

    private suspend fun prepareKeepLocalLocked(
        preview: BookOrbitConflictPreview,
        inReader: Boolean,
    ): BookOrbitPositionAgreement {
        val context = preview.context
        val fresh = progress.read(context)
        if (fresh != preview.remote) throw BookOrbitPositionUnresolved()
        return fenced(context.bookUrl, inReader) {
            database.withTransaction {
                checkCurrent(context)
                val row = current(context)
                val local = database.readingProgressDao().get(context.bookUrl)
                    ?: throw BookOrbitPositionUnresolved()
                val verified = database.bookOrbitLocalCfiDao().get(context.request.accountKey, context.bookUrl)
                if (!row.canChoose() ||
                    !row.matchesChoiceBaseline(preview.baseline) ||
                    !row.matchesCandidate(fresh) ||
                    local.ownerAccount != null && local.ownerAccount != context.request.accountKey ||
                    local.positionRevision != preview.localRevision ||
                    local.locatorJson != preview.localLocator ||
                    local.totalProgression != preview.localProgression ||
                    verified?.bookId != context.bookId || verified.fileId != context.fileId ||
                    verified.bindingRevision != context.bindingRevision ||
                    verified.localRevision != preview.localRevision ||
                    verified.locatorJson != preview.localLocator ||
                    verified.rawCfi != preview.localCfi ||
                    (!preview.retryRequired &&
                        (!fresh.isSaved || fresh.cfi == null || fresh.cfi == verified.rawCfi))
                ) throw BookOrbitPositionUnresolved()
                row.copy(
                    outgoingBytes = outgoingBytes(local, verified.rawCfi),
                    sentLocalRevision = local.positionRevision,
                    sentLocatorJson = local.locatorJson,
                    preflightCfi = fresh.cfi, preflightPercentage = fresh.percentage,
                    preflightSaved = fresh.isSaved, attemptState = BookOrbitAttempt.PREPARED.name,
                    attemptGeneration = Math.addExact(row.attemptGeneration, 1),
                ).also { dao.write(it) }
            }
        } ?: throw BookOrbitPositionUnresolved()
    }

    private fun BookOrbitPositionAgreement.matchesCandidate(remote: BookOrbitFileProgress): Boolean =
        candidateSaved == remote.isSaved && candidateCfi == remote.cfi &&
            candidatePercentage == remote.percentage && candidateUpdatedAt == remote.displayTime

    private fun BookOrbitPositionAgreement.canChoose(): Boolean =
        attemptState in listOf(null, BookOrbitAttempt.ACKNOWLEDGED.name) ||
            outgoingBytes != null && attemptState in listOf(
                BookOrbitAttempt.MAY_HAVE_BEEN_SENT.name, BookOrbitAttempt.UNCERTAIN.name,
                BookOrbitAttempt.RETRY_REQUIRED.name, BookOrbitAttempt.REJECTED.name,
            )


    private fun outgoingBytes(local: ReadingProgress, cfi: String): ByteArray {
        val percentage = local.totalProgression?.times(100)
            ?.takeIf { it.isFinite() && it in 0.0..100.0 } ?: throw BookOrbitPositionUnresolved()
        val storedPercentage = percentage.toFloat().toString()
        return """{"percentage":$storedPercentage,"cfi":${JSONObject.quote(cfi)}}"""
            .toByteArray(Charsets.UTF_8)
    }

    private fun BookOrbitPositionAgreement.matchesPreparedLocal(
        context: BookOrbitCfiContext,
        local: ReadingProgress?,
        verified: BookOrbitLocalCfi?,
    ): Boolean =
        outgoingBytes != null && local != null && verified != null &&
            (local.ownerAccount == null || local.ownerAccount == context.request.accountKey) &&
            local.positionRevision == sentLocalRevision && local.locatorJson == sentLocatorJson &&
            local.totalProgression?.times(100)?.let { it.isFinite() && it in 0.0..100.0 } == true &&
            verified.bookId == context.bookId && verified.fileId == context.fileId &&
            verified.bindingRevision == context.bindingRevision &&
            verified.localRevision == sentLocalRevision && verified.locatorJson == sentLocatorJson &&
            outgoingBytes(local, verified.rawCfi).contentEquals(outgoingBytes)

    suspend fun preparedStillMatches(context: BookOrbitCfiContext): Boolean = database.withTransaction {
        checkCurrent(context)
        val row = current(context)
        row.attemptState == BookOrbitAttempt.PREPARED.name && row.matchesPreparedLocal(
            context, database.readingProgressDao().get(context.bookUrl),
            database.bookOrbitLocalCfiDao().get(context.request.accountKey, context.bookUrl),
        )
    }

    /** Only a previously agreed, unchanged local place can be offered automatically. */
    suspend fun canOfferPull(
        context: BookOrbitCfiContext,
        remote: BookOrbitFileProgress,
    ): Boolean = database.withTransaction {
        checkCurrent(context)
        val row = current(context)
        val local = database.readingProgressDao().get(context.bookUrl)
        row.attemptState in listOf(null, BookOrbitAttempt.ACKNOWLEDGED.name) &&
            local != null && row.agreedLocalRevision != null &&
            (local.ownerAccount == null || local.ownerAccount == context.request.accountKey) &&
            local.positionRevision == row.agreedLocalRevision &&
            local.locatorJson == row.agreedLocatorJson &&
            row.agreedRemoteSaved != null && remote.isSaved && remote.cfi != null &&
            (row.agreedRemoteSaved != remote.isSaved || row.agreedRemoteCfi != remote.cfi)
    }

    /**
     * A percentage-only BookOrbit place this device may open at. Nothing is
     * agreed yet: [adoptApproximateBaseline] records it once the reader moves.
     * Offered for a book with no local place, for an agreed and untouched
     * local place the server has since moved away from, or for a local place
     * never matched with BookOrbit that the server is further ahead of.
     */
    suspend fun approximateOffer(
        context: BookOrbitCfiContext,
        remote: BookOrbitFileProgress,
    ): BookOrbitApproximateOffer? = attemptMutex.withLock {
        database.withTransaction {
            checkCurrent(context)
            val row = current(context)
            val progression = (remote.percentage / 100).takeIf {
                remote.isSaved && remote.cfi == null && it.isFinite() && it in 0.0..1.0
            } ?: return@withTransaction null
            if (row.attemptState !in listOf(null, BookOrbitAttempt.ACKNOWLEDGED.name))
                return@withTransaction null
            val local = database.readingProgressDao().get(context.bookUrl)
            if (local?.ownerAccount != null && local.ownerAccount != context.request.accountKey)
                return@withTransaction null
            val noPlace = local?.locatorJson == null && local?.totalProgression == null
            val eligible = when {
                noPlace -> true
                local == null -> false
                row.agreedRemoteSaved != null ->
                    local.positionRevision == row.agreedLocalRevision &&
                        local.locatorJson == row.agreedLocatorJson &&
                        !(row.agreedRemoteSaved == true && row.agreedRemoteCfi == null &&
                            row.agreedRemotePercentage == remote.percentage)
                else ->
                    database.bookOrbitLocalCfiDao().get(context.request.accountKey, context.bookUrl) == null &&
                        (local.totalProgression ?: 0.0) + EPSILON < progression
            }
            if (!eligible) return@withTransaction null
            val observed = row.copy(
                candidateCfi = remote.cfi, candidatePercentage = remote.percentage,
                candidateSaved = remote.isSaved, candidateUpdatedAt = remote.displayTime,
            )
            dao.write(observed)
            BookOrbitApproximateOffer(
                context, remote, progression, replacesLocalPlace = !noPlace,
                local?.positionRevision, local?.locatorJson, observed,
            )
        }
    }

    /**
     * The reader moved on from an approximate opening; see
     * [adoptApproximateBaselineIn]. The reader itself adopts through the
     * position write, so the move and its agreement commit together.
     */
    suspend fun adoptApproximateBaseline(offer: BookOrbitApproximateOffer): Boolean = attemptMutex.withLock {
        database.withTransaction { adoptApproximateBaselineIn(database, offer) }
    }

    /**
     * Called only after the original EPUB and active Readium DOM verified the offer.
     * A fresh GET and a closed-book fence precede the atomic revision-guarded write.
     * Status, generic sync baselines and acknowledgements are deliberately untouched.
     */
    suspend fun adoptVerifiedClosed(offer: BookOrbitPullOffer): Boolean =
        attemptMutex.withLock { adoptClosed(offer, null) }

    /** An unresolved choice additionally binds the verified offer to the preview's exact state. */
    suspend fun adoptChosenVerifiedClosed(
        preview: BookOrbitConflictPreview,
        offer: BookOrbitPullOffer,
    ): Boolean = attemptMutex.withLock { adoptClosed(offer, preview) }

    /** The same choice, made in the reader that shows the verified offer; see [keepLocal]. */
    suspend fun adoptChosenVerifiedInReader(
        preview: BookOrbitConflictPreview,
        offer: BookOrbitPullOffer,
    ): Boolean = attemptMutex.withLock { adoptClosed(offer, preview, inReader = true) }

    /**
     * The server moved while the book was open, and the reader accepted the
     * offer to follow it; see [catchUpOffer]. The reader has verified the
     * passage on screen and holds its own writes, so this device's place
     * must still be the one the offer replaces, even if it moved since the
     * agreement: accepting is the answer to that disagreement.
     */
    suspend fun adoptCaughtUpInReader(offer: BookOrbitPullOffer): Boolean =
        attemptMutex.withLock { adoptClosed(offer, null, inReader = true, caughtUp = true) }

    /**
     * Where the server moved since the agreement, read fresh, if the reader
     * may offer to follow it: an exact place, no request of this device's
     * own that explains it, and not the place this device is already on;
     * see [serverMovedAway].
     */
    suspend fun catchUpOffer(context: BookOrbitCfiContext): BookOrbitFileProgress? = attemptMutex.withLock {
        readBackIfPendingLocked(context)
        val remote = progress.read(context)
        observe(context, remote)
        database.withTransaction {
            checkCurrent(context)
            val row = current(context)
            val local = database.readingProgressDao().get(context.bookUrl)
            val verified = database.bookOrbitLocalCfiDao().get(context.request.accountKey, context.bookUrl)
            remote.takeIf {
                row.serverMovedAway() && row.matchesCandidate(remote) && local != null &&
                    (local.ownerAccount == null || local.ownerAccount == context.request.accountKey) &&
                    (verified?.localRevision != local.positionRevision || verified.rawCfi != remote.cfi)
            }
        }
    }

    /**
     * A [catchUpOffer] saved without a CFI, as a place the open reader can
     * go to like an approximate opening: nothing is agreed until the reader
     * moves on from it, and [adoptApproximateBaselineIn] refuses it once
     * anything changed since.
     */
    suspend fun approximateCatchUp(
        context: BookOrbitCfiContext,
        remote: BookOrbitFileProgress,
    ): BookOrbitApproximateOffer? = attemptMutex.withLock {
        database.withTransaction {
            checkCurrent(context)
            val row = current(context)
            val progression = (remote.percentage / 100).takeIf {
                remote.isSaved && remote.cfi == null && it.isFinite() && it in 0.0..1.0
            } ?: return@withTransaction null
            val local = database.readingProgressDao().get(context.bookUrl)
            if (!row.serverMovedAway() || !row.matchesCandidate(remote) || local == null ||
                local.ownerAccount != null && local.ownerAccount != context.request.accountKey
            ) return@withTransaction null
            BookOrbitApproximateOffer(
                context, remote, progression,
                replacesLocalPlace = local.locatorJson != null || local.totalProgression != null,
                local.positionRevision, local.locatorJson, row,
            )
        }
    }

    /** The reader kept its own place over [offer]; see [declineServerPlaceIn]. */
    suspend fun declineServerPlace(offer: BookOrbitPullOffer): Boolean = attemptMutex.withLock {
        database.withTransaction { declineServerPlaceIn(database, offer) }
    }

    private suspend fun adoptClosed(
        offer: BookOrbitPullOffer,
        preview: BookOrbitConflictPreview?,
        inReader: Boolean = false,
        caughtUp: Boolean = false,
    ): Boolean {
        val context = offer.context
        if (preview != null && (
                preview.context != context || preview.remote != offer.remote ||
                    preview.localRevision != offer.expectedRevision ||
                    preview.localLocator != offer.expectedLocator
            )
        ) throw BookOrbitPositionUnresolved()
        val localProgression = runCatching {
            Locator.fromJSON(JSONObject(offer.locatorJson))?.locations?.totalProgression
        }.getOrNull()?.takeIf { it.isFinite() && it in 0.0..1.0 }
            ?: throw BookOrbitPositionUnresolved()
        val fresh = progress.read(context)
        if (!fresh.isSaved || fresh.cfi == null || fresh.cfi != offer.remote.cfi ||
            fresh.percentage != offer.remote.percentage ||
            preview != null && fresh != preview.remote
        ) {
            observe(context, fresh)
            return false
        }
        return fenced(context.bookUrl, inReader) {
            database.withTransaction {
                checkCurrent(context)
                val row = current(context)
                val local = database.readingProgressDao().get(context.bookUrl)
                if ((preview == null && !caughtUp &&
                        row.attemptState !in listOf(null, BookOrbitAttempt.ACKNOWLEDGED.name)) ||
                    (preview != null && !row.canChoose()) ||
                    local == null || local.positionRevision != offer.expectedRevision ||
                    (local.ownerAccount != null && local.ownerAccount != context.request.accountKey) ||
                    local.locatorJson != offer.expectedLocator ||
                    row.candidateCfi != offer.remote.cfi ||
                    row.candidatePercentage != offer.remote.percentage ||
                    row.candidateSaved != offer.remote.isSaved ||
                    (caughtUp && !row.serverMovedAway()) ||
                    (preview == null && !caughtUp && (
                        row.agreedLocalRevision != offer.expectedRevision ||
                            row.agreedLocatorJson != offer.expectedLocator ||
                            row.agreedRemoteSaved == null ||
                            row.agreedRemoteSaved == fresh.isSaved && row.agreedRemoteCfi == fresh.cfi
                    )) ||
                    (preview != null && (
                        !row.matchesChoiceBaseline(preview.baseline) ||
                            !row.matchesCandidate(fresh) ||
                            local.totalProgression != preview.localProgression ||
                            database.bookOrbitLocalCfiDao().get(context.request.accountKey, context.bookUrl)
                                ?.let {
                                    it.bookId == context.bookId && it.fileId == context.fileId &&
                                        it.bindingRevision == context.bindingRevision &&
                                        it.localRevision == preview.localRevision &&
                                        it.locatorJson == preview.localLocator &&
                                        it.rawCfi == preview.localCfi
                                } != true
                    ))
                ) return@withTransaction false
                val changed = database.readingProgressDao().adoptBookOrbitPosition(
                    context.bookUrl, offer.expectedRevision, offer.expectedLocator,
                    offer.locatorJson, localProgression, System.currentTimeMillis(),
                )
                if (changed != 1) return@withTransaction false
                val revision = local.positionRevision + 1
                database.bookOrbitLocalCfiDao().write(BookOrbitLocalCfi(
                    context.request.accountKey, context.bookUrl, context.bookId,
                    context.fileId, context.bindingRevision, revision,
                    offer.locatorJson, checkNotNull(fresh.cfi),
                ))
                dao.write(row.copy(
                    agreedLocalRevision = revision, agreedLocatorJson = offer.locatorJson,
                    agreedRemoteCfi = fresh.cfi, agreedRemotePercentage = fresh.percentage,
                    agreedRemoteSaved = true,
                    candidateCfi = fresh.cfi, candidatePercentage = fresh.percentage,
                    candidateSaved = true, candidateUpdatedAt = fresh.displayTime,
                    attemptState = BookOrbitAttempt.ACKNOWLEDGED.name,
                    outgoingBytes = null, sentLocalRevision = null, sentLocatorJson = null,
                    preflightCfi = null, preflightPercentage = null, preflightSaved = null,
                ))
                true
            }
        } ?: false
    }

    suspend fun observe(
        context: BookOrbitCfiContext,
        remote: BookOrbitFileProgress,
        remoteVerified: Boolean = false,
    ): ExactPositionDecision = database.withTransaction {
        checkCurrent(context)
        val row = current(context)
        if (row.attemptState in listOf(
                BookOrbitAttempt.PREPARED.name, BookOrbitAttempt.MAY_HAVE_BEEN_SENT.name,
                BookOrbitAttempt.UNCERTAIN.name, BookOrbitAttempt.RETRY_REQUIRED.name, BookOrbitAttempt.REJECTED.name,
            )
        ) return@withTransaction ExactPositionDecision.Unresolved
        val local = database.readingProgressDao().get(context.bookUrl)
        val verified = database.bookOrbitLocalCfiDao().get(context.request.accountKey, context.bookUrl)
            ?.takeIf { it.bookId == context.bookId && it.fileId == context.fileId &&
                it.bindingRevision == context.bindingRevision &&
                it.localRevision == local?.positionRevision && it.locatorJson == local?.locatorJson &&
                (local?.ownerAccount == null || local.ownerAccount == context.request.accountKey) }
        val decision = reconcileExactPosition(
            row.agreedLocalRevision, row.agreedLocatorJson,
            row.agreedRemoteSaved, row.agreedRemoteCfi, row.agreedRemotePercentage,
            local?.positionRevision, local?.locatorJson, verified?.rawCfi,
            remote.isSaved, remote.cfi, remote.percentage, remoteVerified,
        )
        dao.write(row.copy(
            candidateCfi = remote.cfi, candidatePercentage = remote.percentage,
            candidateSaved = remote.isSaved, candidateUpdatedAt = remote.displayTime,
        ))
        if (decision == ExactPositionDecision.Settled && verified != null && local != null &&
            (!remote.isSaved || remote.cfi == verified.rawCfi)
        ) {
            dao.write(current(context).copy(
                agreedLocalRevision = local.positionRevision, agreedLocatorJson = local.locatorJson,
                agreedRemoteCfi = remote.cfi, agreedRemotePercentage = remote.percentage,
                agreedRemoteSaved = remote.isSaved,
            ))
        }
        decision
    }

    /** Reads a fresh preflight and persists the exact UTF-8 bytes before any POST. */
    suspend fun prepare(context: BookOrbitCfiContext): BookOrbitPositionAgreement {
        requireUploadedBytes(context)
        database.withTransaction {
            checkCurrent(context)
            if (current(context).attemptState in listOf(
                    BookOrbitAttempt.PREPARED.name, BookOrbitAttempt.MAY_HAVE_BEEN_SENT.name,
                    BookOrbitAttempt.UNCERTAIN.name, BookOrbitAttempt.RETRY_REQUIRED.name, BookOrbitAttempt.REJECTED.name,
                )
            ) throw BookOrbitPositionUnresolved()
        }
        val remote = progress.read(context)
        val prepared = database.withTransaction {
            checkCurrent(context)
            val row = current(context)
            if (row.attemptState in listOf(
                    BookOrbitAttempt.PREPARED.name, BookOrbitAttempt.MAY_HAVE_BEEN_SENT.name,
                    BookOrbitAttempt.UNCERTAIN.name, BookOrbitAttempt.RETRY_REQUIRED.name, BookOrbitAttempt.REJECTED.name,
                )
            ) throw BookOrbitPositionUnresolved()
            val local = database.readingProgressDao().get(context.bookUrl) ?: throw BookOrbitPositionUnresolved()
            if (local.ownerAccount != null && local.ownerAccount != context.request.accountKey)
                throw BookOrbitPositionUnresolved()
            val verified = database.bookOrbitLocalCfiDao().get(context.request.accountKey, context.bookUrl)
                ?.takeIf { it.bookId == context.bookId && it.fileId == context.fileId &&
                    it.bindingRevision == context.bindingRevision &&
                    it.localRevision == local.positionRevision && it.locatorJson == local.locatorJson }
                ?: throw BookOrbitPositionUnresolved()
            val decision = reconcileExactPosition(
                row.agreedLocalRevision, row.agreedLocatorJson,
                row.agreedRemoteSaved, row.agreedRemoteCfi, row.agreedRemotePercentage,
                local.positionRevision, local.locatorJson, verified.rawCfi,
                remote.isSaved, remote.cfi, remote.percentage, false,
            )
            if (decision != ExactPositionDecision.Push) {
                dao.write(row.copy(
                    candidateCfi = remote.cfi, candidatePercentage = remote.percentage,
                    candidateSaved = remote.isSaved, candidateUpdatedAt = remote.displayTime,
                ))
                return@withTransaction null
            }
            row.copy(
                outgoingBytes = outgoingBytes(local, verified.rawCfi), sentLocalRevision = local.positionRevision,
                sentLocatorJson = local.locatorJson,
                preflightCfi = remote.cfi, preflightPercentage = remote.percentage,
                preflightSaved = remote.isSaved, attemptState = BookOrbitAttempt.PREPARED.name,
                attemptGeneration = Math.addExact(row.attemptGeneration, 1),
                candidateCfi = remote.cfi, candidatePercentage = remote.percentage,
                candidateSaved = remote.isSaved, candidateUpdatedAt = remote.displayTime,
            ).also { dao.write(it) }
        }
        return prepared ?: throw BookOrbitPositionUnresolved()
    }

    /** A prepared request that never reached send can be replaced after the reader moved. */
    suspend fun discardStalePreparation(context: BookOrbitCfiContext) = database.withTransaction {
        checkCurrent(context)
        val row = current(context)
        if (row.attemptState != BookOrbitAttempt.PREPARED.name ||
            row.matchesPreparedLocal(
                context, database.readingProgressDao().get(context.bookUrl),
                database.bookOrbitLocalCfiDao().get(context.request.accountKey, context.bookUrl),
            )
        ) throw BookOrbitPositionUnresolved()
        dao.write(row.copy(
            attemptState = null, outgoingBytes = null, sentLocalRevision = null,
            sentLocatorJson = null, preflightCfi = null, preflightPercentage = null,
            preflightSaved = null,
        ))
    }

    suspend fun send(context: BookOrbitCfiContext): BookOrbitReadBack {
        attemptMutex.lock()
        try {
            return sendLocked(context)
        } finally {
            attemptMutex.unlock()
        }
    }

    /**
     * Not fenced by [com.chmouel.liseur.data.remote.OpenBooks]: a send
     * writes only the agreement row and never moves the page on screen, so
     * the book being read is exactly the one worth sending. A page turned
     * meanwhile is a newer revision that stays dirty for the next send.
     */
    private suspend fun sendLocked(context: BookOrbitCfiContext): BookOrbitReadBack {
        val before = state(context)
        if (before.attemptState != BookOrbitAttempt.PREPARED.name ||
            before.outgoingBytes == null
        ) throw BookOrbitPositionUnresolved()
        if (!preparedStillMatches(context)) throw BookOrbitPositionUnresolved()
        // Also for a send resumed after a restart, which never reopens
        // the book: the stored CFI was computed in bytes that must still
        // be the ones on the device.
        requireUploadedBytes(context)
        val preflight = progress.read(context)
        val row = database.withTransaction {
            checkCurrent(context)
            val pending = current(context)
            if (pending.attemptState != BookOrbitAttempt.PREPARED.name ||
                pending.outgoingBytes == null || pending.sentLocalRevision == null ||
                pending.attemptGeneration != before.attemptGeneration ||
                !pending.outgoingBytes.contentEquals(before.outgoingBytes)
            ) throw BookOrbitPositionUnresolved()
            if (preflight.isSaved != pending.preflightSaved ||
                preflight.cfi != pending.preflightCfi ||
                preflight.percentage != pending.preflightPercentage ||
                preflight.displayTime != pending.candidateUpdatedAt
            ) {
                dao.write(pending.copy(
                    attemptState = null, outgoingBytes = null, sentLocalRevision = null,
                    sentLocatorJson = null, preflightCfi = null,
                    preflightPercentage = null, preflightSaved = null,
                    candidateCfi = preflight.cfi, candidatePercentage = preflight.percentage,
                    candidateSaved = preflight.isSaved, candidateUpdatedAt = preflight.displayTime,
                ))
                return@withTransaction null
            }
            val local = database.readingProgressDao().get(context.bookUrl)
            val verified = database.bookOrbitLocalCfiDao().get(context.request.accountKey, context.bookUrl)
            if (!pending.matchesPreparedLocal(context, local, verified))
                throw BookOrbitPositionUnresolved()
            pending.copy(attemptState = BookOrbitAttempt.MAY_HAVE_BEEN_SENT.name)
                .also { dao.write(it) }
        } ?: throw BookOrbitPositionUnresolved()
        val result = transport.sendBytes(context, checkNotNull(row.outgoingBytes))
        if (result is BookOrbitHttp.MutationResult.Rejected) {
            database.withTransaction {
                checkCurrent(context)
                val pending = current(context)
                if (pending.attemptState != BookOrbitAttempt.MAY_HAVE_BEEN_SENT.name ||
                    pending.attemptGeneration != row.attemptGeneration ||
                    !pending.outgoingBytes.contentEquals(row.outgoingBytes)
                ) throw BookOrbitPositionUnresolved()
                dao.write(pending.copy(attemptState = BookOrbitAttempt.REJECTED.name))
            }
            return BookOrbitReadBack.Rejected(result.status)
        }
        return withContext(NonCancellable) {
            readBackLocked(context)
        }
    }

    /** Also handles a MAY_HAVE_BEEN_SENT row after process death, without replaying it. */
    suspend fun readBackIfPending(context: BookOrbitCfiContext): BookOrbitReadBack? =
        attemptMutex.withLock { readBackIfPendingLocked(context) }

    private suspend fun readBackIfPendingLocked(context: BookOrbitCfiContext): BookOrbitReadBack? {
        if (state(context).attemptState !in listOf(
                BookOrbitAttempt.MAY_HAVE_BEEN_SENT.name, BookOrbitAttempt.UNCERTAIN.name,
                BookOrbitAttempt.RETRY_REQUIRED.name,
            )
        ) return null
        return readBackLocked(context)
    }

    suspend fun readBack(context: BookOrbitCfiContext): BookOrbitReadBack {
        attemptMutex.lock()
        try {
            return readBackLocked(context)
        } finally {
            attemptMutex.unlock()
        }
    }

    private suspend fun readBackLocked(context: BookOrbitCfiContext): BookOrbitReadBack {
        val pending = database.withTransaction {
            checkCurrent(context)
            current(context).takeIf {
                it.attemptState in listOf(
                    BookOrbitAttempt.MAY_HAVE_BEEN_SENT.name, BookOrbitAttempt.UNCERTAIN.name,
                    BookOrbitAttempt.RETRY_REQUIRED.name,
                ) && it.outgoingBytes != null
            } ?: throw BookOrbitPositionUnresolved()
        }
        val remote = try {
            progress.read(context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            database.withTransaction {
                checkCurrent(context)
                val row = current(context)
                if (row.attemptGeneration != pending.attemptGeneration ||
                    !row.outgoingBytes.contentEquals(pending.outgoingBytes)
                ) throw BookOrbitPositionUnresolved()
                dao.write(row.copy(attemptState = BookOrbitAttempt.UNCERTAIN.name))
            }
            throw error
        }
        return database.withTransaction {
            checkCurrent(context)
            val row = current(context)
            if (row.attemptState !in listOf(
                    BookOrbitAttempt.MAY_HAVE_BEEN_SENT.name, BookOrbitAttempt.UNCERTAIN.name,
                    BookOrbitAttempt.RETRY_REQUIRED.name,
                ) || row.attemptGeneration != pending.attemptGeneration ||
                !row.outgoingBytes.contentEquals(pending.outgoingBytes)
            ) throw BookOrbitPositionUnresolved()
            if (database.readingProgressDao().get(context.bookUrl) == null) {
                throw BookOrbitPositionUnresolved()
            }
            val sent = org.json.JSONObject(String(checkNotNull(row.outgoingBytes), Charsets.UTF_8))
            val preflight = BookOrbitFileProgress(
                checkNotNull(row.preflightPercentage), row.preflightCfi, null, null,
                if (row.preflightSaved == true) "saved" else null,
            )
            val result = when {
                remote.isSaved && remote.cfi == sent.getString("cfi") &&
                    remote.percentage == sent.getDouble("percentage") -> BookOrbitReadBack.Agreed
                (remote.sameAnchor(preflight) || remote.isSaved && preflight.isSaved &&
                    remote.cfi == null && preflight.cfi == null) &&
                    remote.percentage == preflight.percentage ->
                    BookOrbitReadBack.ExplicitRetryRequired
                else -> BookOrbitReadBack.Conflict
            }
            dao.write(row.copy(
                agreedLocalRevision = if (result == BookOrbitReadBack.Agreed)
                    row.sentLocalRevision else row.agreedLocalRevision,
                agreedLocatorJson = if (result == BookOrbitReadBack.Agreed)
                    row.sentLocatorJson else row.agreedLocatorJson,
                agreedRemoteCfi = if (result == BookOrbitReadBack.Agreed)
                    remote.cfi else row.agreedRemoteCfi,
                agreedRemotePercentage = if (result == BookOrbitReadBack.Agreed)
                    remote.percentage else row.agreedRemotePercentage,
                agreedRemoteSaved = if (result == BookOrbitReadBack.Agreed)
                    remote.isSaved else row.agreedRemoteSaved,
                candidateCfi = remote.cfi, candidatePercentage = remote.percentage,
                candidateSaved = remote.isSaved, candidateUpdatedAt = remote.displayTime,
                attemptState = when (result) {
                    BookOrbitReadBack.Agreed -> BookOrbitAttempt.ACKNOWLEDGED.name
                    BookOrbitReadBack.Conflict -> BookOrbitAttempt.UNCERTAIN.name
                    else -> BookOrbitAttempt.RETRY_REQUIRED.name
                },
                outgoingBytes = if (result == BookOrbitReadBack.Agreed) null else row.outgoingBytes,
                sentLocalRevision = if (result == BookOrbitReadBack.Agreed) null else row.sentLocalRevision,
                sentLocatorJson = if (result == BookOrbitReadBack.Agreed) null else row.sentLocatorJson,
            ))
            // The local row is never rewritten; a newer reader revision stays dirty.
            result
        }
    }

    /** A closed book normally; the open one when its reader made the choice. */
    private suspend fun <T> fenced(bookUrl: String, inReader: Boolean, apply: suspend () -> T): T? {
        val books = database.readingProgressDao().openBooks
        return if (inReader) books.whileHeld(bookUrl, apply) else books.unlessOpen(bookUrl, apply)
    }

    private suspend fun current(context: BookOrbitCfiContext): BookOrbitPositionAgreement {
        val row = dao.get(context.request.accountKey, context.bookUrl)
            ?: return BookOrbitPositionAgreement(
                context.request.accountKey, context.bookUrl, context.bookId,
                context.fileId, context.bindingRevision, context.request.epoch,
                context.request.baseUrl,
            )
        if (row.bookId != context.bookId || row.fileId != context.fileId ||
            row.bindingRevision != context.bindingRevision ||
            row.connectionEpoch != context.request.epoch ||
            row.baseUrl != context.request.baseUrl
        ) throw BookOrbitIdentityChanged()
        return row
    }

    private suspend fun checkCurrent(context: BookOrbitCfiContext) {
        if (!context.request.matches(database.remoteServerDao().get()) ||
            !context.matches(database.bookOrbitBindingDao().get(context.request.accountKey, context.bookUrl))
        ) throw BookOrbitIdentityChanged()
    }

    /**
     * For a book adopted from an upload, refuses to go on unless its file
     * still holds the bytes the server took.
     *
     * A CFI names a place in particular bytes. An uploaded book keeps its
     * own file, which can be replaced without the reader opening the book
     * again; sending a place computed in the old bytes would move every
     * other device to a place in a book that is no longer this one.
     * Nothing is sent and nothing is marked as possibly sent.
     */
    private suspend fun requireUploadedBytes(context: BookOrbitCfiContext) {
        val binding = database.bookOrbitBindingDao().get(context.request.accountKey, context.bookUrl)
            ?: throw BookOrbitIdentityChanged()
        val expected = binding.localSha256 ?: return
        val book = database.bookDao().getByUrl(context.bookUrl) ?: throw BookOrbitIdentityChanged()
        val source = book.openableUri()?.toUri() ?: throw BookOrbitPositionUnresolved()
        if (!sources.holds(context.bookUrl, source, expected)) throw BookOrbitPositionUnresolved()
    }
}

class BookOrbitPositionUnresolved : IOException("BookOrbit position needs exact verification or read-back")

private fun BookOrbitPositionAgreement.matchesChoiceBaseline(other: BookOrbitPositionAgreement): Boolean =
    accountKey == other.accountKey && bookUrl == other.bookUrl &&
        bookId == other.bookId && fileId == other.fileId &&
        bindingRevision == other.bindingRevision && connectionEpoch == other.connectionEpoch &&
        baseUrl == other.baseUrl && agreedLocalRevision == other.agreedLocalRevision &&
        agreedLocatorJson == other.agreedLocatorJson &&
        agreedRemoteCfi == other.agreedRemoteCfi &&
        agreedRemotePercentage == other.agreedRemotePercentage &&
        agreedRemoteSaved == other.agreedRemoteSaved &&
        candidateCfi == other.candidateCfi &&
        candidatePercentage == other.candidatePercentage &&
        candidateSaved == other.candidateSaved &&
        candidateUpdatedAt == other.candidateUpdatedAt &&
        attemptState == other.attemptState && attemptGeneration == other.attemptGeneration &&
        outgoingBytes.contentEquals(other.outgoingBytes) &&
        sentLocalRevision == other.sentLocalRevision && sentLocatorJson == other.sentLocatorJson &&
        preflightCfi == other.preflightCfi && preflightPercentage == other.preflightPercentage &&
        preflightSaved == other.preflightSaved

/**
 * The percentage an approximate opening showed becomes the agreed remote,
 * and the place before opening the agreed local, so the move is pushed as
 * an exact place. Must run inside a transaction: from
 * [BookOrbitLocalPositionWriter] it is the one that saved the move. The
 * whole-row snapshot stands in for the attempt lock, so anything a sync
 * wrote since the offer, a changed server or a started attempt, refuses it,
 * as does a move that did not land.
 */
internal suspend fun adoptApproximateBaselineIn(
    database: LiseurDatabase,
    offer: BookOrbitApproximateOffer,
): Boolean {
    val context = offer.context
    if (!context.request.matches(database.remoteServerDao().get()) ||
        !context.matches(database.bookOrbitBindingDao().get(context.request.accountKey, context.bookUrl))
    ) return false
    val dao = database.bookOrbitPositionAgreementDao()
    val row = dao.get(context.request.accountKey, context.bookUrl) ?: return false
    val local = database.readingProgressDao().get(context.bookUrl)
    val uncertain = row.attemptState == BookOrbitAttempt.UNCERTAIN.name && row.serverMovedAway()
    if (local == null || local.positionRevision == offer.localRevision ||
        row.attemptState !in listOf(null, BookOrbitAttempt.ACKNOWLEDGED.name) && !uncertain ||
        !row.matchesChoiceBaseline(offer.baseline) ||
        local.ownerAccount != null && local.ownerAccount != context.request.accountKey
    ) return false
    val agreed = row.copy(
        agreedLocalRevision = offer.localRevision, agreedLocatorJson = offer.localLocator,
        agreedRemoteSaved = true, agreedRemoteCfi = null,
        agreedRemotePercentage = offer.remote.percentage,
    )
    // Followed from the reader after someone else wrote over this device's
    // request: that request is dropped rather than sent again.
    dao.write(
        if (!uncertain) agreed else agreed.copy(
            attemptState = null, outgoingBytes = null, sentLocalRevision = null,
            sentLocatorJson = null, preflightCfi = null, preflightPercentage = null,
            preflightSaved = null,
        ),
    )
    return true
}

/**
 * The reader moved on from a verified server place it opened at. That place
 * becomes the agreed remote one in the move's own transaction, so the move
 * is pushed instead of looking like movement on both sides. The pull was
 * offered only because the local place was the agreed one; anything else,
 * or an agreement that has since moved on, refuses it.
 */
internal suspend fun agreeOpeningPullIn(
    database: LiseurDatabase,
    offer: BookOrbitPullOffer,
): Boolean {
    val context = offer.context
    if (!context.request.matches(database.remoteServerDao().get()) ||
        !context.matches(database.bookOrbitBindingDao().get(context.request.accountKey, context.bookUrl))
    ) return false
    val dao = database.bookOrbitPositionAgreementDao()
    val row = dao.get(context.request.accountKey, context.bookUrl) ?: return false
    val local = database.readingProgressDao().get(context.bookUrl) ?: return false
    val baseline = if (offer.fresh) {
        row.agreedRemoteSaved == null && row.agreedLocalRevision == null && row.agreedLocatorJson == null
    } else {
        row.agreedLocalRevision == offer.expectedRevision && row.agreedLocatorJson == offer.expectedLocator
    }
    if (local.positionRevision == offer.expectedRevision ||
        local.ownerAccount != null && local.ownerAccount != context.request.accountKey ||
        row.attemptState !in listOf(null, BookOrbitAttempt.ACKNOWLEDGED.name) ||
        !baseline ||
        !offer.remote.isSaved || offer.remote.cfi == null ||
        row.agreedRemoteSaved == true && row.agreedRemoteCfi == offer.remote.cfi ||
        row.candidateCfi != offer.remote.cfi ||
        row.candidatePercentage != offer.remote.percentage ||
        row.candidateSaved != offer.remote.isSaved
    ) return false
    dao.write(row.copy(
        agreedRemoteCfi = offer.remote.cfi, agreedRemotePercentage = offer.remote.percentage,
        agreedRemoteSaved = true,
    ))
    return true
}

/**
 * The server holds an exact place this device has not agreed with, and no
 * request of this device's own explains it: someone else read on (or back)
 * since the agreement. That includes a request whose read-back found
 * neither the place it sent nor the one seen before sending, when someone
 * else wrote in between. That request is never sent again; the reader
 * follows the server's place or keeps its own, which starts a new one.
 */
fun BookOrbitPositionAgreement.serverMovedAway(): Boolean {
    if (candidateSaved != true) return false
    return when (attemptState) {
        null, BookOrbitAttempt.ACKNOWLEDGED.name -> when {
            candidateCfi != null ->
                agreedRemoteSaved != null && (agreedRemoteSaved != true || agreedRemoteCfi != candidateCfi)
            // A place saved without a CFI is never pushed over unasked, so
            // one never agreed is offered too; otherwise it would hold this
            // book's sync forever.
            else -> agreedRemoteSaved != true || agreedRemoteCfi != null ||
                agreedRemotePercentage != candidatePercentage
        }
        BookOrbitAttempt.UNCERTAIN.name -> {
            val sent = outgoingBytes?.let {
                runCatching { JSONObject(String(it, Charsets.UTF_8)).getString("cfi") }.getOrNull()
            } ?: return false
            candidateCfi != sent && (
                preflightSaved != true || preflightCfi != candidateCfi ||
                    preflightPercentage != candidatePercentage
                )
        }
        else -> false
    }
}

/**
 * The reader saw the server's place and kept its own. That place becomes
 * the agreed remote one, so this device's next move is pushed over it: the
 * last write wins. Refused unless it is still the place last seen.
 */
internal suspend fun declineServerPlaceIn(
    database: LiseurDatabase,
    offer: BookOrbitPullOffer,
): Boolean {
    val context = offer.context
    if (!context.request.matches(database.remoteServerDao().get()) ||
        !context.matches(database.bookOrbitBindingDao().get(context.request.accountKey, context.bookUrl))
    ) return false
    val dao = database.bookOrbitPositionAgreementDao()
    val row = dao.get(context.request.accountKey, context.bookUrl) ?: return false
    if (!row.serverMovedAway() ||
        row.candidateCfi != offer.remote.cfi ||
        row.candidatePercentage != offer.remote.percentage
    ) return false
    val agreed = row.copy(
        agreedRemoteCfi = offer.remote.cfi, agreedRemotePercentage = offer.remote.percentage,
        agreedRemoteSaved = true,
    )
    // An uncertain request is dropped, not replayed: the next send prepares
    // this device's current place afresh, with its own preflight.
    dao.write(
        if (row.attemptState != BookOrbitAttempt.UNCERTAIN.name) agreed else agreed.copy(
            attemptState = null, outgoingBytes = null, sentLocalRevision = null,
            sentLocatorJson = null, preflightCfi = null, preflightPercentage = null,
            preflightSaved = null,
        ),
    )
    return true
}
