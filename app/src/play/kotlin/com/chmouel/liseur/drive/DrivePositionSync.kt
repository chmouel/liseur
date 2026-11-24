package com.chmouel.liseur.drive

import androidx.room.withTransaction
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.FurthestPosition
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.PeakDelivery
import com.chmouel.liseur.data.db.ReadingProgress
import com.chmouel.liseur.data.db.SyncPeerState
import com.chmouel.liseur.data.db.WorkAlias
import com.chmouel.liseur.data.library.FinishedState
import com.chmouel.liseur.data.remote.FurthestDestination
import com.chmouel.liseur.data.remote.PeerPositionSync
import com.chmouel.liseur.data.remote.PreviewOutcome
import com.chmouel.liseur.data.remote.ResolveOutcome
import com.chmouel.liseur.data.remote.ResumeConfidence
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.SyncIdentity
import com.chmouel.liseur.data.remote.SyncOutcome
import com.chmouel.liseur.data.remote.SyncPreview
import com.chmouel.liseur.data.remote.SyncSnapshot
import com.chmouel.liseur.domain.FinishedOverride
import com.chmouel.liseur.domain.NarrativeCoverage
import com.chmouel.liseur.domain.ReadingBaseline
import com.chmouel.liseur.domain.ReadingState
import com.chmouel.liseur.domain.ReadingStatus
import com.chmouel.liseur.domain.ReadingStatusChoice
import com.chmouel.liseur.domain.SyncDecision
import com.chmouel.liseur.domain.readingStatusFor
import com.chmouel.liseur.domain.reconcileReadingState
import com.chmouel.liseur.reader.progress.ExactLocatorAnchor
import com.chmouel.liseur.reader.progress.ResourceAnchor
import java.util.Locale
import java.util.UUID
import org.json.JSONObject

/** Outbound delivery metadata tracked alongside a frozen batch so ACK can settle `liseur.db`. */
internal data class DrivePositionAckMeta(
    val bookUrl: String,
    val localRevision: Long,
    val statusRevision: Long,
    val progression: Double?,
    val status: String,
    val recordKey: String = "",
    val recordFp: String = "",
)

internal data class DrivePeakAckMeta(
    val bookUrl: String,
    val workKey: String,
    val peakRevision: Long,
    val recordKey: String = "",
    val recordFp: String = "",
)

internal data class DriveOutboxAckMetadata(
    val positionAcks: List<DrivePositionAckMeta> = emptyList(),
    val peakAcks: List<DrivePeakAckMeta> = emptyList(),
)

/**
 * Reading position, reading status, per-device positions, edition-gated locators,
 * backward reading moves, and furthest-position peaks/resets for Google Drive sync
 * (`docs/DRIVE_SYNC.md` §4.5–4.6).
 */
