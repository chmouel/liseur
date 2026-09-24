package com.chmouel.liseur.data.bookorbit

import com.chmouel.liseur.domain.ExactPositionDecision
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A single selected-file exchange shared by checked choices and bounded sync. */
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

    suspend fun keepLocal(
        preview: BookOrbitConflictPreview,
        inReader: Boolean = false,
    ): Result = turn.withLock {
        delivered(agreement.keepLocal(preview, inReader))
    }

    suspend fun run(bookUrl: String): Result = run(cfis.capture(bookUrl), allowPush = true)

    internal suspend fun run(context: BookOrbitCfiContext, allowPush: Boolean): Result = turn.withLock {
        cfis.check(context)
        val pending = agreement.state(context)
        when (pending.attemptState) {
            BookOrbitAttempt.REJECTED.name -> return@withLock Result.Unresolved
            BookOrbitAttempt.MAY_HAVE_BEEN_SENT.name, BookOrbitAttempt.UNCERTAIN.name,
            BookOrbitAttempt.RETRY_REQUIRED.name ->
                return@withLock recovered(agreement.readBack(context))
            BookOrbitAttempt.PREPARED.name -> {
                if (!allowPush) return@withLock Result.Unresolved
                if (agreement.preparedStillMatches(context)) {
                    return@withLock delivered(agreement.send(context))
                }
                agreement.discardStalePreparation(context)
            }
            null, BookOrbitAttempt.ACKNOWLEDGED.name -> Unit
            else -> return@withLock Result.Unresolved
        }

        val remote = progress.read(context)
        when (agreement.observe(context, remote)) {
            ExactPositionDecision.Settled -> Result.Agreed
            ExactPositionDecision.Pull -> Result.Unresolved
            ExactPositionDecision.Conflict -> Result.Conflict
            ExactPositionDecision.Unresolved -> Result.Unresolved
            ExactPositionDecision.Push -> {
                if (!allowPush) return@withLock Result.Unresolved
                agreement.prepare(context)
                delivered(agreement.send(context))
            }
        }
    }

    private fun delivered(result: BookOrbitReadBack): Result = when (result) {
        BookOrbitReadBack.Agreed -> Result.Pushed
        BookOrbitReadBack.ExplicitRetryRequired -> Result.RetryAfterReadBack
        BookOrbitReadBack.Conflict -> Result.Conflict
        is BookOrbitReadBack.Rejected -> Result.Rejected(result.status)
    }

    private fun recovered(result: BookOrbitReadBack): Result = when (result) {
        BookOrbitReadBack.Agreed -> Result.Recovered
        else -> delivered(result)
    }
}
