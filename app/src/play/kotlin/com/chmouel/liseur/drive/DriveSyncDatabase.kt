package com.chmouel.liseur.drive

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import org.json.JSONObject

/**
 * Play-only Room database for Google Drive sync transport, causal lineage,
 * conflict tracking, and upload queues (ADR-0078).
 *
 * Kept separate from `liseur.db` so the F-Droid build never sees Drive tables
 * and excluded from Android cloud backup / device transfer so every install
 * gets its own `drive_install.device_id`.
 */
@Database(
    entities = [
        DriveInstall::class,
        DriveAccount::class,
        DriveDevice::class,
        DriveCursor::class,
        DriveFile::class,
        DriveOutbox::class,
        DriveRecordState::class,
        DriveResolution::class,
        DriveConflict::class,
        DriveSessionAck::class,
        DriveUpload::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class DriveSyncDatabase : RoomDatabase() {
    abstract fun installs(): DriveInstallDao
    abstract fun accounts(): DriveAccountDao
    abstract fun devices(): DriveDeviceDao
    abstract fun cursors(): DriveCursorDao
    abstract fun files(): DriveFileDao
    abstract fun outbox(): DriveOutboxDao
    abstract fun recordStates(): DriveRecordStateDao
    abstract fun resolutions(): DriveResolutionDao
    abstract fun conflicts(): DriveConflictDao
    abstract fun sessionAcks(): DriveSessionAckDao
    abstract fun uploads(): DriveUploadDao

    companion object {
        const val NAME = "drive_sync.db"

        /** Adds the pending-switch marker that lets startup recover an abandoned confirmation. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE drive_account ADD COLUMN pending_switch_previous_state TEXT")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE drive_outbox ADD COLUMN ack_metadata_json TEXT")
            }
        }

        /**
         * Adds the remote branch's envelope fields, so a conflict reopened from a
         * compacted successor still knows what it was resolving against.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE drive_conflict ADD COLUMN remote_op TEXT")
                connection.execSQL("ALTER TABLE drive_conflict ADD COLUMN remote_seq INTEGER")
                connection.execSQL("ALTER TABLE drive_conflict ADD COLUMN remote_anc_json TEXT")
                connection.execSQL("ALTER TABLE drive_conflict ADD COLUMN remote_res_refs_json TEXT")
            }
        }

        /**
         * Adds the picker-confirmation time the upload offer leaves older books to, and
         * marks every queue row already there as picked by hand: none came from the offer.
         *
         * The upgrade itself is the cutoff: no account knew of this answer before,
         * so every book already on the device stays out of the automatic offer.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE drive_account ADD COLUMN upload_selection_at INTEGER")
                connection.execSQL(
                    "UPDATE drive_account " +
                        "SET upload_selection_at = CAST(strftime('%s', 'now') AS INTEGER) * 1000",
                )
                connection.execSQL("ALTER TABLE drive_upload ADD COLUMN auto_selected INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Adds the held marker: a record retained for a work whose catalog tombstone arrived
         * before its book, so restoring the work later still has its history and lineage.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE drive_record_state ADD COLUMN held INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Separates the authoring clock belonging to `version_fp` from `last_hlc` (highest
         * observed clock). Only rows whose authoring clock can be verified are backfilled;
         * unverified rows stay NULL (unknown) so a bumped `last_hlc` is never mistaken for
         * the version's authoring clock.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE drive_record_state ADD COLUMN version_hlc TEXT")
                connection.execSQL(
                    "UPDATE drive_record_state " +
                        "SET version_hlc = (" +
                        "SELECT r.hlc FROM drive_resolution r " +
                        "WHERE r.permission_id = drive_record_state.permission_id " +
                        "AND r.domain = drive_record_state.domain " +
                        "AND r.record_key = drive_record_state.record_key " +
                        "AND r.resolution_fp = drive_record_state.version_fp" +
                        ") WHERE version_op = 'resolve'",
                )
                val verified = mutableListOf<Array<String>>()
                connection.prepare(
                    "SELECT permission_id, domain, record_key, version_fp, version_op, last_hlc, body_json " +
                        "FROM drive_record_state WHERE version_op IN ('put', 'del') AND last_hlc != ''",
                ).use { stmt ->
                    while (stmt.step()) {
                        val permissionId = stmt.getText(0)
                        val domain = stmt.getText(1)
                        val recordKey = stmt.getText(2)
                        val versionFp = stmt.getText(3)
                        val versionOp = stmt.getText(4)
                        val lastHlc = stmt.getText(5)
                        val bodyJson = if (stmt.isNull(6)) null else stmt.getText(6)
                        val matches = when (versionOp) {
                            DriveRecordState.OP_PUT -> bodyJson != null &&
                                runCatching { JSONObject(bodyJson) }.getOrNull()
                                    ?.let { DriveCanonicalJson.putFp(it, lastHlc) == versionFp } == true
                            DriveRecordState.OP_DEL ->
                                DriveCanonicalJson.delFp(domain, recordKey, lastHlc) == versionFp
                            else -> false
                        }
                        if (matches) {
                            verified.add(arrayOf(lastHlc, permissionId, domain, recordKey))
                        }
                    }
                }
                if (verified.isNotEmpty()) {
                    connection.prepare(
                        "UPDATE drive_record_state SET version_hlc = ? " +
                            "WHERE permission_id = ? AND domain = ? AND record_key = ?",
                    ).use { update ->
                        for (row in verified) {
                            update.bindText(1, row[0])
                            update.bindText(2, row[1])
                            update.bindText(3, row[2])
                            update.bindText(4, row[3])
                            update.step()
                            update.reset()
                        }
                    }
                }
            }
        }

        val MIGRATIONS: Array<Migration>
            get() = arrayOf(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
            )

        fun open(context: Context): DriveSyncDatabase =
            Room.databaseBuilder(context.applicationContext, DriveSyncDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
