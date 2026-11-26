package com.chmouel.liseur.drive

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** An upgraded account must not upload what the picker never listed for it. */
    @Test
    fun `accounts gain the upgrade as their upload selection time`() {
        helper.createDatabase(UPLOAD_SELECTION_DB, 4).use { old ->
            old.execSQL(
                "INSERT INTO drive_account (permission_id, email, state, connected_at) " +
                    "VALUES ('perm-a', 'alice@example.com', 'ACTIVE', 100)",
            )
        }
        helper.runMigrationsAndValidate(UPLOAD_SELECTION_DB, 5, true, *DriveSyncDatabase.MIGRATIONS).use { db ->
            db.query("SELECT email, state, upload_selection_at FROM drive_account").use {
                assertTrue(it.moveToFirst())
                assertEquals("alice@example.com", it.getString(0))
                assertEquals(DriveAccount.STATE_ACTIVE, it.getString(1))
                assertFalse(it.isNull(2))
                assertTrue(it.getLong(2) > 1_000_000_000_000L)
            }
        }
    }

    /** History retained before the held marker existed was materialized, never retained. */
    @Test
    fun `record state gains the held marker as not held`() {
        helper.createDatabase(HELD_DB, 5).use { old ->
            old.execSQL(
                "INSERT INTO drive_record_state (permission_id, domain, record_key, version_fp, version_op, " +
                    "published_fp, content_fp, last_synced_revision, origin_device_id, origin_seq, " +
                    "ancestors_json, res_refs_json, last_hlc, body_json) " +
                    "VALUES ('perm-a', 'series', 'isbn:1', 'fp-1', 'put', 'fp-1', 'cfp-1', NULL, 'dev-a', 3, " +
                    "'[]', '[]', '001', '{\"workKey\":\"isbn:1\"}')",
            )
        }
        helper.runMigrationsAndValidate(HELD_DB, 6, true, *DriveSyncDatabase.MIGRATIONS).use { db ->
            db.query("SELECT version_op, body_json, held FROM drive_record_state WHERE record_key = 'isbn:1'").use {
                assertTrue(it.moveToFirst())
                assertEquals(DriveRecordState.OP_PUT, it.getString(0))
                assertEquals("{\"workKey\":\"isbn:1\"}", it.getString(1))
                assertEquals(0, it.getInt(2))
            }
        }
    }

    private companion object {
        const val ACCOUNTS_DB = "drive-sync-migration-accounts.db"
        const val OUTBOX_DB = "drive-sync-migration-outbox.db"
        const val CONFLICTS_DB = "drive-sync-migration-conflicts.db"
        const val V2_OUTBOX_DB = "drive-sync-migration-v2-outbox.db"
        const val UPLOAD_SELECTION_DB = "drive-sync-migration-upload-selection.db"
        const val HELD_DB = "drive-sync-migration-held.db"
    }
}
