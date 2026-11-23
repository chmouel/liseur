package com.chmouel.liseur.drive

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Per-install identity for Google Drive sync (ADR-0078).
 *
 * Lives in `drive_sync.db` (excluded from Android cloud backup and device
 * transfer), so a restored install always mints its own `device_id` rather
 * than inheriting `DeviceIdentity.id` from backup.
 */
@Entity(tableName = "drive_install")
data class DriveInstall(
    @PrimaryKey val id: Long = SINGLE_ID,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    companion object {
        const val SINGLE_ID = 1L
    }
}

/**
 * Account-level Google Drive sync state, partitioned by the immutable
 * Drive `permissionId` (never by mutable email address).
 */
@Entity(tableName = "drive_account")
data class DriveAccount(
    @PrimaryKey @ColumnInfo(name = "permission_id") val permissionId: String,
    val email: String,
    /** One of [STATE_ACTIVE], [STATE_PAUSED], [STATE_NEEDS_RECONNECT]. */
    val state: String,
    @ColumnInfo(name = "connected_at") val connectedAt: Long,
    @ColumnInfo(name = "last_success_at") val lastSuccessAt: Long? = null,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "folder_id") val folderId: String? = null,
    @ColumnInfo(name = "start_page_token") val startPageToken: String? = null,
    @ColumnInfo(name = "next_seq", defaultValue = "1") val nextSeq: Long = 1L,
    @ColumnInfo(name = "manifest_file_id") val manifestFileId: String? = null,
    @ColumnInfo(name = "snapshot_through_seq", defaultValue = "0") val snapshotThroughSeq: Long = 0L,
    @ColumnInfo(name = "deltas_since_snapshot", defaultValue = "0") val deltasSinceSnapshot: Int = 0,
    @ColumnInfo(name = "hlc_wall_ms", defaultValue = "0") val hlcWallMs: Long = 0L,
    @ColumnInfo(name = "hlc_counter", defaultValue = "0") val hlcCounter: Int = 0,
    @ColumnInfo(name = "metered_transfers", defaultValue = "0") val meteredTransfers: Boolean = false,
) {
    companion object {
        const val STATE_ACTIVE = "ACTIVE"
        const val STATE_PAUSED = "PAUSED"
        const val STATE_NEEDS_RECONNECT = "NEEDS_RECONNECT"
    }
}

/** Remote device discovered in `appDataFolder` for a Drive account. */
@Entity(tableName = "drive_device", primaryKeys = ["permission_id", "device_id"])
data class DriveDevice(
    @ColumnInfo(name = "permission_id") val permissionId: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    val label: String,
    @ColumnInfo(name = "manifest_file_id") val manifestFileId: String? = null,
    @ColumnInfo(name = "app_version_code", defaultValue = "0") val appVersionCode: Int = 0,
    @ColumnInfo(name = "snapshot_through_seq", defaultValue = "0") val snapshotThroughSeq: Long = 0L,
    @ColumnInfo(name = "last_seen_at", defaultValue = "0") val lastSeenAt: Long = 0L,
)

/** Per-device, per-domain applied sequence cursor and optional schema-version pause marker. */
@Entity(tableName = "drive_cursor", primaryKeys = ["permission_id", "device_id", "domain"])
data class DriveCursor(
    @ColumnInfo(name = "permission_id") val permissionId: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    val domain: String,
    @ColumnInfo(name = "applied_seq", defaultValue = "0") val appliedSeq: Long = 0L,
    @ColumnInfo(name = "paused_schema_version") val pausedSchemaVersion: Int? = null,
)

