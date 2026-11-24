package com.chmouel.liseur.drive

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DriveInstallDao {
    @Query("SELECT * FROM drive_install WHERE id = :id")
    suspend fun get(id: Long = DriveInstall.SINGLE_ID): DriveInstall?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(install: DriveInstall): Long
}

@Dao
interface DriveAccountDao {
    @Query("SELECT * FROM drive_account WHERE state = 'ACTIVE' LIMIT 1")
    fun observeActive(): Flow<DriveAccount?>

    @Query(
        "SELECT * FROM drive_account ORDER BY " +
            "CASE state WHEN 'ACTIVE' THEN 0 WHEN 'NEEDS_RECONNECT' THEN 1 ELSE 2 END, " +
            "connected_at DESC LIMIT 1",
    )
    fun observePrimary(): Flow<DriveAccount?>

    @Query("SELECT * FROM drive_account ORDER BY connected_at DESC")
    fun observeAll(): Flow<List<DriveAccount>>

    @Query("SELECT * FROM drive_account WHERE state = 'ACTIVE' LIMIT 1")
    suspend fun active(): DriveAccount?

    @Query("SELECT * FROM drive_account WHERE permission_id = :permissionId")
    suspend fun get(permissionId: String): DriveAccount?

    @Query("SELECT * FROM drive_account WHERE permission_id = :permissionId")
    fun observe(permissionId: String): Flow<DriveAccount?>

    @Query("SELECT * FROM drive_account ORDER BY connected_at")
    suspend fun all(): List<DriveAccount>

    @Upsert
    suspend fun upsert(account: DriveAccount)

    @Query("UPDATE drive_account SET state = :state WHERE permission_id = :permissionId")
    suspend fun setState(permissionId: String, state: String)

    @Query("UPDATE drive_account SET state = 'PAUSED' WHERE state IN ('ACTIVE', 'NEEDS_RECONNECT')")
    suspend fun pauseAllActive()

    @Query("UPDATE drive_account SET start_page_token = :startPageToken WHERE permission_id = :permissionId")
    suspend fun updateStartPageToken(permissionId: String, startPageToken: String?)

    @Query("UPDATE drive_account SET folder_id = :folderId WHERE permission_id = :permissionId")
    suspend fun updateFolderId(permissionId: String, folderId: String?)

    @Query(
        "UPDATE drive_account SET manifest_file_id = :fileId, snapshot_through_seq = :snapshotThroughSeq, " +
            "deltas_since_snapshot = :deltasSinceSnapshot WHERE permission_id = :permissionId",
    )
    suspend fun updateManifest(
        permissionId: String,
        fileId: String?,
        snapshotThroughSeq: Long,
        deltasSinceSnapshot: Int,
    )

    @Query("UPDATE drive_account SET hlc_wall_ms = :wallMs, hlc_counter = :counter WHERE permission_id = :permissionId")
    suspend fun updateHlc(permissionId: String, wallMs: Long, counter: Int)

    @Query("UPDATE drive_account SET next_seq = :nextSeq WHERE permission_id = :permissionId")
    suspend fun setNextSeq(permissionId: String, nextSeq: Long)

    @Query("UPDATE drive_account SET last_success_at = :at, last_error = NULL WHERE permission_id = :permissionId")
    suspend fun recordSuccess(permissionId: String, at: Long)

    @Query("UPDATE drive_account SET last_error = :error WHERE permission_id = :permissionId")
    suspend fun recordError(permissionId: String, error: String?)

    @Query("UPDATE drive_account SET metered_transfers = :metered WHERE permission_id = :permissionId")
    suspend fun setMeteredTransfers(permissionId: String, metered: Boolean)

    @Query(
        "UPDATE drive_account SET pending_switch_previous_state = :previousState " +
            "WHERE permission_id = :permissionId",
    )
    suspend fun markPendingSwitch(permissionId: String, previousState: String)

    @Query("UPDATE drive_account SET pending_switch_previous_state = NULL")
    suspend fun clearPendingSwitches()

    @Query("SELECT * FROM drive_account WHERE pending_switch_previous_state IS NOT NULL")
    suspend fun withPendingSwitch(): List<DriveAccount>
}

@Dao
interface DriveDeviceDao {
    @Query("SELECT * FROM drive_device WHERE permission_id = :permissionId AND device_id = :deviceId")
    suspend fun get(permissionId: String, deviceId: String): DriveDevice?

    @Query("SELECT * FROM drive_device WHERE permission_id = :permissionId ORDER BY device_id")
    suspend fun forAccount(permissionId: String): List<DriveDevice>

