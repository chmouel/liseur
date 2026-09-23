package com.chmouel.liseur.data.bookorbit

import androidx.room.withTransaction
import com.chmouel.liseur.data.db.BookOrbitPositionAgreement
import com.chmouel.liseur.data.db.BookOrbitLocalCfi
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.domain.ExactPositionDecision
import com.chmouel.liseur.domain.reconcileExactPosition
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

enum class BookOrbitAttempt { PREPARED, MAY_HAVE_BEEN_SENT, UNCERTAIN, REJECTED, ACKNOWLEDGED }

/** An opening proposal; only the active navigator can verify its locator. */
data class BookOrbitPullOffer(
    val context: BookOrbitCfiContext,
    val remote: BookOrbitFileProgress,
    val expectedRevision: Long,
    val expectedLocator: String,
    val locatorJson: String,
    val href: String,
)

sealed interface BookOrbitReadBack {
    data object Agreed : BookOrbitReadBack
    data object SafeToPrepareAgain : BookOrbitReadBack
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
) {
    private val dao get() = database.bookOrbitPositionAgreementDao()
    private val attemptMutex = Mutex()

    suspend fun state(context: BookOrbitCfiContext): BookOrbitPositionAgreement =
        database.withTransaction {
            checkCurrent(context)
            current(context)
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
            local.localRevision == row.agreedLocalRevision &&
            local.locatorJson == row.agreedLocatorJson &&
            row.agreedRemoteSaved != null && remote.isSaved && remote.cfi != null &&
            (row.agreedRemoteSaved != remote.isSaved || row.agreedRemoteCfi != remote.cfi)
    }

    /**
     * Called only after the original EPUB and active Readium DOM verified the offer.
     * A fresh GET and a closed-book fence precede the atomic revision-guarded write.
     * Status, generic sync baselines and acknowledgements are deliberately untouched.
     */
    suspend fun adoptVerifiedClosed(offer: BookOrbitPullOffer): Boolean {
        val context = offer.context
        val localProgression = runCatching {
            Locator.fromJSON(JSONObject(offer.locatorJson))?.locations?.totalProgression
        }.getOrNull()?.takeIf { it.isFinite() && it in 0.0..1.0 }
            ?: throw BookOrbitPositionUnresolved()
        val fresh = progress.read(context)
        if (!fresh.isSaved || fresh.cfi != offer.remote.cfi ||
            fresh.percentage != offer.remote.percentage
        ) {
            observe(context, fresh)
            return false
        }
        return database.readingProgressDao().openBooks.unlessOpen(context.bookUrl) {
            database.withTransaction {
                checkCurrent(context)
                val row = current(context)
                val local = database.readingProgressDao().get(context.bookUrl)
                if (row.attemptState !in listOf(null, BookOrbitAttempt.ACKNOWLEDGED.name) ||
                    local == null || local.localRevision != offer.expectedRevision ||
                    (local.ownerAccount != null && local.ownerAccount != context.request.accountKey) ||
                    local.locatorJson != offer.expectedLocator ||
                    row.agreedLocalRevision != offer.expectedRevision ||
                    row.agreedLocatorJson != offer.expectedLocator ||
                    row.agreedRemoteSaved == null ||
                    (row.agreedRemoteSaved == fresh.isSaved && row.agreedRemoteCfi == fresh.cfi) ||
                    row.candidateCfi != offer.remote.cfi ||
                    row.candidatePercentage != offer.remote.percentage ||
                    row.candidateSaved != offer.remote.isSaved
                ) return@withTransaction false
                val changed = database.readingProgressDao().adoptBookOrbitPosition(
                    context.bookUrl, offer.expectedRevision, offer.expectedLocator,
                    offer.locatorJson, localProgression, System.currentTimeMillis(),
                )
                if (changed != 1) return@withTransaction false
                val revision = offer.expectedRevision + 1
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
                BookOrbitAttempt.UNCERTAIN.name, BookOrbitAttempt.REJECTED.name,
            )
        ) return@withTransaction ExactPositionDecision.Unresolved
        val local = database.readingProgressDao().get(context.bookUrl)
        val verified = database.bookOrbitLocalCfiDao().get(context.request.accountKey, context.bookUrl)
            ?.takeIf { it.bookId == context.bookId && it.fileId == context.fileId &&
                it.bindingRevision == context.bindingRevision &&
                it.localRevision == local?.localRevision && it.locatorJson == local?.locatorJson }
        val decision = reconcileExactPosition(
            row.agreedLocalRevision, row.agreedLocatorJson,
            row.agreedRemoteSaved, row.agreedRemoteCfi,
            local?.localRevision, local?.locatorJson, verified?.rawCfi,
            remote.isSaved, remote.cfi, remoteVerified,
        )
        dao.write(row.copy(
            candidateCfi = remote.cfi, candidatePercentage = remote.percentage,
            candidateSaved = remote.isSaved, candidateUpdatedAt = remote.displayTime,
        ))
        if (decision == ExactPositionDecision.Settled && verified != null && local != null &&
            (!remote.isSaved || remote.cfi == verified.rawCfi)
        ) {
            dao.write(current(context).copy(
                agreedLocalRevision = local.localRevision, agreedLocatorJson = local.locatorJson,
                agreedRemoteCfi = remote.cfi, agreedRemotePercentage = remote.percentage,
                agreedRemoteSaved = remote.isSaved,
            ))
        }
        decision
    }

    /** Reads a fresh preflight and persists the exact UTF-8 bytes before any POST. */
    suspend fun prepare(context: BookOrbitCfiContext): BookOrbitPositionAgreement {
        database.withTransaction {
            checkCurrent(context)
            if (current(context).attemptState in listOf(
                    BookOrbitAttempt.PREPARED.name, BookOrbitAttempt.MAY_HAVE_BEEN_SENT.name,
                    BookOrbitAttempt.UNCERTAIN.name, BookOrbitAttempt.REJECTED.name,
                )
            ) throw BookOrbitPositionUnresolved()
        }
        val remote = progress.read(context)
        val prepared = database.withTransaction {
            checkCurrent(context)
            val row = current(context)
            if (row.attemptState in listOf(
                    BookOrbitAttempt.PREPARED.name, BookOrbitAttempt.MAY_HAVE_BEEN_SENT.name,
                    BookOrbitAttempt.UNCERTAIN.name, BookOrbitAttempt.REJECTED.name,
                )
            ) throw BookOrbitPositionUnresolved()
            val local = database.readingProgressDao().get(context.bookUrl) ?: throw BookOrbitPositionUnresolved()
            val verified = database.bookOrbitLocalCfiDao().get(context.request.accountKey, context.bookUrl)
                ?.takeIf { it.bookId == context.bookId && it.fileId == context.fileId &&
                    it.bindingRevision == context.bindingRevision &&
                    it.localRevision == local.localRevision && it.locatorJson == local.locatorJson }
                ?: throw BookOrbitPositionUnresolved()
            val decision = reconcileExactPosition(
                row.agreedLocalRevision, row.agreedLocatorJson,
                row.agreedRemoteSaved, row.agreedRemoteCfi,
                local.localRevision, local.locatorJson, verified.rawCfi,
                remote.isSaved, remote.cfi, false,
            )
            if (decision != ExactPositionDecision.Push) {
                dao.write(row.copy(
                    candidateCfi = remote.cfi, candidatePercentage = remote.percentage,
                    candidateSaved = remote.isSaved, candidateUpdatedAt = remote.displayTime,
                ))
                return@withTransaction null
            }
            val percentage = local.totalProgression?.times(100)
                ?.takeIf { it.isFinite() && it in 0.0..100.0 } ?: throw BookOrbitPositionUnresolved()
            val storedPercentage = percentage.toFloat().toString()
            val bytes = """{"percentage":$storedPercentage,"cfi":${org.json.JSONObject.quote(verified.rawCfi)}}"""
                .toByteArray(Charsets.UTF_8)
            row.copy(
                outgoingBytes = bytes, sentLocalRevision = local.localRevision,
                sentLocatorJson = local.locatorJson,
                preflightCfi = remote.cfi, preflightPercentage = remote.percentage,
                preflightSaved = remote.isSaved, attemptState = BookOrbitAttempt.PREPARED.name,
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
            database.readingProgressDao().get(context.bookUrl)?.localRevision == row.sentLocalRevision
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

    private suspend fun sendLocked(context: BookOrbitCfiContext): BookOrbitReadBack {
        val before = state(context)
        if (before.attemptState != BookOrbitAttempt.PREPARED.name ||
            before.outgoingBytes == null
        ) throw BookOrbitPositionUnresolved()
        if (database.readingProgressDao().get(context.bookUrl)?.localRevision != before.sentLocalRevision)
            throw BookOrbitPositionUnresolved()
        val preflight = progress.read(context)
        val row = database.withTransaction {
            checkCurrent(context)
            val pending = current(context)
            if (pending.attemptState != BookOrbitAttempt.PREPARED.name ||
                pending.outgoingBytes == null || pending.sentLocalRevision == null ||
                !pending.outgoingBytes.contentEquals(before.outgoingBytes)
            ) throw BookOrbitPositionUnresolved()
            if (preflight.isSaved != pending.preflightSaved ||
                preflight.cfi != pending.preflightCfi ||
                preflight.percentage != pending.preflightPercentage
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
            if (local?.localRevision != pending.sentLocalRevision ||
                local.locatorJson != pending.sentLocatorJson ||
                verified?.localRevision != pending.sentLocalRevision ||
                verified.rawCfi != org.json.JSONObject(String(pending.outgoingBytes, Charsets.UTF_8)).getString("cfi")
            ) throw BookOrbitPositionUnresolved()
            pending.copy(attemptState = BookOrbitAttempt.MAY_HAVE_BEEN_SENT.name)
                .also { dao.write(it) }
        } ?: throw BookOrbitPositionUnresolved()
        val result = transport.sendBytes(context, checkNotNull(row.outgoingBytes))
        if (result is BookOrbitHttp.MutationResult.Rejected) {
            database.withTransaction {
                checkCurrent(context)
                val pending = current(context)
                if (pending.attemptState != BookOrbitAttempt.MAY_HAVE_BEEN_SENT.name ||
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
                if (!row.outgoingBytes.contentEquals(pending.outgoingBytes)) throw BookOrbitPositionUnresolved()
                dao.write(row.copy(attemptState = BookOrbitAttempt.UNCERTAIN.name))
            }
            throw error
        }
        return database.withTransaction {
            checkCurrent(context)
            val row = current(context)
            if (row.attemptState !in listOf(
                    BookOrbitAttempt.MAY_HAVE_BEEN_SENT.name, BookOrbitAttempt.UNCERTAIN.name,
                ) || !row.outgoingBytes.contentEquals(pending.outgoingBytes)
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
                remote.sameAnchor(preflight) && remote.percentage == preflight.percentage ->
                    BookOrbitReadBack.SafeToPrepareAgain
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
                    else -> null
                },
                outgoingBytes = if (result == BookOrbitReadBack.Conflict) row.outgoingBytes else null,
                sentLocalRevision = if (result == BookOrbitReadBack.Conflict) row.sentLocalRevision else null,
                sentLocatorJson = if (result == BookOrbitReadBack.Conflict) row.sentLocatorJson else null,
            ))
            // The local row is never rewritten; a newer reader revision stays dirty.
            result
        }
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
}

class BookOrbitPositionUnresolved : IOException("BookOrbit position needs exact verification or read-back")
