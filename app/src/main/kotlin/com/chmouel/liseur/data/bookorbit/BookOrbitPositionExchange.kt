package com.chmouel.liseur.data.bookorbit

import com.chmouel.liseur.domain.ExactPositionDecision
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A single selected-file exchange. Not registered for automatic position sync. */
class BookOrbitPositionExchange(
    private val cfis: BookOrbitCfiRepository,
    private val progress: BookOrbitProgressClient,
    private val agreement: BookOrbitPositionAgreementRepository,
) {
    private val turn = Mutex()

    sealed interface Result {
        data object Agreed : Result
        data object Pushed : Result
        data object Recovered : Result
        data object Conflict : Result
        data object Unresolved : Result
        data object RetryAfterReadBack : Result
        data class Rejected(val status: Int) : Result
    }

    suspend fun previewConflict(bookUrl: String): BookOrbitConflictPreview? = turn.withLock {
        agreement.previewConflict(cfis.capture(bookUrl))
    }

    suspend fun keepLocal(preview: BookOrbitConflictPreview): Result = turn.withLock {
        agreement.prepareKeepLocal(preview)
        delivered(agreement.send(preview.context))
    }

    suspend fun run(bookUrl: String): Result = turn.withLock {
        val context = cfis.capture(bookUrl)
        val pending = agreement.state(context)
        when (pending.attemptState) {
            BookOrbitAttempt.REJECTED.name -> return@withLock Result.Unresolved
            BookOrbitAttempt.MAY_HAVE_BEEN_SENT.name, BookOrbitAttempt.UNCERTAIN.name ->
                return@withLock recovered(agreement.readBack(context))
            BookOrbitAttempt.PREPARED.name -> {
                if (agreement.preparedStillMatches(context)) {
                    return@withLock delivered(agreement.send(context))
                }
                agreement.discardStalePreparation(context)
            }
        }

        val remote = progress.read(context)
        when (agreement.observe(context, remote)) {
            ExactPositionDecision.Settled -> Result.Agreed
            ExactPositionDecision.Pull -> Result.Unresolved
            ExactPositionDecision.Conflict -> Result.Conflict
            ExactPositionDecision.Unresolved -> Result.Unresolved
            ExactPositionDecision.Push -> {
                agreement.prepare(context)
                delivered(agreement.send(context))
            }
        }
    }

    private fun delivered(result: BookOrbitReadBack): Result = when (result) {
        BookOrbitReadBack.Agreed -> Result.Pushed
        BookOrbitReadBack.SafeToPrepareAgain -> Result.RetryAfterReadBack
        BookOrbitReadBack.Conflict -> Result.Conflict
        is BookOrbitReadBack.Rejected -> Result.Rejected(result.status)
    }

    private fun recovered(result: BookOrbitReadBack): Result = when (result) {
        BookOrbitReadBack.Agreed -> Result.Recovered
        else -> delivered(result)
    }
}