    @Upsert
    suspend fun upsert(device: DriveDevice)
}

@Dao
interface DriveCursorDao {
    @Query(
        "SELECT * FROM drive_cursor WHERE permission_id = :permissionId AND device_id = :deviceId AND domain = :domain",
    )
    suspend fun get(permissionId: String, deviceId: String, domain: String): DriveCursor?

    @Query(
        "SELECT * FROM drive_cursor WHERE permission_id = :permissionId AND device_id = :deviceId ORDER BY domain",
    )
    suspend fun forDevice(permissionId: String, deviceId: String): List<DriveCursor>

    @Query("SELECT * FROM drive_cursor WHERE permission_id = :permissionId ORDER BY device_id, domain")
    suspend fun forAccount(permissionId: String): List<DriveCursor>

    @Query(
        "SELECT * FROM drive_cursor WHERE permission_id = :permissionId AND paused_schema_version IS NOT NULL " +
            "ORDER BY device_id, domain",
    )
    suspend fun pausedForAccount(permissionId: String): List<DriveCursor>

    @Query(
        "SELECT * FROM drive_cursor WHERE permission_id = :permissionId AND paused_schema_version IS NOT NULL " +
            "ORDER BY device_id, domain",
    )
    fun observePausedForAccount(permissionId: String): Flow<List<DriveCursor>>

    @Upsert
    suspend fun upsert(cursor: DriveCursor)
}

@Dao
interface DriveFileDao {
    @Query("SELECT * FROM drive_file WHERE permission_id = :permissionId AND file_id = :fileId")
    suspend fun get(permissionId: String, fileId: String): DriveFile?

    @Query("SELECT * FROM drive_file WHERE permission_id = :permissionId AND kind = :kind ORDER BY created_time, file_id")
    suspend fun byKind(permissionId: String, kind: String): List<DriveFile>

    @Query(
        "SELECT * FROM drive_file WHERE permission_id = :permissionId AND kind = :kind AND sha256 = :sha256 " +
            "AND trashed = 0 ORDER BY created_time, file_id",
    )
    suspend fun byKindAndSha256(permissionId: String, kind: String, sha256: String): List<DriveFile>

    @Query(
        "SELECT * FROM drive_file WHERE permission_id = :permissionId AND device_id = :deviceId AND kind = :kind " +
            "ORDER BY seq, file_id",
    )
    suspend fun byDeviceAndKind(permissionId: String, deviceId: String, kind: String): List<DriveFile>

    @Upsert
    suspend fun upsert(file: DriveFile)

    @Query("UPDATE drive_file SET trashed = :trashed WHERE permission_id = :permissionId AND file_id = :fileId")
    suspend fun setTrashed(permissionId: String, fileId: String, trashed: Boolean)

    @Query("DELETE FROM drive_file WHERE permission_id = :permissionId AND file_id = :fileId")
    suspend fun delete(permissionId: String, fileId: String)
}

@Dao
interface DriveOutboxDao {
    @Query("SELECT * FROM drive_outbox WHERE permission_id = :permissionId AND seq = :seq")
    suspend fun get(permissionId: String, seq: Long): DriveOutbox?

    @Query(
        "SELECT * FROM drive_outbox WHERE permission_id = :permissionId AND state IN ('BUILT', 'UPLOADED') " +
            "ORDER BY seq ASC LIMIT 1",
    )
    suspend fun inFlight(permissionId: String): DriveOutbox?

    @Query("SELECT * FROM drive_outbox WHERE permission_id = :permissionId ORDER BY seq ASC")
    suspend fun forAccount(permissionId: String): List<DriveOutbox>

    @Query(
        "SELECT * FROM drive_outbox WHERE permission_id = :permissionId AND state = 'ACKED' " +
            "AND seq <= :throughSeq ORDER BY seq ASC",
    )
    suspend fun ackedUpTo(permissionId: String, throughSeq: Long): List<DriveOutbox>

    @Query(
        "SELECT * FROM drive_outbox WHERE permission_id = :permissionId AND state = 'ACKED' " +
            "AND seq > :afterSeq ORDER BY seq ASC",
    )
    suspend fun ackedAfter(permissionId: String, afterSeq: Long): List<DriveOutbox>

    @Upsert
    suspend fun upsert(entry: DriveOutbox)

    @Query("UPDATE drive_outbox SET state = 'UPLOADED', file_id = :fileId WHERE permission_id = :permissionId AND seq = :seq")
    suspend fun markUploaded(permissionId: String, seq: Long, fileId: String)