/** Known Drive file metadata (manifests, deltas, snapshots, covers, folders, and EPUBs). */
@Entity(
    tableName = "drive_file",
    primaryKeys = ["permission_id", "file_id"],
    indices = [Index(value = ["permission_id", "kind", "sha256"])],
)
data class DriveFile(
    @ColumnInfo(name = "permission_id") val permissionId: String,
    @ColumnInfo(name = "file_id") val fileId: String,
    val name: String,
    /** One of [KIND_MANIFEST], [KIND_DELTA], [KIND_SNAPSHOT], [KIND_COVER], [KIND_EPUB], [KIND_FOLDER]. */
    val kind: String,
    @ColumnInfo(name = "device_id") val deviceId: String? = null,
    val seq: Long? = null,
    val sha256: String? = null,
    val size: Long? = null,
    val md5: String? = null,
    @ColumnInfo(name = "trashed", defaultValue = "0") val trashed: Boolean = false,
    @ColumnInfo(name = "created_time") val createdTime: String? = null,
) {
    companion object {
        const val KIND_MANIFEST = "manifest"
        const val KIND_DELTA = "delta"
        const val KIND_SNAPSHOT = "snapshot"
        const val KIND_COVER = "cover"
        const val KIND_EPUB = "epub"
        const val KIND_FOLDER = "folder"
    }
}

/**
 * Frozen outgoing delta batch and receipt for this device's stream.
 *
 * A batch is built and persisted with its allocated [seq] and deterministic
 * [batchId] before upload; retries deduplicate on `appProperties.batchId`.
 */
@Entity(tableName = "drive_outbox", primaryKeys = ["permission_id", "seq"])
data class DriveOutbox(
    @ColumnInfo(name = "permission_id") val permissionId: String,
    val seq: Long,
    @ColumnInfo(name = "batch_id") val batchId: String,
    val payload: String,
    /** One of [STATE_BUILT], [STATE_UPLOADED], [STATE_ACKED]. */
    val state: String,
    @ColumnInfo(name = "file_id") val fileId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "ack_metadata_json") val ackMetadataJson: String? = null,
) {
    companion object {
        const val STATE_BUILT = "BUILT"
        const val STATE_UPLOADED = "UPLOADED"
        const val STATE_ACKED = "ACKED"
    }
}

/**
 * Per-record causal lineage and delivery state for mutable Drive domains.
 *
 * Keeps delivery state ([publishedFp]) strictly separate from causal version
 * state ([versionFp], [versionOp], [ancestorsJson], [resRefsJson], [originDeviceId], [originSeq]).
 */
@Entity(tableName = "drive_record_state", primaryKeys = ["permission_id", "domain", "record_key"])
data class DriveRecordState(
    @ColumnInfo(name = "permission_id") val permissionId: String,
    val domain: String,
    @ColumnInfo(name = "record_key") val recordKey: String,
    @ColumnInfo(name = "version_fp") val versionFp: String,
    /** One of [OP_PUT], [OP_DEL], [OP_RESOLVE]. */
    @ColumnInfo(name = "version_op") val versionOp: String,
    @ColumnInfo(name = "published_fp") val publishedFp: String? = null,
    @ColumnInfo(name = "content_fp") val contentFp: String? = null,
    @ColumnInfo(name = "last_synced_revision") val lastSyncedRevision: Long? = null,
    @ColumnInfo(name = "origin_device_id") val originDeviceId: String? = null,
    @ColumnInfo(name = "origin_seq") val originSeq: Long? = null,
    @ColumnInfo(name = "ancestors_json", defaultValue = "'[]'") val ancestorsJson: String = "[]",
    @ColumnInfo(name = "res_refs_json", defaultValue = "'[]'") val resRefsJson: String = "[]",
    @ColumnInfo(name = "last_hlc") val lastHlc: String,
    @ColumnInfo(name = "body_json") val bodyJson: String? = null,
) {
    companion object {
        const val OP_PUT = "put"
        const val OP_DEL = "del"
        const val OP_RESOLVE = "resolve"
    }
}

/**
 * Durable uncapped conflict-resolution facts, preserved outside the 64-entry
 * `anc` cap so returning offline devices settle conflicts cleanly after
 * compaction when incoming records prove descent via `resRefs`.
 */
