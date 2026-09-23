package com.chmouel.liseur.data.bookorbit

import androidx.room.withTransaction
import com.chmouel.liseur.data.db.BookOrbitStatusAgreement
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.ReadingProgress
import com.chmouel.liseur.data.library.FinishedState
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.SyncOutcome
import com.chmouel.liseur.domain.FinishedOverride
import kotlinx.coroutines.sync.withLock

/** Reconciles a book-level status independently of its saved passage. */
class BookOrbitStatusSync(
    private val database: LiseurDatabase,
    private val client: BookOrbitStatusClient,
    private val finishedState: FinishedState,
) {
    private val cfis = BookOrbitCfiRepository(database)
    private val agreements get() = database.bookOrbitStatusAgreementDao()
    private val progress get() = database.readingProgressDao()

    suspend fun sync(context: BookOrbitCfiContext): SyncOutcome =
        database.bookOrbitStatusMutex.withLock {
            cfis.check(context)
            var agreement = agreements.get(context.request.accountKey, context.bookUrl)
                ?.takeIf { it.bookId == context.bookId }
                ?.forContext(context)

            var local = progress.get(context.bookUrl)
            if (local.ownedByAnother(context)) return@withLock SyncOutcome.NotApplicable
            if (agreement?.outgoingBytes != null) {
                val isNewLocalChoice =
                    local?.statusRevision != agreement.sentLocalStatusRevision ||
                        local?.finishedOverride != agreement.sentLocalOverride
                if (!isNewLocalChoice && agreement.attemptState != PREPARED) {
                    return@withLock recover(context, agreement)
                }

                agreement = agreement.copy(
                    outgoingBytes = null,
                    sentStatus = null,
                    sentLocalStatusRevision = null,
                    sentLocalOverride = null,
                    preflightRemoteStatus = null,
                    preflightRemoteSource = null,
                    attemptState = null,
                )
                save(context, agreement)
            }

            val remote = client.read(context)
            cfis.check(context)
            val latestLocal = progress.get(context.bookUrl)
            if (latestLocal?.statusRevision != local?.statusRevision ||
                latestLocal?.finishedOverride != local?.finishedOverride ||
                latestLocal?.ownerAccount != local?.ownerAccount
            ) return@withLock SyncOutcome.Failure(SyncFailure.StaleIdentity)
            local = latestLocal

            val localOverride = local?.finishedOverride ?: FinishedOverride.NONE.ordinal
            val localRevision = local?.statusRevision ?: 0L
            val localChanged = if (agreement == null) {
                localOverride != FinishedOverride.NONE.ordinal
            } else {
                localRevision != agreement.agreedLocalStatusRevision ||
                    localOverride != agreement.agreedLocalOverride
            }
            val remoteChanged = agreement == null ||
                remote?.status != agreement.agreedRemoteStatus ||
                remote?.source != agreement.agreedRemoteSource
            val target = BookOrbitStatusMapping.toRemote(FinishedOverride.fromStored(localOverride))

            if (localChanged && target != null) {
                if (isAcknowledged(remote, target)) {
                    settle(context, agreement, remote, localRevision, localOverride)
                    return@withLock SyncOutcome.Success
                }
                return@withLock send(context, agreement, remote, localRevision, localOverride, target)
            }

            if (remoteChanged) {
                val mapped = remote?.let {
                    BookOrbitStatusMapping.fromRemote(it.status, it.source, local?.totalProgression)
                }
                if (mapped != null) {
                    val currentStatus = local?.status
                    if (local?.override != mapped.override || currentStatus != mapped.status.wireName) {
                        val applied = finishedState.adoptRemoteStatus(
                            context.bookUrl,
                            local?.statusRevision ?: 0L,
                            context.request.accountKey,
                            mapped.override,
                            mapped.status,
                        )
                        if (!applied) return@withLock SyncOutcome.Failure(SyncFailure.StaleIdentity)
                        val adoptedRevision = (local?.statusRevision ?: 0L) + 1
                        settle(context, agreement, remote, adoptedRevision, mapped.override.ordinal)
                        return@withLock SyncOutcome.Success
                    }
                }
            }

            settle(
                context,
                agreement,
                remote,
                local?.statusRevision ?: 0L,
                local?.finishedOverride ?: FinishedOverride.NONE.ordinal,
            )
            SyncOutcome.Success
        }

    private suspend fun recover(
        context: BookOrbitCfiContext,
        agreement: BookOrbitStatusAgreement,
    ): SyncOutcome {
        val remote = client.read(context)
        cfis.check(context)
        val target = agreement.sentStatus
            ?: return SyncOutcome.Failure(SyncFailure.StatusUnresolved)
        val sentRevision = agreement.sentLocalStatusRevision
            ?: return SyncOutcome.Failure(SyncFailure.StatusUnresolved)
        val sentOverride = agreement.sentLocalOverride
            ?: return SyncOutcome.Failure(SyncFailure.StatusUnresolved)
        if (isAcknowledged(remote, target)) {
            // Only the choice that was sent is agreed; a newer one made
            // during the read-back stays unsent for the next run.
            settle(context, agreement, remote, sentRevision, sentOverride)
            return SyncOutcome.Success
        }
        return SyncOutcome.Failure(SyncFailure.StatusUnresolved)
    }

    private suspend fun send(
        context: BookOrbitCfiContext,
        previous: BookOrbitStatusAgreement?,
        preflight: BookOrbitStatus?,
        localRevision: Long,
        localOverride: Int,
        target: String,
    ): SyncOutcome {
        val bytes = client.requestBytes(target)
        var attempt = (previous ?: newAgreement(context)).copy(
            outgoingBytes = bytes,
            sentStatus = target,
            sentLocalStatusRevision = localRevision,
            sentLocalOverride = localOverride,
            preflightRemoteStatus = preflight?.status,
            preflightRemoteSource = preflight?.source,
            attemptState = PREPARED,
            attemptGeneration = (previous?.attemptGeneration ?: 0) + 1,
        )
        save(context, attempt)
        attempt = markMayHaveBeenSent(context, attempt, localRevision, localOverride)
            ?: return SyncOutcome.Failure(SyncFailure.StaleIdentity)

        when (val mutation = client.set(context, bytes)) {
            is BookOrbitHttp.MutationResult.Rejected -> {
                attempt = attempt.copy(
                    attemptState = if (mutation.status == 401) REJECTED_UNAUTHORISED else REJECTED,
                )
                save(context, attempt)
                return SyncOutcome.Failure(
                    if (mutation.status == 401) SyncFailure.Unauthorised else SyncFailure.Forbidden,
                )
            }
            BookOrbitHttp.MutationResult.ReadBackRequired,
            BookOrbitHttp.MutationResult.Uncertain -> Unit
        }

        val readBack = client.read(context)
        cfis.check(context)
        if (!isAcknowledged(readBack, target)) {
            attempt = attempt.copy(attemptState = UNCERTAIN)
            save(context, attempt)
            return SyncOutcome.Failure(SyncFailure.StatusUnresolved)
        }
        settle(
            context,
            attempt,
            readBack,
            localRevision,
            localOverride,
        )
        return SyncOutcome.Success
    }

    private suspend fun settle(
        context: BookOrbitCfiContext,
        previous: BookOrbitStatusAgreement?,
        remote: BookOrbitStatus?,
        localRevision: Long,
        localOverride: Int,
    ) {
        val row = (previous ?: newAgreement(context)).copy(
            agreedLocalStatusRevision = localRevision,
            agreedLocalOverride = localOverride,
            agreedRemoteStatus = remote?.status,
            agreedRemoteSource = remote?.source,
            outgoingBytes = null,
            sentStatus = null,
            sentLocalStatusRevision = null,
            sentLocalOverride = null,
            preflightRemoteStatus = null,
            preflightRemoteSource = null,
            attemptState = null,
        )
        save(context, row)
    }

    private suspend fun save(context: BookOrbitCfiContext, row: BookOrbitStatusAgreement) {
        database.withTransaction {
            cfis.check(context)
            val binding = database.bookOrbitBindingDao().get(
                context.request.accountKey,
                context.bookUrl,
            )
            if (binding?.bookId != context.bookId) throw BookOrbitIdentityChanged()
            agreements.write(row)
        }
    }

    private suspend fun markMayHaveBeenSent(
        context: BookOrbitCfiContext,
        prepared: BookOrbitStatusAgreement,
        localRevision: Long,
        localOverride: Int,
    ): BookOrbitStatusAgreement? = database.withTransaction {
        cfis.check(context)
        val current = agreements.get(context.request.accountKey, context.bookUrl)
        val local = progress.get(context.bookUrl)
        if (current?.outgoingBytes?.contentEquals(prepared.outgoingBytes) != true ||
            local?.statusRevision != localRevision ||
            local.finishedOverride != localOverride ||
            local.ownedByAnother(context)
        ) return@withTransaction null
        current.copy(attemptState = MAY_HAVE_BEEN_SENT).also { agreements.write(it) }
    }

    private fun newAgreement(context: BookOrbitCfiContext) = BookOrbitStatusAgreement(
        accountKey = context.request.accountKey,
        bookUrl = context.bookUrl,
        bookId = context.bookId,
        bindingRevision = context.bindingRevision,
        connectionEpoch = context.request.epoch,
        baseUrl = context.request.baseUrl,
    )

    private fun BookOrbitStatusAgreement.forContext(
        context: BookOrbitCfiContext,
    ): BookOrbitStatusAgreement = copy(
        bindingRevision = context.bindingRevision,
        connectionEpoch = context.request.epoch,
        baseUrl = context.request.baseUrl,
    )

    private fun ReadingProgress?.ownedByAnother(context: BookOrbitCfiContext): Boolean =
        this?.ownerAccount?.let { it != context.request.accountKey } == true

    private fun isAcknowledged(remote: BookOrbitStatus?, target: String): Boolean =
        remote?.status == target && remote.source == MANUAL_SOURCE

    companion object {
        private const val MANUAL_SOURCE = "manual"
        private const val PREPARED = "PREPARED"
        private const val MAY_HAVE_BEEN_SENT = "MAY_HAVE_BEEN_SENT"
        private const val UNCERTAIN = "UNCERTAIN"
        private const val REJECTED = "REJECTED"
        private const val REJECTED_UNAUTHORISED = "REJECTED_UNAUTHORISED"
        /** Turn on only after independent-client and write-race acceptance. */
        const val AUTOMATIC_SYNC_ENABLED = false
    }
}