    @Query("UPDATE drive_outbox SET state = 'ACKED', file_id = :fileId WHERE permission_id = :permissionId AND seq = :seq")
    suspend fun markAcked(permissionId: String, seq: Long, fileId: String)

    @Query("DELETE FROM drive_outbox WHERE permission_id = :permissionId AND state = 'ACKED' AND seq = :seq")
    suspend fun deleteAckedSeq(permissionId: String, seq: Long)
}

@Dao
interface DriveRecordStateDao {
    @Query(
        "SELECT * FROM drive_record_state WHERE permission_id = :permissionId AND domain = :domain " +
            "AND record_key = :recordKey",
    )
    suspend fun get(permissionId: String, domain: String, recordKey: String): DriveRecordState?

    @Query(
        "SELECT * FROM drive_record_state WHERE permission_id = :permissionId AND domain = :domain " +
            "ORDER BY record_key",
    )
    suspend fun forDomain(permissionId: String, domain: String): List<DriveRecordState>

    @Query("SELECT * FROM drive_record_state WHERE permission_id = :permissionId ORDER BY domain, record_key")
    suspend fun forAccount(permissionId: String): List<DriveRecordState>

    @Upsert
    suspend fun upsert(state: DriveRecordState)

    @Upsert
    suspend fun upsertAll(states: List<DriveRecordState>)

    @Query(
        "DELETE FROM drive_record_state WHERE permission_id = :permissionId AND domain = :domain " +
            "AND record_key = :recordKey",
    )
    suspend fun delete(permissionId: String, domain: String, recordKey: String)

    @Query("DELETE FROM drive_record_state WHERE permission_id = :permissionId AND domain = :domain")
    suspend fun deleteDomain(permissionId: String, domain: String)
}

@Dao
interface DriveResolutionDao {
    @Query(
        "SELECT * FROM drive_resolution WHERE permission_id = :permissionId AND domain = :domain " +
            "AND record_key = :recordKey AND resolution_fp = :resolutionFp",
    )
    suspend fun get(
        permissionId: String,
        domain: String,
        recordKey: String,
        resolutionFp: String,
    ): DriveResolution?

    @Query(
        "SELECT * FROM drive_resolution WHERE permission_id = :permissionId AND domain = :domain " +
            "AND record_key = :recordKey ORDER BY hlc ASC, resolution_fp ASC",
    )
    suspend fun forRecord(permissionId: String, domain: String, recordKey: String): List<DriveResolution>

    @Query(
        "SELECT * FROM drive_resolution WHERE permission_id = :permissionId AND author_device_id = :authorDeviceId " +
            "ORDER BY hlc ASC, resolution_fp ASC",
    )
    suspend fun authoredBy(permissionId: String, authorDeviceId: String): List<DriveResolution>

    @Query("SELECT * FROM drive_resolution WHERE permission_id = :permissionId ORDER BY hlc ASC, resolution_fp ASC")
    suspend fun forAccount(permissionId: String): List<DriveResolution>

    @Upsert
    suspend fun upsert(resolution: DriveResolution)
}

@Dao
interface DriveConflictDao {
    @Query("SELECT * FROM drive_conflict WHERE permission_id = :permissionId AND conflict_id = :conflictId")
    suspend fun get(permissionId: String, conflictId: String): DriveConflict?

    @Query(
        "SELECT * FROM drive_conflict WHERE permission_id = :permissionId AND state = 'OPEN' " +
            "ORDER BY opened_at DESC, conflict_id ASC",
    )
    fun observeOpen(permissionId: String): Flow<List<DriveConflict>>

    @Query("SELECT COUNT(*) FROM drive_conflict WHERE permission_id = :permissionId AND state = 'OPEN'")
    fun observeOpenCount(permissionId: String): Flow<Int>

    @Query(
        "SELECT * FROM drive_conflict WHERE permission_id = :permissionId AND state = 'OPEN' " +
            "ORDER BY opened_at DESC, conflict_id ASC",
    )
    suspend fun openForAccount(permissionId: String): List<DriveConflict>

    @Query(
        "SELECT * FROM drive_conflict WHERE permission_id = :permissionId AND domain = :domain " +
            "AND record_key = :recordKey AND state IN ('OPEN', 'RESOLVING') ORDER BY opened_at ASC",
    )
    suspend fun unsettledForRecord(permissionId: String, domain: String, recordKey: String): List<DriveConflict>

    @Query(
        "SELECT * FROM drive_conflict WHERE permission_id = :permissionId AND state = 'RESOLVING' " +
            "ORDER BY opened_at ASC",
    )
    suspend fun resolvingForAccount(permissionId: String): List<DriveConflict>

    @Upsert
    suspend fun upsert(conflict: DriveConflict)

