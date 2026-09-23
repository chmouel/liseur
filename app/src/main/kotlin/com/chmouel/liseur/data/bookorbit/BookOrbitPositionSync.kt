package com.chmouel.liseur.data.bookorbit

import androidx.room.withTransaction
import com.chmouel.liseur.data.db.BookOrbitPositionTraversal
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.remote.PositionSync
import com.chmouel.liseur.data.remote.PreviewOutcome
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.ResolveOutcome
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.SyncIdentity
import com.chmouel.liseur.data.remote.SyncOutcome
import com.chmouel.liseur.data.remote.SyncPreview
import com.chmouel.liseur.data.remote.SyncSnapshot
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import org.json.JSONException

/**
 * Bounded selected-file exchanges. Automatic pushes require their own acceptance
 * gate; observation-only callers and generic reader choices remain separate.
 */
class BookOrbitPositionSync(
    private val database: LiseurDatabase,
    private val exchange: BookOrbitPositionExchange,
    private val manualBookSync: Boolean = false,
    private val accountSync: Boolean = false,
    private val automaticPush: Boolean = false,
) : PositionSync {
    private val turn get() = database.bookOrbitTraversalMutex
    private val cfis = BookOrbitCfiRepository(database)
    private val traversals = database.bookOrbitPositionTraversalDao()

    override suspend fun dialledAddress(): String? = connection()?.baseUrl

    override suspend fun syncAll(snapshot: SyncSnapshot?): SyncOutcome = syncAll(snapshot, carryingOn = false)

    override suspend fun syncAll(snapshot: SyncSnapshot?, carryingOn: Boolean): SyncOutcome = turn.withLock {
        if (!accountSync) return@withLock SyncOutcome.NotApplicable
        outcome {
            val request = connection() ?: return@outcome SyncOutcome.NotApplicable
            var traversal = database.withTransaction {
                checkConnection(request)
                val saved = traversals.get(request.accountKey)
                val sameConnection = saved?.connectionEpoch == request.epoch && saved.baseUrl == request.baseUrl
                if (sameConnection && (carryingOn || !saved.finished)) {
                    checkNotNull(saved)
                } else {
                    // A queued continuation never starts work for a replacement connection.
                    if (carryingOn) return@withTransaction null
                    traversals.clearAccount(request.accountKey)
                    BookOrbitPositionTraversal(request.accountKey, request.epoch, request.baseUrl).also {
                        traversals.write(it)
                        traversals.capture(request.accountKey)
                    }
                }
            } ?: return@outcome SyncOutcome.NotApplicable
            if (traversal.finished) return@outcome traversal.report()
            val page = traversals.page(request.accountKey, traversal.afterUrl, PAGE_SIZE + 1)
            for (binding in page.take(PAGE_SIZE)) {
                val context = BookOrbitCfiContext(
                    request, binding.bookUrl, binding.bookId, binding.fileId, binding.bindingRevision,
                )
                checkConnection(request)
                val current = database.bookOrbitBindingDao().get(request.accountKey, binding.bookUrl)
                val result = if (current?.bookId != binding.bookId || current.fileId != binding.fileId ||
                    current.revision != binding.bindingRevision ||
                    current.state !in setOf("SELECTED", "DOWNLOADED") ||
                    !current.fileFormat.equals("epub", ignoreCase = true)
                ) {
                    // A removed or rebound member cannot hold a frozen traversal open forever.
                    SyncOutcome.Failure(SyncFailure.PositionUnresolved)
                } else {
                    sync(context, allowPush = automaticPush)
                }
                if (result is SyncOutcome.Failure && result.reason.worthRetrying) {
                    return@outcome if (traversal.succeeded) SyncOutcome.Partial(result.reason) else result
                }
                val next = traversal.copy(
                    afterUrl = binding.bookUrl,
                    succeeded = traversal.succeeded || result == SyncOutcome.Success,
                    failure = traversal.failure ?: (result as? SyncOutcome.Failure)?.reason?.stored(),
                )
                saveTraversal(request, traversal, next)
                traversal = next
            }
            if (page.size <= PAGE_SIZE) {
                val finished = traversal.copy(finished = true)
                saveTraversal(request, traversal, finished)
                traversal = finished
            }
            traversal.report().also { report ->
                if (report == SyncOutcome.Success) database.withTransaction {
                    checkConnection(request)
                    database.remoteServerDao().setPositionSyncedAt(System.currentTimeMillis())
                }
            }
        }
    }

    private suspend fun checkConnection(request: BookOrbitRequestContext) {
        if (!request.matches(database.remoteServerDao().get())) throw BookOrbitIdentityChanged()
    }

    private suspend fun saveTraversal(
        request: BookOrbitRequestContext,
        previous: BookOrbitPositionTraversal,
        next: BookOrbitPositionTraversal,
    ) = database.withTransaction {
        checkConnection(request)
        if (traversals.get(request.accountKey) != previous) throw BookOrbitIdentityChanged()
        traversals.write(next)
    }

    private fun BookOrbitPositionTraversal.report(): SyncOutcome {
        val reason = failure?.let { stored ->
            when (stored) {
                "unresolved" -> SyncFailure.PositionUnresolved
                "unauthorised" -> SyncFailure.Unauthorised
                "forbidden" -> SyncFailure.Forbidden
                "not_found" -> SyncFailure.NotFound
                "insecure" -> SyncFailure.InsecureTransport
                "local_network" -> SyncFailure.LocalNetworkBlocked
                else -> {
                    check(stored.startsWith("http:")) { "Unknown BookOrbit traversal failure" }
                    SyncFailure.ServerError(stored.removePrefix("http:").toInt())
                }
            }
        }
        return when {
            reason != null && succeeded -> SyncOutcome.Partial(reason, continuation = !finished)
            reason != null -> SyncOutcome.Failure(reason, continuation = !finished)
            !finished -> SyncOutcome.Incomplete
            succeeded -> SyncOutcome.Success
            else -> SyncOutcome.NotApplicable
        }
    }

    private fun SyncFailure.stored(): String = when (this) {
        SyncFailure.PositionUnresolved -> "unresolved"
        SyncFailure.Unauthorised -> "unauthorised"
        SyncFailure.Forbidden -> "forbidden"
        SyncFailure.NotFound -> "not_found"
        SyncFailure.InsecureTransport -> "insecure"
        SyncFailure.LocalNetworkBlocked -> "local_network"
        is SyncFailure.ServerError -> "http:$code"
        else -> error("Retryable failures must leave the traversal at the failed item")
    }

    override suspend fun syncBook(bookUrl: String): SyncOutcome = turn.withLock {
        outcome {
            val request = connection() ?: return@outcome SyncOutcome.NotApplicable
            if (database.bookOrbitBindingDao().get(request.accountKey, bookUrl) == null)
                return@outcome SyncOutcome.NotApplicable
            // A book gone from the catalog keeps its binding but not its link.
            if (database.bookDao().getByUrl(bookUrl)?.remoteUuid == null)
                return@outcome SyncOutcome.NotApplicable
            val context = cfis.capture(bookUrl)
            if (context.request != request) return@outcome SyncOutcome.Failure(SyncFailure.StaleIdentity)
            sync(context, allowPush = manualBookSync || automaticPush)
        }
    }

    private suspend fun connection(): BookOrbitRequestContext? =
        database.remoteServerDao().get()?.takeIf {
            (manualBookSync || accountSync) &&
                (it.orbitAccessCipher != null || it.orbitRefreshCipher != null)
        }?.let(BookOrbitRequestContext::from)

    private suspend fun sync(context: BookOrbitCfiContext, allowPush: Boolean): SyncOutcome =
        outcome {
            cfis.check(context)
            val position = database.readingProgressDao().get(context.bookUrl)
            if (automaticPush && position == null &&
                database.bookOrbitPositionAgreementDao().get(context.request.accountKey, context.bookUrl)
                    ?.outgoingBytes == null
            ) return@outcome SyncOutcome.NotApplicable
            if (position?.ownerAccount
                ?.let { it != context.request.accountKey } == true
            ) return@outcome SyncOutcome.NotApplicable
            when (val result = exchange.run(context, allowPush)) {
                BookOrbitPositionExchange.Result.Agreed,
                BookOrbitPositionExchange.Result.Pushed,
                BookOrbitPositionExchange.Result.Recovered -> database.withTransaction {
                    cfis.check(context)
                    val local = database.readingProgressDao().get(context.bookUrl)
                    val agreed = database.bookOrbitPositionAgreementDao()
                        .get(context.request.accountKey, context.bookUrl)
                    if (local?.ownerAccount != null && local.ownerAccount != context.request.accountKey ||
                        local != null && (agreed?.agreedLocalRevision != local.localRevision ||
                            agreed.agreedLocatorJson != local.locatorJson)
                    ) SyncOutcome.Failure(SyncFailure.StaleIdentity) else SyncOutcome.Success
                }
                is BookOrbitPositionExchange.Result.Rejected -> SyncOutcome.Failure(
                    if (result.status == 401) SyncFailure.Unauthorised else SyncFailure.Forbidden,
                )
                BookOrbitPositionExchange.Result.Conflict,
                BookOrbitPositionExchange.Result.Unresolved,
                BookOrbitPositionExchange.Result.RetryAfterReadBack ->
                    SyncOutcome.Failure(SyncFailure.PositionUnresolved)
            }
        }

    private suspend fun outcome(block: suspend () -> SyncOutcome): SyncOutcome =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: RemoteHttpFailure) {
            SyncOutcome.Failure(error.reason)
        } catch (_: SocketTimeoutException) {
            SyncOutcome.Failure(SyncFailure.Timeout)
        } catch (_: BookOrbitIdentityChanged) {
            SyncOutcome.Failure(SyncFailure.StaleIdentity)
        } catch (_: BookOrbitPositionUnresolved) {
            SyncOutcome.Failure(SyncFailure.PositionUnresolved)
        } catch (_: JSONException) {
            SyncOutcome.Failure(SyncFailure.Malformed)
        } catch (_: IOException) {
            SyncOutcome.Failure(SyncFailure.Offline)
        }

    // The generic position-choice API cannot verify a foreign CFI in the active reader.
    override suspend fun canSync(bookUrl: String): Boolean = false
    override suspend fun previewBook(bookUrl: String): PreviewOutcome = PreviewOutcome.NotSynced
    override suspend fun preservedConflict(bookUrl: String, peerId: String?): SyncPreview? = null
    override suspend fun takeRemotePosition(
        bookUrl: String,
        atRevision: Long,
        peerId: String?,
        expectedAccountKey: String?,
    ): ResolveOutcome = ResolveOutcome.Superseded

    override suspend fun keepLocalPosition(bookUrl: String, peerId: String?): ResolveOutcome =
        ResolveOutcome.Superseded

    override suspend fun refreshUnresolved() = Unit
    override suspend fun identity(): SyncIdentity? = null

    companion object {
        const val AUTOMATIC_SYNC_ENABLED = true
        internal const val PAGE_SIZE = 20
    }
}
