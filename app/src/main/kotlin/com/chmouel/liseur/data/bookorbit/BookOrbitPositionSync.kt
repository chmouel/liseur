package com.chmouel.liseur.data.bookorbit

import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.remote.PositionSync
import com.chmouel.liseur.data.remote.PreviewOutcome
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.ResolveOutcome
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.SyncIdentity
import com.chmouel.liseur.data.remote.SyncOutcome
import com.chmouel.liseur.data.remote.SyncPreview
import com.chmouel.liseur.data.remote.SyncSnapshot
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import org.json.JSONException

/**
 * Selected-book, opt-in position exchange. No catalog-wide walk or conflict choice is
 * exposed through PositionSync until a reader-verified remote locator can be supplied.
 * The default is deliberately inert; this is not registered for ordinary sync.
 */
class BookOrbitPositionSync(
    private val database: LiseurDatabase,
    private val exchange: BookOrbitPositionExchange,
    private val manualBookSync: Boolean = false,
) : PositionSync {
    override suspend fun dialledAddress(): String? =
        database.remoteServerDao().get()?.takeIf {
            manualBookSync && it.kind == ServerKind.BOOKORBIT &&
                (it.orbitAccessCipher != null || it.orbitRefreshCipher != null)
        }?.baseUrl

    override suspend fun syncAll(snapshot: SyncSnapshot?): SyncOutcome = SyncOutcome.NotApplicable

    override suspend fun syncBook(bookUrl: String): SyncOutcome {
        if (dialledAddress() == null) return SyncOutcome.NotApplicable
        return try {
            val server = database.remoteServerDao().get() ?: return SyncOutcome.NotApplicable
            if (database.bookOrbitBindingDao().get(server.accountKey, bookUrl) == null)
                return SyncOutcome.NotApplicable
            val context = BookOrbitCfiRepository(database).capture(bookUrl)
            if (database.readingProgressDao().get(bookUrl)?.ownerAccount
                ?.let { it != context.request.accountKey } == true
            ) return SyncOutcome.NotApplicable
            when (val result = exchange.run(bookUrl)) {
                BookOrbitPositionExchange.Result.Agreed,
                BookOrbitPositionExchange.Result.Pushed,
                BookOrbitPositionExchange.Result.Recovered -> {
                    BookOrbitCfiRepository(database).check(context)
                    val local = database.readingProgressDao().get(bookUrl)
                    val agreed = database.bookOrbitPositionAgreementDao()
                        .get(context.request.accountKey, bookUrl)
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
}