@Entity(
    tableName = "drive_resolution",
    primaryKeys = ["permission_id", "domain", "record_key", "resolution_fp"],
)
data class DriveResolution(
    @ColumnInfo(name = "permission_id") val permissionId: String,
    val domain: String,
    @ColumnInfo(name = "record_key") val recordKey: String,
    @ColumnInfo(name = "resolution_fp") val resolutionFp: String,
    @ColumnInfo(name = "resolved_fps_json") val resolvedFpsJson: String,
    /** One of [OUTCOME_PUT], [OUTCOME_DELETE]. */
    val outcome: String,
    val hlc: String,
    @ColumnInfo(name = "author_device_id") val authorDeviceId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    companion object {
        const val OUTCOME_PUT = "put"
        const val OUTCOME_DELETE = "delete"
    }
}

/** Unresolved or settled mutable-record conflict awaiting or reflecting a reader choice. */
@Entity(
    tableName = "drive_conflict",
    primaryKeys = ["permission_id", "conflict_id"],
    indices = [
        Index(value = ["permission_id", "state"]),
        Index(value = ["permission_id", "domain", "record_key"]),
    ],
)
data class DriveConflict(
    @ColumnInfo(name = "permission_id") val permissionId: String,
    @ColumnInfo(name = "conflict_id") val conflictId: String,
    val domain: String,
    @ColumnInfo(name = "record_key") val recordKey: String,
    @ColumnInfo(name = "book_url") val bookUrl: String? = null,
    /** One of [KIND_EDIT_EDIT], [KIND_DELETE_EDIT]. */
    val kind: String,
    @ColumnInfo(name = "local_fp") val localFp: String,
    @ColumnInfo(name = "remote_fp") val remoteFp: String,
    @ColumnInfo(name = "remote_json") val remoteJson: String? = null,
    @ColumnInfo(name = "remote_device_id") val remoteDeviceId: String,
    @ColumnInfo(name = "remote_hlc") val remoteHlc: String,
    /** One of [STATE_OPEN], [STATE_RESOLVING], [STATE_RESOLVED]. */
    val state: String,
    val choice: String? = null,
    @ColumnInfo(name = "resolution_fp") val resolutionFp: String? = null,
    @ColumnInfo(name = "opened_at") val openedAt: Long,
    @ColumnInfo(name = "resolved_at") val resolvedAt: Long? = null,
) {
    companion object {
        const val KIND_EDIT_EDIT = "EDIT_EDIT"
        const val KIND_DELETE_EDIT = "DELETE_EDIT"

        const val STATE_OPEN = "OPEN"
        const val STATE_RESOLVING = "RESOLVING"
        const val STATE_RESOLVED = "RESOLVED"
    }
}

/**
 * Per-account delivery acknowledgement for uploaded reading sessions.
 *
 * Kept per `permission_id` so switching to a second Google account or
 * restoring `liseur.db` without `drive_sync.db` still seeds all eligible
 * local sessions cleanly.
 */
@Entity(tableName = "drive_session_ack", primaryKeys = ["permission_id", "remote_session_id"])
data class DriveSessionAck(
    @ColumnInfo(name = "permission_id") val permissionId: String,
    @ColumnInfo(name = "remote_session_id") val remoteSessionId: String,
    @ColumnInfo(name = "batch_seq") val batchSeq: Long,
    @ColumnInfo(name = "acked_at") val ackedAt: Long,
)

/** Per-account opt-in EPUB upload queue and resumable upload session state. */
@Entity(tableName = "drive_upload", primaryKeys = ["permission_id", "sha256"])
data class DriveUpload(
    @ColumnInfo(name = "permission_id") val permissionId: String,
    val sha256: String,
    @ColumnInfo(name = "book_url") val bookUrl: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    /** One of [STATE_PENDING], [STATE_UPLOADING], [STATE_UPLOADED], [STATE_FAILED]. */
    val state: String,
    @ColumnInfo(name = "session_uri") val sessionUri: String? = null,
    @ColumnInfo(name = "bytes_sent", defaultValue = "0") val bytesSent: Long = 0L,
    @ColumnInfo(name = "file_id") val fileId: String? = null,
    @ColumnInfo(name = "selected_at") val selectedAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    companion object {
        const val STATE_PENDING = "PENDING"
        const val STATE_UPLOADING = "UPLOADING"
        const val STATE_UPLOADED = "UPLOADED"
        const val STATE_FAILED = "FAILED"
    }
}
