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
        helper.runMigrationsAndValidate(OUTBOX_DB, 3, true, *DriveSyncDatabase.MIGRATIONS).use { db ->
            db.query("SELECT batch_id, state, created_at, ack_metadata_json FROM drive_outbox WHERE seq = 7").use {
                assertTrue(it.moveToFirst())
                assertEquals("batch-1", it.getString(0))
                assertEquals("BUILT", it.getString(1))
                assertEquals(1000L, it.getLong(2))
                assertTrue(it.isNull(3))
            }
        }
    }

    @Test
    fun `existing version two outbox gains ack metadata`() {
        helper.createDatabase(V2_OUTBOX_DB, 2).use { old ->
            old.execSQL("INSERT INTO drive_outbox (permission_id, seq, batch_id, payload, state, file_id, created_at) " +
                "VALUES ('perm-1', 8, 'batch-2', '{}', 'BUILT', NULL, 1001)")
        }
        helper.runMigrationsAndValidate(V2_OUTBOX_DB, 3, true, *DriveSyncDatabase.MIGRATIONS).use { db ->
            db.query("SELECT batch_id, ack_metadata_json FROM drive_outbox WHERE seq = 8").use {
                assertTrue(it.moveToFirst())
                assertEquals("batch-2", it.getString(0))
                assertTrue(it.isNull(1))
            }
        }
    }

    @Test
    fun `open conflicts survive the upgrade that adds remote lineage columns`() {
        helper.createDatabase(CONFLICTS_DB, 3).use { old ->
            old.execSQL(
                "INSERT INTO drive_conflict (permission_id, conflict_id, domain, record_key, book_url, kind, " +
                    "local_fp, remote_fp, remote_json, remote_device_id, remote_hlc, state, choice, " +
                    "resolution_fp, opened_at, resolved_at) VALUES ('perm-1', 'c1', 'annotation', 'k', NULL, " +
                    "'EDIT_EDIT', 'fl', 'fr', '{\"a\":1}', 'dev-b', 'h1', 'OPEN', NULL, NULL, 5, NULL)",
            )
        }

        helper.runMigrationsAndValidate(CONFLICTS_DB, 4, true, *DriveSyncDatabase.MIGRATIONS).use { db ->
            db.query(
                "SELECT local_fp, remote_fp, remote_json, state, remote_op, remote_seq, " +
                    "remote_anc_json, remote_res_refs_json FROM drive_conflict WHERE conflict_id = 'c1'",
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals("fl", it.getString(0))
                assertEquals("fr", it.getString(1))
                assertEquals("{\"a\":1}", it.getString(2))
                assertEquals("OPEN", it.getString(3))
                for (column in 4..7) assertTrue(it.isNull(column))
            }
        }
    }

    private companion object {
        const val ACCOUNTS_DB = "drive-sync-migration-accounts.db"
        const val OUTBOX_DB = "drive-sync-migration-outbox.db"
        const val CONFLICTS_DB = "drive-sync-migration-conflicts.db"
        const val V2_OUTBOX_DB = "drive-sync-migration-v2-outbox.db"
    }
}
