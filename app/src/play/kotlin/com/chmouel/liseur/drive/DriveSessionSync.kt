package com.chmouel.liseur.drive

import androidx.room.withTransaction
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.ReadingSession
import java.util.UUID

internal data class DriveSessionOutboundBundle(
    val records: List<DriveWireRecord>,
    val acks: List<DriveSessionAckMeta>,
)

/**
 * Reading session sync for Google Drive (`docs/DRIVE_SYNC.md` §4.12 & ADR-0078).
 *
 * Key invariants:
 * - Deterministic content-derived `remote_session_id` is generated ONCE if null
 *   (immune to Android Auto Backup `DeviceIdentity` collisions) and never cleared.
 * - Eligible local sessions are selected by missing per-account receipt in
 *   `drive_session_ack` AND `ended_at IS NOT NULL` AND `imported_at IS NULL`
 *   AND `origin_device_id IS NULL` AND valid progressions — NEVER by
 *   `remote_session_id IS NULL`, so switching to Account B or restoring
 *   `liseur.db` without `drive_sync.db` seeds all eligible local sessions.
 * - Incoming remote sessions persist idempotently via `INSERT OR IGNORE` on
 *   `reading_sessions.remote_session_id` with non-null `origin_device_id` and
 *   `imported_at = NULL`, so duplicate/reordered deliveries and Tx1-Tx2 crash
 *   replays count every session exactly once, never get re-uploaded to Drive or
 *   `liseur-sync`, and feed local cross-device statistics directly.
 */