internal class DrivePositionSync(
    private val database: LiseurDatabase,
    private val storeProvider: () -> DriveSyncDatabase,
    private val workIdentityProvider: () -> DriveWorkIdentity,
    private val finishedState: FinishedState,
    private val runSync: suspend (bookUrl: String?) -> SyncOutcome,
    private val isActive: suspend () -> Boolean,
    private val activeAccount: suspend () -> DriveAccount?,
    private val localDeviceId: suspend () -> String,
    private val nextHlc: suspend (permissionId: String) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) : PeerPositionSync {

    private val store get() = storeProvider()
    private val workIdentity get() = workIdentityProvider()
    private val books get() = database.bookDao()
    private val progressDao get() = database.readingProgressDao()
    private val peerStateDao get() = database.syncPeerStateDao()
    private val furthestDao get() = database.furthestPositionDao()
    private val identities get() = database.workIdentityDao()
    private val recordStates get() = store.recordStates()

    override val peerId: String get() = DriveLibrarySync.PEER_ID

    override suspend fun dialledAddress(): String? =
        if (isActive()) DRIVE_DIALLED_ADDRESS else null

    override suspend fun syncAll(snapshot: SyncSnapshot?): SyncOutcome =
        if (isActive()) runSync(null) else SyncOutcome.NotApplicable

    override suspend fun syncBook(bookUrl: String): SyncOutcome =
        if (isActive()) runSync(bookUrl) else SyncOutcome.NotApplicable

    override suspend fun canSync(bookUrl: String): Boolean {
        if (!isActive()) return false
        val book = books.getByUrl(bookUrl) ?: return false
        return book.browseServerId == null
    }

    override suspend fun previewBook(bookUrl: String): PreviewOutcome =
        previewKnownBook(bookUrl)

    override suspend fun previewKnownBook(bookUrl: String): PreviewOutcome {
        val account = activeAccount() ?: return PreviewOutcome.NotSynced
        val drivePeerId = DriveLibrarySync.accountPeerId(account.permissionId)
        val book = books.getByUrl(bookUrl) ?: return PreviewOutcome.NotSynced
        val alias = workIdentity.resolveBook(account.permissionId, book, computeFingerprintIfMissing = false)
            ?.takeIf { it.usable }
            ?: return PreviewOutcome.NotSynced
        val stored = progressDao.get(bookUrl)
        val peerState = peerStateDao.get(bookUrl, drivePeerId)
        val destination = furthestDestination(account.permissionId, bookUrl, alias)
        val hasPending = peerState?.hasPending == true && peerState.pendingProgression != null
        if (!hasPending && destination == null) return PreviewOutcome.NotSynced

        val exact = if (hasPending) peerState.exactLocatorFor(alias) else null
        return PreviewOutcome.Ready(
            SyncPreview(
                local = stored?.totalProgression,
                remote = if (hasPending) peerState.pendingProgression else null,
                remoteAt = if (hasPending) peerState.remoteUpdatedAt?.takeIf { it > 0L } else null,
                excerpt = ExactLocatorAnchor.excerpt(exact),
                confidence = if (exact != null) ResumeConfidence.EXACT else ResumeConfidence.APPROXIMATE,
                exactPositionAgreement = if (hasPending) {
                    ExactLocatorAnchor.agreement(stored?.locatorJson, exact)
                } else {
                    null
                },
                accountKey = drivePeerId,
                remoteStatus = if (hasPending) peerState.pendingStatus else null,
                remoteLocatorJson = if (hasPending) peerState.restorableLocatorFor(alias) else null,
                localRevision = stored?.localRevision,
                resolvable = hasPending,
                furthest = destination,
                localLocatorJson = stored?.locatorJson,
                furthestUptoSeq = knownMaxSeq(account.permissionId, alias.workId),
                localUnreadResetPending = stored?.hasPendingUnreadReset(peerState?.ackedRevision ?: 0L) == true,
            ),
        )
    }

    override suspend fun preservedConflict(bookUrl: String, peerId: String?): SyncPreview? {
        val account = activeAccount() ?: return null
        val drivePeerId = DriveLibrarySync.accountPeerId(account.permissionId)
        val state = peerStateDao.get(bookUrl, drivePeerId) ?: return null
        if (!state.hasPending) return null
        val there = state.pendingProgression ?: return null
        val stored = progressDao.get(bookUrl)
        val alias = identities.alias(bookUrl, drivePeerId)
        val exact = state.exactLocatorFor(alias)
        return SyncPreview(
            local = stored?.totalProgression,
            remote = there,
            remoteAt = state.remoteUpdatedAt?.takeIf { it > 0L },
            excerpt = ExactLocatorAnchor.excerpt(exact),
            confidence = if (exact != null) ResumeConfidence.EXACT else ResumeConfidence.APPROXIMATE,
            exactPositionAgreement = ExactLocatorAnchor.agreement(stored?.locatorJson, exact),
            accountKey = drivePeerId,
            remoteStatus = state.pendingStatus,
            remoteLocatorJson = state.restorableLocatorFor(alias),
            localRevision = stored?.localRevision,
            localUnreadResetPending = stored?.hasPendingUnreadReset(state.ackedRevision) == true,
        ).takeIf { !it.agrees }
    }

    override suspend fun takeRemotePosition(
        bookUrl: String,
        atRevision: Long,
        peerId: String?,
        expectedAccountKey: String?,
    ): ResolveOutcome {
        val account = activeAccount() ?: return if (expectedAccountKey != null) {
            ResolveOutcome.Superseded
        } else {
            ResolveOutcome.Done
        }
        val drivePeerId = DriveLibrarySync.accountPeerId(account.permissionId)
        if (expectedAccountKey != null && expectedAccountKey != drivePeerId) {
            return ResolveOutcome.Superseded
        }
        val state = peerStateDao.get(bookUrl, drivePeerId) ?: return if (expectedAccountKey != null) {
            ResolveOutcome.Superseded
        } else {
            ResolveOutcome.Done
        }
        val progression = state.pendingProgression ?: return if (expectedAccountKey != null) {
            ResolveOutcome.Superseded
        } else {
            ResolveOutcome.Done
        }
        val alias = identities.alias(bookUrl, drivePeerId)
        val locator = state.restorableLocatorFor(alias)
        val partial = state.pendingCoverage == NarrativeCoverage.PARTIAL.wireName
        val status = readingStatusFor(progression, partialCoverage = partial)
        val now = clock()
        var applied = false
        database.withTransaction {
            applied = progressDao.applyPeerPull(
                bookUrl = bookUrl,
                expectedRevision = atRevision,
                progression = progression,
                status = status.wireName,
                now = now,
                locatorJson = locator,
                remoteUpdatedAt = state.pendingUpdatedAt,
                coverage = state.pendingCoverage,
            )
            if (applied) {
                peerStateDao.settle(
                    bookUrl = bookUrl,
                    peerId = drivePeerId,
                    ackedRevision = atRevision + 1L,
                    progression = progression,
                    status = status.wireName,
                    now = now,
                )
            }
        }
        if (!applied) return ResolveOutcome.Superseded
        finishedState.refreshFromProgress(bookUrl)
        return ResolveOutcome.Done
    }

    override suspend fun keepLocalPosition(bookUrl: String, peerId: String?): ResolveOutcome {
        val account = activeAccount() ?: return ResolveOutcome.Done
        val drivePeerId = DriveLibrarySync.accountPeerId(account.permissionId)
        peerStateDao.clearPending(bookUrl, drivePeerId)
        return ResolveOutcome.Done
    }

    override suspend fun takeFurthestPosition(
        bookUrl: String,
        atRevision: Long,
        destination: FurthestDestination,
        peerId: String?,
    ): ResolveOutcome {
        val account = activeAccount() ?: return ResolveOutcome.Superseded
        val drivePeerId = DriveLibrarySync.accountPeerId(account.permissionId)
        if (destination.accountKey != drivePeerId) return ResolveOutcome.Superseded
        var applied = false
        val now = clock()
        database.withTransaction {
            val alias = identities.alias(bookUrl, drivePeerId)?.takeIf { it.usable }
                ?: return@withTransaction
            if (alias.workId != destination.workId ||
                alias.editionSha != destination.localEdition ||
                furthestDestination(account.permissionId, bookUrl, alias) != destination
            ) {
                return@withTransaction
            }
            val status = readingStatusFor(
                destination.progression,
                partialCoverage = destination.coverage == NarrativeCoverage.PARTIAL,
            )
            applied = progressDao.applyPeerPull(
                bookUrl = bookUrl,
                expectedRevision = atRevision,
                progression = destination.progression,
                status = status.wireName,
                now = now,
                locatorJson = destination.locatorJson,
                remoteUpdatedAt = destination.at,
                coverage = destination.coverage?.wireName,
            )
            if (applied) {
                peerStateDao.clearPending(bookUrl, drivePeerId)
            }
        }
        if (!applied) return ResolveOutcome.Superseded
        finishedState.refreshFromProgress(bookUrl)
        return ResolveOutcome.Done
    }

    override suspend fun forgetFurthestPosition(
        bookUrl: String,
        destination: FurthestDestination,
        uptoSeq: Long,
        peerId: String?,
    ): ResolveOutcome {
        val account = activeAccount() ?: return ResolveOutcome.Superseded
        val drivePeerId = DriveLibrarySync.accountPeerId(account.permissionId)
        if (destination.accountKey != drivePeerId) return ResolveOutcome.Superseded
        val alias = identities.alias(bookUrl, drivePeerId)?.takeIf { it.usable }
            ?: return ResolveOutcome.Superseded
        if (alias.workId != destination.workId ||
            alias.editionSha != destination.localEdition ||
            furthestDestination(account.permissionId, bookUrl, alias) != destination
        ) {
            return ResolveOutcome.Superseded
        }

        val devId = localDeviceId()
        val now = clock()
        val permissionId = account.permissionId
        val workKey = workIdentity.canonicalWorkKey(permissionId, alias.workId)
        val observed = furthestDao.forWork(drivePeerId, workKey)
        val editions = localEditions(permissionId, drivePeerId, workKey)
        val templates = peakWinners(workKey, devId, editions).map { (edition, template) ->
            edition.progress.peakRevision!! to template
        }

        val floors = linkedMapOf<String, Long>()
        for (row in observed) {
            val origin = row.origin.ifBlank { devId }
            floors[origin] = maxOf(floors[origin] ?: 0L, row.seq)
        }

        val resetId = UUID.randomUUID().toString().lowercase(Locale.ROOT)
        val resetKey = resetRecordKey(workKey, resetId)
        val hlc = nextHlc(permissionId)

        // Persist the reset, its floors and a replay marker in `drive_sync.db` before any local
        // data is cleared, so a stop at any later point is completed by [reapplyPendingResets].
        // Every local edition's peak is bound first: the local floor is the highest sequence
        // this device has allocated, so all of them fall under it.
        store.withTransaction {
            for ((peakRev, template) in templates) {
                val peakKey = peakRecordKey(workKey, template.editionSha, devId)
                bindPeak(
                    permissionId, peakKey, peakRev, template,
                    getPersistedFloor(permissionId, workKey, devId),
                ) { hlc }
            }
            val allocated = peakSeqHighWater(permissionId)
            if (allocated > 0L) floors[devId] = maxOf(floors[devId] ?: 0L, allocated)
            if (floors.isEmpty() && uptoSeq > 0L) {
                floors[devId] = uptoSeq
            }
            val body = DriveFurthestBody.Reset(
                workKey = workKey,
                resetId = resetId,
                floors = floors.toSortedMap(),
                resetAt = now,
            )
            val bodyMap = DriveWireCodec.encodeFurthestBody(body)
            val fp = DriveCanonicalJson.contentFp(bodyMap)
            for ((origin, floor) in floors) {
                raisePersistedFloor(permissionId, workKey, origin, floor, hlc)
            }
            recordStates.upsert(
                DriveRecordState(
                    permissionId = permissionId,
                    domain = DriveWireCodec.DOMAIN_FURTHEST,
                    recordKey = resetKey,
                    versionFp = fp,
                    versionOp = DriveRecordState.OP_PUT,
                    publishedFp = null,
                    contentFp = fp,
                    ancestorsJson = "[]",
                    resRefsJson = "[]",
                    lastHlc = hlc,
                    bodyJson = DriveCanonicalJson.encode(bodyMap),
                ),
            )
            recordStates.upsert(
                DriveRecordState(
                    permissionId = permissionId,
                    domain = RESET_PENDING_DOMAIN,
                    recordKey = resetKey,
                    versionFp = fp,
                    versionOp = DriveRecordState.OP_PUT,
                    lastHlc = hlc,
                    bodyJson = JSONObject().put(
                        PENDING_REVISIONS,
                        JSONObject().apply {
                            for (edition in editions) put(edition.bookUrl, edition.progress.localRevision)
                        },
                    ).toString(),
                ),
            )
        }

        reapplyPendingResets(permissionId, devId)
        return ResolveOutcome.Done
    }

    /** Completes the local effects of forgets whose reset was persisted but not yet applied. */
    suspend fun reapplyPendingResets(permissionId: String, localDeviceId: String) {
        for (marker in recordStates.forDomain(permissionId, RESET_PENDING_DOMAIN)) {
            val reset = recordStates.get(permissionId, DriveWireCodec.DOMAIN_FURTHEST, marker.recordKey)
                ?.bodyJson
                ?.let { runCatching { DriveWireCodec.decodeFurthestBody(JSONObject(it)) }.getOrNull() }
                as? DriveFurthestBody.Reset
            if (reset != null) {
                applyFloorsLocally(
                    permissionId,
                    workIdentity.canonicalWorkKey(permissionId, reset.workKey),
                    reset.floors,
                    localDeviceId,
                    guardRevisions = pendingRevisions(marker.bodyJson),
                )
            }
            store.withTransaction {
                recordStates.delete(permissionId, RESET_PENDING_DOMAIN, marker.recordKey)
            }
        }
    }

    private fun pendingRevisions(bodyJson: String?): Map<String, Long> {
        val revisions = bodyJson
            ?.let { runCatching { JSONObject(it).optJSONObject(PENDING_REVISIONS) }.getOrNull() }
            ?: return emptyMap()
        return revisions.keys().asSequence().associateWith { revisions.getLong(it) }
    }

    /**
     * Drops observations at or below each origin's floor and resets every local edition whose
     * bound peak sequence is covered by this device's floor. With [guardRevisions] (a replayed
     * forget) a book is reset only while its local revision is still the one recorded when the
     * forget was persisted, so a replay after the reader moved never erases a newer peak.
     */
    private suspend fun applyFloorsLocally(
        permissionId: String,
        workKey: String,
        floors: Map<String, Long>,
        localDeviceId: String,
        guardRevisions: Map<String, Long>? = null,
    ) {
        val drivePeerId = DriveLibrarySync.accountPeerId(permissionId)
        val localFloor = floors[localDeviceId]
        val covered = mutableListOf<Triple<String, String?, Long>>()
        if (localFloor != null) {
            val eligible = localEditions(permissionId, drivePeerId, workKey).filter { edition ->
                edition.progress.peakRevision != null &&
                    (guardRevisions == null ||
                        guardRevisions[edition.bookUrl] == edition.progress.localRevision)
            }
            for ((winner, template) in peakWinners(workKey, localDeviceId, eligible)) {
                val existing = recordStates.get(
                    permissionId,
                    DriveWireCodec.DOMAIN_FURTHEST,
                    peakRecordKey(workKey, template.editionSha, localDeviceId),
                )
                val seq = boundPeakSeq(existing, winner.progress.peakRevision!!, template) ?: continue
                if (seq > localFloor) continue
                // A forget captured these revisions, so every same-edition peak it saw is at or
                // below the bound winner; a remote reset only covers the peak it can name.
                val members = if (guardRevisions != null) {
                    eligible.filter { peakEdition(it) == template.editionSha }
                } else {
                    listOf(winner)
                }
                for (member in members) {
                    covered.add(Triple(member.bookUrl, member.editionSha, member.progress.peakRevision!!))
                }
            }
        }
        // Peaks retained above a floor were skipped by `observe` when a higher one from the
        // same origin and edition arrived first, so rebuild them once the reset drops the rest.
        val retained = retainedRemotePeaks(permissionId, workKey, localDeviceId, floors)
        database.withTransaction {
            for ((origin, floor) in floors) {
                furthestDao.deleteUpToForOrigin(drivePeerId, workKey, origin, floor)
            }
            for (peak in retained) observePeak(drivePeerId, workKey, peak)
            for ((bookUrl, editionSha, peakRev) in covered) {
                val current = progressDao.get(bookUrl)
                if (current?.peakRevision == peakRev &&
                    (guardRevisions == null || guardRevisions[bookUrl] == current.localRevision)
                ) {
                    progressDao.resetPeak(bookUrl, editionSha)
                    furthestDao.deleteDelivery(drivePeerId, bookUrl, workKey)
                }
            }
        }
    }

    private suspend fun retainedRemotePeaks(
        permissionId: String,
        workKey: String,
        localDeviceId: String,
        floors: Map<String, Long>,
    ): List<DriveFurthestBody.Peak> {
        val peaks = recordStates.forDomain(permissionId, DriveWireCodec.DOMAIN_FURTHEST)
            .filter { it.recordKey.startsWith(PEAK_KEY_PREFIX) }
            .mapNotNull { decodePeak(it.bodyJson) }
            .filter { it.deviceId != localDeviceId && workIdentity.canonicalWorkKey(permissionId, it.workKey) == workKey }
        return peaks.filter { peak ->
            val floor = maxOf(floors[peak.deviceId] ?: 0L, getPersistedFloor(permissionId, workKey, peak.deviceId))
            peak.seq > floor
        }
    }

    private suspend fun observePeak(drivePeerId: String, workKey: String, body: DriveFurthestBody.Peak) {
        val payload = DriveCanonicalJson.encode(
            mapOf(
                "locatorJson" to body.locatorJson,
                "coverage" to body.coverage,
                "peakAt" to body.peakAt,
            ),
        )
        furthestDao.observe(
            FurthestPosition(
                peerId = drivePeerId,
                workId = workKey,
                edition = body.editionSha,
                origin = body.deviceId,
                progression = body.progression,
                seq = body.seq,
                payload = payload,
            ),
        )
    }

    private suspend fun peakEdition(edition: LocalEdition): String? =
        peakTemplate("", "", edition.bookUrl, edition.editionSha, edition.progress)?.editionSha

    /**
     * One local book speaks for each edition of a work: the wire holds a single peak per
     * `(work, edition, device)`, so the furthest peak wins, with ties broken by the latest
     * `peakAt` and then the URL to keep the choice stable across runs.
     */
    private suspend fun peakWinners(
        workKey: String,
        deviceId: String,
        editions: List<LocalEdition>,
    ): List<Pair<LocalEdition, DriveFurthestBody.Peak>> = editions
        .mapNotNull { edition ->
            peakTemplate(workKey, deviceId, edition.bookUrl, edition.editionSha, edition.progress)
                ?.let { edition to it }
        }
        .groupBy { it.second.editionSha }
        .values
        .map { group -> group.maxWith(PEAK_WINNER_ORDER) }

    private class LocalEdition(val bookUrl: String, val editionSha: String?, val progress: ReadingProgress)

    private suspend fun localEditions(
        permissionId: String,
        drivePeerId: String,
        workKey: String,
    ): List<LocalEdition> = identities.aliasesFor(drivePeerId)
        .filter { it.usable && workIdentity.canonicalWorkKey(permissionId, it.workId) == workKey }
        .mapNotNull { alias -> progressDao.get(alias.bookUrl)?.let { LocalEdition(alias.bookUrl, alias.editionSha, it) } }

    override suspend fun refreshUnresolved() = Unit

    override suspend fun identity(): SyncIdentity? {
        val account = activeAccount() ?: return null
        return SyncIdentity(login = account.email, strandedBooks = 0)
    }

    // -- Outbound collection & ACK --------------------------------------------

    internal data class OutboundBundle(
        val records: List<DriveWireRecord>,
        val positionAcks: List<DrivePositionAckMeta>,
        val peakAcks: List<DrivePeakAckMeta>,
    )

    /** One local book's outbound position state, resolved once per sync run. */
    private class LocalPosition(
        val book: Book,
        val workKey: String,
        val aliasEditionSha: String?,
        val progress: ReadingProgress,
        val peerState: SyncPeerState?,
    ) {
        fun owesPosition(): Boolean = peerState == null ||
            peerState.isDirty(progress.localRevision) ||
            peerState.owesStatus(progress.statusRevision) ||
            progress.hasPendingUnreadReset(peerState.ackedRevision)
    }

    /** Collects dirty `position` and `furthest` (peaks + resets) records for publication. */
    suspend fun collectDirtyRecords(
        permissionId: String,
        deviceId: String,
        bookUrlFilter: String? = null,
        nextHlc: suspend () -> String,
    ): OutboundBundle {
        val drivePeerId = DriveLibrarySync.accountPeerId(permissionId)
        val allBooks = books.allOnce().filter { it.browseServerId == null }

        val records = mutableListOf<DriveWireRecord>()
        val posAcks = mutableListOf<DrivePositionAckMeta>()
        val peakAcks = mutableListOf<DrivePeakAckMeta>()

        val resolved = allBooks.mapNotNull { book ->
            val alias = workIdentity.resolveBook(permissionId, book) ?: return@mapNotNull null
            if (!alias.usable) return@mapNotNull null
            val progress = progressDao.get(book.url) ?: return@mapNotNull null
            LocalPosition(
                book = book,
                workKey = workIdentity.canonicalWorkKey(permissionId, alias.workId),
                aliasEditionSha = alias.editionSha,
                progress = progress,
                peerState = peerStateDao.get(book.url, drivePeerId),
            )
        }

        // The wire holds one `position` record per (workKey, deviceId), so two local books of
        // the same work must not both publish into that slot: the furthest-along book speaks
        // for the group and the others stay dirty until one of them wins. The winner is chosen
        // before the dirty filter, and across every local edition even for a single-book sync,
        // so an older edition never overwrites an ACKed winner.
        val local = if (bookUrlFilter == null) resolved else resolved.filter { it.book.url == bookUrlFilter }
        for (winner in resolved.groupBy { it.workKey to deviceId }
            .values
            .map { group -> group.maxWith(POSITION_WINNER_ORDER) }
            .filter { bookUrlFilter == null || it.book.url == bookUrlFilter }
            .filter { it.owesPosition() }) {
            val book = winner.book
            val workKey = winner.workKey
            val progress = winner.progress
            val pendingUnread = progress.hasPendingUnreadReset(winner.peerState?.ackedRevision ?: 0L)
            val editionSha = identities.fingerprint(book.url)?.sha256?.lowercase(Locale.ROOT)
                ?: winner.aliasEditionSha?.lowercase(Locale.ROOT)
            val status = readingStatusFor(
                progress.totalProgression,
                progress.override,
                progress.partialCoverage,
            )
            val choice = ReadingStatusChoice.forOverride(progress.override)
            val posKey = positionRecordKey(workKey, deviceId)
            val body = DrivePositionBody(
                workKey = workKey,
                deviceId = deviceId,
                editionSha = editionSha,
                locatorJson = DriveCanonicalJson.canonicalizeJsonString(
                    progress.locatorJson.ifBlank { ReadingProgress.EMPTY_LOCATOR },
                ),
                totalProgression = progress.totalProgression,
                status = status.wireName,
                finishedOverride = choice.wireName,
                coverage = progress.coverage,
                unreadResetAt = if (pendingUnread) progress.updatedAt else null,
                readAt = progress.readAt ?: progress.updatedAt,
                updatedAt = progress.updatedAt,
            )
            val bodyMap = DriveWireCodec.encodePositionBody(body)
            val fp = DriveCanonicalJson.contentFp(bodyMap)
            val existing = recordStates.get(permissionId, DriveWireCodec.DOMAIN_POSITION, posKey)
            if (existing == null || existing.publishedFp != fp) {
                val hlc = nextHlc()
                val anc = buildList {
                    existing?.versionFp?.takeIf { it != fp }?.let(::add)
                    addAll(DriveWorkIdentity.decodeStringArray(existing?.ancestorsJson))
                }.distinct().take(DriveWireCodec.MAX_ANCESTORS)
                val record = DriveWireCodec.putRecord(
                    domain = DriveWireCodec.DOMAIN_POSITION,
                    key = posKey,
                    hlc = hlc,
                    bodyMap = bodyMap,
                    ancestors = anc,
                )
                recordStates.upsert(
                    DriveRecordState(
                        permissionId = permissionId,
                        domain = DriveWireCodec.DOMAIN_POSITION,
                        recordKey = posKey,
                        versionFp = fp,
                        versionOp = DriveRecordState.OP_PUT,
                        publishedFp = existing?.publishedFp,
                        contentFp = fp,
                        ancestorsJson = DriveCanonicalJson.encode(anc),
                        resRefsJson = "[]",
                        lastHlc = hlc,
                        bodyJson = DriveCanonicalJson.encode(bodyMap),
                    ),
                )
                records.add(record)
                val positionStatus = readingStatusFor(
                    progress.totalProgression,
                    partialCoverage = progress.partialCoverage,
                )
                posAcks.add(
                    DrivePositionAckMeta(
                        bookUrl = book.url,
                        localRevision = progress.localRevision,
                        statusRevision = progress.statusRevision,
                        progression = progress.totalProgression,
                        status = positionStatus.wireName,
                        recordKey = posKey,
                        recordFp = fp,
                    ),
                )
            }
        }

        val peakSpeakers = resolved.groupBy { it.workKey }
            .flatMap { (workKey, group) ->
                val editions = group.map { LocalEdition(it.book.url, it.aliasEditionSha, it.progress) }
                peakWinners(workKey, deviceId, editions).map { (edition, _) -> edition.bookUrl }
            }
            .toSet()
        for (entry in local.filter { it.book.url in peakSpeakers }) {
            collectPeak(permissionId, deviceId, drivePeerId, entry, nextHlc, records, peakAcks)
        }

        // Unpublished furthest resets (append-only).
        for (state in recordStates.forDomain(permissionId, DriveWireCodec.DOMAIN_FURTHEST)) {
            if (!state.recordKey.startsWith(RESET_KEY_PREFIX)) continue
            if (state.publishedFp == state.versionFp) continue
            val bodyJson = state.bodyJson ?: continue
            records.add(
                DriveWireRecord(
                    d = DriveWireCodec.DOMAIN_FURTHEST,
                    k = state.recordKey,
                    op = DriveRecordState.OP_PUT,
                    hlc = state.lastHlc,
                    fp = state.versionFp,
                    anc = emptyList(),
                    resRefs = emptyList(),
                    body = JSONObject(bodyJson),
                ),
            )
        }

        return OutboundBundle(records = records, positionAcks = posAcks, peakAcks = peakAcks)
    }

    private suspend fun peakTemplate(
        workKey: String,
        deviceId: String,
        bookUrl: String,
        aliasEditionSha: String?,
        progress: ReadingProgress,
    ): DriveFurthestBody.Peak? {
        val progression = progress.peakProgression
        val peakAt = progress.peakAt
        if (progress.peakRevision == null || progression == null || peakAt == null || progression !in 0.0..1.0) {
            return null
        }
        val edition = (progress.peakEdition
            ?: identities.fingerprint(bookUrl)?.sha256
            ?: aliasEditionSha
            ?: "").lowercase(Locale.ROOT)
        return DriveFurthestBody.Peak(
            workKey = workKey,
            editionSha = edition,
            deviceId = deviceId,
            seq = 0L,
            progression = progression,
            locatorJson = DriveCanonicalJson.canonicalizeJsonString(
                progress.peakLocator?.ifBlank { ReadingProgress.EMPTY_LOCATOR }
                    ?: ReadingProgress.EMPTY_LOCATOR,
            ),
            coverage = progress.peakCoverage,
            peakAt = peakAt,
        )
    }

    private suspend fun collectPeak(
        permissionId: String,
        deviceId: String,
        drivePeerId: String,
        entry: LocalPosition,
        nextHlc: suspend () -> String,
        records: MutableList<DriveWireRecord>,
        peakAcks: MutableList<DrivePeakAckMeta>,
    ) {
        val workKey = entry.workKey
        val peakRev = entry.progress.peakRevision ?: return
        val template = peakTemplate(workKey, deviceId, entry.book.url, entry.aliasEditionSha, entry.progress)
            ?: return
        val delivered = furthestDao.delivered(drivePeerId, entry.book.url, workKey)
        if (delivered?.acknowledged == true && delivered.revision >= peakRev) return

        val peakKey = peakRecordKey(workKey, template.editionSha, deviceId)
        // Allocation and binding commit together so a retry re-sends the same sequence.
        val bound = store.withTransaction {
            val floor = getPersistedFloor(permissionId, workKey, deviceId)
            bindPeak(permissionId, peakKey, peakRev, template, floor, nextHlc)
                .takeIf { it.body.seq > floor && it.state.publishedFp != it.state.versionFp }
        } ?: return
        records.add(
            DriveWireCodec.putRecord(
                domain = DriveWireCodec.DOMAIN_FURTHEST,
                key = peakKey,
                hlc = bound.state.lastHlc,
                bodyMap = DriveWireCodec.encodeFurthestBody(bound.body),
                ancestors = DriveWorkIdentity.decodeStringArray(bound.state.ancestorsJson),
            ),
        )
        peakAcks.add(
            DrivePeakAckMeta(
                bookUrl = entry.book.url,
                workKey = workKey,
                peakRevision = peakRev,
                recordKey = peakKey,
                recordFp = bound.state.versionFp,
            ),
        )
    }

    private fun decodePeak(bodyJson: String?): DriveFurthestBody.Peak? =
        bodyJson?.let { runCatching { DriveWireCodec.decodeFurthestBody(JSONObject(it)) }.getOrNull() }
            as? DriveFurthestBody.Peak

    private class BoundPeak(val state: DriveRecordState, val body: DriveFurthestBody.Peak)

    /** The wire sequence bound to this exact local peak, or null if none was allocated yet. */
    private fun boundPeakSeq(
        existing: DriveRecordState?,
        peakRev: Long,
        template: DriveFurthestBody.Peak,
    ): Long? {
        if (existing == null || existing.versionOp != DriveRecordState.OP_PUT ||
            existing.lastSyncedRevision != peakRev
        ) {
            return null
        }
        val seq = decodePeak(existing.bodyJson)?.seq?.takeIf { it > 0L } ?: return null
        val fp = DriveCanonicalJson.contentFp(
            DriveWireCodec.encodeFurthestBody(template.copy(seq = seq)),
        )
        return seq.takeIf { fp == existing.versionFp }
    }

    /**
     * Binds a local peak to a device-wide wire sequence, allocating one above [floor] and the
     * counter when this peak has none. Per-book revisions never act as wire sequences: they
     * restart for every book, while floors apply to the whole work. Call inside a store transaction.
     */
    private suspend fun bindPeak(
        permissionId: String,
        peakKey: String,
        peakRev: Long,
        template: DriveFurthestBody.Peak,
        floor: Long,
        hlc: suspend () -> String,
    ): BoundPeak {
        val existing = recordStates.get(permissionId, DriveWireCodec.DOMAIN_FURTHEST, peakKey)
        val boundSeq = boundPeakSeq(existing, peakRev, template)
        if (boundSeq != null) {
            return BoundPeak(requireNotNull(existing), template.copy(seq = boundSeq))
        }
        val allocatedAt = hlc()
        val seq = maxOf(peakSeqHighWater(permissionId), floor) + 1L
        recordStates.upsert(
            DriveRecordState(
                permissionId = permissionId,
                domain = PEAK_SEQ_DOMAIN,
                recordKey = PEAK_SEQ_KEY,
                versionFp = seq.toString(),
                versionOp = DriveRecordState.OP_PUT,
                publishedFp = seq.toString(),
                lastSyncedRevision = seq,
                lastHlc = allocatedAt,
            ),
        )
        val body = template.copy(seq = seq)
        val bodyMap = DriveWireCodec.encodeFurthestBody(body)
        val fp = DriveCanonicalJson.contentFp(bodyMap)
        val anc = buildList {
            existing?.versionFp?.takeIf { it != fp }?.let(::add)
            addAll(DriveWorkIdentity.decodeStringArray(existing?.ancestorsJson))
        }.distinct().take(DriveWireCodec.MAX_ANCESTORS)
        val state = DriveRecordState(
            permissionId = permissionId,
            domain = DriveWireCodec.DOMAIN_FURTHEST,
            recordKey = peakKey,
            versionFp = fp,
            versionOp = DriveRecordState.OP_PUT,
            publishedFp = existing?.publishedFp,
            contentFp = fp,
            lastSyncedRevision = peakRev,
            ancestorsJson = DriveCanonicalJson.encode(anc),
            resRefsJson = "[]",
            lastHlc = allocatedAt,
            bodyJson = DriveCanonicalJson.encode(bodyMap),
        )
        recordStates.upsert(state)
        return BoundPeak(state, body)
    }

    private suspend fun peakSeqHighWater(permissionId: String): Long =
        recordStates.get(permissionId, PEAK_SEQ_DOMAIN, PEAK_SEQ_KEY)?.lastSyncedRevision ?: 0L

    /** Commits delivery ACKs to `liseur.db` first, then marks `published_fp` in `drive_sync.db`. */
    suspend fun acknowledgePublished(
        permissionId: String,
        records: List<DriveWireRecord>,
        positionAcks: List<DrivePositionAckMeta>,
        peakAcks: List<DrivePeakAckMeta>,
    ) {
        val drivePeerId = DriveLibrarySync.accountPeerId(permissionId)
        val now = clock()

        // Transaction 1 (`liseur.db`): advance per-peer position/status/peak delivery acks.
        if (positionAcks.isNotEmpty() || peakAcks.isNotEmpty()) {
            database.withTransaction {
                for (ack in positionAcks) {
                    val currentPeerState = peerStateDao.get(ack.bookUrl, drivePeerId)
                    if (currentPeerState?.hasPending == true) {
                        // Preserve unresolved remote conflict until the reader chooses;
                        // only advance delivery revision so this device stops re-uploading.
                        peerStateDao.settlePositionRevision(ack.bookUrl, drivePeerId, ack.localRevision)
                    } else if (currentPeerState == null || currentPeerState.ackedRevision <= ack.localRevision) {
                        peerStateDao.settle(
                            bookUrl = ack.bookUrl,
                            peerId = drivePeerId,
                            ackedRevision = ack.localRevision,
                            progression = ack.progression,
                            status = ack.status,
                            now = now,
                        )
                    }
                    if (ack.statusRevision > 0L) {
                        peerStateDao.settleStatus(ack.bookUrl, drivePeerId, ack.statusRevision)
                    }
                }
                for (peak in peakAcks) {
                    if (progressDao.get(peak.bookUrl) != null) {
                        val existingDelivery = furthestDao.delivered(drivePeerId, peak.bookUrl, peak.workKey)
                        if (existingDelivery == null || !existingDelivery.acknowledged || existingDelivery.revision < peak.peakRevision) {
                            furthestDao.acknowledge(
                                PeakDelivery(
                                    peerId = drivePeerId,
                                    bookUrl = peak.bookUrl,
                                    workId = peak.workKey,
                                    revision = peak.peakRevision,
                                    acknowledged = true,
                                ),
                            )
                        }
                    }
                }
            }
        }

        // Transaction 2 (`drive_sync.db`): mark published_fp.
        store.withTransaction {
            for (record in records) {
                if (record.d != DriveWireCodec.DOMAIN_POSITION && record.d != DriveWireCodec.DOMAIN_FURTHEST) continue
                val existing = recordStates.get(permissionId, record.d, record.k) ?: continue
                if (existing.versionFp == record.fp) {
                    recordStates.upsert(existing.copy(publishedFp = record.fp))
                }
            }
        }
    }

    // -- Inbound apply --------------------------------------------------------

    /** Applies an incoming remote `position` record via `reconcileReadingState`. */
    suspend fun applyRemotePosition(
        permissionId: String,
        localDeviceId: String,
        record: DriveWireRecord,
        remoteDeviceId: String,
        remoteSeq: Long,
    ) {
        if (record.op != DriveRecordState.OP_PUT) return
        val bodyJson = record.body ?: return
        val body = DriveWireCodec.decodePositionBody(bodyJson) ?: return
        if (body.deviceId == localDeviceId) return

        val existing = recordStates.get(permissionId, DriveWireCodec.DOMAIN_POSITION, record.k)
        if (existing != null && record.hlc <= existing.lastHlc) return

        val canonicalWorkKey = workIdentity.canonicalWorkKey(permissionId, body.workKey)
        val incoming = RemotePosition(lastHlc = record.hlc, recordKey = record.k, body = body)
        val stored = selectWinningRemotePosition(permissionId, localDeviceId, canonicalWorkKey)
        // Only apply when this record is the current winning remote position across all writers,
        // so an older writer delivered after a newer one never overwrites the newer resume point.
        if (stored == null || remotePositionOrder.compare(incoming, stored) > 0) {
            val drivePeerId = DriveLibrarySync.accountPeerId(permissionId)
            val matchingAliases = identities.aliasesFor(drivePeerId).filter { alias ->
                alias.usable && workIdentity.canonicalWorkKey(permissionId, alias.workId) == canonicalWorkKey
            }
            val now = clock()

            // Transaction 1 (`liseur.db`) before Transaction 2 (`drive_sync.db`): a crash in
            // between must leave the record unpublished so the replay applies it, instead of
            // storing it as done while the reading position was never reconciled.
            for (alias in matchingAliases) {
                reconcileIncomingPositionForBook(
                    drivePeerId = drivePeerId,
                    alias = alias,
                    body = incoming.body,
                    now = now,
                )
            }
        }

        // Transaction 2 (`drive_sync.db`): remember the record as applied.
        store.withTransaction {
            val anc = (listOfNotNull(existing?.versionFp) +
                DriveWorkIdentity.decodeStringArray(existing?.ancestorsJson) + record.anc)
                .distinct()
                .filter { it != record.fp }
                .take(DriveWireCodec.MAX_ANCESTORS)
            recordStates.upsert(
                DriveRecordState(
                    permissionId = permissionId,
                    domain = DriveWireCodec.DOMAIN_POSITION,
                    recordKey = record.k,
                    versionFp = record.fp,
                    versionOp = DriveRecordState.OP_PUT,
                    publishedFp = record.fp,
                    contentFp = record.fp,
                    originDeviceId = remoteDeviceId,
                    originSeq = remoteSeq,
                    ancestorsJson = DriveCanonicalJson.encode(anc),
                    resRefsJson = "[]",
                    lastHlc = record.hlc,
                    bodyJson = DriveCanonicalJson.encode(DriveWireCodec.encodePositionBody(body)),
                ),
            )
        }
    }

    /**
     * Applies the current winning remote position already stored for each work to Drive aliases
     * not yet seeded: a book matched, merged or re-keyed after those records arrived would
     * otherwise never see them, since their stream cursors have moved on.
     */
    suspend fun seedUnseededAliases(permissionId: String, localDeviceId: String) {
        val drivePeerId = DriveLibrarySync.accountPeerId(permissionId)
        val unseeded = identities.aliasesFor(drivePeerId).filter { it.usable && !it.seeded }
        if (unseeded.isEmpty()) return
        val winningByWork = linkedMapOf<String, RemotePosition>()
        for (state in recordStates.forDomain(permissionId, DriveWireCodec.DOMAIN_POSITION)) {
            if (state.versionOp != DriveRecordState.OP_PUT) continue
            val body = state.bodyJson
                ?.let { runCatching { DriveWireCodec.decodePositionBody(JSONObject(it)) }.getOrNull() }
                ?.takeIf { it.deviceId != localDeviceId }
                ?: continue
            val canonical = workIdentity.canonicalWorkKey(permissionId, body.workKey)
            val candidate = RemotePosition(lastHlc = state.lastHlc, recordKey = state.recordKey, body = body)
            val current = winningByWork[canonical]
            if (current == null || remotePositionOrder.compare(candidate, current) > 0) {
                winningByWork[canonical] = candidate
            }
        }
        val now = clock()
        for (alias in unseeded) {
            val canonical = workIdentity.canonicalWorkKey(permissionId, alias.workId)
            val winner = winningByWork[canonical]
            if (winner != null) {
                reconcileIncomingPositionForBook(
                    drivePeerId = drivePeerId,
                    alias = alias,
                    body = winner.body,
                    now = now,
                )
            }
            identities.markSeeded(alias.bookUrl, drivePeerId)
        }
    }

    /** A remote writer's position, comparable without first storing it. */
    private class RemotePosition(
        val lastHlc: String,
        val recordKey: String,
        val body: DrivePositionBody,
    )

    private suspend fun selectWinningRemotePosition(
        permissionId: String,
        localDeviceId: String,
        canonicalWorkKey: String,
    ): RemotePosition? {
        var winner: RemotePosition? = null
        for (state in recordStates.forDomain(permissionId, DriveWireCodec.DOMAIN_POSITION)) {
            if (state.versionOp != DriveRecordState.OP_PUT) continue
            val body = state.bodyJson
                ?.let { runCatching { DriveWireCodec.decodePositionBody(JSONObject(it)) }.getOrNull() }
                ?.takeIf { it.deviceId != localDeviceId }
                ?: continue
            if (workIdentity.canonicalWorkKey(permissionId, body.workKey) != canonicalWorkKey) continue
            val candidate = RemotePosition(lastHlc = state.lastHlc, recordKey = state.recordKey, body = body)
            if (winner == null || remotePositionOrder.compare(candidate, winner) > 0) {
                winner = candidate
            }
        }
        return winner
    }

    private val remotePositionOrder = compareBy<RemotePosition>(
        { maxOf(it.body.readAt ?: 0L, it.body.updatedAt) },
        { it.body.updatedAt },
        { it.lastHlc },
        { it.recordKey },
    )

    /** Applies an incoming remote `furthest` record (peak or reset). */
    suspend fun applyRemoteFurthest(
        permissionId: String,
        localDeviceId: String,
        record: DriveWireRecord,
        remoteDeviceId: String,
        remoteSeq: Long,
    ) {
        if (record.op != DriveRecordState.OP_PUT) return
        val bodyJson = record.body ?: return
        val body = DriveWireCodec.decodeFurthestBody(bodyJson) ?: return
        val drivePeerId = DriveLibrarySync.accountPeerId(permissionId)
        val canonicalWorkKey = workIdentity.canonicalWorkKey(permissionId, body.workKey)

        when (body) {
            is DriveFurthestBody.Reset -> {
                // Transaction 1 (`liseur.db`): delete observations <= floor per origin & reset local peaks it covers.
                applyFloorsLocally(permissionId, canonicalWorkKey, body.floors, localDeviceId)
                // Transaction 2 (`drive_sync.db`): persist durable floors & reset record forever.
                store.withTransaction {
                    for ((origin, floor) in body.floors) {
                        raisePersistedFloor(permissionId, canonicalWorkKey, origin, floor, record.hlc)
                    }
                    recordStates.upsert(
                        DriveRecordState(
                            permissionId = permissionId,
                            domain = DriveWireCodec.DOMAIN_FURTHEST,
                            recordKey = record.k,
                            versionFp = record.fp,
                            versionOp = DriveRecordState.OP_PUT,
                            publishedFp = record.fp,
                            contentFp = record.fp,
                            originDeviceId = remoteDeviceId,
                            originSeq = remoteSeq,
                            ancestorsJson = "[]",
                            resRefsJson = "[]",
                            lastHlc = record.hlc,
                            bodyJson = DriveCanonicalJson.encode(DriveWireCodec.encodeFurthestBody(body)),
                        ),
                    )
                }
            }
            is DriveFurthestBody.Peak -> {
                if (body.deviceId == localDeviceId) return
                val floor = getPersistedFloor(permissionId, canonicalWorkKey, body.deviceId)
                if (body.seq <= floor) return

                observePeak(drivePeerId, canonicalWorkKey, body)
                // Batches can arrive out of order; the retained record must stay the highest seq.
                val retained = decodePeak(recordStates.get(permissionId, DriveWireCodec.DOMAIN_FURTHEST, record.k)?.bodyJson)
                if (retained != null && retained.seq > body.seq) return
                store.withTransaction {
                    recordStates.upsert(
                        DriveRecordState(
                            permissionId = permissionId,
                            domain = DriveWireCodec.DOMAIN_FURTHEST,
                            recordKey = record.k,
                            versionFp = record.fp,
                            versionOp = DriveRecordState.OP_PUT,
                            publishedFp = record.fp,
                            contentFp = record.fp,
                            originDeviceId = remoteDeviceId,
                            originSeq = remoteSeq,
                            ancestorsJson = DriveCanonicalJson.encode(record.anc.take(DriveWireCodec.MAX_ANCESTORS)),
                            resRefsJson = "[]",
                            lastHlc = record.hlc,
                            bodyJson = DriveCanonicalJson.encode(DriveWireCodec.encodeFurthestBody(body)),
                        ),
                    )
                }
            }
        }
    }

    private suspend fun reconcileIncomingPositionForBook(
        drivePeerId: String,
        alias: WorkAlias,
        body: DrivePositionBody,
        now: Long,
    ) {
        val bookUrl = alias.bookUrl
        var stored = progressDao.get(bookUrl)
        var peerState = peerStateDao.get(bookUrl, drivePeerId)
        val localEditionSha = identities.fingerprint(bookUrl)?.sha256?.lowercase(Locale.ROOT)
            ?: alias.editionSha?.lowercase(Locale.ROOT)
        val sameEdition = !body.editionSha.isNullOrBlank() &&
            !localEditionSha.isNullOrBlank() &&
            body.editionSha.equals(localEditionSha, ignoreCase = true)

        val remoteChoice = ReadingStatusChoice.fromWire(body.finishedOverride) ?: ReadingStatusChoice.AUTOMATIC
        // Capture pre-adoption position debt before any status adoption bumps localRevision,
        // so adopting a remote status never silently acknowledges unrelated local movement.
        val hadPositionDebt = stored != null && (peerState == null || peerState.isDirty(stored.localRevision))
        val statusDirty = stored != null && (peerState == null || peerState.owesStatus(stored.statusRevision))

        // Handle explicit remote unread reset first.
        if (body.unreadResetAt != null || remoteChoice == ReadingStatusChoice.UNREAD) {
            if (!hadPositionDebt && !statusDirty) {
                val applied = progressDao.openBooks.unlessOpen(bookUrl) {
                    database.withTransaction {
                        progressDao.setFinishedOverride(
                            bookUrl = bookUrl,
                            override = FinishedOverride.UNREAD.ordinal,
                            status = ReadingStatus.READY_TO_READ.wireName,
                            progression = 0.0,
                            now = now,
                            resetPosition = true,
                        )
                        val refreshed = progressDao.get(bookUrl)
                        peerStateDao.settle(
                            bookUrl = bookUrl,
                            peerId = drivePeerId,
                            ackedRevision = refreshed?.localRevision ?: 1L,
                            progression = 0.0,
                            status = ReadingStatus.READY_TO_READ.wireName,
                            now = now,
                        )
                        if (refreshed != null && refreshed.statusRevision > 0L) {
                            peerStateDao.settleStatus(bookUrl, drivePeerId, refreshed.statusRevision)
                        }
                    }
                    true
                } == true
                if (applied) {
                    finishedState.refreshFromProgress(bookUrl)
                    return
                }
            }
            peerStateDao.persistPendingStatus(
                bookUrl = bookUrl,
                peerId = drivePeerId,
                choice = ReadingStatusChoice.UNREAD.wireName,
                remoteUpdatedAt = body.updatedAt,
            )
            if (!hadPositionDebt && !statusDirty) {
                peerStateDao.persistPending(
                    bookUrl = bookUrl,
                    peerId = drivePeerId,
                    progression = 0.0,
                    status = ReadingStatus.READY_TO_READ.wireName,
                    remoteUpdatedAt = body.readAt ?: body.updatedAt,
                    locatorJson = null,
                    editionSha = body.editionSha,
                    coverage = body.coverage,
                )
                return
            }
        } else {
            val localOverride = stored?.override ?: FinishedOverride.NONE
            if (localOverride != remoteChoice.override) {
                if (!statusDirty) {
                    val expectedBefore = stored?.localRevision ?: 0L
                    val adopted = finishedState.adoptRemoteStatus(
                        bookUrl = bookUrl,
                        expectedStatusRevision = stored?.statusRevision ?: 0L,
                        account = drivePeerId,
                        override = remoteChoice.override,
                        status = resolvedStatus(remoteChoice, stored),
                        finishedAt = body.updatedAt.takeIf { remoteChoice == ReadingStatusChoice.FINISHED },
                        expectedLocalRevision = expectedBefore,
                        keepUnreadReset = true,
                        handOverOwnership = true,
                    )
                    if (adopted) {
                        val after = progressDao.get(bookUrl)
                        if (after != null) {
                            peerStateDao.settleStatus(bookUrl, drivePeerId, after.statusRevision)
                            peerStateDao.clearPendingStatus(bookUrl, drivePeerId)
                            // Taking a status is not a position move: only settle localRevision
                            // when the local position had no unsent debt prior to adoption.
                            if (!hadPositionDebt && after.localRevision == expectedBefore + 1L) {
                                peerStateDao.settlePositionRevision(bookUrl, drivePeerId, after.localRevision)
                            }
                            if (after.localRevision != expectedBefore + 1L) {
                                // A page turned concurrently during adoption; abandon this pass.
                                return
                            }
                            stored = after
                            peerState = peerStateDao.get(bookUrl, drivePeerId)
                        }
                    } else if ((progressDao.get(bookUrl)?.localRevision ?: 0L) != expectedBefore) {
                        // A page turned concurrently before adoption; abandon this pass.
                        return
                    }
                } else {
                    peerStateDao.persistPendingStatus(
                        bookUrl = bookUrl,
                        peerId = drivePeerId,
                        choice = remoteChoice.wireName,
                        remoteUpdatedAt = body.updatedAt,
                    )
                }
            }
        }

        val remotePosStatus = if (body.totalProgression != null) {
            readingStatusFor(
                body.totalProgression,
                partialCoverage = body.coverage == NarrativeCoverage.PARTIAL.wireName,
            )
        } else {
            ReadingStatus.fromWire(body.status)
        }
        val remoteState = ReadingState(
            progression = body.totalProgression,
            status = remotePosStatus,
            updatedAt = body.readAt ?: body.updatedAt,
        )
        val localState = stored?.let { row ->
            ReadingState(
                progression = row.totalProgression,
                status = readingStatusFor(row.totalProgression, partialCoverage = row.partialCoverage),
                updatedAt = row.readAt ?: row.updatedAt,
            )
        }
        val baseline = peerState?.let { ps ->
            if (ps.agreedProgression == null && ps.agreedStatus == null) {
                null
            } else {
                ReadingBaseline(
                    progression = ps.agreedProgression,
                    status = ReadingStatus.fromWire(ps.agreedStatus),
                )
            }
        }

        val exactRemote = if (sameEdition && ExactLocatorAnchor.isExactJson(body.locatorJson)) {
            body.locatorJson
        } else {
            null
        }
        val restorableRemote = if (sameEdition) {
            body.locatorJson.takeIf { it.isNotBlank() && it != ReadingProgress.EMPTY_LOCATOR }
        } else {
            null
        }

        val decision = reconcileReadingState(
            local = localState,
            remote = remoteState,
            baseline = baseline,
            localDirty = hadPositionDebt,
            localUnreadOverride = stored?.override == FinishedOverride.UNREAD,
            unreadResetUndelivered = stored?.hasPendingUnreadReset(peerState?.ackedRevision ?: 0L) == true,
            exactPositionAgreement = ExactLocatorAnchor.agreement(stored?.locatorJson, exactRemote),
        )

        when (decision) {
            SyncDecision.InSync -> {
                peerStateDao.settle(
                    bookUrl = bookUrl,
                    peerId = drivePeerId,
                    ackedRevision = stored?.localRevision ?: 0L,
                    progression = stored?.totalProgression,
                    status = localState?.status?.wireName,
                    now = now,
                )
            }
            is SyncDecision.Pull -> {
                val progression = decision.state.progression ?: return
                val expected = stored?.localRevision ?: 0L
                val effectiveStatus = readingStatusFor(
                    progression,
                    stored?.override ?: FinishedOverride.NONE,
                    partialCoverage = body.coverage == NarrativeCoverage.PARTIAL.wireName,
                )
                var applied = false
                database.withTransaction {
                    // Always respect the open-reader fence for automatic imports (including
                    // book-scoped syncs); direct applyPeerPull is reserved for explicit reader resolutions.
                    applied = progressDao.applyUnattendedPeerPull(
                        bookUrl = bookUrl,
                        expectedRevision = expected,
                        progression = progression,
                        status = effectiveStatus.wireName,
                        now = now,
                        locatorJson = restorableRemote,
                        remoteUpdatedAt = body.readAt ?: body.updatedAt,
                        coverage = body.coverage,
                    )
                    if (applied) {
                        peerStateDao.settle(
                            bookUrl = bookUrl,
                            peerId = drivePeerId,
                            ackedRevision = expected + 1L,
                            progression = progression,
                            status = decision.state.status.wireName,
                            now = now,
                        )
                    } else {
                        // Book is open or moved concurrently: preserve pending for next open.
                        peerStateDao.persistPending(
                            bookUrl = bookUrl,
                            peerId = drivePeerId,
                            progression = progression,
                            status = decision.state.status.wireName,
                            remoteUpdatedAt = body.readAt ?: body.updatedAt,
                            locatorJson = body.locatorJson.takeUnless { it == ReadingProgress.EMPTY_LOCATOR },
                            editionSha = body.editionSha,
                            coverage = body.coverage,
                        )
                    }
                }
                if (applied) finishedState.refreshFromProgress(bookUrl)
            }
            is SyncDecision.AdoptStatus -> {
                val adopted = progressDao.adoptPeerStatus(bookUrl, decision.status.wireName, now)
                if (adopted) finishedState.refreshFromProgress(bookUrl)
            }
            is SyncDecision.Push -> {
                // Local moved (including intentional backward moves); keep local state for outbound publish.
                peerStateDao.clearPending(bookUrl, drivePeerId)
            }
            is SyncDecision.Conflict -> {
                peerStateDao.persistPending(
                    bookUrl = bookUrl,
                    peerId = drivePeerId,
                    progression = decision.remote.progression,
                    status = decision.remote.status.wireName,
                    remoteUpdatedAt = body.readAt ?: body.updatedAt,
                    locatorJson = body.locatorJson.takeUnless { it == ReadingProgress.EMPTY_LOCATOR },
                    editionSha = body.editionSha,
                    coverage = body.coverage,
                )
            }
        }
    }

    private fun resolvedStatus(choice: ReadingStatusChoice, stored: ReadingProgress?): ReadingStatus =
        when (choice) {
            ReadingStatusChoice.FINISHED -> ReadingStatus.FINISHED
            ReadingStatusChoice.UNREAD -> ReadingStatus.READY_TO_READ
            ReadingStatusChoice.WANT_TO_READ -> ReadingStatus.WANT_TO_READ
            ReadingStatusChoice.READING -> ReadingStatus.READING
            ReadingStatusChoice.PAUSED -> ReadingStatus.PAUSED
            ReadingStatusChoice.DID_NOT_FINISH -> ReadingStatus.DID_NOT_FINISH
            ReadingStatusChoice.AUTOMATIC ->
                readingStatusFor(
                    stored?.totalProgression,
                    FinishedOverride.NONE,
                    stored?.partialCoverage == true,
                )
        }

    // -- Furthest helpers -----------------------------------------------------

    private suspend fun furthestDestination(
        permissionId: String,
        bookUrl: String,
        alias: WorkAlias,
    ): FurthestDestination? {
        val drivePeerId = DriveLibrarySync.accountPeerId(permissionId)
        val workKey = workIdentity.canonicalWorkKey(permissionId, alias.workId)
        val localEdition = identities.fingerprint(bookUrl)?.sha256?.lowercase(Locale.ROOT)
            ?: alias.editionSha?.lowercase(Locale.ROOT)

        data class Candidate(
            val id: String,
            val edition: String?,
            val progression: Double,
            val seq: Long,
            val locatorJson: String?,
            val coverage: NarrativeCoverage?,
            val at: Long,
            val locallyAuthored: Boolean,
        )

        val candidates = mutableListOf<Candidate>()
        for (row in furthestDao.forWork(drivePeerId, workKey)) {
            val payload = runCatching { JSONObject(row.payload) }.getOrNull()
            val loc = payload?.optString("locatorJson")?.takeIf { it.isNotBlank() && it != ReadingProgress.EMPTY_LOCATOR }
            val cov = payload?.takeIf { it.has("coverage") && !it.isNull("coverage") }
                ?.optString("coverage")?.let(NarrativeCoverage::fromWire)
            val at = payload?.optLong("peakAt", 0L) ?: 0L
            candidates.add(
                Candidate(
                    id = "${row.origin}:${row.edition}:${row.seq}",
                    edition = row.edition.takeIf { it.isNotBlank() },
                    progression = row.progression,
                    seq = row.seq,
                    locatorJson = loc,
                    coverage = cov,
                    at = at,
                    locallyAuthored = false,
                ),
            )
        }
        val stored = progressDao.get(bookUrl)
        val localProg = stored?.peakProgression
        val localAt = stored?.peakAt
        if (localProg != null && localAt != null && localProg in 0.0..1.0) {
            candidates.add(
                Candidate(
                    id = "local:${stored.peakRevision ?: 0L}",
                    edition = stored.peakEdition ?: localEdition,
                    progression = localProg,
                    seq = stored.peakRevision ?: 0L,
                    locatorJson = stored.peakLocator?.takeUnless { it == ReadingProgress.EMPTY_LOCATOR },
                    coverage = stored.peakNarrativeCoverage,
                    at = localAt,
                    locallyAuthored = true,
                ),
            )
        }
        val best = candidates.sortedWith(
            compareByDescending<Candidate> { it.progression }.thenBy { it.seq }.thenBy { it.id },
        ).firstOrNull() ?: return null

        val sameEdition = best.locallyAuthored ||
            (!best.edition.isNullOrBlank() && !localEdition.isNullOrBlank() &&
                best.edition.equals(localEdition, ignoreCase = true))
        val restorable = if (sameEdition) best.locatorJson else null
        return FurthestDestination(
            accountKey = drivePeerId,
            workId = alias.workId,
            localEdition = alias.editionSha,
            candidateId = best.id,
            progression = best.progression,
            locatorJson = restorable,
            at = best.at,
            confidence = if (sameEdition && ExactLocatorAnchor.isExactJson(restorable)) {
                ResumeConfidence.EXACT
            } else {
                ResumeConfidence.APPROXIMATE
            },
            locallyAuthored = best.locallyAuthored,
            coverage = best.coverage,
        )
    }

    private suspend fun knownMaxSeq(permissionId: String, workKey: String): Long {
        val drivePeerId = DriveLibrarySync.accountPeerId(permissionId)
        val canonical = workIdentity.canonicalWorkKey(permissionId, workKey)
        val observedMax = furthestDao.forWork(drivePeerId, canonical).maxOfOrNull { it.seq } ?: 0L
        return observedMax
    }

    internal suspend fun getPersistedFloor(
        permissionId: String,
        workKey: String,
        originDeviceId: String,
    ): Long {
        val state = recordStates.get(permissionId, FLOOR_STATE_DOMAIN, floorRecordKey(workKey, originDeviceId))
        return state?.lastSyncedRevision ?: 0L
    }

    private suspend fun raisePersistedFloor(
        permissionId: String,
        workKey: String,
        originDeviceId: String,
        floor: Long,
        hlc: String,
    ) {
        val key = floorRecordKey(workKey, originDeviceId)
        val existing = recordStates.get(permissionId, FLOOR_STATE_DOMAIN, key)
        val current = existing?.lastSyncedRevision ?: 0L
        if (floor <= current) return
        recordStates.upsert(
            DriveRecordState(
                permissionId = permissionId,
                domain = FLOOR_STATE_DOMAIN,
                recordKey = key,
                versionFp = floor.toString(),
                versionOp = DriveRecordState.OP_PUT,
                publishedFp = floor.toString(),
                lastSyncedRevision = floor,
                lastHlc = hlc,
            ),
        )
    }

    private fun SyncPeerState.sameEdition(alias: WorkAlias?): Boolean {
        val remoteSha = pendingEditionSha?.takeIf { it.isNotBlank() } ?: return false
        val localSha = alias?.editionSha?.takeIf { it.isNotBlank() } ?: return false
        return remoteSha.equals(localSha, ignoreCase = true)
    }

    private fun SyncPeerState.exactLocatorFor(alias: WorkAlias?): String? =
        if (sameEdition(alias) && ExactLocatorAnchor.isExactJson(pendingLocatorJson)) {
            pendingLocatorJson
        } else {
            null
        }

    private fun SyncPeerState.restorableLocatorFor(alias: WorkAlias?): String? {
        if (!sameEdition(alias)) return null
        val json = pendingLocatorJson?.takeIf { it.isNotBlank() && it != ReadingProgress.EMPTY_LOCATOR }
            ?: return null
        return if (ExactLocatorAnchor.isExactJson(json) || ResourceAnchor.isResourceJson(json)) json else null
    }

    companion object {
        const val DRIVE_DIALLED_ADDRESS = "https://www.googleapis.com"
        const val PEAK_KEY_PREFIX = "peak/"
        const val RESET_KEY_PREFIX = "reset/"
        private const val FLOOR_STATE_DOMAIN = "_furthest_floor"
        private const val PEAK_SEQ_DOMAIN = "_furthest_peak_seq"
        private const val PEAK_SEQ_KEY = "self"
        private const val RESET_PENDING_DOMAIN = "_furthest_reset_pending"
        private const val PENDING_REVISIONS = "revisions"

        /** Latest reading wins a shared `(workKey, deviceId)` slot; the URL keeps the choice stable. */
        private val POSITION_WINNER_ORDER: Comparator<LocalPosition> = compareBy<LocalPosition>(
            { maxOf(it.progress.readAt ?: 0L, it.progress.updatedAt) },
            { it.progress.updatedAt },
            { it.book.url },
        )

        private val PEAK_WINNER_ORDER: Comparator<Pair<LocalEdition, DriveFurthestBody.Peak>> =
            compareBy<Pair<LocalEdition, DriveFurthestBody.Peak>>(
                { it.second.progression },
                { it.second.peakAt },
            ).thenComparator { a, b -> b.first.bookUrl.compareTo(a.first.bookUrl) }

        fun positionRecordKey(workKey: String, deviceId: String): String = "$workKey/$deviceId"
        fun peakRecordKey(workKey: String, editionSha: String, deviceId: String): String =
            "$PEAK_KEY_PREFIX$workKey/$editionSha/$deviceId"
        fun resetRecordKey(workKey: String, resetId: String): String =
            "$RESET_KEY_PREFIX$workKey/$resetId"
        private fun floorRecordKey(workKey: String, originDeviceId: String): String =
            "$workKey/$originDeviceId"
    }
}
