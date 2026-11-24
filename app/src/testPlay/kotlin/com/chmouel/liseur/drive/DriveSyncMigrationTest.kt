package com.chmouel.liseur.drive

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class DriveSyncMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DriveSyncDatabase::class.java,
    )

    @Test
    fun `accounts keep their state and start with no pending switch`() {
        helper.createDatabase(ACCOUNTS_DB, 1).use { old ->
            old.execSQL(
                "INSERT INTO drive_account (permission_id, email, state, connected_at) " +
                    "VALUES ('perm-a', 'alice@example.com', 'ACTIVE', 100)",
            )
        }
        helper.runMigrationsAndValidate(ACCOUNTS_DB, 2, true, *DriveSyncDatabase.MIGRATIONS).use { db ->
            db.query("SELECT email, state, pending_switch_previous_state IS NULL FROM drive_account").use {
                assertTrue(it.moveToFirst())
                assertEquals("alice@example.com", it.getString(0))
                assertEquals(DriveAccount.STATE_ACTIVE, it.getString(1))
                assertEquals(1, it.getInt(2))
            }
        }
    }

    @Test
    fun `queued outbox batches survive the upgrade that adds ack metadata`() {
        helper.createDatabase(OUTBOX_DB, 1).use { old ->
            old.execSQL(
                "INSERT INTO drive_outbox (permission_id, seq, batch_id, payload, state, file_id, created_at) " +
                    "VALUES ('perm-1', 7, 'batch-1', '{}', 'BUILT', NULL, 1000)",
            )
        }
        helper.runMigrationsAndValidate(OUTBOX_DB, 2, true, *DriveSyncDatabase.MIGRATIONS).use { db ->
            db.query("SELECT batch_id, state, created_at, ack_metadata_json FROM drive_outbox WHERE seq = 7").use {
                assertTrue(it.moveToFirst())
                assertEquals("batch-1", it.getString(0))
                assertEquals("BUILT", it.getString(1))
                assertEquals(1000L, it.getLong(2))
                assertTrue(it.isNull(3))
            }
        }
    }

    private companion object {
        const val ACCOUNTS_DB = "drive-sync-migration-accounts.db"
        const val OUTBOX_DB = "drive-sync-migration-outbox.db"
    }
}
