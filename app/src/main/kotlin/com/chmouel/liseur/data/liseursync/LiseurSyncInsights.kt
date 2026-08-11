package com.chmouel.liseur.data.liseursync

import android.util.Log
import com.chmouel.liseur.data.db.SyncAccountDao
import com.chmouel.liseur.data.db.WorkIdentityDao
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import org.json.JSONObject

/**
 * Reading added up across every device, as the server sees it.
 *
 * `range_days` is echoed back rather than assumed: a server may cap a
 * long range, and reporting thirty days of reading as a year's would be
 * a lie about the reader.
 */
data class InsightsSummary(
    val rangeDays: Int,
    val activeMinutes: Double,
    val sessions: Int,
    val streakDays: Int,
)

/** One book, added up across every device. */
data class WorkInsights(
    val sessions: Int,
    val activeMinutes: Double,
    /** Null when the server has no speed history to divide by. */
    val etaSeconds: Double?,
    /** The newest session end, across devices. */
    val lastReadAt: Long? = null,
)

/** One server-timezone calendar day, added up across every device. */
data class InsightDay(val date: LocalDate, val activeMinutes: Double)

/**
 * Statistics from the sync server, when there are any.
 *
 * Every answer here is decoration. The dashboard is built from this
 * device's own sessions and stands on its own; what this adds is the
 * reading done on the reader's other devices, which no amount of local
 * arithmetic can produce. So every failure is null and silent: a
 * statistics screen is not worth an error banner, and a reader offline
 * on a train should see their own figures rather than a complaint.
 *
 * It needs the second, narrower token — the one that may read but not
 * write — which a reader who pasted a single sync token will not have.
 * That is the ordinary case for the answer being null.
 */
class LiseurSyncInsights(
    private val accountDao: SyncAccountDao,
    private val identityDao: WorkIdentityDao,
    private val http: LiseurSyncHttp = LiseurSyncHttp(),
) {

    suspend fun summary(rangeDays: Int = DEFAULT_RANGE_DAYS): InsightsSummary? {
        val account = accountDao.get() ?: return null
        val credentials = account.insightsCredentials ?: return null
        val answer = try {
            http.get(
                LiseurSyncApi.insightsSummary(account.baseUrl, "${rangeDays}d"),
                credentials,
            )
        } catch (e: IOException) {
            Log.i(TAG, "No statistics from the server this time", e)
            return null
        }
        return InsightsSummary(
            rangeDays = answer.optInt("range_days", rangeDays),
            activeMinutes = answer.optDouble("total_active_minutes", 0.0),
            sessions = answer.optInt("sessions"),
            streakDays = answer.optInt("streak_days"),
        ).takeIf { it.sessions > 0 || it.activeMinutes > 0 }
    }

    /**
     * One book, if this server has a name for it and something to say.
     *
     * A book nobody has finished reading anywhere has no speed history
     * to divide the remainder by, and the server says so with a null
     * rather than a guess. That null is carried through untouched: an
     * invented "about four hours left" is worse than no estimate.
     */
    suspend fun forBook(bookUrl: String): WorkInsights? {
        val account = accountDao.get() ?: return null
        val credentials = account.insightsCredentials ?: return null
        val alias = identityDao.alias(bookUrl, account.peerId)?.takeIf { it.usable } ?: return null
        val answer = try {
            http.get(LiseurSyncApi.workInsights(account.baseUrl, alias.workId), credentials)
        } catch (e: IOException) {
            Log.i(TAG, "No statistics for this book", e)
            return null
        }
        return parseWork(answer)?.takeIf { it.sessions > 0 || it.etaSeconds != null }
    }

    /** The requested calendar span, with absent days filled by the caller. */
    suspend fun calendar(from: LocalDate, to: LocalDate): List<InsightDay>? {
        val account = accountDao.get() ?: return null
        val credentials = account.insightsCredentials ?: return null
        val answers = try {
            (from.year..to.year).map { year ->
                http.get(LiseurSyncApi.insightsCalendar(account.baseUrl, year), credentials)
            }
        } catch (e: IOException) {
            Log.i(TAG, "No reading calendar from the server this time", e)
            return null
        }
        return runCatching {
            answers.flatMap { answer ->
                val days = answer.getJSONArray("days")
                (0 until days.length()).mapNotNull { index ->
                    val day = days.optJSONObject(index) ?: return@mapNotNull null
                    val date = LocalDate.parse(day.getString("date"))
                    if (date.isBefore(from) || date.isAfter(to)) return@mapNotNull null
                    InsightDay(
                        date = date,
                        activeMinutes = day.optDouble("minutes", 0.0)
                            .takeIf { it.isFinite() && it >= 0 } ?: 0.0,
                    )
                }
            }
        }.getOrElse {
            Log.i(TAG, "The server returned a malformed reading calendar", it)
            null
        }
    }

    /**
     * Lifetime per-book aggregates, keyed by this device's permanent book URL.
     *
     * One server work may have more than one local URL (for example after a
     * file move); each usable alias receives the same aggregate. Doubtful
     * aliases stay excluded until the reader confirms them, just like position
     * sync.
     */
    suspend fun allBooks(): Map<String, WorkInsights>? {
        val account = accountDao.get() ?: return null
        val credentials = account.insightsCredentials ?: return null
        val aliases = identityDao.aliasesFor(account.peerId).filter { it.usable }
        if (aliases.isEmpty()) return emptyMap()
        val answer = try {
            http.get(LiseurSyncApi.workInsights(account.baseUrl), credentials)
        } catch (e: IOException) {
            Log.i(TAG, "No per-book statistics from the server this time", e)
            return null
        }
        return runCatching {
            val byWork = aliases.groupBy { it.workId }
            val works = answer.getJSONArray("works")
            buildMap {
                for (index in 0 until works.length()) {
                    val item = works.optJSONObject(index) ?: continue
                    val insight = parseWork(item) ?: continue
                    for (alias in byWork[item.getString("work_id")].orEmpty()) {
                        put(alias.bookUrl, insight)
                    }
                }
            }
        }.getOrElse {
            Log.i(TAG, "The server returned malformed per-book statistics", it)
            null
        }
    }

    private fun parseWork(answer: JSONObject): WorkInsights? = runCatching {
        WorkInsights(
            sessions = answer.optInt("sessions"),
            activeMinutes = answer.optDouble("total_active_minutes", 0.0)
                .takeIf { it.isFinite() && it >= 0 } ?: 0.0,
            etaSeconds = if (answer.isNull("eta_seconds")) {
                null
            } else {
                answer.optDouble("eta_seconds").takeIf { it.isFinite() && it > 0 }
            },
            lastReadAt = answer.optString("last_read_at")
                .takeIf { it.isNotEmpty() }
                ?.let { Instant.parse(it).toEpochMilli() },
        )
    }.getOrNull()

    private companion object {
        const val TAG = "liseur-sync-insights"
        const val DEFAULT_RANGE_DAYS = 30
    }
}
