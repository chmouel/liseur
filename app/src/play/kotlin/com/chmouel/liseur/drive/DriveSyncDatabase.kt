package com.chmouel.liseur.drive

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

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
    version = 2,
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

        val MIGRATIONS: Array<Migration> get() = arrayOf(MIGRATION_1_2)

        fun open(context: Context): DriveSyncDatabase =
            Room.databaseBuilder(context.applicationContext, DriveSyncDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