    @Query(
        "UPDATE drive_conflict SET state = 'RESOLVING', choice = :choice " +
            "WHERE permission_id = :permissionId AND conflict_id = :conflictId",
    )
    suspend fun markResolving(permissionId: String, conflictId: String, choice: String)

    @Query(
        "UPDATE drive_conflict SET state = 'OPEN', choice = NULL " +
            "WHERE permission_id = :permissionId AND conflict_id = :conflictId AND state = 'RESOLVING'",
    )
    suspend fun reopen(permissionId: String, conflictId: String)

    @Query(
        "UPDATE drive_conflict SET state = 'RESOLVED', choice = :choice, resolution_fp = :resolutionFp, " +
            "resolved_at = :resolvedAt WHERE permission_id = :permissionId AND conflict_id = :conflictId",
    )
    suspend fun markResolved(
        permissionId: String,
        conflictId: String,
        choice: String?,
        resolutionFp: String,
        resolvedAt: Long,
    )
}

@Dao
interface DriveSessionAckDao {
    @Query(
        "SELECT EXISTS(SELECT 1 FROM drive_session_ack WHERE permission_id = :permissionId " +
            "AND remote_session_id = :remoteSessionId)",
    )
    suspend fun isAcked(permissionId: String, remoteSessionId: String): Boolean

    @Query("SELECT remote_session_id FROM drive_session_ack WHERE permission_id = :permissionId")
    suspend fun ackedIds(permissionId: String): List<String>

    @Query("SELECT * FROM drive_session_ack WHERE permission_id = :permissionId ORDER BY batch_seq, remote_session_id")
    suspend fun forAccount(permissionId: String): List<DriveSessionAck>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(acks: List<DriveSessionAck>)
}

@Dao
interface DriveUploadDao {
    @Query("SELECT * FROM drive_upload WHERE permission_id = :permissionId AND sha256 = :sha256")
    suspend fun get(permissionId: String, sha256: String): DriveUpload?

    @Query("SELECT * FROM drive_upload WHERE permission_id = :permissionId ORDER BY selected_at ASC, sha256 ASC")
    fun observeForAccount(permissionId: String): Flow<List<DriveUpload>>

    @Query(
        "SELECT COUNT(*) FROM drive_upload WHERE permission_id = :permissionId " +
            "AND state IN ('PENDING', 'UPLOADING', 'FAILED')",
    )
    fun observePendingCount(permissionId: String): Flow<Int>

    @Query(
        "SELECT * FROM drive_upload WHERE permission_id = :permissionId AND state IN ('PENDING', 'UPLOADING', 'FAILED') " +
            "ORDER BY selected_at ASC, sha256 ASC",
    )
    suspend fun pendingForAccount(permissionId: String): List<DriveUpload>

    @Query("SELECT * FROM drive_upload WHERE permission_id = :permissionId ORDER BY selected_at ASC, sha256 ASC")
    suspend fun forAccount(permissionId: String): List<DriveUpload>

    @Upsert
    suspend fun upsert(upload: DriveUpload)

    @Query(
        "UPDATE drive_upload SET state = :state, session_uri = :sessionUri, bytes_sent = :bytesSent, " +
            "updated_at = :updatedAt WHERE permission_id = :permissionId AND sha256 = :sha256",
    )
    suspend fun updateProgress(
        permissionId: String,
        sha256: String,
        state: String,
        sessionUri: String?,
        bytesSent: Long,
        updatedAt: Long,
    )

    @Query(
        "UPDATE drive_upload SET state = :state, updated_at = :updatedAt " +
            "WHERE permission_id = :permissionId AND sha256 = :sha256",
    )
    suspend fun updateState(
        permissionId: String,
        sha256: String,
        state: String,
        updatedAt: Long,
    )

    @Query(
        "UPDATE drive_upload SET state = 'UPLOADED', file_id = :fileId, session_uri = NULL, " +
            "bytes_sent = size_bytes, updated_at = :updatedAt " +
            "WHERE permission_id = :permissionId AND sha256 = :sha256",
    )
    suspend fun markUploaded(permissionId: String, sha256: String, fileId: String, updatedAt: Long)

    @Query(
        "DELETE FROM drive_upload WHERE permission_id = :permissionId " +
            "AND state = 'UPLOADED' AND file_id = :fileId",
    )
    suspend fun deleteUploadedByFileId(permissionId: String, fileId: String)

    @Query("DELETE FROM drive_upload WHERE permission_id = :permissionId AND sha256 = :sha256")
    suspend fun delete(permissionId: String, sha256: String)
}
