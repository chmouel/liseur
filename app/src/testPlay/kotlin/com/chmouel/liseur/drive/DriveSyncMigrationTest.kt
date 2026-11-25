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
        helper.createDatabase(TEST_DB, 1).use { old ->
            old.execSQL(
                "INSERT INTO drive_account (permission_id, email, state, connected_at) " +
                    "VALUES ('perm-a', 'alice@example.com', 'ACTIVE', 100)",
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 2, true, *DriveSyncDatabase.MIGRATIONS).use { db ->
            db.query("SELECT email, state, pending_switch_previous_state IS NULL FROM drive_account").use {
                assertTrue(it.moveToFirst())
                assertEquals("alice@example.com", it.getString(0))
                assertEquals(DriveAccount.STATE_ACTIVE, it.getString(1))
                assertEquals(1, it.getInt(2))
            }
        }
    }

    private companion object {
        const val TEST_DB = "drive-sync-migration-test.db"
    }
}
