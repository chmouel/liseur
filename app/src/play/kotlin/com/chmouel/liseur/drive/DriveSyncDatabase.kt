package com.chmouel.liseur.drive

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
    version = 1,
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

        fun open(context: Context): DriveSyncDatabase =
            Room.databaseBuilder(context.applicationContext, DriveSyncDatabase::class.java, NAME).build()
    }
}