internal class DriveSessionSync(
    private val database: LiseurDatabase,
    private val store: DriveSyncDatabase,
    private val workIdentity: DriveWorkIdentity,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val sessions get() = database.readingSessionDao()
    private val identities get() = database.workIdentityDao()
    private val books get() = database.bookDao()
    private val sessionAcks get() = store.sessionAcks()
    private val recordStates get() = store.recordStates()

    /**
     * Ensures all eligible local sessions for books with a usable Drive `work_alias`
     * have their deterministic `remote_session_id` persisted BEFORE pulling remote
     * sessions. Prevents duplicate rows if `liseur.db` was restored from backup
     * without `drive_sync.db` before initial sync.
     */
    suspend fun ensureLocalRemoteSessionIds(permissionId: String) {
        val peerId = DriveLibrarySync.accountPeerId(permissionId)
        val candidates = sessions.localSessionsForSync().filter { it.remoteSessionId.isNullOrBlank() }
        if (candidates.isEmpty()) return
        val usableAliasByBook = identities.aliasesFor(peerId)
            .filter { it.usable }
            .associateBy { it.bookUrl }
        for (session in candidates) {
            val endedAt = session.endedAt ?: continue
            val rawStart = session.startProgression ?: continue
            val rawEnd = session.endProgression ?: continue
            if (!rawStart.isFinite() || !rawEnd.isFinite()) continue
            val alias = usableAliasByBook[session.bookUrl] ?: continue
            val workKey = workIdentity.canonicalWorkKey(permissionId, alias.workId)
            ensureRemoteSessionId(
                session = session,
                workKey = workKey,
                endedAt = maxOf(endedAt, session.startedAt),
                durationMs = session.durationMs.coerceAtLeast(0L),
                startProgression = rawStart.coerceIn(0.0, 1.0),
                endProgression = rawEnd.coerceIn(0.0, 1.0),
            )
        }
    }

    /**
     * Collects eligible local sessions missing a per-account receipt in `drive_session_ack`.
     */
    suspend fun collectDirtyRecords(
        permissionId: String,
        localDeviceId: String,
        bookUrlFilter: String?,
        nextHlc: suspend () -> String,
    ): DriveSessionOutboundBundle {
        val peerId = DriveLibrarySync.accountPeerId(permissionId)
        val candidates = if (bookUrlFilter != null) {
            sessions.localSessionsForBookSync(bookUrlFilter)
        } else {
            sessions.localSessionsForSync()
        }
        if (candidates.isEmpty()) {
            return DriveSessionOutboundBundle(emptyList(), emptyList())
        }

        val ackedIds = sessionAcks.ackedIds(permissionId).toSet()
        val usableAliasByBook = identities.aliasesFor(peerId)
            .filter { it.usable }
            .associateBy { it.bookUrl }

        val records = mutableListOf<DriveWireRecord>()
        val acks = mutableListOf<DriveSessionAckMeta>()
        val seenIds = mutableSetOf<String>()

        for (session in candidates) {
            val endedAt = session.endedAt ?: continue
            val rawStart = session.startProgression ?: continue
            val rawEnd = session.endProgression ?: continue
            if (!rawStart.isFinite() || !rawEnd.isFinite()) continue
            val startProg = rawStart.coerceIn(0.0, 1.0)
            val endProg = rawEnd.coerceIn(0.0, 1.0)
            val safeEndedAt = maxOf(endedAt, session.startedAt)
            val safeDurationMs = session.durationMs.coerceAtLeast(0L)
            val safeIdleMs = (session.idleMs ?: 0L).coerceAtLeast(0L)

            val alias = usableAliasByBook[session.bookUrl] ?: continue
            val workKey = workIdentity.canonicalWorkKey(permissionId, alias.workId)

            val sessionId = ensureRemoteSessionId(
                session = session,
                workKey = workKey,
                endedAt = safeEndedAt,
                durationMs = safeDurationMs,
                startProgression = startProg,
                endProgression = endProg,
            ) ?: continue

            if (sessionId in ackedIds) continue
            if (!seenIds.add(sessionId)) continue

            val body = DriveSessionBody(
                sessionId = sessionId,
                workKey = workKey,
                startedAt = session.startedAt,
                endedAt = safeEndedAt,
                durationMs = safeDurationMs,
                idleMs = safeIdleMs,
                startProgression = startProg,
                endProgression = endProg,
                originDeviceId = localDeviceId,
            )
            val bodyMap = DriveWireCodec.encodeSessionBody(body)
            val hlc = nextHlc()
            val record = DriveWireCodec.putRecord(
                domain = DriveWireCodec.DOMAIN_SESSION,
                key = sessionId,
                hlc = hlc,
                bodyMap = bodyMap,
            )
            records.add(record)
            acks.add(
                DriveSessionAckMeta(
                    remoteSessionId = sessionId,
                    recordFp = record.fp,
                ),
            )
        }

        return DriveSessionOutboundBundle(records = records, acks = acks)
    }

    /**
     * Records per-account delivery receipts in `drive_session_ack` and persists
     * `drive_record_state` for authored sessions (used by Phase 6 compaction).
     */
    suspend fun acknowledgePublished(
        permissionId: String,
        batchSeq: Long,
        records: List<DriveWireRecord>,
        acks: List<DriveSessionAckMeta>,
    ) {
        val sessionRecords = records.filter { it.d == DriveWireCodec.DOMAIN_SESSION }
        if (sessionRecords.isEmpty() && acks.isEmpty()) return

        val now = clock()
        val ackIds = linkedSetOf<String>().apply {
            acks.forEach { add(it.remoteSessionId) }
            sessionRecords.forEach { add(it.k) }
        }
        val ackRows = ackIds.map { id ->
            DriveSessionAck(
                permissionId = permissionId,
                remoteSessionId = id,
                batchSeq = batchSeq,
                ackedAt = now,
            )
        }

        store.withTransaction {
            if (ackRows.isNotEmpty()) {
                sessionAcks.insertAll(ackRows)
            }
            for (record in sessionRecords) {
                val existing = recordStates.get(permissionId, DriveWireCodec.DOMAIN_SESSION, record.k)
                recordStates.upsert(
                    DriveRecordState(
                        permissionId = permissionId,
                        domain = DriveWireCodec.DOMAIN_SESSION,
                        recordKey = record.k,
                        versionFp = record.fp,
                        versionOp = DriveRecordState.OP_PUT,
                        publishedFp = record.fp,
                        contentFp = record.fp,
                        originDeviceId = null,
                        originSeq = null,
                        ancestorsJson = "[]",
                        resRefsJson = "[]",
                        lastHlc = maxOf(existing?.lastHlc.orEmpty(), record.hlc),
                        bodyJson = record.body?.let { DriveCanonicalJson.encode(it) },
                    ),
                )
            }
        }
    }

    /**
     * Applies an incoming remote `session` record idempotently (`INSERT OR IGNORE`
     * on `remote_session_id` with non-null `origin_device_id` and `imported_at = NULL`).
     *
     * Returns `false` when the work has neither a local book nor a catalog tombstone,
     * so the session replays after the catalog entry arrives.
     */
    suspend fun applyRemoteRecord(
        permissionId: String,
        record: DriveWireRecord,
        remoteDeviceId: String,
        remoteSeq: Long,
    ): Boolean {
        if (record.op != DriveRecordState.OP_PUT) return true
        val bodyJson = record.body ?: return true
        val body = DriveWireCodec.decodeSessionBody(bodyJson) ?: return true

        val canonicalWorkKey = workIdentity.canonicalWorkKey(permissionId, body.workKey)
        val targetBookUrl = findBookUrlForWorkKey(permissionId, canonicalWorkKey)
            ?: if (workIdentity.hasDeletedCatalogEntry(permissionId, body.workKey)) {
                DriveWorkIdentity.driveBookUrl(canonicalWorkKey)
            } else {
                return false
            }
        val authorDeviceId = body.originDeviceId.ifBlank { remoteDeviceId }

        // Transaction 1: liseur.db idempotent insert (INSERT OR IGNORE on UNIQUE remote_session_id).
        var alreadyOwnedLocally = false
        database.withTransaction {
            val existing = sessions.byRemoteSessionId(body.sessionId)
            if (existing != null) {
                alreadyOwnedLocally = (existing.originDeviceId == null)
                return@withTransaction
            }
            sessions.insertIgnoringConflicts(
                ReadingSession(
                    bookUrl = targetBookUrl,
                    startedAt = body.startedAt,
                    endedAt = body.endedAt,
                    lastCheckpointAt = body.endedAt,
                    durationMs = body.durationMs,
                    startProgression = body.startProgression,
                    endProgression = body.endProgression,
                    idleMs = body.idleMs,
                    uploadedAt = null,
                    legacyEvidenceUnknown = false,
                    importedAt = null,
                    remoteSessionId = body.sessionId,
                    originDeviceId = authorDeviceId,
                ),
            )
        }

        // Transaction 2: drive_sync.db record state (and per-account ack if this exact sitting was already owned locally).
        val canonicalBody = DriveCanonicalJson.encode(
            DriveWireCodec.encodeSessionBody(body.copy(workKey = canonicalWorkKey, originDeviceId = authorDeviceId)),
        )
        store.withTransaction {
            if (alreadyOwnedLocally) {
                sessionAcks.insertAll(
                    listOf(
                        DriveSessionAck(
                            permissionId = permissionId,
                            remoteSessionId = body.sessionId,
                            batchSeq = 0L,
                            ackedAt = clock(),
                        ),
                    ),
                )
            }
            val existing = recordStates.get(permissionId, DriveWireCodec.DOMAIN_SESSION, body.sessionId)
            recordStates.upsert(
                DriveRecordState(
                    permissionId = permissionId,
                    domain = DriveWireCodec.DOMAIN_SESSION,
                    recordKey = body.sessionId,
                    versionFp = record.fp,
                    versionOp = DriveRecordState.OP_PUT,
                    publishedFp = record.fp,
                    contentFp = record.fp,
                    originDeviceId = remoteDeviceId,
                    originSeq = remoteSeq,
                    ancestorsJson = "[]",
                    resRefsJson = "[]",
                    lastHlc = maxOf(existing?.lastHlc.orEmpty(), record.hlc),
                    bodyJson = canonicalBody,
                ),
            )
        }
        return true
    }

    private suspend fun ensureRemoteSessionId(
        session: ReadingSession,
        workKey: String,
        endedAt: Long,
        durationMs: Long,
        startProgression: Double,
        endProgression: Double,
    ): String? {
        session.remoteSessionId?.takeIf { it.isNotBlank() }?.let { return it }

        val candidate = deterministicSessionId(
            workKey = workKey,
            startedAt = session.startedAt,
            endedAt = endedAt,
            durationMs = durationMs,
            startProgression = startProgression,
            endProgression = endProgression,
        )

        var assigned: String? = null
        database.withTransaction {
            val current = sessions.get(session.id) ?: return@withTransaction
            current.remoteSessionId?.takeIf { it.isNotBlank() }?.let {
                assigned = it
                return@withTransaction
            }
            val collision = sessions.byRemoteSessionId(candidate)
            if (collision != null && collision.id != session.id) {
                // Another row already holds this identical sitting's deterministic ID
                // (e.g. a backup-restored device that pulled the exact same sitting).
                return@withTransaction
            }
            sessions.assignRemoteSessionIdIfNull(session.id, candidate)
            assigned = sessions.get(session.id)?.remoteSessionId ?: candidate
        }
        return assigned
    }

    private suspend fun findBookUrlForWorkKey(permissionId: String, canonicalWorkKey: String): String? {
        val peerId = DriveLibrarySync.accountPeerId(permissionId)
        val matched = identities.aliasesFor(peerId)
            .filter {
                it.usable &&
                    workIdentity.canonicalWorkKey(permissionId, it.workId) == canonicalWorkKey &&
                    books.getByUrl(it.bookUrl) != null
            }
        if (matched.isNotEmpty()) {
            return matched.firstOrNull { !it.bookUrl.startsWith(DriveWorkIdentity.DRIVE_BOOK_URL_PREFIX) }?.bookUrl
                ?: matched.first().bookUrl
        }
        val stubUrl = DriveWorkIdentity.driveBookUrl(canonicalWorkKey)
        return books.getByUrl(stubUrl)?.url
    }

    companion object {
        /**
         * Deterministic content-derived session UUID immune to Android Auto Backup
         * `DeviceIdentity` collisions (`docs/DRIVE_SYNC.md` §4.12).
         */
        fun deterministicSessionId(
            workKey: String,
            startedAt: Long,
            endedAt: Long,
            durationMs: Long,
            startProgression: Double,
            endProgression: Double,
        ): String {
            val safeEndedAt = maxOf(endedAt, startedAt)
            val safeDurationMs = durationMs.coerceAtLeast(0L)
            val startStr = DriveCanonicalJson.formatDouble(startProgression.coerceIn(0.0, 1.0))
            val endStr = DriveCanonicalJson.formatDouble(endProgression.coerceIn(0.0, 1.0))
            val input = "liseur-drive-session|v1|$workKey|$startedAt|$safeEndedAt|$safeDurationMs|$startStr|$endStr"
            return UUID.nameUUIDFromBytes(input.toByteArray(Charsets.UTF_8)).toString()
        }
    }
}
