package com.chmouel.liseur.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/**
 * One day of reading a liseur-sync snapshot counted on other devices.
 *
 * The snapshot's overlap with this device's sittings is already taken
 * out, so the home-screen widget can add this device's live sessions on
 * top without counting any sitting twice. A row with nothing read is
 * kept on purpose: it says the day was covered and came back empty.
 */
@Entity(tableName = "remote_stats_day", primaryKeys = ["account_key", "date"])
data class RemoteStatsDay(
    @ColumnInfo(name = "account_key") val accountKey: String,
    /** ISO date in [zone]. */
    @ColumnInfo(name = "date") val date: String,
    @ColumnInfo(name = "zone") val zone: String,
    @ColumnInfo(name = "residual_ms") val residualMs: Long,
)

/** A week or month snapshot's other-device sittings and works. */
@Entity(tableName = "remote_stats_window", primaryKeys = ["account_key", "range_id"])
data class RemoteStatsWindow(
    @ColumnInfo(name = "account_key") val accountKey: String,
    @ColumnInfo(name = "range_id") val rangeId: String,
    @ColumnInfo(name = "from_date") val fromDate: String,
    /** The last day the snapshot covered. */
    @ColumnInfo(name = "today") val today: String,
    @ColumnInfo(name = "zone") val zone: String,
    @ColumnInfo(name = "residual_sessions") val residualSessions: Int,
    /** Server work ids, one per line. */
    @ColumnInfo(name = "work_ids") val workIds: String,
    @ColumnInfo(name = "combined_streak") val combinedStreak: Int,
)

@Dao
interface RemoteStatsDao {
    @Upsert
    suspend fun upsertDays(rows: List<RemoteStatsDay>)

    @Upsert
    suspend fun upsertWindow(row: RemoteStatsWindow)

    @Query("DELETE FROM remote_stats_day WHERE account_key = :accountKey AND (zone != :zone OR date < :oldest)")
    suspend fun pruneDays(accountKey: String, zone: String, oldest: String)

    @Query("DELETE FROM remote_stats_window WHERE account_key = :accountKey AND zone != :zone")
    suspend fun pruneWindows(accountKey: String, zone: String)

    @Query("SELECT * FROM remote_stats_day WHERE account_key = :accountKey AND zone = :zone ORDER BY date")
    suspend fun days(accountKey: String, zone: String): List<RemoteStatsDay>

    @Query("SELECT * FROM remote_stats_window WHERE account_key = :accountKey AND zone = :zone")
    suspend fun windows(accountKey: String, zone: String): List<RemoteStatsWindow>

    @Query("UPDATE remote_stats_day SET account_key = :to WHERE account_key = :from")
    suspend fun rekeyDays(from: String, to: String)

    @Query("UPDATE remote_stats_window SET account_key = :to WHERE account_key = :from")
    suspend fun rekeyWindows(from: String, to: String)

    @Query("DELETE FROM remote_stats_day WHERE account_key = :accountKey")
    suspend fun clearDays(accountKey: String)

    @Query("DELETE FROM remote_stats_window WHERE account_key = :accountKey")
    suspend fun clearWindows(accountKey: String)

    /**
     * Stores one snapshot's residual. Rows from another timezone describe
     * other calendar days and go; days older than [oldest] are no longer
     * drawn by any widget.
     */
    @Transaction
    suspend fun save(
        accountKey: String,
        zone: String,
        oldest: String,
        days: List<RemoteStatsDay>,
        window: RemoteStatsWindow?,
    ) {
        pruneDays(accountKey, zone, oldest)
        pruneWindows(accountKey, zone)
        upsertDays(days)
        window?.let { upsertWindow(it) }
    }

    /** Derived data: whatever was already under [to] is replaced, never merged. */
    @Transaction
    suspend fun rekeyPeer(from: String, to: String) {
        clearPeer(to)
        rekeyDays(from, to)
        rekeyWindows(from, to)
    }

    @Transaction
    suspend fun clearPeer(accountKey: String) {
        clearDays(accountKey)
        clearWindows(accountKey)
    }
}
